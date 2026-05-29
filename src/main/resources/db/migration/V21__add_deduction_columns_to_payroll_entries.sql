-- Adds unpaid-leave and attendance deduction columns to payroll_entries.
-- Both are stored in kobo (Long) as separate line items on the payslip.
-- late_days_in_period is informational only — no automatic deduction is applied;
-- HR can review late records during payroll approval and act in a later phase.

ALTER TABLE payroll_entries
    ADD COLUMN unpaid_leave_deduction BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN attendance_deduction   BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN late_days_in_period    INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN payroll_entries.unpaid_leave_deduction IS
    'Kobo deducted for approved unpaid leave days in the payroll period: (grossSalary / workingDays) * unpaidLeaveDays';
COMMENT ON COLUMN payroll_entries.attendance_deduction IS
    'Kobo deducted for ABSENT attendance records in the payroll period: (grossSalary / workingDays) * absentDays';
COMMENT ON COLUMN payroll_entries.late_days_in_period IS
    'Count of LATE attendance records in the period — informational only, no automatic deduction applied';
