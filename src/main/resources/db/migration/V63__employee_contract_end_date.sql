-- V63__employee_contract_end_date.sql
--
-- First-class fixed-term contract end date on the employee record. Until now a contract's end
-- was tracked only indirectly via a CONTRACT-type document's expiry_date in the vault; this
-- promotes it to a real, queryable column so the Compliance & Expiry dashboard can flag
-- fixed-term contracts approaching their end independently of whether a document was uploaded.
--
-- Nullable: permanent staff have no contract end date. No RLS/policy change needed — the
-- employees table already carries the standard NULLIF tenant policy and this is an added column.

ALTER TABLE employees
    ADD COLUMN contract_end_date DATE;

-- Backs the compliance dashboard's "fixed-term contracts ending" scan: employees with a contract
-- end date, per tenant, ordered by how soon. Partial to match the (mostly-null) column.
CREATE INDEX idx_employees_contract_end
    ON employees (tenant_id, contract_end_date)
    WHERE contract_end_date IS NOT NULL AND deleted_at IS NULL;
