-- V47: Platform settings keys to finish wiring PAYECalculator/NSITFCalculator to
-- PlatformSettingsService instead of hardcoded constants.
--
--   nsitf_rate_pct       — NSITFCalculator employer contribution rate (was hardcoded 0.01)
--   rent_relief_rate_pct — PAYECalculator NTA 2025 rent relief rate (was hardcoded 0.20)
--   rent_relief_cap_kobo — PAYECalculator NTA 2025 rent relief annual cap (was hardcoded 50_000_000)
--
-- min_wage_kobo, pension_employee_pct, pension_employer_pct, and nhf_rate_pct already exist
-- from V40 and are now actually read by the calculators (previously seeded but unused).
INSERT INTO platform_settings (key, value) VALUES
  ('nsitf_rate_pct', '1'),
  ('rent_relief_rate_pct', '20'),
  ('rent_relief_cap_kobo', '50000000')
ON CONFLICT (key) DO NOTHING;
