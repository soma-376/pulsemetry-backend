-- 구 writer가 모두 종료된 뒤, 신규 writer 기동 전에 enrollment 소유 계정으로 실행한다(ADR 0076).
-- 중단 중 누계는 기준값일 뿐 신규 손실로 백필하지 않는다. 기존 원장과 실행 커서는 보존한다.
BEGIN;
LOCK TABLE enrollment.installation_loss_tracking IN EXCLUSIVE MODE;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM enrollment.installation_loss_tracking WHERE stopped_at IS NULL) THEN
        RAISE EXCEPTION '이미 손실 추적이 실행 중입니다. 중단 이력을 확인하세요.';
    END IF;
END $$;
LOCK TABLE enrollment.installation_heartbeats IN SHARE MODE;
INSERT INTO enrollment.installation_loss_runs(installation_id,run_id,tenant_id,high_water_lost)
SELECT h.installation_id,h.run_id,i.tenant_id,h.lost
FROM enrollment.installation_heartbeats h JOIN enrollment.installations i ON i.id=h.installation_id
ON CONFLICT (installation_id,run_id) DO UPDATE
SET high_water_lost=GREATEST(installation_loss_runs.high_water_lost,EXCLUDED.high_water_lost),historical=false;
INSERT INTO enrollment.installation_loss_runs(installation_id,run_id,tenant_id,high_water_lost,historical)
SELECT DISTINCT s.installation_id,s.run_id,i.tenant_id,0,true
FROM enrollment.installation_collection_segments s JOIN enrollment.installations i ON i.id=s.installation_id
ON CONFLICT (installation_id,run_id) DO NOTHING;
INSERT INTO enrollment.installation_loss_tracking(started_at) VALUES (clock_timestamp());
COMMIT;
