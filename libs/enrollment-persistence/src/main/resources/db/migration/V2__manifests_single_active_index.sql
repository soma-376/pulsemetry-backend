-- "tenant 당 is_active manifest 는 최대 하나" 를 DB 가 보장하는 부분 유니크 인덱스.
--
-- enroll 은 tenant 의 활성 manifest 를 단수로 가정한다(findByTenantIdAndIsActiveTrue).
-- 둘 이상이면 어느 것이 내려갈지 비결정적이므로 DB 레벨에서 한 개임을 강제한다.
-- dbml 에는 없는 의도적 추가다 (SCHEMA-DRIFT).
--
-- V1 이 아니라 V2 인 이유: 공유 RDS 에 파이프라인 DDL(ai-telemetry-pipeline/sql/rds/schema.sql)로
-- 수동 부트스트랩된 스키마가 있으면 baseline-on-migrate 가 V1 을 건너뛴다. 그 DDL 에는
-- 이 인덱스가 없으므로, V1 에 두면 그런 DB 에는 영원히 적용되지 않는다. V2 는 baseline
-- 이후에도 실행되므로 어느 경로로든 불변식이 확보된다. IF NOT EXISTS 는 그 두 경로의
-- 멱등성을 위한 것이다.

CREATE UNIQUE INDEX IF NOT EXISTS ux_manifests_tenant_active
    ON enrollment.manifests (tenant_id)
    WHERE is_active;
