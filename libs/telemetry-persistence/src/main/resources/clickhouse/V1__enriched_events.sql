-- ClickHouse 적재 타깃. 이 파일이 `enriched_events` 스키마의 진실원이고, 이 위치가 곧
-- 쓰기 소유의 근거다 (ADR 0008 규칙 1 의 판정법 · ADR 0015).
--
-- Flyway 는 이 DDL 을 적용하지 않는다 — ClickHouse 구현 모듈이 10.24.0 에서 멈춰
-- 이 저장소가 해석하는 flyway-core 12.x 계열에 없다 (허브 ADR 0004). 적용은
-- ClickHouseSchemaMigrator 가 기동 시 전량으로 한다.
--
-- ⚠️ **모든 문장은 IF NOT EXISTS 형태여야 한다** (ADR 0015). 매 기동마다 V1 부터 전부
-- 다시 실행되므로, 멱등하지 않은 문장을 넣으면 두 번째 기동에서 죽는다. 컬럼을 더할 때는
-- 이 파일을 고치지 말고 V2 파일에 ALTER TABLE ... ADD COLUMN IF NOT EXISTS 를 쓴다 —
-- CREATE TABLE IF NOT EXISTS 는 이미 있는 테이블에 아무 일도 하지 않는다.
--
-- 슬림 공통 스키마: 검증 조인 키(installation_id) + 얇은 공통 컬럼 + raw_json(정규화 이벤트
-- 재직렬화) + enrichment_json(provider 주석). org 승격 컬럼은 team_ids_as_of 하나뿐이다
-- (파이프라인 ADR 0006) — org provider 가 ingest 시점에 enrollment 스키마를 as-of 조인해 채운다.
--
-- 엔진 ReplacingMergeTree ORDER BY event_id → 같은 event_id(= envelope.record_id, 어댑터가
-- 만드는 결정적 멱등 키) 재적재는 멱등이다. 조회는 FINAL 이 필요하다.

CREATE TABLE IF NOT EXISTS enriched_events
(
    event_id         String,
    ts               DateTime('UTC'),
    tenant_id        String,
    installation_id  String,
    signal           String,
    product          String,
    team_ids_as_of   Array(String),
    raw_json         String,
    enrichment_json  String
)
ENGINE = ReplacingMergeTree
ORDER BY event_id;
