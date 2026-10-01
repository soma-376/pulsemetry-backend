-- 좌석 회수·복원 (ADR 0049). 미리보기는 요청자·좌석 판·실행 방식에 묶인 짧은 기록이고, 실행은 공통 작업(seat_reclaim·seat_restore)이다.
-- 대상마다 실행 방식(벤더 제어·관리자 조치)을 남기고, 벤더 제어 대상은 주기 실행이 선점해 커넥터를 부른다.
CREATE TABLE enrollment.seat_reclaim_previews (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    requested_by uuid NOT NULL REFERENCES enrollment.members(id),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    targets jsonb NOT NULL CHECK (jsonb_typeof(targets) = 'array'),
    operation_id uuid REFERENCES enrollment.operations(id),
    CONSTRAINT seat_reclaim_previews_expiry CHECK (expires_at > created_at)
);
CREATE INDEX seat_reclaim_previews_tenant_idx ON enrollment.seat_reclaim_previews (tenant_id, created_at);

CREATE TABLE enrollment.seat_controls (
    operation_id uuid NOT NULL REFERENCES enrollment.operations(id),
    seat_assignment_id uuid NOT NULL REFERENCES enrollment.seat_assignments(id),
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    action varchar(16) NOT NULL CHECK (action IN ('release', 'restore')),
    method varchar(16) NOT NULL CHECK (method IN ('vendor_control', 'admin_action')),
    connection_id uuid REFERENCES enrollment.vendor_connections(id),
    requested_at timestamptz NOT NULL,
    claimed_by varchar(128),
    claimed_until timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    PRIMARY KEY (operation_id, seat_assignment_id),
    CONSTRAINT seat_controls_connection_for_vendor CHECK ((method = 'vendor_control') = (connection_id IS NOT NULL)),
    CONSTRAINT seat_controls_claim_pair CHECK ((claimed_by IS NULL) = (claimed_until IS NULL))
);
CREATE INDEX seat_controls_seat_idx ON enrollment.seat_controls (seat_assignment_id);
CREATE INDEX seat_controls_vendor_idx ON enrollment.seat_controls (requested_at) WHERE method = 'vendor_control';

COMMENT ON TABLE enrollment.seat_reclaim_previews IS '좌석 회수 미리보기 (ADR 0049). 요청자·대상 좌석의 판·실행 방식에 묶이고 짧게 산다. 실행하면 operation_id가 채워지고 다시 쓰지 못한다.';
COMMENT ON COLUMN enrollment.seat_reclaim_previews.targets IS '실행할 수 있는 대상 [{seatAssignmentId, version, method, connectionId}]. 거절한 대상은 남기지 않는다.';
COMMENT ON TABLE enrollment.seat_controls IS '회수·복원 작업의 대상별 실행 방식 (ADR 0049). 결과는 operation_targets가 담는다.';
COMMENT ON COLUMN enrollment.seat_controls.method IS 'vendor_control: 주기 실행이 커넥터를 부른다. admin_action: 관리자가 벤더 콘솔에서 조치하고 확인해야 끝난다.';
COMMENT ON COLUMN enrollment.seat_controls.claimed_until IS '벤더 제어 대상의 선점 기한. 기한이 지나면 다른 실행이 가져간다(벤더 호출이 두 번 갈 수 있다).';
