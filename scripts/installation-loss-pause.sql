-- 구 writer rollback 직전에 enrollment 소유 계정으로 실행한다. 앱/사용자 활성화 토글이 아니다.
-- 새 writer의 진행 중 heartbeat가 종료될 때까지 기다린 후 추적을 중단한다(ADR 0076).
BEGIN;
LOCK TABLE enrollment.installation_loss_tracking IN EXCLUSIVE MODE;
UPDATE enrollment.installation_loss_tracking SET stopped_at=clock_timestamp() WHERE stopped_at IS NULL;
COMMIT;
