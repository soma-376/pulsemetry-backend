-- 전달 대기가 언제부터 이어졌는지 (ADR 0041). 최신 상태 한 행만으로는 "대기가 이어지는 중"과 "보고 순간에 마침 하나가 전송 중"을 가를 수 없다.
ALTER TABLE enrollment.installation_heartbeats ADD COLUMN pending_since timestamptz;

-- 이미 있는 행은 마지막 보고에서 처음 본 것으로 둔다. 그보다 앞선 시각을 지어내지 않는다.
UPDATE enrollment.installation_heartbeats SET pending_since = received_at WHERE pending > 0;

ALTER TABLE enrollment.installation_heartbeats
    ADD CONSTRAINT installation_heartbeats_pending_since_matches CHECK (
        (pending = 0 AND pending_since IS NULL) OR (pending > 0 AND pending_since IS NOT NULL AND pending_since <= received_at));

COMMENT ON COLUMN enrollment.installation_heartbeats.pending_since IS '전달 대기가 0이 아닌 보고가 끊김 없이 이어지기 시작한 보고의 수신 시각. 대기가 없으면 NULL.';
