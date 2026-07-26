-- V62__employee_confirmation.sql
--
-- Probation → Confirmation workflow. An employee on probation (status = ACTIVE with a
-- probation_end_date and no confirmation) is "confirmed" by HR in one click once the
-- probation period is served. Rather than introducing a PROBATION value into
-- employee_status — which would silently exclude those staff from payroll, headcount,
-- retirement and birthday sweeps, all of which key off status = 'ACTIVE' — probation is
-- modelled as an overlay on ACTIVE via these two nullable columns:
--
--   confirmed_at  NULL  → still on probation (when probation_end_date is set)
--   confirmed_at  set   → confirmation recorded; benefits / pay-grade changes unlocked
--
-- No RLS/policy change is needed: the employees table already carries the standard NULLIF
-- tenant policy, and adding nullable columns leaves it intact.

ALTER TABLE employees
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD COLUMN confirmed_by VARCHAR(255);

-- Backs the compliance dashboard's "probation reviews due" scan: unconfirmed employees
-- whose probation window is closing, per tenant, ordered by how soon.
CREATE INDEX idx_employees_probation_due
    ON employees (tenant_id, probation_end_date)
    WHERE confirmed_at IS NULL AND probation_end_date IS NOT NULL AND deleted_at IS NULL;
