-- Adds the late-deduction column to payroll_entries (Stage 3 of the attendance-deduction
-- feature). Separate from attendance_deduction, which remains absence-only.

ALTER TABLE payroll_entries
    ADD COLUMN late_deduction BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN payroll_entries.late_deduction IS
    'Kobo deducted for LATE attendance records in the payroll period, computed from the '
    'employee''s effective AttendancePolicy (grace period already applied at status-set time; '
    'this deduction applies late-free-count, late-to-absence conversion, and the FLAT/PERCENTAGE '
    'rate). Separate from attendance_deduction, which remains absence-only.';
