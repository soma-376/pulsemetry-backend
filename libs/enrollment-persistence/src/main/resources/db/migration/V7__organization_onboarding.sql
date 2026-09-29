-- 폼 초안과 단계 번호는 저장하지 않는다. 명시적인 정책 확인과 완료 시각만 보존한다.
CREATE TABLE enrollment.organization_onboarding (
    tenant_id uuid PRIMARY KEY REFERENCES enrollment.tenants(id),
    policy_confirmed_at timestamptz NOT NULL,
    policy_confirmed_by uuid NOT NULL REFERENCES enrollment.members(id),
    completed_at timestamptz,
    completed_by uuid REFERENCES enrollment.members(id)
);
