ALTER TABLE app_accounts ADD COLUMN role varchar(16) NOT NULL DEFAULT 'MASTER';
ALTER TABLE app_accounts ADD COLUMN active boolean NOT NULL DEFAULT true;
CREATE UNIQUE INDEX one_master_per_workspace ON app_accounts(workspace_id) WHERE role = 'MASTER';
ALTER TABLE studio_sessions ADD COLUMN author_account_id uuid REFERENCES app_accounts(id);
ALTER TABLE studio_sessions ADD COLUMN publication_version integer NOT NULL DEFAULT 0;
CREATE TABLE workspace_invitations (
  id uuid PRIMARY KEY, token_hash varchar(255) NOT NULL UNIQUE, workspace_id uuid NOT NULL REFERENCES tenants(id),
  email varchar(255) NOT NULL, role varchar(16) NOT NULL CHECK(role IN ('SENIOR','LEARNER')),
  expires_at timestamptz NOT NULL, consumed_at timestamptz, revoked boolean NOT NULL DEFAULT false, version bigint NOT NULL DEFAULT 0
);
CREATE TABLE frame_jobs (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id), session_id uuid NOT NULL REFERENCES studio_sessions(id),
  sequence bigint NOT NULL, elapsed_seconds bigint NOT NULL, image_path varchar(255) NOT NULL, status varchar(255) NOT NULL,
  error varchar(255), attempts integer NOT NULL DEFAULT 0, created_at timestamptz, observation text, UNIQUE(session_id,sequence)
);
CREATE TABLE media_chunks (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id), session_id uuid NOT NULL REFERENCES studio_sessions(id),
  recording_id uuid, sequence bigint NOT NULL, start_seconds double precision NOT NULL, end_seconds double precision NOT NULL,
  kind varchar(255), path varchar(255), content_type varchar(255), checksum varchar(255), final_chunk boolean NOT NULL DEFAULT false,
  removed boolean NOT NULL DEFAULT false, UNIQUE(session_id,recording_id,sequence)
);
CREATE TABLE studio_questions (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id), session_id uuid NOT NULL REFERENCES studio_sessions(id),
  event_id varchar(255), stage varchar(255), text text, status varchar(255), issued_at_seconds bigint NOT NULL,
  delivered_at_seconds bigint, answer_message_id varchar(255), window_index integer NOT NULL DEFAULT 0
);
CREATE TABLE learning_attempts (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id), session_id uuid NOT NULL REFERENCES studio_sessions(id),
  learner_id uuid NOT NULL REFERENCES app_accounts(id), map_version integer NOT NULL, payload text NOT NULL, version bigint NOT NULL DEFAULT 0,
  UNIQUE(session_id,learner_id,map_version)
);
CREATE TABLE studio_publications (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenants(id), session_id uuid NOT NULL REFERENCES studio_sessions(id),
  map_version integer NOT NULL, payload text NOT NULL, withdrawn boolean NOT NULL DEFAULT false, UNIQUE(session_id,map_version)
);
DO $$ DECLARE table_name text; BEGIN
  FOREACH table_name IN ARRAY ARRAY['frame_jobs','media_chunks','studio_questions','learning_attempts','studio_publications'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
    EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',table_name);
  END LOOP;
END $$;
