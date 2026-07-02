package com.admtechhub.maestrohr.disbursement;

import com.admtechhub.maestrohr.disbursement.provider.CSVDisbursementProvider;
import com.admtechhub.maestrohr.employee.Department;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.PayGrade;
import com.admtechhub.maestrohr.payroll.DisbursementService;
import com.admtechhub.maestrohr.payroll.PayrollEntry;
import com.admtechhub.maestrohr.payroll.PayrollEntryRepository;
import com.admtechhub.maestrohr.payroll.PayrollRun;
import com.admtechhub.maestrohr.payroll.PayrollRunRepository;
import com.admtechhub.maestrohr.payroll.PayrollStatus;
import com.admtechhub.maestrohr.payroll.TransferStatus;
import com.admtechhub.maestrohr.paystack.PaystackClient;
import com.admtechhub.maestrohr.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.admtechhub.maestrohr.paystack.dto.PaystackResponse;
import com.admtechhub.maestrohr.tenant.Tenant;
import static org.mockito.ArgumentMatchers.eq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisbursementServiceTest {

    @Mock PayrollRunRepository      payrollRunRepository;
    @Mock PayrollEntryRepository    payrollEntryRepository;
    @Mock PaystackClient            paystackClient;
    @Mock CSVDisbursementProvider   csvDisbursementProvider;
    @InjectMocks DisbursementService disbursementService;

    @BeforeEach
    void bindTenant() {
        TenantContext.setCurrentTenant(UUID.randomUUID().toString());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ── disburseSalaries tests ────────────────────────────────────────────────────────────

    @Test
    void disburseSalaries_wrongStatus_throws() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = mock(PayrollRun.class);
        when(run.getStatus()).thenReturn(PayrollStatus.DRAFT);
        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> disbursementService.disburseSalaries(runId));

        assertTrue(ex.getMessage().contains("APPROVED"),
                "Exception message must mention the required APPROVED status");
    }

    @SuppressWarnings("unchecked")
    @Test
    void disburseSalaries_noRecipientCode_skipsEmployee() {
        UUID runId = UUID.randomUUID();

        PayrollRun run = mock(PayrollRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getStatus()).thenReturn(PayrollStatus.APPROVED);
        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));

        Employee emp = mock(Employee.class);
        when(emp.getPaystackRecipientCode()).thenReturn(null);
        when(emp.getEmployeeNumber()).thenReturn("EMP-NOCODE");
        Department dept = mock(Department.class);
        when(dept.getName()).thenReturn("Engineering");
        when(emp.getDepartment()).thenReturn(dept);
        PayGrade pg = mock(PayGrade.class);
        when(pg.getName()).thenReturn("Grade-A");
        when(emp.getPayGrade()).thenReturn(pg);

        PayrollEntry entry = mock(PayrollEntry.class);
        when(entry.getTransferStatus()).thenReturn(TransferStatus.PENDING);
        when(entry.getEmployee()).thenReturn(emp);
        when(entry.getNetSalary()).thenReturn(100_000L);

        when(payrollEntryRepository.findByPayrollRunId(eq(runId), any(UUID.class))).thenReturn(List.of(entry));

        assertThrows(IllegalStateException.class,
                () -> disbursementService.disburseSalaries(runId));
    }
    // ── generateReference tests (via reflection — method is private) ──────────────────────

    @Test
    void generateReference_includesTenantPrefix() throws Exception {
        UUID tenantId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(tenantId);

        PayrollRun run = mock(PayrollRun.class);
        when(run.getTenant()).thenReturn(tenant);
        when(run.getPeriod()).thenReturn("2025-01");

        Employee emp = mock(Employee.class);
        when(emp.getEmployeeNumber()).thenReturn("EMP-001");

        PayrollEntry entry = mock(PayrollEntry.class);
        when(entry.getPayrollRun()).thenReturn(run);
        when(entry.getEmployee()).thenReturn(emp);

        String ref = callGenerateReference(entry);

        assertEquals("SAL-a1b2c3d4-2025-01-EMP-001", ref,
                "Reference must follow SAL-{tenantPrefix8}-{period}-{empNumber} pattern");
    }

    @Test
    void generateReference_noCollisionAcrossTenants() throws Exception {
        UUID tenantIdA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000");
        UUID tenantIdB = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000000");

        Tenant tenantA = mock(Tenant.class);
        when(tenantA.getId()).thenReturn(tenantIdA);
        Tenant tenantB = mock(Tenant.class);
        when(tenantB.getId()).thenReturn(tenantIdB);

        PayrollRun runA = mock(PayrollRun.class);
        when(runA.getTenant()).thenReturn(tenantA);
        when(runA.getPeriod()).thenReturn("2025-01");

        PayrollRun runB = mock(PayrollRun.class);
        when(runB.getTenant()).thenReturn(tenantB);
        when(runB.getPeriod()).thenReturn("2025-01");

        Employee emp = mock(Employee.class);
        when(emp.getEmployeeNumber()).thenReturn("EMP-001");

        PayrollEntry entryA = mock(PayrollEntry.class);
        when(entryA.getPayrollRun()).thenReturn(runA);
        when(entryA.getEmployee()).thenReturn(emp);

        PayrollEntry entryB = mock(PayrollEntry.class);
        when(entryB.getPayrollRun()).thenReturn(runB);
        when(entryB.getEmployee()).thenReturn(emp);

        String refA = callGenerateReference(entryA);
        String refB = callGenerateReference(entryB);

        assertNotEquals(refA, refB,
                "Same employee number under different tenants must produce different references");
        assertTrue(refA.startsWith("SAL-aaaaaaaa-"), "Tenant A prefix must be the first 8 chars of tenant A's UUID");
        assertTrue(refB.startsWith("SAL-bbbbbbbb-"), "Tenant B prefix must be the first 8 chars of tenant B's UUID");
    }

    // ── Helper ────────────────────────────────────────────────────────────────────────────

    private String callGenerateReference(PayrollEntry entry) throws Exception {
        Method m = DisbursementService.class.getDeclaredMethod("generateReference", PayrollEntry.class);
        m.setAccessible(true);
        return (String) m.invoke(disbursementService, entry);
    }
}