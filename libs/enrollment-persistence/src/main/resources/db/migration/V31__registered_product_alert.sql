-- 허브 ADR 0008. 이전 규칙·목록·확인 이력을 보존하고 신규 규칙은 기본 꺼짐으로 제공한다.
ALTER TABLE enrollment.alert_rule_definitions ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE enrollment.alert_rule_definitions DROP CONSTRAINT alert_rule_definitions_rule_id_check;
ALTER TABLE enrollment.alert_rule_definitions ADD CONSTRAINT alert_rule_definitions_rule_id_check
    CHECK (rule_id IN ('spend_spike', 'quota_exceeded', 'model_not_allowed', 'tool_unapproved', 'product_not_registered'));

UPDATE enrollment.alert_rule_definitions SET active = false WHERE rule_id IN ('model_not_allowed', 'tool_unapproved');
UPDATE enrollment.organization_alert_rules SET enabled = false, version = version + 1, updated_at = now()
    WHERE rule_id IN ('model_not_allowed', 'tool_unapproved') AND enabled;

INSERT INTO enrollment.alert_rule_definitions (rule_id, category, threshold_value, threshold_unit, evaluation_window, comparison_window, position)
    VALUES ('product_not_registered', 'security', 1, 'events', 'rolling_24_hours', NULL, 5);
COMMENT ON COLUMN enrollment.alert_rule_definitions.active IS '현재 설정·평가에 제공하는 규칙. 비활성 규칙은 과거 이력 식별자로 보존한다 (허브 ADR 0008)';
COMMENT ON TABLE enrollment.organization_alert_lists IS '폐기한 모델·도구 목록의 기존 기록. 현재 API는 읽거나 쓰지 않는다 (허브 ADR 0008)';
COMMENT ON TABLE enrollment.organization_alert_list_entries IS '폐기한 모델·도구 목록의 기존 항목. 등록 제품 기준 알림에는 사용하지 않는다 (허브 ADR 0008)';
