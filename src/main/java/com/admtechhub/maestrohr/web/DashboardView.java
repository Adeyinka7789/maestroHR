package com.admtechhub.maestrohr.web;

import java.util.List;
import java.util.UUID;

/**
 * Server-rendered view model for the redesigned dashboard fragment
 * (templates/dashboard.html). Everything the page shows is computed once on the
 * server and rendered into the HTML — no client-side JSON round-trips, no loading flash.
 *
 * The bottom of the page is organised into two HR-meaningful sections instead of a
 * raw audit-log table:
 *   - "Action Required"        — pendingLeaveCount + pendingPayrollApproval
 *   - "This Month at a Glance" — attendanceToday, leaveDaysThisMonth, newHiresThisMonth,
 *                                 plus the birthdays/anniversaries celebrations.
 */
public record DashboardView(
        String periodLabel,        // e.g. "June 2025"
        String role,               // authenticated user's role name; selects the template block
        long activeHeadcount,
        long newHiresThisMonth,
        long onLeaveCount,
        long pendingLeaveCount,
        long pendingPayrollApproval,
        PayrollSummary payroll,
        AttendanceToday attendanceToday,
        long leaveDaysThisMonth,
        List<CelebrationItem> birthdays,
        List<CelebrationItem> anniversaries,
        // Role-specific sections. Exactly one is non-null for a given request (the others
        // stay null); the HR_ADMIN view uses the top-level fields above and leaves all three
        // null. Each maps to a th:if block in dashboard.html keyed off {@code role}.
        EmployeeSection employee,
        FinanceSection finance,
        DeptManagerSection deptManager
) {

    /** The "current payroll" metric card. */
    public record PayrollSummary(
            boolean present,
            String monthLabel,         // e.g. "June 2025"
            String amountFormatted,    // e.g. "₦42,500,000.00"
            String status,             // raw enum name, e.g. "PENDING_APPROVAL"
            String statusLabel,        // humanized, e.g. "Pending Approval"
            String statusKind          // success | warn | error | neutral -> badge colour
    ) {}

    /**
     * Today's attendance snapshot for the "This Month at a Glance" section.
     * {@code present} counts everyone who showed up (PRESENT + LATE + HALF_DAY);
     * {@code total} is the expected roster for the day (all marked records except
     * those ON_LEAVE). When no records exist for today {@code hasRecords} is false
     * and the template shows a graceful empty state.
     */
    public record AttendanceToday(
            boolean hasRecords,
            long present,
            long total,
            int ratePercent            // round(present * 100 / total), 0 when no records
    ) {}

    /** A birthday or work-anniversary entry in the celebrations widgets. */
    public record CelebrationItem(
            String initials,
            String name,
            String subtitle,           // department, or "Role • date"
            String whenLabel,          // e.g. "Today", "Tomorrow", "Jun 16"
            String badge               // e.g. "3y" for anniversaries, empty for birthdays
    ) {}

    // ── EMPLOYEE ────────────────────────────────────────────────────────────────

    /**
     * The self-service dashboard for an EMPLOYEE. {@code hasProfile} is false for accounts
     * that authenticate but have no Employee record (rare for this role); the template then
     * shows a friendly "no profile" card instead of the widgets.
     */
    public record EmployeeSection(
            boolean hasProfile,
            String employeeName,
            List<LeaveBalanceItem> leaveBalances,
            // Today's own attendance
            String attendanceDateLabel,
            String attendanceStatusLabel,
            String attendanceStatusKind,   // success | neutral
            String clockIn,                // "HH:mm" or "—"
            String clockOut,               // "HH:mm" or "—"
            // Latest downloadable payslip (null when none final yet)
            PayslipItem latestPayslip,
            // Own pending leave requests
            long pendingRequestCount,
            List<MyLeaveItem> pendingRequests,
            // Company-wide celebrations (same widgets as HR)
            List<CelebrationItem> birthdays,
            List<CelebrationItem> anniversaries
    ) {}

    /** One leave-type balance row in the EMPLOYEE "My Leave Balances" widget. */
    public record LeaveBalanceItem(
            String typeName,
            int entitled,
            int taken,
            int remaining
    ) {}

    /** The EMPLOYEE's most recent finalized payslip. */
    public record PayslipItem(
            UUID runId,
            String period,             // e.g. "May 2026"
            String amountFormatted,    // net pay, e.g. "₦420,000.00"
            String statusLabel,        // "Approved" | "Disbursing" | "Paid"
            String statusKind          // neutral | warn | success
    ) {}

    /** One of the EMPLOYEE's own pending leave requests. */
    public record MyLeaveItem(
            String typeName,
            String dateRange,          // e.g. "Jun 10 – Jun 14"
            int days,
            String statusLabel,        // "Pending"
            String statusKind          // warn
    ) {}

    // ── FINANCE_OFFICER ───────────────────────────────────────────────────────────

    /** The FINANCE_OFFICER dashboard: payroll approval queue, current cost, recent invoices. */
    public record FinanceSection(
            long pendingPayrollApproval,
            boolean hasPayrollCost,
            String payrollCostFormatted,   // latest approved run net, e.g. "₦42,500,000.00"
            String payrollCostPeriod,      // e.g. "May 2026" or "No approved run yet"
            List<InvoiceItem> recentInvoices
    ) {}

    /** One billing invoice row in the FINANCE "Recent Invoices" panel. */
    public record InvoiceItem(
            String reference,
            String amountFormatted,    // e.g. "₦60,000.00"
            String planLabel,          // humanized plan name
            String statusLabel,        // "Success" | "Pending" | "Failed"
            String statusKind,         // success | warn | error
            String dateLabel           // e.g. "Jun 16"
    ) {}

    // ── DEPT_MANAGER ──────────────────────────────────────────────────────────────

    /**
     * The DEPT_MANAGER dashboard, scoped to the manager's own department. {@code hasDepartment}
     * is false when the account has no Employee record or that employee has no department; the
     * template then shows a friendly "no department" card.
     */
    public record DeptManagerSection(
            boolean hasDepartment,
            String departmentName,
            long headcount,
            long activeCount,
            long onLeaveCount,
            long pendingLeaveCount,
            AttendanceToday attendanceToday,   // reused, scoped to the department
            String payrollCostFormatted        // monthly establishment cost for the department
    ) {}
}
