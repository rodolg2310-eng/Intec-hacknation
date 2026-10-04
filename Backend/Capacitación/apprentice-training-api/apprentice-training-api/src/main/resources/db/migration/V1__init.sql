-- Esquema del modulo de capacitacion (Teach) del AI Apprentice.
-- Todas las tablas de datos llevan tenant_id: cada empresa ve solo lo suyo.

CREATE TABLE tenants (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug text NOT NULL UNIQUE,
  name text NOT NULL,
  api_key_hash text NOT NULL,
  elevenlabs_agent_id text,
  db_url text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE workmaps (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  title text NOT NULL,
  role text,
  onet_code text,
  expert_name text,
  language text NOT NULL DEFAULT 'es',
  summary text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_workmaps_tenant ON workmaps(tenant_id);

CREATE TABLE steps (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  workmap_id uuid NOT NULL REFERENCES workmaps(id) ON DELETE CASCADE,
  position int NOT NULL,
  title text NOT NULL,
  screen_ts text,
  screenshot_url text,
  decision text,
  reason_quote text,
  reason_author text,
  reason_ts text,
  risk text NOT NULL DEFAULT 'low',
  off_record boolean NOT NULL DEFAULT false,
  UNIQUE (workmap_id, position)
);
CREATE INDEX idx_steps_tenant ON steps(tenant_id);

CREATE TABLE guardrails (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  workmap_id uuid NOT NULL REFERENCES workmaps(id) ON DELETE CASCADE,
  step_id uuid REFERENCES steps(id) ON DELETE SET NULL,
  kind text NOT NULL,
  condition text NOT NULL,
  correct_action text NOT NULL,
  expert_quote text,
  expert_ts text,
  off_record boolean NOT NULL DEFAULT false
);
CREATE INDEX idx_guardrails_tenant ON guardrails(tenant_id);

CREATE TABLE cases (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  workmap_id uuid NOT NULL REFERENCES workmaps(id) ON DELETE CASCADE,
  title text NOT NULL,
  kind text NOT NULL,
  novelty text NOT NULL DEFAULT 'seen',
  difficulty int NOT NULL DEFAULT 1,
  data jsonb NOT NULL DEFAULT '{}',
  expected jsonb NOT NULL DEFAULT '[]',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_cases_tenant ON cases(tenant_id);

CREATE TABLE learners (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  name text NOT NULL,
  external_id text,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, external_id)
);

CREATE TABLE training_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  learner_id uuid NOT NULL REFERENCES learners(id),
  workmap_id uuid NOT NULL REFERENCES workmaps(id),
  case_id uuid REFERENCES cases(id),
  level text NOT NULL DEFAULT 'observe',
  status text NOT NULL DEFAULT 'active',
  current_step int NOT NULL DEFAULT 1,
  score double precision,
  summary jsonb,
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz
);
CREATE INDEX idx_sessions_tenant ON training_sessions(tenant_id);

CREATE TABLE session_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  session_id uuid NOT NULL REFERENCES training_sessions(id) ON DELETE CASCADE,
  ts text,
  type text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_events_tenant ON session_events(tenant_id);

CREATE TABLE predictions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  session_id uuid NOT NULL REFERENCES training_sessions(id) ON DELETE CASCADE,
  step_id uuid NOT NULL REFERENCES steps(id),
  answer text NOT NULL,
  explanation text,
  correct boolean NOT NULL,
  feedback text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_predictions_tenant ON predictions(tenant_id);

CREATE TABLE interventions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  session_id uuid NOT NULL REFERENCES training_sessions(id) ON DELETE CASCADE,
  step_id uuid REFERENCES steps(id),
  guardrail_id uuid REFERENCES guardrails(id),
  attempted text,
  message text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_interventions_tenant ON interventions(tenant_id);

CREATE TABLE mastery (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  learner_id uuid NOT NULL REFERENCES learners(id) ON DELETE CASCADE,
  step_id uuid NOT NULL REFERENCES steps(id) ON DELETE CASCADE,
  attempts int NOT NULL DEFAULT 0,
  correct int NOT NULL DEFAULT 0,
  streak int NOT NULL DEFAULT 0,
  seen_new_case boolean NOT NULL DEFAULT false,
  last_practiced timestamptz,
  next_due timestamptz,
  UNIQUE (learner_id, step_id)
);
CREATE INDEX idx_mastery_tenant ON mastery(tenant_id);

-- Segunda barrera de aislamiento: Row Level Security por tenant.
-- La aplicacion fija app.tenant_id en cada conexion; sin el, no se ve nada.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'workmaps','steps','guardrails','cases','learners',
    'training_sessions','session_events','predictions','interventions','mastery']
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
      t);
  END LOOP;
END $$;
