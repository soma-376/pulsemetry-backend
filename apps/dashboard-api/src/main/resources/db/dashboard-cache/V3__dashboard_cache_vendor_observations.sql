-- 설정·벤더 조회의 관측 지표를 조회 기준 시각에 고정한다 (ADR 0044). 설정 첫 화면이 계산해 쓰고, 같은 기준 시각의 다음 페이지·상세가 이것을 읽는다.
-- 없으면 그 기준 시각은 만료다(409) — 다시 계산해 섞지 않는다. 정리 작업이 유효기간이 지난 묶음을 지운다.

CREATE TABLE IF NOT EXISTS dashboard_cache.vendor_observation_sets (
    tenant_id    uuid        NOT NULL,
    as_of        timestamptz NOT NULL,
    -- 고정한 때의 매핑이 잇던 카탈로그 제품. 매핑이 없는 제품은 관측할 수 없는 제품이다(unobserved).
    mapped_products text[]   NOT NULL,
    complete_7d  boolean     NOT NULL,
    complete_30d boolean     NOT NULL,
    created_at   timestamptz NOT NULL,
    CONSTRAINT pk_vendor_observation_sets PRIMARY KEY (tenant_id, as_of)
);

COMMENT ON TABLE dashboard_cache.vendor_observation_sets IS
    '조회 기준 시각 하나의 벤더 관측 고정. 최근 7·30일(기준일 전날까지)의 모든 날이 완전 관측인지(ADR 0042)를 함께 둔다 (ADR 0044)';

CREATE TABLE IF NOT EXISTS dashboard_cache.vendor_observations (
    tenant_id         uuid        NOT NULL,
    as_of             timestamptz NOT NULL,
    subject           text        NOT NULL,
    observed_products text[]      NOT NULL,
    first_seen_at     timestamptz NOT NULL,
    last_seen_at      timestamptz NOT NULL,
    users_7d          bigint      NOT NULL,
    users_30d         bigint      NOT NULL,
    CONSTRAINT pk_vendor_observations PRIMARY KEY (tenant_id, as_of, subject),
    CONSTRAINT fk_vendor_observations_set FOREIGN KEY (tenant_id, as_of)
        REFERENCES dashboard_cache.vendor_observation_sets (tenant_id, as_of) ON DELETE CASCADE
);

COMMENT ON TABLE dashboard_cache.vendor_observations IS
    '관측된 대상마다 한 행. subject 는 카탈로그 제품 ID 이고 매핑 없는 관측은 빈 문자열이다. 관측이 없는 대상은 행이 없다 (ADR 0044)';
