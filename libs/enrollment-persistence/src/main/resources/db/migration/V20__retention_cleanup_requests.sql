-- 보존 정리 요청 (ADR 0047). 조직이 집계 보존을 줄인 저장이 남기고, 보존 작업(:apps:retention-worker)의 요청 모드가 선점해
-- 기존 보존 삭제(ADR 0024 §4)로 실행한다. 요청 하나는 retention_cleanup 작업(enrollment.operations) 하나와 짝이다.
-- 경계는 요청 시각(as_of)에서 계산한다 — 미완으로 끝나 다시 실행해도 같은 경계다.
CREATE TABLE enrollment.retention_cleanup_requests (
    operation_id uuid PRIMARY KEY REFERENCES enrollment.operations(id),
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    retention_months smallint NOT NULL CHECK (retention_months >= 1),
    as_of timestamptz NOT NULL,
    runs integer NOT NULL DEFAULT 0 CHECK (runs >= 0),
    claimed_by varchar(64),
    claimed_until timestamptz,
    closed_at timestamptz,
    outcome varchar(24) CHECK (outcome IN ('logically_deleted','failed','superseded')),
    CONSTRAINT retention_cleanup_requests_claim CHECK ((claimed_by IS NULL) = (claimed_until IS NULL)),
    CONSTRAINT retention_cleanup_requests_closed CHECK ((closed_at IS NULL) = (outcome IS NULL)),
    CONSTRAINT retention_cleanup_requests_closed_unclaimed CHECK (closed_at IS NULL OR claimed_by IS NULL),
    -- 대체는 한 번도 실행하지 않은 요청에만 일어난다.
    CONSTRAINT retention_cleanup_requests_superseded_unrun CHECK (outcome IS DISTINCT FROM 'superseded' OR runs = 0)
);
CREATE INDEX retention_cleanup_requests_open_idx ON enrollment.retention_cleanup_requests (tenant_id, as_of, operation_id) WHERE closed_at IS NULL;
-- 조직마다 아직 한 번도 실행하지 않은 요청은 하나다 — 새 저장이 그것을 대체한다.
CREATE UNIQUE INDEX retention_cleanup_requests_waiting_idx ON enrollment.retention_cleanup_requests (tenant_id) WHERE closed_at IS NULL AND runs = 0;

COMMENT ON TABLE enrollment.retention_cleanup_requests IS '집계 보존 단축이 남긴 보존 정리 요청 (ADR 0047). 실행은 retention-worker 의 요청 모드뿐이다.';
COMMENT ON COLUMN enrollment.retention_cleanup_requests.as_of IS '경계 계산의 기준 시각(저장 시각). 삭제 경계 = 이 시각의 KST 날짜에서 retention_months 개월 전 자정.';
COMMENT ON COLUMN enrollment.retention_cleanup_requests.runs IS '선점한 횟수. 0 이면 아직 한 번도 실행하지 않았다(대체될 수 있다).';
COMMENT ON COLUMN enrollment.retention_cleanup_requests.claimed_until IS '선점 기한. 지나면 다른 실행이 다시 선점한다 — 보존 삭제는 같은 입력으로 다시 돌면 이어서 끝난다.';
COMMENT ON COLUMN enrollment.retention_cleanup_requests.outcome IS 'logically_deleted·failed = 실행 결과, superseded = 실행 전에 새 저장이 대체했다.';
