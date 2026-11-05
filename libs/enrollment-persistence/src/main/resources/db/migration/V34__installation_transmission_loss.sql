-- 구 heartbeat writer를 모두 종료한 후 적용한다. 기존 누계는 기준값이며 증가분을 백필하지 않는다(ADR 0076).
CREATE TABLE enrollment.installation_loss_tracking (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at timestamptz NOT NULL,
    stopped_at timestamptz,
    CHECK (stopped_at IS NULL OR stopped_at >= started_at)
);
CREATE UNIQUE INDEX installation_loss_tracking_active ON enrollment.installation_loss_tracking ((true)) WHERE stopped_at IS NULL;
INSERT INTO enrollment.installation_loss_tracking(started_at) VALUES (transaction_timestamp());

CREATE TABLE enrollment.installation_loss_runs (
    installation_id uuid NOT NULL REFERENCES enrollment.installations(id),
    run_id varchar(64) NOT NULL,
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    high_water_lost bigint NOT NULL CHECK (high_water_lost >= 0),
    historical boolean NOT NULL DEFAULT false,
    first_observed_at timestamptz,
    last_observed_at timestamptz,
    PRIMARY KEY (installation_id, run_id)
);
CREATE INDEX installation_loss_runs_observation ON enrollment.installation_loss_runs(tenant_id, first_observed_at, last_observed_at);

CREATE TABLE enrollment.installation_loss_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    installation_id uuid NOT NULL REFERENCES enrollment.installations(id),
    run_id varchar(64) NOT NULL,
    payload_count bigint NOT NULL CHECK (payload_count > 0),
    observed_at timestamptz NOT NULL
);
CREATE INDEX installation_loss_events_window ON enrollment.installation_loss_events(tenant_id, observed_at, installation_id);
CREATE INDEX installation_loss_events_retention ON enrollment.installation_loss_events(installation_id, observed_at);

CREATE TABLE enrollment.installation_loss_limitations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    installation_id uuid NOT NULL REFERENCES enrollment.installations(id),
    run_id varchar(64) NOT NULL,
    reason varchar(40) NOT NULL CHECK (reason IN ('counter_decreased')),
    observed_at timestamptz NOT NULL
);
CREATE INDEX installation_loss_limitations_window ON enrollment.installation_loss_limitations(tenant_id, observed_at);
CREATE INDEX installation_loss_limitations_retention ON enrollment.installation_loss_limitations(installation_id, observed_at);

INSERT INTO enrollment.installation_loss_runs(installation_id,run_id,tenant_id,high_water_lost)
SELECT h.installation_id,h.run_id,i.tenant_id,h.lost
FROM enrollment.installation_heartbeats h JOIN enrollment.installations i ON i.id=h.installation_id;
-- 과거 구간의 lost는 실행 전체 누계가 아니다. 과거 실행임만 남겨 임의 재보고의 합산을 막는다.
INSERT INTO enrollment.installation_loss_runs(installation_id,run_id,tenant_id,high_water_lost,historical)
SELECT DISTINCT s.installation_id,s.run_id,i.tenant_id,0,true
FROM enrollment.installation_collection_segments s JOIN enrollment.installations i ON i.id=s.installation_id
ON CONFLICT (installation_id,run_id) DO NOTHING;

COMMENT ON TABLE enrollment.installation_loss_events IS '서버가 확인한 양수 손실 페이로드 증가분. 이벤트·토큰 수가 아니다.';
COMMENT ON TABLE enrollment.installation_loss_runs IS '실행별 최고 누계와 관측 근거. 조회 창이 지나도 삭제하지 않는다.';
COMMENT ON TABLE enrollment.installation_loss_tracking IS '추적 시작과 구 writer rollback 중단 이력. 활성 추적은 한 개다.';
