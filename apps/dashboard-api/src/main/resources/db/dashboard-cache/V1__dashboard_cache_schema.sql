-- 대시보드 공통 snapshot 의 RDS 쪽 (ADR 0023 §2). manifest 와 참조 복제.
--
-- enrollment 와 다른 Flyway 인스턴스다 — 이력은 dashboard_cache.flyway_schema_history 이고, 적용은 :apps:dashboard-api 기동이
-- 캐시 계정으로 한다(ADR 0023 §3). enrollment · telemetry_ops 에 외래 키를 걸지 않는다 — 다른 소유자의 스키마이고,
-- 캐시 행이 그쪽의 삭제를 막으면 안 된다.
--
-- 운영 수치(유효기간·상한·유예)를 여기 두지 않는다 — 설정과 코드 상수다.

CREATE SCHEMA IF NOT EXISTS dashboard_cache;

CREATE TYPE dashboard_cache.snapshot_status AS ENUM ('building', 'ready', 'failed');
CREATE TYPE dashboard_cache.compare_mode AS ENUM ('prev_week', 'prev_period', 'none');
-- enrollment.member_role · member_status 의 값을 그대로 복제한다. 화면 어휘로의 대응은 조회 때 한다(ADR 0022 §3).
CREATE TYPE dashboard_cache.member_role AS ENUM ('owner', 'admin', 'member');
CREATE TYPE dashboard_cache.member_status AS ENUM ('invited', 'active', 'suspended');

-- ─────────────────────────────────────────────────────────────────────────────
-- manifest — snapshot 하나에 build 하나. 재시도는 새 snapshot · 새 build 다.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE dashboard_cache.snapshots (
    snapshot_id              text        NOT NULL,
    build_id                 uuid        NOT NULL,
    tenant_id                uuid        NOT NULL,
    requested_by             uuid        NOT NULL,
    access_scope             text        NOT NULL,

    query_contract           text        NOT NULL,
    time_zone                text        NOT NULL,
    compare_mode             dashboard_cache.compare_mode NOT NULL,
    current_start_date       date        NOT NULL,
    current_end_date         date        NOT NULL,
    previous_start_date      date,
    previous_end_date        date,

    as_of                    timestamptz NOT NULL,
    deleted_before           timestamptz,
    policy_epoch             bigint      NOT NULL,
    model_resolution_version text        NOT NULL,
    pricing_versions         text[]      NOT NULL DEFAULT '{}',

    status                   dashboard_cache.snapshot_status NOT NULL,
    created_at               timestamptz NOT NULL,
    build_deadline           timestamptz NOT NULL,
    ready_at                 timestamptz,
    expires_at               timestamptz,
    failed_at                timestamptz,
    failure_reason           text,
    invalidated_at           timestamptz,
    invalidation_reason      text,

    usage_rows               bigint,
    observed_day_rows        bigint,

    CONSTRAINT pk_snapshots PRIMARY KEY (snapshot_id),
    CONSTRAINT uq_snapshots_build UNIQUE (build_id),
    -- 무작위 16바이트의 base64url(패딩 없음) 22자.
    CONSTRAINT ck_snapshots_id_format CHECK (snapshot_id ~ '^[A-Za-z0-9_-]{22}$'),
    CONSTRAINT ck_snapshots_current_order CHECK (current_start_date <= current_end_date),
    CONSTRAINT ck_snapshots_previous CHECK (
        (compare_mode = 'none') = (previous_start_date IS NULL)
        AND (previous_start_date IS NULL) = (previous_end_date IS NULL)
        AND (previous_start_date IS NULL OR previous_start_date <= previous_end_date)
    ),
    CONSTRAINT ck_snapshots_policy_epoch CHECK (policy_epoch >= 0),
    CONSTRAINT ck_snapshots_deadline CHECK (build_deadline > created_at),
    CONSTRAINT ck_snapshots_ready CHECK (
        status <> 'ready'
        OR (ready_at IS NOT NULL AND expires_at IS NOT NULL AND usage_rows IS NOT NULL AND observed_day_rows IS NOT NULL)
    ),
    CONSTRAINT ck_snapshots_expiry CHECK (expires_at IS NULL OR (ready_at IS NOT NULL AND expires_at > ready_at)),
    CONSTRAINT ck_snapshots_failed CHECK (status <> 'failed' OR failed_at IS NOT NULL),
    CONSTRAINT ck_snapshots_rows CHECK (
        (usage_rows IS NULL OR usage_rows >= 0) AND (observed_day_rows IS NULL OR observed_day_rows >= 0)
    )
);

-- tenant 별 동시 build 수를 센다.
CREATE INDEX ix_snapshots_tenant_building ON dashboard_cache.snapshots (tenant_id) WHERE status = 'building';
-- 정리 작업이 오래된 manifest 를 찾는다.
CREATE INDEX ix_snapshots_created_at ON dashboard_cache.snapshots (created_at);

COMMENT ON TABLE dashboard_cache.snapshots IS
    '대시보드 공통 snapshot 의 manifest. 공개·만료·무효화 판정의 진실원이다 — ClickHouse 행의 존재로 판정하지 않는다 (ADR 0023)';
COMMENT ON COLUMN dashboard_cache.snapshots.policy_epoch IS
    'build 시작 때 telemetry_ops.tenant_retention_boundary 의 policy_epoch(행이 없으면 0). 지금 epoch 와 다르면 이 snapshot 은 무효다';
COMMENT ON COLUMN dashboard_cache.snapshots.pricing_versions IS
    '복사한 사용량 행의 가격 판 집합. 둘 이상이면 가격 혼재다';

-- ─────────────────────────────────────────────────────────────────────────────
-- 참조 복제 — snapshot 에 묶인다. manifest 를 지우면 함께 지워진다.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE dashboard_cache.snapshot_teams (
    snapshot_id text    NOT NULL,
    team_id     uuid    NOT NULL,
    name        text    NOT NULL,
    archived    boolean NOT NULL,
    CONSTRAINT pk_snapshot_teams PRIMARY KEY (snapshot_id, team_id),
    CONSTRAINT fk_snapshot_teams_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES dashboard_cache.snapshots (snapshot_id) ON DELETE CASCADE
);

COMMENT ON TABLE dashboard_cache.snapshot_teams IS
    'build 때의 팀 표시. 사용 이력에 나오는 과거 팀(보관된 팀)도 포함한다 (ADR 0023)';

CREATE TABLE dashboard_cache.snapshot_members (
    snapshot_id      text        NOT NULL,
    member_id        uuid        NOT NULL,
    account          text        NOT NULL,
    display_name     text,
    role             dashboard_cache.member_role   NOT NULL,
    status           dashboard_cache.member_status NOT NULL,
    current_team_ids uuid[]      NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT pk_snapshot_members PRIMARY KEY (snapshot_id, member_id),
    CONSTRAINT fk_snapshot_members_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES dashboard_cache.snapshots (snapshot_id) ON DELETE CASCADE
);

COMMENT ON TABLE dashboard_cache.snapshot_members IS
    'build 때의 로스터. 사용량이 없는 구성원도 포함한다. 현재 팀은 이벤트 당시 팀(team_id_as_of)과 다른 개념이다 (ADR 0023)';
