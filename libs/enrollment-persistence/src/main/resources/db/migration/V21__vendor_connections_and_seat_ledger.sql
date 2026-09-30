-- 벤더 연결과 좌석 원장 (ADR 0048). 좌석은 등록 제품(managed_vendors) 하나에서 벤더 계정 하나가 차지하는 자리다.
-- 계약의 구매 수량(tiers[].seats)은 좌석이 아니다 — 이 표들을 수량으로 채우지 않는다. 구 contracts·contract_memberships 와 무관하다.

-- 상태 값은 V14 이후의 표들처럼 varchar + CHECK 다. native enum 은 V1 이 옮긴 dbml 의 열 종류뿐이다(ADR 0009).

-- 등록 제품 하나에 커넥터 하나. 자격증명은 AES-256-GCM 암호문(추가 인증 데이터 = 조직/등록 제품/연결 ID)이고 키 ID 를 함께 남긴다.
CREATE TABLE enrollment.vendor_connections (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    vendor_id varchar(100) NOT NULL,
    connector varchar(50) NOT NULL,
    settings jsonb NOT NULL,
    credential_ciphertext text,
    credential_key_id varchar(64),
    credential_updated_at timestamptz,
    check_status varchar(32) NOT NULL CHECK (check_status IN ('unverified', 'verified', 'invalid_credentials', 'insufficient_permission', 'unavailable')),
    checked_at timestamptz,
    sync_claimed_by varchar(64),
    sync_claimed_until timestamptz,
    last_sync_succeeded_at timestamptz,
    last_sync_failed_at timestamptz,
    last_sync_error varchar(64),
    version bigint NOT NULL CHECK (version >= 1),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES enrollment.members(id),
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL REFERENCES enrollment.members(id),
    deleted_at timestamptz,
    deleted_by uuid REFERENCES enrollment.members(id),
    FOREIGN KEY (tenant_id, vendor_id) REFERENCES enrollment.managed_vendors(tenant_id, vendor_id),
    CONSTRAINT vendor_connections_settings CHECK (jsonb_typeof(settings) = 'object'),
    -- 지운 연결은 암호문을 남기지 않는다. 살아 있는 연결은 암호문·키 ID·갱신 시각을 모두 갖는다.
    CONSTRAINT vendor_connections_credential CHECK ((deleted_at IS NULL) = (credential_ciphertext IS NOT NULL)
        AND (credential_ciphertext IS NULL) = (credential_key_id IS NULL)
        AND (credential_ciphertext IS NULL) = (credential_updated_at IS NULL)),
    CONSTRAINT vendor_connections_deleted CHECK ((deleted_at IS NULL) = (deleted_by IS NULL)),
    CONSTRAINT vendor_connections_claim CHECK ((sync_claimed_by IS NULL) = (sync_claimed_until IS NULL)),
    CONSTRAINT vendor_connections_sync_error CHECK ((last_sync_failed_at IS NULL) = (last_sync_error IS NULL))
);
-- 등록 제품마다 활성 연결은 하나다.
CREATE UNIQUE INDEX vendor_connections_active_idx ON enrollment.vendor_connections (tenant_id, vendor_id) WHERE deleted_at IS NULL;

-- 동기화 실행 한 번의 기록. 연결마다 진행 중인 실행은 하나다.
CREATE TABLE enrollment.seat_sync_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    connection_id uuid NOT NULL REFERENCES enrollment.vendor_connections(id),
    trigger varchar(16) NOT NULL CHECK (trigger IN ('schedule', 'request')),
    worker varchar(64) NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    status varchar(16) NOT NULL CHECK (status IN ('running', 'succeeded', 'failed')),
    error varchar(64),
    listed_seats integer CHECK (listed_seats >= 0),
    changed_seats integer CHECK (changed_seats >= 0),
    CONSTRAINT seat_sync_runs_finished CHECK ((status = 'running') = (finished_at IS NULL)),
    CONSTRAINT seat_sync_runs_error CHECK ((status = 'failed') = (error IS NOT NULL)),
    CONSTRAINT seat_sync_runs_counts CHECK ((status = 'succeeded') = (listed_seats IS NOT NULL) AND (listed_seats IS NULL) = (changed_seats IS NULL))
);
CREATE UNIQUE INDEX seat_sync_runs_running_idx ON enrollment.seat_sync_runs (connection_id) WHERE status = 'running';

-- 좌석 하나 = (등록 제품, 벤더 계정 키) 하나. ID(seatAssignmentId)는 해제·재배정을 거쳐도 바뀌지 않는다.
CREATE TABLE enrollment.seat_assignments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    vendor_id varchar(100) NOT NULL,
    account varchar(320) NOT NULL,
    account_kind varchar(16) NOT NULL CHECK (account_kind IN ('email', 'github_login')),
    vendor_account_ref varchar(200),
    account_email varchar(320),
    state varchar(24) NOT NULL CHECK (state IN ('assigned', 'pending_assignment', 'pending_release', 'released')),
    source varchar(16) NOT NULL CHECK (source IN ('connector', 'manual', 'csv', 'vendor_control', 'admin_action')),
    member_id uuid REFERENCES enrollment.members(id),
    member_link varchar(16) CHECK (member_link IN ('email_match', 'admin')),
    tier_id varchar(100),
    vendor_tier varchar(100),
    assigned_at timestamptz NOT NULL,
    release_effective_on date,
    released_at timestamptz,
    vendor_last_activity_at timestamptz,
    note varchar(1000),
    version bigint NOT NULL CHECK (version >= 1),
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, vendor_id) REFERENCES enrollment.managed_vendors(tenant_id, vendor_id),
    CONSTRAINT seat_assignments_account UNIQUE (tenant_id, vendor_id, account),
    -- 구성원이 있으면 연결 근거가 있다. 이메일 일치는 구성원이 있어야 한다(관리자는 '잇지 않음'도 정한다).
    CONSTRAINT seat_assignments_member CHECK (member_id IS NULL OR member_link IS NOT NULL),
    CONSTRAINT seat_assignments_email_match CHECK (member_link IS DISTINCT FROM 'email_match' OR member_id IS NOT NULL),
    CONSTRAINT seat_assignments_released CHECK ((state = 'released') = (released_at IS NOT NULL)),
    CONSTRAINT seat_assignments_release_effective CHECK (release_effective_on IS NULL OR state = 'pending_release')
);
CREATE INDEX seat_assignments_member_idx ON enrollment.seat_assignments (tenant_id, member_id) WHERE member_id IS NOT NULL;

-- 좌석의 판마다 한 행. 지우지 않는다. 행위자는 구성원·동기화 실행·작업 중 하나다(원천이 connector 면 동기화 실행).
CREATE TABLE enrollment.seat_assignment_events (
    seat_assignment_id uuid NOT NULL REFERENCES enrollment.seat_assignments(id),
    version bigint NOT NULL,
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    state varchar(24) NOT NULL CHECK (state IN ('assigned', 'pending_assignment', 'pending_release', 'released')),
    source varchar(16) NOT NULL CHECK (source IN ('connector', 'manual', 'csv', 'vendor_control', 'admin_action')),
    member_id uuid REFERENCES enrollment.members(id),
    member_link varchar(16) CHECK (member_link IN ('email_match', 'admin')),
    tier_id varchar(100),
    vendor_tier varchar(100),
    release_effective_on date,
    note varchar(1000),
    actor_id uuid REFERENCES enrollment.members(id),
    sync_run_id uuid REFERENCES enrollment.seat_sync_runs(id),
    operation_id uuid REFERENCES enrollment.operations(id),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (seat_assignment_id, version),
    CONSTRAINT seat_assignment_events_actor CHECK (num_nonnulls(actor_id, sync_run_id, operation_id) = 1)
);

COMMENT ON TABLE enrollment.vendor_connections IS '등록 제품의 벤더 연결 (ADR 0048 §6). 자격증명은 암호문뿐이다 — 조회 앱은 암호문 열을 읽지 않는다.';
COMMENT ON COLUMN enrollment.vendor_connections.settings IS '비밀이 아닌 연결 설정(조직 이름·청구 계정 등). 키는 커넥터 설명의 설정 키와 같다.';
COMMENT ON COLUMN enrollment.vendor_connections.credential_key_id IS '암호화에 쓴 키의 ID(pulsemetry.vendor-connections.credential-keys). 옛 키는 이 값을 쓰는 행이 없을 때 뺀다.';
COMMENT ON TABLE enrollment.seat_assignments IS '좌석 원장 (ADR 0048). 권위·우선순위는 ADR §3의 표, 전이는 §2의 표를 따른다.';
COMMENT ON COLUMN enrollment.seat_assignments.source IS '마지막으로 상태를 정한 원천.';
COMMENT ON COLUMN enrollment.seat_assignments.vendor_last_activity_at IS '벤더가 준 마지막 활동. NULL 은 모름이지 미사용이 아니다. 판을 올리지 않는다.';
COMMENT ON COLUMN enrollment.seat_assignments.assigned_at IS '현재(해제면 마지막) 보유 구간의 시작. 벤더가 주면 벤더 값이다.';
