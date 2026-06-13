-- V23__fix_pricing_kobo_scale.sql
-- Fix the 100x price-unit bug.
--
-- The price_kobo columns were seeded (V12) with values that are actually NAIRA, not
-- kobo: e.g. BASIC monthly = 25000, which read as kobo is only ₦250 — 100x too low.
-- Intended prices are ₦25,000 / ₦75,000 / ₦200,000 per month. Everything downstream
-- already treats the column as genuine kobo (÷100 for display, raw to Paystack), so the
-- fix is purely to the stored magnitude: multiply by 100.
--
-- Applied to both pricing_config (the price catalogue) and tenant_subscriptions
-- (whose price_kobo was backfilled from pricing_config in V22, so it inherited the bug).
-- invoices is untouched: the table was created empty in V22.
--
-- Zero rows (FREE_TRIAL) are unaffected (0 * 100 = 0). This runs once; later admin edits
-- via /api/admin/pricing are expected to be entered in correct kobo.

UPDATE pricing_config
   SET price_kobo = price_kobo * 100
 WHERE price_kobo > 0;

UPDATE tenant_subscriptions
   SET price_kobo = price_kobo * 100
 WHERE price_kobo > 0;
