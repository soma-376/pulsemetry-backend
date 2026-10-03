-- 알림 확인 (ADR 0051 §6). 알림은 dashboard-api 가 자기 스키마(dashboard_cache.alerts)에 남기는 평가 결과이고, 확인은 사람의 행위라 여기에 둔다.
-- 알림 행과는 외래 키를 걸 수 없다(쓰기 소유가 다른 스키마) — 확인 명령이 알림이 그 조직의 것인지 읽어 확인한다.
CREATE TABLE enrollment.alert_acknowledgements (
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    alert_id uuid NOT NULL,
    alert_version bigint NOT NULL CHECK (alert_version >= 1),
    acknowledged_by uuid NOT NULL REFERENCES enrollment.members(id),
    acknowledged_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, alert_id)
);

COMMENT ON TABLE enrollment.alert_acknowledgements IS '관리자가 확인한 알림 (ADR 0051 §6). 행이 있으면 확인됨이다 — 미확인 수에서 빠진다. 확인을 되돌리는 명령은 없다.';
COMMENT ON COLUMN enrollment.alert_acknowledgements.alert_version IS '확인할 때 본 알림의 판. 확인한 뒤 묶음이 늘어도(같은 묶음의 위반) 확인됨으로 남는다.';
