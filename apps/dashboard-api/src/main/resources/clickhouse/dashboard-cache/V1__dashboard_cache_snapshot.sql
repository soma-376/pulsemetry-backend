-- 대시보드 공통 snapshot 의 ClickHouse 쪽 (ADR 0023 §1). 이 파일이 dashboard_cache 테이블의 진실원이고
-- 이 위치가 쓰기 소유의 근거다 — 쓰는 주체는 :apps:dashboard-api 하나다.
--
-- DB 이름은 캐시 연결의 설정(pulsemetry.dashboard.clickhouse.cache.database)이고 DB 자체는 infra 가 만든다.
-- 이 파일은 테이블만 만든다. 이름을 한정하지 않는다.
--
-- ⚠️ 모든 문장은 IF NOT EXISTS 형태여야 한다 (ADR 0015). 기동마다 전부 다시 실행된다. 배포된 뒤에는 고치지 말고
-- 다음 번호 파일에 더한다. 문자열 리터럴에 세미콜론을 쓰지 않는다 — 분해가 세미콜론으로 문장을 나눈다.
--
-- 복사본은 쓰인 뒤 바뀌지 않으므로 교체 엔진이 아니라 일반 MergeTree 다. 조회는 ready manifest 의
-- snapshot_id 와 build_id 로 거른다. 행이 있다는 것은 그 snapshot 이 유효하다는 뜻이 아니다.
--
-- 물리 정리는 TTL 이다. purge_after 는 build 시작 + build 제한 시간 + API 수명 10분 + 정리 유예라서,
-- 공개 전에 복사한 행이 API 만료보다 먼저 지워지지 않는다. 만료 판정은 manifest 가 한다.

-- 사용량 payload — 사용량 대표 행만, 관측당 한 행. 겹친 현재·비교 기간의 관측도 한 번만 들어오고 in_current·in_previous 가 소속을 단다.
CREATE TABLE IF NOT EXISTS snapshot_usage
(
    tenant_id LowCardinality(String),
    snapshot_id String,
    build_id String,
    observation_id FixedString(64),
    installation_id String,
    source_time DateTime64(9, 'UTC'),
    in_current Bool,
    in_previous Bool,
    product LowCardinality(String),
    surface LowCardinality(String),
    service_name Nullable(String),
    product_version Nullable(String),
    workload_kind LowCardinality(String),
    usage_scope LowCardinality(String),
    session_id Nullable(String),
    session_id_namespace Nullable(String),
    member_id Nullable(String),
    team_id_as_of Nullable(String),
    model Nullable(String),
    provider Nullable(String),
    model_id String,
    model_resolution_version String,
    tokens_input Nullable(Int64),
    tokens_output Nullable(Int64),
    tokens_cache_read Nullable(Int64),
    tokens_cache_create Nullable(Int64),
    tokens_input_uncached Nullable(Int64),
    tokens_total_derived Nullable(Int64),
    input_semantics LowCardinality(String),
    output_semantics LowCardinality(String),
    semantics_profile Nullable(String),
    cost_reported_usd Nullable(Decimal(38, 12)),
    reported_cost_basis LowCardinality(String),
    cost_estimated_usd Nullable(Decimal(38, 12)),
    pricing_version Nullable(String),
    quality_flags Array(LowCardinality(String)),
    purge_after DateTime('UTC')
)
ENGINE = MergeTree
PARTITION BY toDate(purge_after)
ORDER BY (tenant_id, snapshot_id, build_id, source_time, observation_id)
TTL purge_after
SETTINGS ttl_only_drop_parts = 1;

-- 관측 일자 — 입구의 모든 행(사용량·generic·메트릭 point)을 날짜·출처별 개수로 접은 것. 같은 날짜가 블록마다 한 행씩
-- 여러 번 올 수 있으므로 읽을 때 합친다.
CREATE TABLE IF NOT EXISTS snapshot_observed_days
(
    tenant_id LowCardinality(String),
    snapshot_id String,
    build_id String,
    observed_date Date,
    origin LowCardinality(String),
    observations UInt64,
    purge_after DateTime('UTC')
)
ENGINE = MergeTree
PARTITION BY toDate(purge_after)
ORDER BY (tenant_id, snapshot_id, build_id, observed_date, origin)
TTL purge_after
SETTINGS ttl_only_drop_parts = 1;

-- 기간 밖 기준 시각(asOf)까지의 구성원별 마지막 사용 — 관측 일자 집합이 아니라 별도의 고정 입력이다.
CREATE TABLE IF NOT EXISTS snapshot_member_activity
(
    tenant_id LowCardinality(String),
    snapshot_id String,
    build_id String,
    member_id String,
    last_used_at DateTime64(9, 'UTC'),
    purge_after DateTime('UTC')
)
ENGINE = MergeTree
PARTITION BY toDate(purge_after)
ORDER BY (tenant_id, snapshot_id, build_id, member_id)
TTL purge_after
SETTINGS ttl_only_drop_parts = 1;

-- build 의 유일한 입구. 아무것도 저장하지 않는다. 한 INSERT … SELECT 가 두 분석 테이블을 각각 한 번씩 읽어 여기로 보내고,
-- 아래 구체화 뷰 둘이 같은 블록을 사용량과 관측 일자로 가른다 — 관측 일자를 얻으려고 원본을 다시 읽지 않는다.
-- 사용량 대표 행이 아닌 관측(is_usage = false)은 날짜·출처만 의미가 있고 나머지 열은 기본값이다.
CREATE TABLE IF NOT EXISTS snapshot_intake
(
    tenant_id LowCardinality(String),
    snapshot_id String,
    build_id String,
    is_usage Bool,
    origin LowCardinality(String),
    observed_date Date,
    observation_id FixedString(64),
    installation_id String,
    source_time DateTime64(9, 'UTC'),
    in_current Bool,
    in_previous Bool,
    product LowCardinality(String),
    surface LowCardinality(String),
    service_name Nullable(String),
    product_version Nullable(String),
    workload_kind LowCardinality(String),
    usage_scope LowCardinality(String),
    session_id Nullable(String),
    session_id_namespace Nullable(String),
    member_id Nullable(String),
    team_id_as_of Nullable(String),
    model Nullable(String),
    provider Nullable(String),
    model_id String,
    model_resolution_version String,
    tokens_input Nullable(Int64),
    tokens_output Nullable(Int64),
    tokens_cache_read Nullable(Int64),
    tokens_cache_create Nullable(Int64),
    tokens_input_uncached Nullable(Int64),
    tokens_total_derived Nullable(Int64),
    input_semantics LowCardinality(String),
    output_semantics LowCardinality(String),
    semantics_profile Nullable(String),
    cost_reported_usd Nullable(Decimal(38, 12)),
    reported_cost_basis LowCardinality(String),
    cost_estimated_usd Nullable(Decimal(38, 12)),
    pricing_version Nullable(String),
    quality_flags Array(LowCardinality(String)),
    purge_after DateTime('UTC')
)
ENGINE = Null;

CREATE MATERIALIZED VIEW IF NOT EXISTS snapshot_intake_usage TO snapshot_usage AS
SELECT
    tenant_id, snapshot_id, build_id, observation_id, installation_id, source_time, in_current, in_previous,
    product, surface, service_name, product_version, workload_kind, usage_scope,
    session_id, session_id_namespace, member_id, team_id_as_of,
    model, provider, model_id, model_resolution_version,
    tokens_input, tokens_output, tokens_cache_read, tokens_cache_create, tokens_input_uncached, tokens_total_derived,
    input_semantics, output_semantics, semantics_profile,
    cost_reported_usd, reported_cost_basis, cost_estimated_usd, pricing_version,
    quality_flags, purge_after
FROM snapshot_intake
WHERE is_usage;

CREATE MATERIALIZED VIEW IF NOT EXISTS snapshot_intake_days TO snapshot_observed_days AS
SELECT
    tenant_id, snapshot_id, build_id, observed_date, origin,
    count() AS observations,
    max(purge_after) AS purge_after
FROM snapshot_intake
GROUP BY tenant_id, snapshot_id, build_id, observed_date, origin
