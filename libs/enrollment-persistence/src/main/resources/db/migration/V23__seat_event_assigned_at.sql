-- 좌석 이력에 보유 구간의 시작을 남긴다 (ADR 0048 §2·§7). 조회는 기준 시각의 좌석을 이력으로 다시 세운다 — 배정 시각(벤더가 준 값 포함)이
-- 그 판의 값이어야 유휴 일수를 기준 시각 기준으로 셀 수 있다. 앞선 판은 그 판을 기록한 시각으로 채운다(현재 판은 좌석 행의 값).
ALTER TABLE enrollment.seat_assignment_events ADD COLUMN assigned_at timestamptz;
UPDATE enrollment.seat_assignment_events e SET assigned_at = s.assigned_at
    FROM enrollment.seat_assignments s WHERE s.id = e.seat_assignment_id AND s.version = e.version;
UPDATE enrollment.seat_assignment_events SET assigned_at = recorded_at WHERE assigned_at IS NULL;
ALTER TABLE enrollment.seat_assignment_events ALTER COLUMN assigned_at SET NOT NULL;
-- 기준 시각의 판을 찾는 조회(좌석마다 recorded_at <= 기준 시각인 가장 큰 판).
CREATE INDEX seat_assignment_events_as_of_idx ON enrollment.seat_assignment_events (tenant_id, seat_assignment_id, recorded_at, version);
