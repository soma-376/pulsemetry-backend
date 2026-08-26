-- "installation 당 활성 telemetry token 은 최대 하나" 를 DB 가 보장하는 부분 유니크 인덱스.
--
-- 재발급은 "기존 활성 토큰 전부 폐기 → 새로 발급" 계약인데(PLAN.md §6.3), READ COMMITTED
-- 에서 동시 재발급 두 트랜잭션은 서로의 미커밋 INSERT 를 보지 못해 활성 토큰이 2개 남을
-- 수 있다. 애플리케이션은 installation 행 잠금으로 흐름을 직렬화하고, 이 인덱스가 그
-- 계약의 최종 방어선이다.
--
-- V1 이 아니라 V3 인 이유: V1 은 공유 RDS 부트스트랩 DDL(ai-telemetry-pipeline/sql/rds/schema.sql)
-- 과의 물리 일치 계약이라 고칠 수 없다. DDL 권위는 Flyway 이므로(ADR 0009) 스키마 변경은
-- 신규 마이그레이션으로만 하며, 파이프라인 sql/rds/schema.sql 동기화는 후속 작업이다.
-- IF NOT EXISTS 는 baseline 유무 두 경로의 멱등성을 위한 것이다.

CREATE UNIQUE INDEX IF NOT EXISTS ux_telemetry_tokens_installation_active
    ON enrollment.telemetry_tokens (installation_id)
    WHERE revoked_at IS NULL;
