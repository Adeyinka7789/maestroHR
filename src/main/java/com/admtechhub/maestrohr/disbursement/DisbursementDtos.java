package com.admtechhub.maestrohr.disbursement;

import java.util.List;
import java.util.UUID;

/** View records for the pre-run NUBAN validation panel and the disbursement page. */
public final class DisbursementDtos {

    private DisbursementDtos() {}

    /** One employee's account-validation outcome. status ∈ {OK, WARN, ERROR}. */
    public record ValidationRow(
            UUID employeeId,
            String employeeName,
            String employeeNumber,
            String bank,
            String accountNumber,
            String resolvedName,   // name Paystack returned, or "—"
            String status,         // OK | WARN | ERROR
            String message) {}     // reason for WARN/ERROR, else ""

    /** Result of validating a whole run's accounts against Paystack name-enquiry. */
    public record ValidationResult(
            int okCount, int warnCount, int errorCount, List<ValidationRow> rows) {

        public boolean hasErrors() {
            return errorCount > 0;
        }

        public boolean isEmpty() {
            return rows == null || rows.isEmpty();
        }
    }

    /** A payroll run offered for disbursement. */
    public record RunOption(UUID runId, String periodLabel, String status) {}

    /** A per-status count of the selected run's transfer line items. */
    public record StatusCount(String label, int count, String kind) {}

    /** The disbursement page model. {@code validation} is null until the operator runs it. */
    public record DisbursementPageView(
            String paystackMode,        // LIVE | TEST | UNSET
            boolean liveMode,
            List<RunOption> runs,
            UUID selectedRunId,
            String selectedPeriodLabel,
            String selectedStatus,
            boolean runApproved,        // only an APPROVED run may be disbursed via Paystack
            int transferCount,
            int retryableCount,         // FAILED + REVERSED entries eligible for a retry
            String amountFormatted,     // total net to disburse
            List<StatusCount> entryStatus,
            ValidationResult validation) {

        public boolean hasRuns() {
            return runs != null && !runs.isEmpty();
        }

        public boolean hasSelection() {
            return selectedRunId != null;
        }
    }
}
