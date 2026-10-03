-- 공통 작업 기록 (ADR 0039). 명령이 접수한 뒤 요청 밖에서 끝나는 일과 그 대상별 결과다.
-- 작업의 상태는 대상 결과에서 계산한다. 끝난 작업과 끝난 대상은 다시 바뀌지 않는다.
CREATE TABLE enrollment.operations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    kind varchar(32) NOT NULL CHECK (kind IN ('seat_reclaim','seat_restore','installation_notification','retention_cleanup')),
    status varchar(24) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','running','awaiting_admin_action','succeeded','partially_failed','failed')),
    requested_by uuid NOT NULL REFERENCES enrollment.members(id),
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    restore_until timestamptz,
    restores_operation_id uuid REFERENCES enrollment.operations(id),
    retention_operation_id uuid,
    CONSTRAINT operations_completed_when_closed CHECK ((status IN ('succeeded','partially_failed','failed')) = (completed_at IS NOT NULL)),
    CONSTRAINT operations_restore_until_on_reclaim CHECK (restore_until IS NULL OR kind = 'seat_reclaim'),
    CONSTRAINT operations_restore_names_reclaim CHECK ((kind = 'seat_restore') = (restores_operation_id IS NOT NULL)),
    CONSTRAINT operations_retention_on_cleanup CHECK (retention_operation_id IS NULL OR kind = 'retention_cleanup')
);
CREATE INDEX operations_tenant_idx ON enrollment.operations (tenant_id, created_at);
-- 한 회수 작업에 살아 있는(실패하지 않은) 복원 작업은 하나다.
CREATE UNIQUE INDEX operations_live_restore_idx ON enrollment.operations (restores_operation_id)
    WHERE restores_operation_id IS NOT NULL AND status <> 'failed';

CREATE TABLE enrollment.operation_targets (
    operation_id uuid NOT NULL REFERENCES enrollment.operations(id),
    target_id varchar(128) NOT NULL CHECK (btrim(target_id) <> ''),
    position integer NOT NULL CHECK (position >= 0),
    status varchar(24) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','awaiting_admin_action','succeeded','failed')),
    reason varchar(64),
    action varchar(64),
    confirmed_by uuid REFERENCES enrollment.members(id),
    resolved_at timestamptz,
    PRIMARY KEY (operation_id, target_id),
    UNIQUE (operation_id, position),
    CONSTRAINT operation_targets_reason_when_failed CHECK ((status = 'failed') = (reason IS NOT NULL)),
    CONSTRAINT operation_targets_action_while_awaiting CHECK (status <> 'awaiting_admin_action' OR action IS NOT NULL),
    CONSTRAINT operation_targets_resolved_when_closed CHECK ((status IN ('succeeded','failed')) = (resolved_at IS NOT NULL)),
    -- 관리자 조치가 필요했던 대상은 확인한 사람 없이 성공이 되지 않는다.
    CONSTRAINT operation_targets_confirmed_action CHECK (
        (confirmed_by IS NULL OR (action IS NOT NULL AND status = 'succeeded'))
        AND NOT (status = 'succeeded' AND action IS NOT NULL AND confirmed_by IS NULL))
);

COMMENT ON TABLE enrollment.operations IS '명령이 접수한 비동기 작업 (ADR 0039). 상태는 대상 결과에서 계산한다.';
COMMENT ON COLUMN enrollment.operations.status IS 'pending → running → awaiting_admin_action | succeeded | partially_failed | failed. 끝난 작업은 바뀌지 않는다.';
COMMENT ON COLUMN enrollment.operations.restore_until IS '좌석 회수를 되돌릴 수 있는 기한. NULL이면 되돌릴 수 없다.';
COMMENT ON COLUMN enrollment.operations.restores_operation_id IS '이 복원 작업이 되돌리는 회수 작업.';
COMMENT ON COLUMN enrollment.operations.retention_operation_id IS 'telemetry_ops.retention_operations의 가장 최근 실행. 스키마가 달라 FK를 두지 않는다.';
COMMENT ON TABLE enrollment.operation_targets IS '작업의 대상별 결과 (ADR 0039). target_id가 무엇의 ID인지는 작업 종류가 정한다.';
COMMENT ON COLUMN enrollment.operation_targets.reason IS '실패 분류 코드. 외부 응답 원문을 넣지 않는다.';
COMMENT ON COLUMN enrollment.operation_targets.action IS '관리자가 시스템 밖에서 해야 하는 조치의 코드.';
COMMENT ON COLUMN enrollment.operation_targets.confirmed_by IS '조치를 했다고 확인한 구성원.';
