-- 보존 삭제의 쓰기 fence (ADR 0024 §2). 이 파일이 telemetry_retention_fence 스키마의 진실원이고, 이 위치가 곧
-- 쓰기 소유의 근거다 (ADR 0008 규칙 1 의 판정법 · ADR 0015).
--
-- 한 행은 (tenant, 경계 이동) 하나다. 값은 RDS telemetry_ops.tenant_retention_boundary 에 커밋된 경계의 사본이고,
-- 보존 작업만 그 커밋 뒤에 쓴다(RetentionFence). 두 분석 테이블의 INSERT 가 서버에서 이 테이블을 읽어
-- source_time 이 fence 보다 이른 행을 쓰지 않는다(AnalysisInsert). 경계처럼 MAX 로만 움직이므로 읽는 쪽은
-- FINAL 없이 max(deleted_before) 를 쓴다.
--
-- 파티션·TTL 을 두지 않는다. 행이 tenant 당 경계 이동 수만큼이고, 지우면 집행이 풀린다.
--
-- ⚠️ **모든 문장은 IF NOT EXISTS 형태여야 한다** (ADR 0015). 매 기동마다 V1 부터 전부 다시 실행된다.
-- 이 파일이 배포된 뒤에는 고치지 말고 다음 번호 파일에 ALTER TABLE ... ADD COLUMN IF NOT EXISTS 를 쓴다.

CREATE TABLE IF NOT EXISTS telemetry_retention_fence
(
    tenant_id LowCardinality(String),
    deleted_before DateTime64(6, 'UTC'),
    policy_epoch UInt64,
    fenced_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(policy_epoch)
ORDER BY tenant_id;
