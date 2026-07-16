-- V57__seed_all_platform_flags.sql
-- Guarantee a platform_flags row for every SubscriptionFeature, enabled, at the schema level.
--
-- The runtime PlatformFlagSeeder (ApplicationRunner) already seeds these at boot, but flag
-- resolution is now fail-CLOSED (an absent row → disabled, in both PlatformFlagService and the
-- /api/features/active nav check). Seeding in a migration removes any dependency on the runtime
-- seeder for known flags — rows exist the instant the schema is up, before the first request or
-- any pre-seed boot race. Idempotent: ON CONFLICT DO NOTHING, so it never clobbers an admin's
-- deliberate enabled/rollout changes on re-run or when a row already exists (V30/V32/V34).
--
-- Keep this list in sync with com.admtechhub.maestrohr.tenant.SubscriptionFeature.

INSERT INTO platform_flags (name, enabled, description, updated_by) VALUES
    ('BASIC_EMPLOYEES',      true, 'basic employees',      'system'),
    ('UNLIMITED_EMPLOYEES',  true, 'unlimited employees',  'system'),
    ('BASIC_PAYROLL',        true, 'basic payroll',        'system'),
    ('ADVANCED_PAYROLL',     true, 'advanced payroll',     'system'),
    ('API_ACCESS',           true, 'api access',           'system'),
    ('EMAIL_SUPPORT',        true, 'email support',        'system'),
    ('PRIORITY_SUPPORT',     true, 'priority support',     'system'),
    ('LEAVE_MANAGEMENT',     true, 'leave management',     'system'),
    ('ATTENDANCE_TRACKING',  true, 'attendance tracking',  'system'),
    ('LOAN_MANAGEMENT',      true, 'loan management',      'system'),
    ('DOCUMENT_VAULT',       true, 'document vault',       'system'),
    ('CUSTOM_REPORTING',     true, 'custom reporting',     'system'),
    ('SMS_NOTIFICATIONS',    true, 'sms notifications',    'system'),
    ('HARDWARE_SYNC',        true, 'hardware sync',        'system')
ON CONFLICT (name) DO NOTHING;
