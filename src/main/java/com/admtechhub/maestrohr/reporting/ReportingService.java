package com.admtechhub.maestrohr.reporting;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.audit.AuditTrail;
import com.admtechhub.maestrohr.audit.AuditTrailService;
import com.admtechhub.maestrohr.employee.Department;
import com.admtechhub.maestrohr.employee.DepartmentRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.leave.LeaveBalance;
import com.admtechhub.maestrohr.leave.LeaveBalanceRepository;
import com.admtechhub.maestrohr.leave.LeaveRequest;
import com.admtechhub.maestrohr.leave.LeaveRequestRepository;
import com.admtechhub.maestrohr.leave.LeaveStatus;
import com.admtechhub.maestrohr.notification.PayslipGenerator;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ReportingService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter FILE_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollEntryRepository payrollEntryRepository;
    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AuditTrailService auditTrailService;
    private final PayslipGenerator payslipGenerator;

    public ReportFile generateMonthlyPayrollSummary(Integer month, Integer year, ReportFormat format) {
        log.info("Generating monthly payroll summary report for {}/{} as {}", month, year, format);
        PayrollRun run = getPayrollRun(month, year);
        List<PayrollEntry> entries = hydratePayrollEntries(run.getId());
        String base = "monthly-payroll-summary-" + year + "-" + String.format("%02d", month);

        List<String> headers = List.of("Period", "Employees", "Gross", "PAYE", "Pension Employee", "NHF", "Net", "Status");
        List<List<String>> rows = List.of(List.of(
                run.getPeriod(),
                String.valueOf(entries.size()),
                money(run.getTotalGross()),
                money(run.getTotalPaye()),
                money(run.getTotalPensionEmployee()),
                money(run.getTotalNhf()),
                money(run.getTotalNet()),
                run.getStatus().name()
        ));

        return export("Monthly Payroll Summary", base, headers, rows, format,
                "Summary for payroll run " + run.getPeriod());
    }

    public ReportFile generatePayeSchedule(Integer month, Integer year, ReportFormat format) {
        log.info("Generating PAYE schedule for {}/{} as {}", month, year, format);
        PayrollRun run = getPayrollRun(month, year);
        List<PayrollEntry> entries = hydratePayrollEntries(run.getId());
        List<String> headers = List.of("Employee Number", "Employee", "Gross Salary", "Pension", "NHF", "PAYE Tax");
        List<List<String>> rows = entries.stream()
                .map(entry -> List.of(
                        entry.getEmployee().getEmployeeNumber(),
                        entry.getEmployee().getFullName(),
                        money(entry.getGrossSalary()),
                        money(entry.getPensionEmployee()),
                        money(entry.getNhfDeduction()),
                        money(entry.getPayeTax())
                ))
                .toList();
        return export("PAYE Schedule (Form A)",
                "paye-schedule-" + run.getPeriod(),
                headers,
                rows,
                format,
                "PAYE schedule for " + run.getPeriod());
    }

    public ReportFile generatePensionSchedule(Integer month, Integer year, ReportFormat format) {
        log.info("Generating pension schedule for {}/{} as {}", month, year, format);
        PayrollRun run = getPayrollRun(month, year);
        List<PayrollEntry> entries = hydratePayrollEntries(run.getId());
        List<String> headers = List.of("Employee Number", "Employee", "Employee Pension", "Employer Pension", "Total Pension");
        List<List<String>> rows = entries.stream()
                .map(entry -> List.of(
                        entry.getEmployee().getEmployeeNumber(),
                        entry.getEmployee().getFullName(),
                        money(entry.getPensionEmployee()),
                        money(entry.getPensionEmployer()),
                        money(entry.getPensionEmployee() + entry.getPensionEmployer())
                ))
                .toList();
        return export("Pension Schedule",
                "pension-schedule-" + run.getPeriod(),
                headers,
                rows,
                format,
                "Pension schedule for " + run.getPeriod());
    }

    public ReportFile generateNhfSchedule(Integer month, Integer year, ReportFormat format) {
        log.info("Generating NHF schedule for {}/{} as {}", month, year, format);
        PayrollRun run = getPayrollRun(month, year);
        List<PayrollEntry> entries = hydratePayrollEntries(run.getId());
        List<String> headers = List.of("Employee Number", "Employee", "Basic Salary", "NHF Deduction");
        List<List<String>> rows = entries.stream()
                .map(entry -> List.of(
                        entry.getEmployee().getEmployeeNumber(),
                        entry.getEmployee().getFullName(),
                        money(entry.getBasicSalary()),
                        money(entry.getNhfDeduction())
                ))
                .toList();
        return export("NHF Schedule",
                "nhf-schedule-" + run.getPeriod(),
                headers,
                rows,
                format,
                "NHF deductions for " + run.getPeriod());
    }

    public ReportFile generateEmployeeHeadcountReport(ReportFormat format) {
        log.info("Generating employee headcount report as {}", format);
        List<Employee> employees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(Employee::getLastName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        employees.forEach(this::hydrateEmployee);

        Map<String, Long> byDepartment = new LinkedHashMap<>();
        for (Department department : departmentRepository.findAll()) {
            byDepartment.put(department.getName(), employees.stream()
                    .filter(employee -> employee.getDepartment() != null && department.getId().equals(employee.getDepartment().getId()))
                    .count());
        }

        List<String> headers = List.of("Department", "Active", "On Leave", "Suspended", "Terminated", "Total");
        List<List<String>> rows = new ArrayList<>();
        byDepartment.forEach((departmentName, ignored) -> {
            List<Employee> deptEmployees = employees.stream()
                    .filter(employee -> employee.getDepartment() != null && departmentName.equals(employee.getDepartment().getName()))
                    .toList();
            rows.add(List.of(
                    departmentName,
                    String.valueOf(countByStatus(deptEmployees, EmployeeStatus.ACTIVE)),
                    String.valueOf(countByStatus(deptEmployees, EmployeeStatus.ON_LEAVE)),
                    String.valueOf(countByStatus(deptEmployees, EmployeeStatus.SUSPENDED)),
                    String.valueOf(countByStatus(deptEmployees, EmployeeStatus.TERMINATED)),
                    String.valueOf(deptEmployees.size())
            ));
        });

        return export("Employee Headcount Report",
                "employee-headcount-" + LocalDate.now().format(FILE_MONTH_FORMAT),
                headers,
                rows,
                format,
                "Headcount by department and employment status");
    }

    public ReportFile generateLeaveBalanceReport(Integer year, ReportFormat format) {
        log.info("Generating leave balance report for {} as {}", year, format);
        List<LeaveBalance> balances = leaveBalanceRepository.findByYear(year).stream()
                .sorted(Comparator.comparing(balance -> balance.getEmployee().getLastName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        balances.forEach(balance -> {
            balance.getEmployee().getFullName();
            balance.getLeaveType().getName();
        });

        List<String> headers = List.of("Employee Number", "Employee", "Leave Type", "Year", "Entitled", "Taken", "Carried Over", "Remaining");
        List<List<String>> rows = balances.stream()
                .map(balance -> List.of(
                        balance.getEmployee().getEmployeeNumber(),
                        balance.getEmployee().getFullName(),
                        balance.getLeaveType().getName(),
                        String.valueOf(balance.getYear()),
                        String.valueOf(balance.getTotalDaysEntitled()),
                        String.valueOf(balance.getDaysTaken()),
                        String.valueOf(balance.getDaysCarriedOver()),
                        String.valueOf(balance.getDaysRemaining())
                ))
                .toList();

        return export("Leave Balance Report",
                "leave-balance-" + year,
                headers,
                rows,
                format,
                "Leave balances for " + year);
    }

    public ReportFile generateSalaryHistoryReport(UUID employeeId, ReportFormat format) {
        log.info("Generating salary history report for employee {} as {}", employeeId, format);
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + employeeId));
        hydrateEmployee(employee);

        List<PayrollEntry> entries = payrollEntryRepository.findByEmployeeId(employeeId).stream()
                .sorted(Comparator.comparingInt((PayrollEntry entry) ->
                        entry.getPayrollRun().getPayrollYear() * 100 + entry.getPayrollRun().getPayrollMonth()).reversed())
                .toList();
        entries.forEach(entry -> entry.getPayrollRun().getPeriod());

        List<String> headers = List.of("Period", "Basic", "Gross", "PAYE", "Pension", "NHF", "Net", "Transfer Status");
        List<List<String>> rows = entries.stream()
                .map(entry -> List.of(
                        entry.getPayrollRun().getPeriod(),
                        money(entry.getBasicSalary()),
                        money(entry.getGrossSalary()),
                        money(entry.getPayeTax()),
                        money(entry.getPensionEmployee()),
                        money(entry.getNhfDeduction()),
                        money(entry.getNetSalary()),
                        entry.getTransferStatus().name()
                ))
                .toList();

        return export("Salary History Report",
                "salary-history-" + employee.getEmployeeNumber(),
                headers,
                rows,
                format,
                "Salary history for " + employee.getFullName());
    }

    public ReportFile generateAuditTrailReport(LocalDate from, LocalDate to, ReportFormat format) {
        log.info("Generating audit trail report from {} to {} as {}", from, to, format);
        OffsetDateTime start = from.atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
        OffsetDateTime end = to.plusDays(1).atStartOfDay().minusNanos(1).atOffset(OffsetDateTime.now().getOffset());
        List<AuditTrail> audits = auditTrailService.findBetween(start, end);

        List<String> headers = List.of("When", "Actor", "Action", "Method", "Path", "Status", "Tenant", "Details");
        List<List<String>> rows = audits.stream()
                .map(audit -> List.of(
                        audit.getCreatedAt() != null ? audit.getCreatedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")) : "",
                        value(audit.getActorEmail()),
                        value(audit.getAction()),
                        value(audit.getHttpMethod()),
                        value(audit.getRequestPath()),
                        String.valueOf(audit.getStatusCode()),
                        audit.getTenantId() != null ? audit.getTenantId().toString() : "-",
                        value(audit.getDetails())
                ))
                .toList();

        return export("Audit Trail Report",
                "audit-trail-" + from + "-to-" + to,
                headers,
                rows,
                format,
                "Audit activity from " + from + " to " + to);
    }

    public ReportFile generatePayslip(UUID employeeId, UUID payrollRunId) {
        log.info("Generating payslip for employee {} and payroll run {}", employeeId, payrollRunId);
        PayrollEntry entry = payrollEntryRepository.findByPayrollRunIdAndEmployeeId(payrollRunId, employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Payslip not found for the supplied payroll run and employee"));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + employeeId));
        hydrateEmployee(employee);
        entry.getPayrollRun().getPeriod();

        byte[] content = payslipGenerator.generatePayslip(entry, employee, entry.getPayrollRun().getPeriod());
        if (content == null) {
            throw new IllegalStateException("Failed to generate payslip PDF");
        }

        return new ReportFile(
                "payslip-" + employee.getEmployeeNumber() + "-" + entry.getPayrollRun().getPeriod() + ".pdf",
                "application/pdf",
                content
        );
    }

    public ReportFile generateLatestPayslip(UUID employeeId) {
        PayrollEntry latest = payrollEntryRepository.findByEmployeeId(employeeId).stream()
                .max(Comparator.comparing(entry -> YearMonth.of(entry.getPayrollRun().getPayrollYear(), entry.getPayrollRun().getPayrollMonth())))
                .orElseThrow(() -> new IllegalArgumentException("No payroll history found for employee"));
        return generatePayslip(employeeId, latest.getPayrollRun().getId());
    }

    private ReportFile export(String title, String baseFilename, List<String> headers, List<List<String>> rows,
                              ReportFormat format, String subtitle) {
        return switch (format) {
            case PDF -> new ReportFile(baseFilename + ".pdf", "application/pdf", toPdf(title, subtitle, headers, rows));
            case XLSX -> new ReportFile(baseFilename + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    toExcel(title, headers, rows));
        };
    }

    private byte[] toPdf(String title, String subtitle, List<String> headers, List<List<String>> rows) {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>")
                .append("body{font-family:Arial,sans-serif;font-size:12px;color:#1f2937;} ")
                .append("h1{font-size:20px;margin-bottom:4px;} p{color:#6b7280;margin-top:0;} ")
                .append("table{width:100%;border-collapse:collapse;margin-top:20px;} ")
                .append("th,td{border:1px solid #d1d5db;padding:8px;text-align:left;vertical-align:top;} ")
                .append("th{background:#eff6ff;color:#1d4ed8;} ")
                .append("tr:nth-child(even){background:#f8fafc;} ")
                .append("</style></head><body>")
                .append("<h1>").append(escape(title)).append("</h1>")
                .append("<p>").append(escape(subtitle)).append("</p>")
                .append("<p>Generated ").append(escape(LocalDate.now().format(DATE_FORMAT))).append("</p>")
                .append("<table><thead><tr>");
        for (String header : headers) {
            html.append("<th>").append(escape(header)).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (List<String> row : rows) {
            html.append("<tr>");
            for (String value : row) {
                html.append("<td>").append(escape(value)).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table></body></html>");

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html.toString());
            renderer.layout();
            renderer.createPDF(outputStream);
            return outputStream.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate PDF report", ex);
        }
    }

    private byte[] toExcel(String title, List<String> headers, List<List<String>> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(safeSheetName(title));

            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
            titleCell.setCellStyle(titleStyle);

            Row headerRow = sheet.createRow(2);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 3;
            for (List<String> rowValues : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < rowValues.size(); i++) {
                    row.createCell(i).setCellValue(rowValues.get(i));
                }
            }

            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate Excel report", ex);
        }
    }

    private PayrollRun getPayrollRun(Integer month, Integer year) {
        UUID tenantId = currentTenantId();
        PayrollRun run = payrollRunRepository.findTopByTenant_IdAndPayrollMonthAndPayrollYearOrderByCreatedAtDesc(tenantId, month, year)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found for " + month + "/" + year));
        if (run.getInitiatedBy() != null) {
            run.getInitiatedBy().getEmail();
        }
        if (run.getApprovedBy() != null) {
            run.getApprovedBy().getEmail();
        }
        return run;
    }

    private List<PayrollEntry> hydratePayrollEntries(UUID payrollRunId) {
        List<PayrollEntry> entries = payrollEntryRepository.findByPayrollRunId(payrollRunId).stream()
                .sorted(Comparator.comparing(entry -> entry.getEmployee().getLastName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        entries.forEach(entry -> {
            hydrateEmployee(entry.getEmployee());
            entry.getPayrollRun().getPeriod();
        });
        return entries;
    }

    private void hydrateEmployee(Employee employee) {
        employee.getFullName();
        if (employee.getDepartment() != null) {
            employee.getDepartment().getName();
        }
        if (employee.getPayGrade() != null) {
            employee.getPayGrade().getName();
        }
    }

    private long countByStatus(List<Employee> employees, EmployeeStatus status) {
        return employees.stream().filter(employee -> employee.getStatus() == status).count();
    }

    private String money(Long amountInKobo) {
        double naira = amountInKobo == null ? 0.0 : amountInKobo / 100.0;
        return String.format("NGN %,.2f", naira);
    }

    private String safeSheetName(String value) {
        String cleaned = value.replaceAll("[\\\\/*?:\\[\\]]", "").trim();
        if (cleaned.isEmpty()) {
            return "Report";
        }
        return cleaned.substring(0, Math.min(cleaned.length(), 31));
    }

    private String escape(String value) {
        return value(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String value(String input) {
        return input == null || input.isBlank() ? "-" : input;
    }

    private UUID currentTenantId() {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available for reporting");
        }
        return UUID.fromString(tenantId);
    }
}
