-- 수신 ledger (ADR 0021). 이 파일이 telemetry_ingest_ledger 스키마의 진실원이고, 이 위치가 곧
-- 쓰기 소유의 근거다 (ADR 0008 규칙 1 의 판정법 · ADR 0015).
--
-- 한 행은 (receipt, signal, archive product) 하나다. 인증·마스킹·아카이브를 통과한 push 의 수신
-- 사실이며 분석 테이블이 아니다 — observation_id·row_version 이 없고 record_count 는 사용량이 아니다.
-- HTTP 재전송은 새 receipt 라 새 행이다. 같은 receipt 의 저장 재시도는 정렬 키가 같아 한 행으로 수렴한다.
--
-- TTL 을 두지 않는다. ledger 는 공통 운영 보존 기간을 따르고 그 기간은 운영 설정이다 — 운영 보존
-- 작업이 received_time 월 파티션 중 전부 경계보다 오래된 것을 지운다 (ADR 0021).
--
-- ⚠️ **모든 문장은 IF NOT EXISTS 형태여야 한다** (ADR 0015). 매 기동마다 V1 부터 전부 다시 실행된다.
-- 이 파일이 배포된 뒤에는 고치지 말고 다음 번호 파일에 ALTER TABLE ... ADD COLUMN IF NOT EXISTS 를 쓴다.

CREATE TABLE IF NOT EXISTS telemetry_ingest_ledger
(
    tenant_id LowCardinality(String),
    installation_id String,
    received_time DateTime64(9, 'UTC') CODEC(Delta, ZSTD),
    receipt_id String,
    signal LowCardinality(String),
    product LowCardinality(String),
    source_time_min Nullable(DateTime64(9, 'UTC')),
    source_time_max Nullable(DateTime64(9, 'UTC')),
    record_count UInt32,
    rejected_count UInt32,
    archive_ref Nullable(String),
    masking_version String
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(received_time)
ORDER BY (tenant_id, installation_id, received_time, receipt_id, signal, product);
