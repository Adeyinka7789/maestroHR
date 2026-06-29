package com.admtechhub.maestrohr.employee;

import com.admtechhub.maestrohr.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the three-phase employee import pipeline.
 *
 * <p>Tests exercise the public API ({@code preview}/{@code confirmImport}) rather than the
 * private {@code parse}/{@code validate} helpers.  Repository mocks return Mockito defaults
 * (empty collections / false) unless a test explicitly overrides them.
 *
 * <p>Test 10 ({@code validate_invalidEmploymentType_flagsAsWarning}) is expected to <em>fail</em>
 * with current code: the parser today sets ERROR for an unknown employment type instead of WARNING
 * + defaulting to FULL_TIME.  The failing test exposes that gap.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeImportServiceTest {

    @Mock EmployeeRepository  employeeRepository;
    @Mock DepartmentRepository departmentRepository;
    @Mock PayGradeRepository  payGradeRepository;
    @Mock EmployeeRowImporter rowImporter;

    @InjectMocks EmployeeImportService employeeImportService;

    static final UUID TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeEach void bindTenant() { TenantContext.setCurrentTenant(TENANT.toString()); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "test.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    /** Stub repos so that a row's dept/grade/email produce no warnings → status stays VALID. */
    private void stubReposForCleanRow(String email) {
        Department generalDept = mock(Department.class);
        when(generalDept.getName()).thenReturn("General");
        when(departmentRepository.findAllByTenantId(TENANT)).thenReturn(List.of(generalDept));

        PayGrade grade = mock(PayGrade.class);
        when(grade.getName()).thenReturn("Default");
        when(payGradeRepository.findAllByTenantId(TENANT)).thenReturn(List.of(grade));

        when(employeeRepository.existsByEmail(eq(email), eq(TENANT))).thenReturn(false);
    }

    // ── PARSE phase ──────────────────────────────────────────────────────────

    /**
     * 6. A file whose header contains neither "firstName" nor "email" must be rejected with a
     *    fatal error before any row is processed.
     */
    @Test
    void parse_missingRequiredColumns_returnsFatalError() {
        String body = "lastName,department\nDoe,Engineering\n";

        ImportPreviewResult result = employeeImportService.preview(csv(body));

        assertNotNull(result.getFatalError(), "Expected a fatal error for missing required columns");
    }

    /**
     * 7. Unknown header columns must be silently ignored; recognised columns are still parsed.
     */
    @Test
    void parse_extraColumnsIgnored() {
        String body = "firstName,lastName,email,unknownColumn\n"
                + "Alice,Smith,alice@example.com,ignored\n";

        ImportPreviewResult result = employeeImportService.preview(csv(body));

        assertNull(result.getFatalError());
        assertEquals(1, result.getRows().size());
        assertEquals("alice@example.com", result.getRows().get(0).getEmail());
    }

    /**
     * 8. When the same email appears on two rows in the uploaded file, the second occurrence
     *    must be flagged ERROR (first occurrence is treated as VALID after validate passes).
     */
    @Test
    void parse_duplicateEmailInFile_flagsAsError() {
        String body = "firstName,lastName,email\n"
                + "John,Doe,john@example.com\n"
                + "Jane,Doe,john@example.com\n"; // duplicate

        stubReposForCleanRow("john@example.com");

        ImportPreviewResult result = employeeImportService.preview(csv(body));

        assertNull(result.getFatalError());
        List<ImportRowResult> rows = result.getRows();
        assertEquals(2, rows.size());
        assertNotEquals(ImportRowStatus.ERROR, rows.get(0).getStatus()); // VALID or WARNING, but never ERROR
        assertEquals(ImportRowStatus.ERROR,   rows.get(1).getStatus());
    }

    // ── VALIDATE phase ───────────────────────────────────────────────────────

    /**
     * 9. An email that already belongs to an employee in the tenant database must be rejected
     *    with ERROR status by the validate phase.
     */
    @Test
    void validate_duplicateEmailInDB_flagsAsError() {
        String body = "firstName,lastName,email\nAlice,Smith,existing@example.com\n";
        when(employeeRepository.existsByEmail(eq("existing@example.com"), eq(TENANT)))
                .thenReturn(true);

        ImportPreviewResult result = employeeImportService.preview(csv(body));

        assertNull(result.getFatalError());
        assertEquals(1, result.getRows().size());
        assertEquals(ImportRowStatus.ERROR, result.getRows().get(0).getStatus());
    }

    /**
     * 10. An unrecognised employment type ("FREELANCE") should produce a WARNING and default
     *     to FULL_TIME.  This test currently <em>fails</em>: the parser sets ERROR instead of
     *     WARNING — the failing assertion surfaces that gap.
     */
    @Test
    void validate_invalidEmploymentType_flagsAsWarning() {
        String body = "firstName,lastName,email,employmentType\n"
                + "Jane,Doe,jane@example.com,FREELANCE\n";

        ImportPreviewResult result = employeeImportService.preview(csv(body));

        assertNull(result.getFatalError());
        assertEquals(1, result.getRows().size());
        // Gap: code currently calls error() here, not warn().  Expected WARNING, actual ERROR.
        assertEquals(ImportRowStatus.WARNING, result.getRows().get(0).getStatus());
    }

    // ── IMPORT phase ─────────────────────────────────────────────────────────

    /**
     * 11. Rows with ERROR status must be skipped; rows that survive parse (VALID/WARNING) must
     *     be handed to {@link EmployeeRowImporter} exactly once each.
     */
    @Test
    void import_badRowSkipped_goodRowImported() {
        String body = "firstName,lastName,email\n"
                + "Alice,Smith,alice@example.com\n" // VALID after parse
                + "Bob,Brown,\n";                   // ERROR: email required

        // confirmImport runs parse+validate+import; rowImporter.importRow is a void mock (no-op)
        employeeImportService.confirmImport(csv(body), true);

        // Only alice's row must reach the importer; bob's ERROR row is skipped.
        verify(rowImporter, times(1))
                .importRow(any(EmployeeImportService.ParsedRow.class), eq(true));
    }
}
