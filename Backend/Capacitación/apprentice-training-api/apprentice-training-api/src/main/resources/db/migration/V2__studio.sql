CREATE TABLE app_accounts (
  id uuid PRIMARY KEY, email varchar(255) NOT NULL UNIQUE,
  name varchar(255) NOT NULL, password_hash varchar(255) NOT NULL,
  workspace_id uuid NOT NULL REFERENCES tenants(id), demo boolean NOT NULL
);
CREATE TABLE login_sessions (
  token_hash varchar(255) PRIMARY KEY, account_id uuid NOT NULL REFERENCES app_accounts(id),
  expires_at timestamptz NOT NULL
);
CREATE TABLE studio_sessions (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id),
  title varchar(255) NOT NULL, expert varchar(255) NOT NULL,
  phase varchar(255) NOT NULL, created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL, payload text NOT NULL, version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_studio_tenant ON studio_sessions(tenant_id);
ALTER TABLE studio_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE studio_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON studio_sessions
  USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE workmaps ALTER COLUMN summary TYPE text;
ALTER TABLE steps ALTER COLUMN decision TYPE text;
ALTER TABLE steps ALTER COLUMN reason_quote TYPE text;
ALTER TABLE guardrails ALTER COLUMN condition TYPE text;
ALTER TABLE guardrails ALTER COLUMN correct_action TYPE text;
ALTER TABLE guardrails ALTER COLUMN expert_quote TYPE text;
