-- 완료 시각은 조직이 소유한다. 기존 완료 이력은 보존하며 개발 데이터는 이 마이그레이션에서 변경하지 않는다.
ALTER TABLE enrollment.tenants ADD COLUMN onboarding_completed_at timestamptz;
ALTER TABLE enrollment.tenants ADD COLUMN onboarding_completed boolean
    GENERATED ALWAYS AS (onboarding_completed_at IS NOT NULL) STORED;
ALTER TABLE enrollment.tenants ALTER COLUMN onboarding_completed SET NOT NULL;

UPDATE enrollment.tenants t
SET onboarding_completed_at = o.completed_at
FROM enrollment.organization_onboarding o
WHERE o.tenant_id = t.id AND o.completed_at IS NOT NULL;

ALTER TABLE enrollment.organization_onboarding DROP COLUMN completed_at;

COMMENT ON COLUMN enrollment.tenants.onboarding_completed_at IS '최초 온보딩 완료 시각. 미완료는 NULL';
COMMENT ON COLUMN enrollment.tenants.onboarding_completed IS '완료 시각 유무에서 계산한 온보딩 완료 여부';
