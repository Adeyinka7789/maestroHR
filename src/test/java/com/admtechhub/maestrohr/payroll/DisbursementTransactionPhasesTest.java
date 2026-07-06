package com.admtechhub.maestrohr.payroll;

import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.tenant.Tenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DisbursementTransactionPhases} — the transactional phases behind
 * {@link DisbursementService}'s disbursement orchestration. These tests used to live on
 * {@code DisbursementServiceTest} (as validation-path tests against
 * {@code disbursementService.disburseSalaries(...)}), but that validation logic moved to
 * {@link DisbursementTransactionPhases#markRunDisbursing} when the self-invocation fix split
 * disbursement into separate-bean phases — so the tests moved with it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisbursementTransactionPhasesTest {

    @Mock PayrollRunRepository payrollRunRepository;
    @Mock PayrollEntryRepository payrollEntryRepository;
    @InjectMocks DisbursementTransactionPhases transactionPhases;

    private static final UUID TENANT_ID = UUID.randomUUID();

    // ── markRunDisbursing (Phase 1) ──────────────────────────────────────────────────────────

    @Test
    void markRunDisbursing_wrongStatus_throws() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = mock(PayrollRun.class);
        when(run.getStatus()).thenReturn(PayrollStatus.DRAFT);
        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transactionPhases.markRunDisbursing(runId, TENANT_ID));

        assertTrue(ex.getMessage().contains("APPROVED"),
                "Exception message must mention the required APPROVED status");
        verify(payrollRunRepository, never()).save(any());
    }

    @Test
    void markRunDisbursing_noRecipientCode_throws() {
        UUID runId = UUID.randomUUID();

        PayrollRun run = mock(PayrollRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getStatus()).thenReturn(PayrollStatus.APPROVED);
        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));

        Employee emp = mock(Employee.class);
        when(emp.getPaystackRecipientCode()).thenReturn(null);
        when(emp.getEmployeeNumber()).thenReturn("EMP-NOCODE");

        PayrollEntry entry = mock(PayrollEntry.class);
        when(entry.getTransferStatus()).thenReturn(TransferStatus.PENDING);
        when(entry.getEmployee()).thenReturn(emp);
        when(entry.getNetSalary()).thenReturn(100_000L);

        when(payrollEntryRepository.findByPayrollRunId(eq(runId), eq(TENANT_ID))).thenReturn(List.of(entry));

        assertThrows(IllegalStateException.class,
                () -> transactionPhases.markRunDisbursing(runId, TENANT_ID));
    }

    @Test
    void markRunDisbursing_validRun_marksDisbursingAndReturnsBatchReferenceAndTransfers() {
        UUID runId = UUID.randomUUID();

        PayrollRun run = mock(PayrollRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getStatus()).thenReturn(PayrollStatus.APPROVED);
        when(run.getPeriod()).thenReturn("2026-06");
        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));

        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(TENANT_ID);
        when(run.getTenant()).thenReturn(tenant);

        Employee emp = mock(Employee.class);
        when(emp.getPaystackRecipientCode()).thenReturn("RCP_123");
        when(emp.getEmployeeNumber()).thenReturn("EMP-001");

        PayrollEntry entry = mock(PayrollEntry.class);
        when(entry.getTransferStatus()).thenReturn(TransferStatus.PENDING);
        when(entry.getEmployee()).thenReturn(emp);
        when(entry.getNetSalary()).thenReturn(500_000L);
        when(entry.getPayrollRun()).thenReturn(run);

        when(payrollEntryRepository.findByPayrollRunId(eq(runId), eq(TENANT_ID))).thenReturn(List.of(entry));

        DisbursementTransactionPhases.DisbursementAttempt attempt =
                transactionPhases.markRunDisbursing(runId, TENANT_ID);

        assertNotNull(attempt.batchReference());
        assertEquals(1, attempt.transfers().size());
        assertEquals("RCP_123", attempt.transfers().get(0).getRecipient());
        verify(run).setStatus(PayrollStatus.DISBURSING);
        verify(payrollRunRepository).save(run);
    }

    // Fix C.2: entry.getNetSalary().intValue() silently truncated any net salary above
    // Integer.MAX_VALUE kobo (~₦21.47M) into a wrong (wrapped) amount before being sent to
    // Paystack. Math.toIntExact must fail loudly instead.
    @Test
    void markRunDisbursing_netSalaryExceedsIntRange_throwsInsteadOfSilentlyTruncating() {
        UUID runId = UUID.randomUUID();

        PayrollRun run = mock(PayrollRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getStatus()).thenReturn(PayrollStatus.APPROVED);
        when(run.getPeriod()).thenReturn("2026-06");
        when(payrollRunRepository.findById(eq(runId))).thenReturn(Optional.of(run));

        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(TENANT_ID);
        when(run.getTenant()).thenReturn(tenant);

        Employee emp = mock(Employee.class);
        when(emp.getPaystackRecipientCode()).thenReturn("RCP_123");
        when(emp.getEmployeeNumber()).thenReturn("EMP-001");

        PayrollEntry entry = mock(PayrollEntry.class);
        when(entry.getTransferStatus()).thenReturn(TransferStatus.PENDING);
        when(entry.getEmployee()).thenReturn(emp);
        when(entry.getNetSalary()).thenReturn(Integer.MAX_VALUE + 1_000_000L);
        when(entry.getPayrollRun()).thenReturn(run);

        when(payrollEntryRepository.findByPayrollRunId(eq(runId), eq(TENANT_ID))).thenReturn(List.of(entry));

        assertThrows(ArithmeticException.class, () -> transactionPhases.markRunDisbursing(runId, TENANT_ID));
    }

    // ── generateReference ─────────────────────────────────────────────────────────────────────

    @Test
    void generateReference_includesTenantPrefix() {
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

        String ref = DisbursementTransactionPhases.generateReference(entry);

        assertEquals("SAL-a1b2c3d4-2025-01-EMP-001", ref,
                "Reference must follow SAL-{tenantPrefix8}-{period}-{empNumber} pattern");
    }

    @Test
    void generateReference_noCollisionAcrossTenants() {
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

        String refA = DisbursementTransactionPhases.generateReference(entryA);
        String refB = DisbursementTransactionPhases.generateReference(entryB);

        assertNotEquals(refA, refB,
                "Same employee number under different tenants must produce different references");
        assertTrue(refA.startsWith("SAL-aaaaaaaa-"), "Tenant A prefix must be the first 8 chars of tenant A's UUID");
        assertTrue(refB.startsWith("SAL-bbbbbbbb-"), "Tenant B prefix must be the first 8 chars of tenant B's UUID");
    }
}
