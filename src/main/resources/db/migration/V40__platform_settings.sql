CREATE TABLE platform_settings (
  key VARCHAR(100) PRIMARY KEY,
  value TEXT,
  updated_by VARCHAR(255),
  updated_at TIMESTAMPTZ DEFAULT now()
);

INSERT INTO platform_settings (key, value) VALUES
  ('support_email', null),
  ('support_whatsapp', null),
  ('platform_name', 'MaestroHR'),
  ('support_url', null),
  ('trial_duration_days', '14'),
  ('trial_grace_period_days', '0'),
  ('min_wage_kobo', '7000000'),
  ('pension_employee_pct', '8'),
  ('pension_employer_pct', '10'),
  ('nhf_rate_pct', '2.5'),
  ('max_failed_logins', '5'),
  ('sms_sender_id', 'MaestroHR')
ON CONFLICT (key) DO NOTHING;

-- Remove support columns that V39 added to tenants
ALTER TABLE tenants DROP COLUMN IF EXISTS support_email;
ALTER TABLE tenants DROP COLUMN IF EXISTS support_whatsapp;
