-- 알림 규칙과 모델·도구 목록 (ADR 0051). 규칙 정의는 기준 데이터이고, 조직마다 규칙의 켜짐과 두 목록을 저장한다.
-- 평가 기록·알림·확인은 이 표들에 없다. 행이 없는 조직은 모든 규칙이 꺼짐(판 0)이고 목록이 비어 있다(판 0).
CREATE TABLE enrollment.alert_rule_definitions (
    rule_id varchar(32) PRIMARY KEY CHECK (rule_id IN ('spend_spike', 'quota_exceeded', 'model_not_allowed', 'tool_unapproved')),
    category varchar(16) NOT NULL CHECK (category IN ('cost', 'security')),
    threshold_value numeric(12, 4) NOT NULL CHECK (threshold_value > 0),
    threshold_unit varchar(16) NOT NULL CHECK (threshold_unit IN ('ratio', 'users', 'events')),
    evaluation_window varchar(64) NOT NULL CHECK (btrim(evaluation_window) <> ''),
    comparison_window varchar(64) CHECK (comparison_window IS NULL OR btrim(comparison_window) <> ''),
    position smallint NOT NULL UNIQUE
);

-- 화면 요청서가 제안한 초기 기준이다. 화면은 토글만 바꾼다 — 임계값·창을 바꾸는 명령은 없다.
INSERT INTO enrollment.alert_rule_definitions (rule_id, category, threshold_value, threshold_unit, evaluation_window, comparison_window, position) VALUES
    ('spend_spike', 'cost', 0.4, 'ratio', 'last_complete_7_calendar_days', 'preceding_7_calendar_days', 1),
    ('quota_exceeded', 'cost', 5, 'users', 'rolling_24_hours', NULL, 2),
    ('model_not_allowed', 'security', 1, 'events', 'rolling_24_hours', NULL, 3),
    ('tool_unapproved', 'security', 1, 'events', 'rolling_24_hours', NULL, 4);

CREATE TABLE enrollment.organization_alert_rules (
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    rule_id varchar(32) NOT NULL REFERENCES enrollment.alert_rule_definitions(rule_id),
    enabled boolean NOT NULL,
    version bigint NOT NULL CHECK (version >= 1),
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL REFERENCES enrollment.members(id),
    PRIMARY KEY (tenant_id, rule_id)
);

CREATE TABLE enrollment.organization_alert_lists (
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    list_id varchar(32) NOT NULL CHECK (list_id IN ('allowed_models', 'approved_tools')),
    version bigint NOT NULL CHECK (version >= 1),
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL REFERENCES enrollment.members(id),
    PRIMARY KEY (tenant_id, list_id)
);

CREATE TABLE enrollment.organization_alert_list_entries (
    tenant_id uuid NOT NULL,
    list_id varchar(32) NOT NULL,
    entry varchar(200) NOT NULL CHECK (entry <> '' AND entry = btrim(entry)),
    PRIMARY KEY (tenant_id, list_id, entry),
    FOREIGN KEY (tenant_id, list_id) REFERENCES enrollment.organization_alert_lists(tenant_id, list_id) ON DELETE CASCADE
);

COMMENT ON TABLE enrollment.alert_rule_definitions IS '알림 규칙 네 개의 분류·임계값·창 (ADR 0051). 마이그레이션이 넣는 기준 데이터다.';
COMMENT ON TABLE enrollment.organization_alert_rules IS '조직이 켠·끈 알림 규칙 (ADR 0051). 행이 없으면 꺼짐·판 0. 근거가 없는 규칙은 켤 수 없다.';
COMMENT ON COLUMN enrollment.organization_alert_rules.version IS '켜짐이 바뀔 때마다 1씩 오르는 판.';
COMMENT ON TABLE enrollment.organization_alert_lists IS '모델 허용 목록(allowed_models)·승인 도구 목록(approved_tools)의 판 (ADR 0051). 전체 교체로만 바뀐다.';
COMMENT ON TABLE enrollment.organization_alert_list_entries IS '목록의 항목. 대소문자를 구분하는 정확 일치이고, 끝의 * 하나는 접두사 일치다.';
