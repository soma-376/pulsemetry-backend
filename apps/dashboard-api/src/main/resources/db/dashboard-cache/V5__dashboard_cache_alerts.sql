-- 알림 평가 기록 (ADR 0051 §5·§6). 켜진 알림 규칙을 이 앱의 주기 작업이 평가해 남긴다 — snapshot 이 아니라서 snapshot 정리 작업이 지우지 않는다.
-- 원천(분석 행·규칙 설정)에서 다시 계산할 수 있는 파생값이다. 사람의 확인은 enrollment.alert_acknowledgements(enrollment-api)에 있다.

-- 알림 하나 = (조직, 규칙, 대상, 창의 키). 급증은 대상이 '' 이고 창의 키가 평가한 날, 24시간 규칙은 대상이 모델·도구 이름이고 창의 키가 묶음의 첫 위반 시각이다.
CREATE TABLE IF NOT EXISTS dashboard_cache.alerts (
    alert_id        uuid           NOT NULL,
    tenant_id       uuid           NOT NULL,
    rule_id         text           NOT NULL,
    category        text           NOT NULL,
    subject         text           NOT NULL,
    window_key      text           NOT NULL,
    status          text           NOT NULL,
    qualified       boolean        NOT NULL,
    occurred_at     timestamptz    NOT NULL,
    last_seen_at    timestamptz    NOT NULL,
    window_start    timestamptz    NOT NULL,
    window_end      timestamptz    NOT NULL,
    event_count     bigint         NOT NULL,
    member_ids      jsonb          NOT NULL,
    summary         jsonb          NOT NULL,
    threshold_value numeric(12, 4) NOT NULL,
    rule_version    bigint         NOT NULL,
    version         bigint         NOT NULL,
    created_at      timestamptz    NOT NULL,
    updated_at      timestamptz    NOT NULL,
    CONSTRAINT pk_alerts PRIMARY KEY (alert_id),
    CONSTRAINT uq_alerts_key UNIQUE (tenant_id, rule_id, subject, window_key),
    CONSTRAINT ck_alerts_category CHECK (category IN ('cost', 'security')),
    CONSTRAINT ck_alerts_status CHECK (status IN ('open', 'closed')),
    CONSTRAINT ck_alerts_counts CHECK (event_count >= 0 AND version >= 1 AND last_seen_at >= occurred_at),
    CONSTRAINT ck_alerts_members CHECK (jsonb_typeof(member_ids) = 'array')
);
CREATE INDEX IF NOT EXISTS ix_alerts_tenant_occurred ON dashboard_cache.alerts (tenant_id, occurred_at DESC, alert_id);
-- 한 대상에 열린 묶음은 하나다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_alerts_open ON dashboard_cache.alerts (tenant_id, rule_id, subject) WHERE status = 'open';

-- 규칙마다 마지막 평가와 다음 평가의 시작점. 판(rule_version)이 바뀌면(껐다 켜면) 시작점을 다시 정한다.
CREATE TABLE IF NOT EXISTS dashboard_cache.alert_evaluations (
    tenant_id    uuid        NOT NULL,
    rule_id      text        NOT NULL,
    rule_version bigint      NOT NULL,
    cursor_at    timestamptz,
    cursor_date  date,
    evaluated_at timestamptz NOT NULL,
    status       text        NOT NULL,
    reason       text,
    window_start timestamptz,
    window_end   timestamptz,
    CONSTRAINT pk_alert_evaluations PRIMARY KEY (tenant_id, rule_id),
    CONSTRAINT ck_alert_evaluations_status CHECK (status IN ('evaluated', 'not_evaluated', 'failed')),
    CONSTRAINT ck_alert_evaluations_reason CHECK ((status = 'evaluated') = (reason IS NULL))
);

-- 조직 단위 평가 선점. 여러 인스턴스가 같은 조직을 동시에 평가하지 않는다.
CREATE TABLE IF NOT EXISTS dashboard_cache.alert_evaluation_leases (
    tenant_id     uuid        NOT NULL,
    claimed_by    text        NOT NULL,
    claimed_until timestamptz NOT NULL,
    CONSTRAINT pk_alert_evaluation_leases PRIMARY KEY (tenant_id)
);

COMMENT ON TABLE dashboard_cache.alerts IS
    '알림 평가 결과 (ADR 0051 §6). qualified 는 묶음의 위반 수가 임계값에 이르렀는가 — 이른 것만 알림으로 낸다. snapshot 정리 대상이 아니다';
COMMENT ON COLUMN dashboard_cache.alerts.member_ids IS '관련 구성원 ID 배열. 본문·마스킹된 값은 싣지 않는다';
COMMENT ON COLUMN dashboard_cache.alerts.version IS '평가가 묶음을 늘릴 때마다 1 오른다. 확인 명령의 expectedVersion 이다';
COMMENT ON TABLE dashboard_cache.alert_evaluations IS
    '규칙마다 마지막 평가 (ADR 0051 §6). 전제가 깨진 회차는 not_evaluated 와 사유 — 0건으로 기록하지 않는다';
