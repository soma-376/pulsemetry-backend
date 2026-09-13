CREATE SCHEMA IF NOT EXISTS dashboard;
CREATE TYPE dashboard.run_status AS ENUM ('queued','running','succeeded','failed','cancelled');
CREATE TYPE dashboard.time_mode AS ENUM ('fixed','relative');
CREATE TABLE dashboard.scenario_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    scenario_id varchar(32) NOT NULL,
    status dashboard.run_status NOT NULL DEFAULT 'queued',
    params jsonb NOT NULL,
    resolved_from timestamptz NOT NULL,
    resolved_to timestamptz NOT NULL,
    result jsonb,
    error jsonb,
    created_by_member_id uuid NOT NULL REFERENCES enrollment.members(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    lease_until timestamptz,
    CHECK (resolved_from < resolved_to),
    CHECK ((status IN ('queued','running') AND finished_at IS NULL) OR
           (status IN ('succeeded','failed','cancelled') AND finished_at IS NOT NULL))
);
CREATE INDEX scenario_runs_tenant_page ON dashboard.scenario_runs(tenant_id,created_at DESC,id DESC);
CREATE TABLE dashboard.saved_reports (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES dashboard.scenario_runs(id),
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    name varchar(200) NOT NULL,
    note text,
    time_mode dashboard.time_mode NOT NULL,
    created_by_member_id uuid NOT NULL REFERENCES enrollment.members(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX saved_reports_tenant_page ON dashboard.saved_reports(tenant_id,created_at DESC,id DESC);
CREATE TABLE dashboard.audit_log (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    member_id uuid NOT NULL REFERENCES enrollment.members(id),
    action varchar(100) NOT NULL,
    target text NOT NULL,
    reason varchar(500) NOT NULL CHECK (char_length(reason) BETWEEN 10 AND 500),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_tenant_time ON dashboard.audit_log(tenant_id,created_at DESC);
