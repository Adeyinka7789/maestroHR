package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Robust employee import pipeline. Three distinct phases, each side-effect-scoped:
 *
 * <ol>
 *   <li><b>PARSE</b> — read CSV or Excel, map columns by <em>header name</em> (order-independent),
 *       parse/normalise each cell. No DB access.</li>
 *   <li><b>VALIDATE</b> — read-only DB checks (duplicate emails, unknown departments / pay grades).</li>
 *   <li><b>IMPORT</b> — write phase, delegated to {@link EmployeeRowImporter} so each row commits in
 *       its <em>own</em> transaction. One bad row can never roll back a good one, and the reported
 *       success count reflects what was actually committed.</li>
 * </ol>
 *
 * <p>{@link #preview(MultipartFile)} runs PARSE + VALIDATE only. {@link #confirmImport} runs all
 * three. {@link #importInOneShot} (PARSE + VALIDATE + IMPORT, no preview) backs the legacy
 * {@code POST /api/employees/import} endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeImportService {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final PayGradeRepository payGradeRepository;
    private final EmployeeRowImporter rowImporter;

    /** Hard ceiling on rows processed in a single import. */
    static final int MAX_ROWS = 500;
    /** Soft threshold above which we surface a "large import" advisory. */
    static final int WARN_ROWS = 200;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    );

    /**
     * Canonical field → accepted header aliases. Headers are matched after normalisation
     * (lower-cased, all non-alphanumerics stripped), so "First Name", "firstName" and
     * "first_name" all collapse to {@code firstname}.
     */
    private static final Map<String, List<String>> FIELD_ALIASES = buildAliases();

    private static Map<String, List<String>> buildAliases() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("firstName", List.of("firstname"));
        m.put("lastName", List.of("lastname", "surname"));
        m.put("email", List.of("email", "workemail", "emailaddress"));
        m.put("phone", List.of("phone", "phonenumber", "mobile"));
        m.put("jobTitle", List.of("jobtitle", "position"));
        m.put("employmentType", List.of("employmenttype", "emptype"));
        m.put("bankName", List.of("bankname"));
        m.put("accountNumber", List.of("accountnumber", "bankaccount", "accountno"));
        m.put("accountName", List.of("accountname"));
        m.put("department", List.of("department", "dept"));
        m.put("payGrade", List.of("paygrade", "grade"));
        m.put("dateOfBirth", List.of("dateofbirth", "dob", "birthdate"));
        m.put("gender", List.of("gender", "sex"));
        m.put("maritalStatus", List.of("maritalstatus"));
        m.put("address", List.of("address", "residentialaddress", "homeaddress"));
        m.put("startDate", List.of("startdate", "employmentstart", "employmentstartdate", "hiredate"));
        return m;
    }

    /** Header display names used by the downloadable template and the aligned Excel export. */
    static final String[] TEMPLATE_HEADERS = {
            "First Name", "Last Name", "Email", "Phone", "Job Title", "Employment Type",
            "Bank Name", "Account Number", "Account Name", "Department", "Pay Grade",
            "Date of Birth", "Gender", "Marital Status", "Address", "Start Date"
    };

    // ── Public API ────────────────────────────────────────────────────────────────────────

    /** PARSE + VALIDATE only. No DB writes. */
    public ImportPreviewResult preview(MultipartFile file) {
        UUID tenantId = currentTenantId();
        ParseOutcome parsed = parse(file);
        if (parsed.fatalError != null) {
            return ImportPreviewResult.builder().fatalError(parsed.fatalError).build();
        }
        List<String> unknownDepartments = validate(parsed, tenantId);
        return toPreview(parsed, unknownDepartments);
    }

    /**
     * PARSE + VALIDATE + IMPORT. Re-reads the uploaded file (so the import is validated against the
     * live database at confirm time, not against a stale client payload) and writes every row that
     * is not an ERROR, each in its own transaction.
     */
    public ImportSummaryResult confirmImport(MultipartFile file, boolean createMissingDepartments) {
        UUID tenantId = currentTenantId();
        ParseOutcome parsed = parse(file);
        if (parsed.fatalError != null) {
            return ImportSummaryResult.builder()
                    .failures(List.of(new ImportSummaryResult.FailedRow(0, null, parsed.fatalError)))
                    .build();
        }
        List<String> unknownDepartments = validate(parsed, tenantId);
        return runImport(parsed, unknownDepartments, createMissingDepartments);
    }

    /** Legacy one-shot import used by {@code POST /api/employees/import}. */
    public ImportSummaryResult importInOneShot(MultipartFile file) {
        return confirmImport(file, true);
    }

    /** A ready-to-fill CSV template: recognised headers + one illustrative example row. */
    public byte[] buildCsvTemplate() {
        String[] example = {
                "John", "Doe", "john.doe@example.com", "08012345678", "Software Engineer", "FULL_TIME",
                "GTBank", "0123456789", "John Doe", "Engineering", "Level 1", "1990-05-20", "MALE",
                "SINGLE", "12 Allen Avenue, Lagos", "2024-01-15"
        };
        String csv = toCsvLine(TEMPLATE_HEADERS) + "\r\n" + toCsvLine(example) + "\r\n";
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    private static String toCsvLine(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            String f = fields[i] == null ? "" : fields[i];
            if (f.contains(",") || f.contains("\"") || f.contains("\n")) {
                sb.append('"').append(f.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(f);
            }
        }
        return sb.toString();
    }

    // ── PARSE ─────────────────────────────────────────────────────────────────────────────

    private ParseOutcome parse(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        boolean excel = name.endsWith(".xlsx") || name.endsWith(".xls")
                || contentType.contains("spreadsheetml") || contentType.contains("ms-excel");
        boolean csv = name.endsWith(".csv") || contentType.contains("csv") || contentType.contains("text/plain");

        try {
            if (excel) {
                return parseExcel(file);
            } else if (csv || name.isEmpty()) {
                return parseCsv(file);
            }
            return ParseOutcome.fatal("Unsupported file type. Please upload a .csv or .xlsx file.");
        } catch (Exception e) {
            log.error("Failed to parse import file '{}': {}", name, e.getMessage(), e);
            return ParseOutcome.fatal("Could not read the file. Make sure it is a valid CSV or Excel spreadsheet.");
        }
    }

    private ParseOutcome parseCsv(MultipartFile file) throws Exception {
        List<List<String>> grid = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    line = stripBom(line);
                    first = false;
                }
                if (line.trim().isEmpty()) continue;
                grid.add(splitCsvLine(line));
            }
        }
        return fromGrid(grid);
    }

    private ParseOutcome parseExcel(MultipartFile file) throws Exception {
        List<List<String>> grid = new ArrayList<>();
        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            int lastCol = 0;
            for (Row row : sheet) {
                lastCol = Math.max(lastCol, row.getLastCellNum());
            }
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < lastCol; c++) {
                    cells.add(cellToString(row.getCell(c)));
                }
                if (cells.stream().allMatch(s -> s == null || s.isBlank())) continue;
                grid.add(cells);
            }
        }
        return fromGrid(grid);
    }

    /** Shared logic once a CSV/Excel file has been reduced to a string grid. */
    private ParseOutcome fromGrid(List<List<String>> grid) {
        ParseOutcome out = new ParseOutcome();
        if (grid.isEmpty()) {
            return ParseOutcome.fatal("The file is empty.");
        }

        // ── Header row → field index map ──
        List<String> header = grid.get(0);
        Map<String, Integer> fieldIndex = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String raw = header.get(i) == null ? "" : header.get(i).trim();
            if (raw.isEmpty()) continue;
            String field = resolveField(raw);
            if (field != null && !fieldIndex.containsKey(field)) {
                fieldIndex.put(field, i);
                out.columnMapping.put(raw, field);
            } else if (field == null) {
                out.ignoredColumns.add(raw);
            }
        }

        List<String> missingRequired = new ArrayList<>();
        if (!fieldIndex.containsKey("firstName")) missingRequired.add("First Name");
        if (!fieldIndex.containsKey("email")) missingRequired.add("Email");
        if (!missingRequired.isEmpty()) {
            return ParseOutcome.fatal("Missing required column(s): " + String.join(", ", missingRequired)
                    + ". Download the template for the expected headers.");
        }

        int dataRows = grid.size() - 1;
        if (dataRows <= 0) {
            return ParseOutcome.fatal("The file has a header but no data rows.");
        }
        if (dataRows > MAX_ROWS) {
            return ParseOutcome.fatal("The file has " + dataRows + " rows; the maximum per import is "
                    + MAX_ROWS + ". Please split it into smaller files.");
        }
        if (dataRows > WARN_ROWS) {
            out.globalWarnings.add(dataRows + " rows detected — large imports may take a moment.");
        }

        // ── Parse each data row ──
        for (int r = 1; r < grid.size(); r++) {
            List<String> cols = grid.get(r);
            ParsedRow row = parseRow(cols, fieldIndex, r); // r == 1-based data row number
            out.rows.add(row);
        }
        return out;
    }

    private ParsedRow parseRow(List<String> cols, Map<String, Integer> fieldIndex, int rowNumber) {
        ParsedRow row = new ParsedRow();
        row.rowNumber = rowNumber;
        row.status = ImportRowStatus.VALID;

        row.firstName = get(cols, fieldIndex, "firstName");
        row.lastName = get(cols, fieldIndex, "lastName");
        row.email = get(cols, fieldIndex, "email");
        row.jobTitle = get(cols, fieldIndex, "jobTitle");
        row.bankName = get(cols, fieldIndex, "bankName");
        row.accountName = get(cols, fieldIndex, "accountName");
        row.address = get(cols, fieldIndex, "address");

        // Required: first name
        if (isBlank(row.firstName)) {
            error(row, "First name is required.");
        }
        // Required: email + format
        if (isBlank(row.email)) {
            error(row, "Email is required.");
        } else if (!EMAIL_PATTERN.matcher(row.email).matches()) {
            error(row, "Email '" + row.email + "' is not a valid address.");
        }

        // Phone — normalise Nigerian numbers
        row.phone = normalisePhone(get(cols, fieldIndex, "phone"));

        // Account number — strip spaces
        String acct = get(cols, fieldIndex, "accountNumber");
        row.accountNumber = acct == null ? "" : acct.replaceAll("\\s+", "");

        // Employment type
        String empType = get(cols, fieldIndex, "employmentType");
        if (isBlank(empType)) {
            row.employmentType = EmploymentType.FULL_TIME; // documented default
        } else {
            try {
                row.employmentType = EmploymentType.valueOf(empType.trim().toUpperCase().replace(' ', '_'));
            } catch (IllegalArgumentException e) {
                row.employmentType = EmploymentType.FULL_TIME;
                warn(row, "Unknown employment type '" + empType + "', defaulted to FULL_TIME.");
            }
        }

        // Department (optional; defaults to "General")
        String dept = get(cols, fieldIndex, "department");
        row.departmentName = isBlank(dept) ? "General" : dept.trim();

        // Pay grade (optional name)
        String grade = get(cols, fieldIndex, "payGrade");
        row.payGradeName = isBlank(grade) ? null : grade.trim();

        // Gender (optional → NOT NULL column forces a default; flag it)
        String gender = get(cols, fieldIndex, "gender");
        if (isBlank(gender)) {
            warn(row, "Gender not provided — defaulted to MALE.");
        } else {
            try {
                row.gender = Gender.valueOf(gender.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                warn(row, "Gender '" + gender + "' not recognised — defaulted to MALE.");
            }
        }

        // Marital status (optional → NOT NULL column forces a default; flag it)
        String marital = get(cols, fieldIndex, "maritalStatus");
        if (isBlank(marital)) {
            warn(row, "Marital status not provided — defaulted to SINGLE.");
        } else {
            try {
                row.maritalStatus = MaritalStatus.valueOf(marital.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                warn(row, "Marital status '" + marital + "' not recognised — defaulted to SINGLE.");
            }
        }

        // Date of birth (optional → NOT NULL column forces a placeholder; flag it)
        String dob = get(cols, fieldIndex, "dateOfBirth");
        if (isBlank(dob)) {
            warn(row, "Date of birth not provided — a placeholder was applied; update it later.");
        } else {
            row.dateOfBirth = parseDate(dob);
            if (row.dateOfBirth == null) {
                warn(row, "Date of birth '" + dob + "' not recognised — a placeholder was applied.");
            } else if (!row.dateOfBirth.isBefore(LocalDate.now())) {
                warn(row, "Date of birth is not in the past — a placeholder was applied.");
                row.dateOfBirth = null;
            }
        }

        // Start date (optional → defaults to today)
        String start = get(cols, fieldIndex, "startDate");
        if (!isBlank(start)) {
            row.startDate = parseDate(start);
            if (row.startDate == null) {
                warn(row, "Start date '" + start + "' not recognised — defaulted to today.");
            }
        }
        return row;
    }

    // ── VALIDATE (read-only DB) ─────────────────────────────────────────────────────────────

    /** Returns the distinct list of department names referenced but not yet present for the tenant. */
    private List<String> validate(ParseOutcome parsed, UUID tenantId) {
        Set<String> existingDepts = new HashSet<>();
        for (Department d : departmentRepository.findAllByTenantId(tenantId)) {
            existingDepts.add(d.getName().toLowerCase());
        }
        Set<String> existingGrades = new HashSet<>();
        for (PayGrade g : payGradeRepository.findAllByTenantId(tenantId)) {
            existingGrades.add(g.getName().toLowerCase());
        }
        boolean tenantHasGrades = !existingGrades.isEmpty();
        if (!tenantHasGrades) {
            parsed.globalWarnings.add("No pay grade is configured for this organization. "
                    + "Create a pay grade first, otherwise every row will be skipped at import.");
        }

        Map<String, Integer> seenEmails = new HashMap<>(); // lower(email) → first row number
        Set<String> unknownDepts = new LinkedHashSet<>();

        for (ParsedRow row : parsed.rows) {
            if (row.status == ImportRowStatus.ERROR) continue; // already invalid; skip further checks

            // Duplicate within the file
            if (!isBlank(row.email)) {
                String key = row.email.toLowerCase();
                Integer firstRow = seenEmails.get(key);
                if (firstRow != null) {
                    error(row, "Duplicate email — also appears on row " + firstRow + " of this file.");
                    continue;
                }
                seenEmails.put(key, row.rowNumber);

                // Duplicate against existing employees
                if (employeeRepository.existsByEmail(row.email, tenantId)) {
                    error(row, "An employee with this email already exists.");
                    continue;
                }
            }

            // Unknown department
            if (!existingDepts.contains(row.departmentName.toLowerCase())) {
                unknownDepts.add(row.departmentName);
                warn(row, "Department '" + row.departmentName + "' does not exist yet — it will be created on import.");
            }

            // Pay grade resolution
            if (!tenantHasGrades) {
                warn(row, "No pay grade configured — this row cannot be imported until one exists.");
            } else if (row.payGradeName != null && !existingGrades.contains(row.payGradeName.toLowerCase())) {
                warn(row, "Pay grade '" + row.payGradeName + "' not found — the organization's default grade will be used.");
            }
        }
        return new ArrayList<>(unknownDepts);
    }

    // ── IMPORT ──────────────────────────────────────────────────────────────────────────────

    private ImportSummaryResult runImport(ParseOutcome parsed, List<String> unknownDepartments,
                                          boolean createMissingDepartments) {
        int success = 0, skipped = 0, failed = 0;
        List<ImportSummaryResult.FailedRow> failures = new ArrayList<>();

        for (ParsedRow row : parsed.rows) {
            if (row.status == ImportRowStatus.ERROR) {
                skipped++;
                failures.add(new ImportSummaryResult.FailedRow(
                        row.rowNumber, row.email, String.join(" ", row.messages)));
                continue;
            }
            try {
                rowImporter.importRow(row, createMissingDepartments);
                success++;
            } catch (Exception e) {
                failed++;
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("Import failed for row {} ({}): {}", row.rowNumber, row.email, reason);
                failures.add(new ImportSummaryResult.FailedRow(row.rowNumber, row.email, reason));
            }
        }

        List<String> warnings = new ArrayList<>(parsed.globalWarnings);
        if (createMissingDepartments && !unknownDepartments.isEmpty()) {
            warnings.add("Created new department(s): " + String.join(", ", unknownDepartments));
        }

        return ImportSummaryResult.builder()
                .successCount(success)
                .skippedCount(skipped)
                .failedCount(failed)
                .failures(failures)
                .warnings(warnings)
                .build();
    }

    // ── Preview projection ──────────────────────────────────────────────────────────────────

    private ImportPreviewResult toPreview(ParseOutcome parsed, List<String> unknownDepartments) {
        List<ImportRowResult> rows = new ArrayList<>();
        int valid = 0, warning = 0, errorCount = 0;
        for (ParsedRow r : parsed.rows) {
            switch (r.status) {
                case VALID -> valid++;
                case WARNING -> warning++;
                case ERROR -> errorCount++;
            }
            rows.add(ImportRowResult.builder()
                    .rowNumber(r.rowNumber)
                    .firstName(r.firstName)
                    .lastName(r.lastName)
                    .email(r.email)
                    .phone(r.phone)
                    .jobTitle(r.jobTitle)
                    .employmentType(r.employmentType == null ? "" : r.employmentType.name())
                    .department(r.departmentName)
                    .payGrade(r.payGradeName == null ? "" : r.payGradeName)
                    .status(r.status)
                    .messages(new ArrayList<>(r.messages))
                    .build());
        }
        return ImportPreviewResult.builder()
                .columnMapping(parsed.columnMapping)
                .ignoredColumns(parsed.ignoredColumns)
                .unknownDepartments(unknownDepartments)
                .globalWarnings(parsed.globalWarnings)
                .rows(rows)
                .totalRows(parsed.rows.size())
                .validCount(valid)
                .warningCount(warning)
                .errorCount(errorCount)
                .importableCount(valid + warning)
                .build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────

    private static String resolveField(String header) {
        String norm = normalise(header);
        for (Map.Entry<String, List<String>> e : FIELD_ALIASES.entrySet()) {
            if (e.getValue().contains(norm)) return e.getKey();
        }
        return null;
    }

    private static String normalise(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String get(List<String> cols, Map<String, Integer> fieldIndex, String field) {
        Integer idx = fieldIndex.get(field);
        if (idx == null || idx >= cols.size()) return null;
        String v = cols.get(idx);
        return v == null ? null : v.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void error(ParsedRow row, String msg) {
        row.messages.add(msg);
        row.status = ImportRowStatus.ERROR;
    }

    private static void warn(ParsedRow row, String msg) {
        row.messages.add(msg);
        if (row.status == ImportRowStatus.VALID) {
            row.status = ImportRowStatus.WARNING;
        }
    }

    private static LocalDate parseDate(String value) {
        String v = value.trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, fmt);
            } catch (Exception ignored) {
                // try next format
            }
        }
        return null;
    }

    /** Normalise common Nigerian mobile formats to E.164 (+234…); leave anything else stripped. */
    static String normalisePhone(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("[\\s\\-()]", "");
        if (digits.isEmpty()) return "";
        if (digits.startsWith("+234")) return digits;
        if (digits.startsWith("234")) return "+" + digits;
        if (digits.startsWith("0") && digits.length() == 11) return "+234" + digits.substring(1);
        return digits;
    }

    private static String stripBom(String s) {
        return (!s.isEmpty() && s.charAt(0) == '﻿') ? s.substring(1) : s;
    }

    /** Minimal RFC-4180-ish splitter: handles quoted fields, embedded commas and "" escapes. */
    static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String cellToString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double n = cell.getNumericCellValue();
                if (n == Math.floor(n) && !Double.isInfinite(n)) {
                    return String.valueOf((long) n);
                }
                return String.valueOf(n);
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default:
                return "";
        }
    }

    private UUID currentTenantId() {
        String tenantIdStr = TenantContext.getCurrentTenant();
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return UUID.fromString(tenantIdStr);
    }

    // ── Internal carriers (not serialised) ──────────────────────────────────────────────────

    /** Typed, normalised representation of one data row, shared between preview and import. */
    static class ParsedRow {
        int rowNumber;
        String firstName;
        String lastName;
        String email;
        String phone;
        String jobTitle;
        EmploymentType employmentType;
        String departmentName;
        String payGradeName;
        Gender gender;
        MaritalStatus maritalStatus;
        LocalDate dateOfBirth;
        LocalDate startDate;
        String address;
        String bankName;
        String accountNumber;
        String accountName;
        ImportRowStatus status = ImportRowStatus.VALID;
        final List<String> messages = new ArrayList<>();
    }

    /** Outcome of the parse phase: either a fatal error, or a grid of parsed rows + metadata. */
    static class ParseOutcome {
        String fatalError;
        final Map<String, String> columnMapping = new LinkedHashMap<>();
        final List<String> ignoredColumns = new ArrayList<>();
        final List<String> globalWarnings = new ArrayList<>();
        final List<ParsedRow> rows = new ArrayList<>();

        static ParseOutcome fatal(String msg) {
            ParseOutcome o = new ParseOutcome();
            o.fatalError = msg;
            return o;
        }
    }
}
