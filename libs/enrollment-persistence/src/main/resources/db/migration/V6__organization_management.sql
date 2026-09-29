-- 화면의 수동 벤더 계약과 명령 재시도 기록. 기존 약정/토큰 할인 계약을 좌석 계약으로 재해석하지 않는다.
CREATE TABLE enrollment.managed_vendors (
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    vendor_id varchar(100) NOT NULL,
    kind varchar(50) NOT NULL,
    source varchar(16) NOT NULL CHECK (source IN ('manual','detected')),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, vendor_id)
);
CREATE TABLE enrollment.vendor_contract_versions (
    tenant_id uuid NOT NULL,
    vendor_id varchar(100) NOT NULL,
    version bigint NOT NULL,
    display_name varchar(100) NOT NULL,
    contract jsonb,
    archived boolean NOT NULL DEFAULT false,
    recorded_at timestamptz NOT NULL,
    recorded_by uuid NOT NULL REFERENCES enrollment.members(id),
    PRIMARY KEY (tenant_id, vendor_id, version),
    FOREIGN KEY (tenant_id, vendor_id) REFERENCES enrollment.managed_vendors(tenant_id,vendor_id)
);
CREATE TABLE enrollment.management_commands (
    tenant_id uuid NOT NULL REFERENCES enrollment.tenants(id),
    actor_id uuid NOT NULL REFERENCES enrollment.members(id),
    command_path varchar(300) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    encrypted_response text NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, actor_id, command_path, idempotency_key)
);
