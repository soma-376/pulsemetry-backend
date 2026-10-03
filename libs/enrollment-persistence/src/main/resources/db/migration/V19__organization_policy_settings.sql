-- 조직 정책 설정 (ADR 0046). 좌석 회수 기준과 집계 보존 기간이다. 설치에 배포하는 정책이 아니라서 manifest 와 따로 저장하고 판도 따로 센다.
-- 행이 없으면 조직이 아직 정하지 않았다(판 0). 값이 NULL 인 칸도 같다 — 회수 기준은 조회 서버의 기본 설정, 집계 보존은 무기한이다.
CREATE TABLE enrollment.organization_policy_settings (
    tenant_id uuid PRIMARY KEY REFERENCES enrollment.tenants(id),
    reclaim_idle_days smallint CHECK (reclaim_idle_days IN (7, 14, 30, 60)),
    aggregate_retention_months smallint CHECK (aggregate_retention_months IN (12, 24, 36)),
    version bigint NOT NULL CHECK (version >= 1),
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL REFERENCES enrollment.members(id)
);

COMMENT ON TABLE enrollment.organization_policy_settings IS '조직이 저장한 회수 기준·집계 보존 (ADR 0046). manifest 판과 별개의 판을 쓴다. 조직당 한 행.';
COMMENT ON COLUMN enrollment.organization_policy_settings.reclaim_idle_days IS '좌석 회수 후보의 유휴 기준 일수. NULL 이면 조회 서버의 기본 설정을 쓴다.';
COMMENT ON COLUMN enrollment.organization_policy_settings.aggregate_retention_months IS '분석 원본 보존 개월 수. NULL 이면 무기한이다.';
COMMENT ON COLUMN enrollment.organization_policy_settings.version IS '값이 바뀔 때마다 1씩 오르는 판. 저장하지 않은 조직은 0 으로 읽는다.';
