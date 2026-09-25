-- telemetry_ops 스키마 초기 생성 (ADR 0021).
--
-- 수집 운영 기록을 enrollment 와 분리된 스키마에 둔다. 계정·조직 설정을 담지 않는다.
-- 적용은 :apps:enrollment-api 기동의 두 번째 Flyway 인스턴스가 하고(TelemetryOpsSchemaMigrator),
-- 이력은 telemetry_ops.flyway_schema_history 다 — enrollment 의 V1–V4 와 섞이지 않는다.
-- :apps:telemetry-ingest 기동은 이 DDL 을 실행하지 않는다.
--
-- enrollment.tenants 에 외래 키를 걸지 않는다. tenant 삭제는 각 소유자에게 전파되는 별도 절차이고,
-- FK 검사가 ingest 의 매 upsert 마다 tenants 행을 잠근다 (ADR 0021 §2).
--
-- 운영 수치(보존 기간 등)를 여기 두지 않는다 — 설정이다.

CREATE SCHEMA IF NOT EXISTS telemetry_ops;

-- ─────────────────────────────────────────────────────────────────────────────
-- tenant 생애 요약
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE telemetry_ops.tenant_ingest_summary (
    tenant_id              uuid        NOT NULL,
    first_received_at      timestamptz,
    first_observed_at      timestamptz,
    last_received_at       timestamptz,
    has_pre_ledger_history boolean     NOT NULL DEFAULT false,
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_tenant_ingest_summary PRIMARY KEY (tenant_id),
    CONSTRAINT ck_tenant_ingest_summary_received_order CHECK (first_received_at <= last_received_at)
);

COMMENT ON TABLE telemetry_ops.tenant_ingest_summary IS
    'tenant별 수집 생애주기 요약. 분석·ledger 보존 정리와 무관하게 남는다 (ADR 0021)';
COMMENT ON COLUMN telemetry_ops.tenant_ingest_summary.first_received_at IS
    '인증·마스킹을 통과한 최초 live push 의 서버 수신 시각. NULL 이면 이 요약이 기록한 수신이 없다';
COMMENT ON COLUMN telemetry_ops.tenant_ingest_summary.first_observed_at IS
    '받은 관측 중 유효한 source_time 의 최솟값(마이크로초로 내림). 시각을 얻지 못한 push 만 있으면 NULL';
COMMENT ON COLUMN telemetry_ops.tenant_ingest_summary.last_received_at IS
    '마지막 live push 의 서버 수신 시각. 재처리 실행 시각이 아니다';
COMMENT ON COLUMN telemetry_ops.tenant_ingest_summary.has_pre_ledger_history IS
    '요약 도입 전 분석 이력이 있음을 백필이 표시한 것. 시각을 지어내지 않는다';

-- ─────────────────────────────────────────────────────────────────────────────
-- 요약 백필 완료 기록
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE telemetry_ops.tenant_summary_backfill (
    backfill       text        NOT NULL,
    source         text        NOT NULL,
    completed_at   timestamptz NOT NULL,
    tenants_marked integer     NOT NULL,
    CONSTRAINT pk_tenant_summary_backfill PRIMARY KEY (backfill),
    CONSTRAINT ck_tenant_summary_backfill_tenants_marked CHECK (tenants_marked >= 0)
);

COMMENT ON TABLE telemetry_ops.tenant_summary_backfill IS
    '요약 도입 전 이력의 백필 완료 기록. 요약도 이 기록도 없으면 수집한 적 없음으로 해석하지 않는다 (ADR 0021)';

-- ─────────────────────────────────────────────────────────────────────────────
-- tenant별 삭제 경계
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE telemetry_ops.tenant_retention_boundary (
    tenant_id      uuid        NOT NULL,
    deleted_before timestamptz NOT NULL,
    policy_epoch   bigint      NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_tenant_retention_boundary PRIMARY KEY (tenant_id),
    CONSTRAINT ck_tenant_retention_boundary_policy_epoch CHECK (policy_epoch >= 0)
);

COMMENT ON TABLE telemetry_ops.tenant_retention_boundary IS
    'tenant별 영속 삭제 경계. 이보다 이전 source_time 의 분석 행은 지워졌거나 지워질 대상이다. 단조롭게만 움직인다 (ADR 0020 §8 · ADR 0021)';
