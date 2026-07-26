package com.admtechhub.maestrohr.gl;

import java.util.List;
import java.util.UUID;

/** View/records for the cost-center manager and GL journal export. */
public final class GlDtos {

    private GlDtos() {}

    /** A cost center row for the management page. */
    public record CostCenterView(
            UUID id, String name, String code, String location,
            String glAccountCode, boolean active, long employeeCount) {}

    /** Request to create/update a cost center (from the htmx form). */
    public record CostCenterForm(String name, String code, String location, String glAccountCode) {}

    /** The tenant's GL account codes, for the config form. */
    public record GlConfigView(
            String salaryExpenseAccount, String pensionExpenseAccount, String netPayAccount,
            String payePayableAccount, String pensionPayableAccount, String nhfPayableAccount,
            String otherDeductionsAccount) {

        public static GlConfigView from(GlAccountConfig c) {
            return new GlConfigView(
                    c.getSalaryExpenseAccount(), c.getPensionExpenseAccount(), c.getNetPayAccount(),
                    c.getPayePayableAccount(), c.getPensionPayableAccount(), c.getNhfPayableAccount(),
                    c.getOtherDeductionsAccount());
        }
    }

    /** A finalized payroll run offered for GL export. */
    public record RunOption(UUID runId, String periodLabel, String status) {}

    /** A built, balanced journal for one payroll run. */
    public record JournalView(
            UUID runId, String periodLabel, String status,
            List<JournalLine> lines,
            String totalDebitFormatted, String totalCreditFormatted,
            boolean balanced) {

        public boolean hasLines() {
            return lines != null && !lines.isEmpty();
        }

        /** One journal line: exactly one of debit/credit is non-zero. */
        public record JournalLine(
                String accountCode, String accountName, String costCenter,
                long debitKobo, long creditKobo,
                String debitFormatted, String creditFormatted, String memo) {}
    }
}
