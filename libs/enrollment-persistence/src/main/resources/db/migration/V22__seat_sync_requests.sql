-- 좌석 동기화 요청 (ADR 0048 §7). 관리자의 "지금 동기화"는 공통 작업(seat_sync, 대상 = 연결 ID)으로 접수하고,
-- 연결의 요청 칸에 걸어 둔다. 다음 주기 실행이 그 칸을 가져가 실행 기록에 잇고, 결과를 작업의 대상 결과로 옮긴다.
ALTER TABLE enrollment.operations DROP CONSTRAINT operations_kind_check;
ALTER TABLE enrollment.operations ADD CONSTRAINT operations_kind_check
    CHECK (kind IN ('seat_reclaim','seat_restore','installation_notification','retention_cleanup','seat_sync'));

ALTER TABLE enrollment.vendor_connections ADD COLUMN sync_requested_operation_id uuid REFERENCES enrollment.operations(id);
ALTER TABLE enrollment.seat_sync_runs ADD COLUMN operation_id uuid REFERENCES enrollment.operations(id);

COMMENT ON COLUMN enrollment.vendor_connections.sync_requested_operation_id IS '아직 실행이 가져가지 않은 동기화 요청(seat_sync 작업). 있으면 주기와 무관하게 다음 실행 대상이다.';
COMMENT ON COLUMN enrollment.seat_sync_runs.operation_id IS '이 실행이 끝내는 동기화 요청 작업. 선점을 잃은 실행의 요청은 다음 실행이 이어받는다.';
