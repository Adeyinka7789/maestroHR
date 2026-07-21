-- V59: Add annual rent paid to employees for PAYE Rent Relief (Nigeria Tax Act 2025).
--
-- PAYECalculator computes Rent Relief = rent_relief_rate_pct% of annual rent paid,
-- capped at rent_relief_cap_kobo (both from platform_settings, seeded in V47). Until now
-- the calculator was fed annualRentPaid = 0 because the employee profile had no rent field,
-- so the relief branch was a permanent no-op. This column sources the real figure.
--
-- Stored in kobo (1 NGN = 100 kobo), consistent with every other money column. Defaults to
-- 0 (no rent declared → no relief) and is NOT NULL so the calculator never sees a null.
ALTER TABLE employees
    ADD COLUMN annual_rent_paid BIGINT NOT NULL DEFAULT 0;
