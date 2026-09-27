-- 조직별 보존 삭제 작업의 기록 (ADR 0024 §4·§5).
--
-- 한 행은 보존 작업의 한 번 실행이다. 같은 입력으로 다시 실행하면 새 행이다 — 경계 발효·fence·DELETE 가 모두
-- 멱등이라 재실행이 이어서 끝낸다. 쓰는 것은 보존 작업(:apps:retention-worker)뿐이다.
--
-- logically_deleted 는 **논리 삭제 완료**다 — 경계 발효, 구 경계로 등록된 INSERT 의 drain, 두 분석 테이블의
-- DELETE, 그 뒤 남은 행 0 을 확인했다는 뜻이다. 디스크에서의 물리 제거(merge)는 기록하지 않는다 — 이 작업에는
-- 그것을 판정할 근거가 없다.
--
-- 관측 고유 수와 물리 revision 행 수를 따로 둔다. 같은 관측의 교체 행(row_version)이 merge 전에 여럿 남아 있을 수
-- 있어 두 수가 다르다. 둘 다 DELETE 직전에 센 값이다.
--
-- 보존 수치를 여기 두지 않는다 — retention_months 는 실행의 입력이다.

CREATE TYPE telemetry_ops.retention_operation_status AS ENUM ('running', 'incomplete', 'logically_deleted', 'failed');

CREATE TABLE telemetry_ops.retention_operations (
    operation_id              uuid        NOT NULL,
    tenant_id                 uuid        NOT NULL,
    retention_months          integer     NOT NULL,
    as_of                     timestamptz NOT NULL,
    requested_before          timestamptz NOT NULL,
    deleted_before            timestamptz,
    policy_epoch              bigint,
    status                    telemetry_ops.retention_operation_status NOT NULL,
    started_at                timestamptz NOT NULL,
    finished_at               timestamptz,
    event_observations        bigint,
    event_rows                bigint,
    metric_point_observations bigint,
    metric_point_rows         bigint,
    detail                    text,
    CONSTRAINT pk_retention_operations PRIMARY KEY (operation_id),
    CONSTRAINT ck_retention_operations_months CHECK (retention_months > 0),
    CONSTRAINT ck_retention_operations_applied CHECK ((deleted_before IS NULL) = (policy_epoch IS NULL)),
    CONSTRAINT ck_retention_operations_boundary CHECK (deleted_before IS NULL OR deleted_before >= requested_before),
    CONSTRAINT ck_retention_operations_finished CHECK ((status = 'running') = (finished_at IS NULL)),
    CONSTRAINT ck_retention_operations_deleted CHECK (
        status <> 'logically_deleted' OR (
            deleted_before IS NOT NULL
            AND event_observations IS NOT NULL AND event_rows IS NOT NULL
            AND metric_point_observations IS NOT NULL AND metric_point_rows IS NOT NULL
        )
    )
);

CREATE INDEX ix_retention_operations_tenant ON telemetry_ops.retention_operations (tenant_id, started_at);

COMMENT ON TABLE telemetry_ops.retention_operations IS
    '조직별 보존 삭제 작업의 실행 기록. logically_deleted 는 논리 삭제 완료이고 물리 제거(merge) 완료가 아니다 (ADR 0024)';
COMMENT ON COLUMN telemetry_ops.retention_operations.requested_before IS
    '입력(as_of·retention_months)에서 계산한 경계. 이미 더 늦은 경계가 있으면 deleted_before 가 그 값이다';
COMMENT ON COLUMN telemetry_ops.retention_operations.deleted_before IS
    '발효된 경계(tenant_retention_boundary 의 값). DELETE 조건이 이것이다. 발효 전이면 NULL';
COMMENT ON COLUMN telemetry_ops.retention_operations.status IS
    'incomplete = 구 경계 INSERT 가 대기 상한 안에 끝나지 않았거나 DELETE 뒤에도 행이 남았다. 다시 실행한다';
COMMENT ON COLUMN telemetry_ops.retention_operations.event_rows IS
    'DELETE 직전의 물리 revision 행 수(FINAL 없이). event_observations 는 그 가운데 서로 다른 observation_id 수';
