-- V67__seed_new_feature_flags.sql
-- Register platform_flags rows for the SubscriptionFeature values added after V57, so every
-- gateable feature has a row the instant the schema is up (flag resolution is fail-CLOSED — an
-- absent row resolves to disabled in both PlatformFlagService and /api/features/active).
--
--   * RECRUITMENT      — was added to the enum after V57 and never migration-seeded (it existed
--                        only via the runtime PlatformFlagSeeder). Backfilled here.
--   * EXIT_MANAGEMENT  — Exit Management page, now flag-gated.
--   * COMPLIANCE       — Compliance & Expiry dashboard, now flag-gated.
--   * PUBLIC_HOLIDAYS  — Public-holiday calendar settings, now flag-gated.
--
-- All enabled, so current tenants see no change; the Super-Admin Feature Flags page can now flip
-- them platform-wide for staged rollout. Idempotent (ON CONFLICT DO NOTHING) — never clobbers an
-- admin's deliberate enabled/rollout changes, or a row the runtime seeder already created.
--
-- Keep this list in sync with com.admtechhub.maestrohr.tenant.SubscriptionFeature.

INSERT INTO platform_flags (name, enabled, description, updated_by) VALUES
    ('RECRUITMENT',      true, 'recruitment',      'system'),
    ('EXIT_MANAGEMENT',  true, 'exit management',  'system'),
    ('COMPLIANCE',       true, 'compliance',       'system'),
    ('PUBLIC_HOLIDAYS',  true, 'public holidays',  'system')
ON CONFLICT (name) DO NOTHING;
