-- enrollment 스키마 초기 생성.
--
-- 원본은 rdb-schema/dbdiagram.dbml 이며 그 파일은 읽기 전용이다.
-- 의도적으로 다르게 옮긴 지점은 ralph-loop/PROGRESS.md 의 SCHEMA-DRIFT 에 기록한다.
--
-- 이식 규칙
--   * dbml 의 Postgres native enum 은 varchar + CHECK 제약으로 옮긴다 (PLAN.md L5).
--     JPA 는 @Enumerated(EnumType.STRING) 으로 매핑한다.
--   * uuid PK 에 DB 기본값을 두지 않는다. 식별자는 애플리케이션이 만든다.

CREATE SCHEMA IF NOT EXISTS enrollment;

-- ─────────────────────────────────────────────────────────────────────────────
-- 조직과 구성원
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE enrollment.tenants (
    id         uuid         NOT NULL,
    name       varchar(100) NOT NULL,
    slug       varchar(100),
    timezone   varchar(50)  NOT NULL DEFAULT 'Asia/Seoul',
    logo_url   text,
    status     varchar(20)  NOT NULL DEFAULT 'active',
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT pk_tenants PRIMARY KEY (id),
    CONSTRAINT uq_tenants_slug UNIQUE (slug),
    CONSTRAINT ck_tenants_status CHECK (status IN ('active', 'suspended', 'terminated'))
);

COMMENT ON TABLE enrollment.tenants IS 'Pulsemetry를 사용하는 고객 조직';

CREATE TABLE enrollment.members (
    id               uuid         NOT NULL,
    tenant_id        uuid         NOT NULL,
    cognito_user_sub varchar(255),
    email            varchar(320) NOT NULL,
    display_name     varchar(100),
    role             varchar(20)  NOT NULL DEFAULT 'member',
    status           varchar(20)  NOT NULL DEFAULT 'active',
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_members PRIMARY KEY (id),
    CONSTRAINT fk_members_tenant FOREIGN KEY (tenant_id) REFERENCES enrollment.tenants (id),
    CONSTRAINT uq_members_tenant_cognito_user_sub UNIQUE (tenant_id, cognito_user_sub),
    CONSTRAINT uq_members_tenant_email UNIQUE (tenant_id, email),
    CONSTRAINT ck_members_role CHECK (role IN ('owner', 'admin', 'member')),
    CONSTRAINT ck_members_status CHECK (status IN ('invited', 'active', 'suspended'))
);

CREATE INDEX ix_members_cognito_user_sub ON enrollment.members (cognito_user_sub);

COMMENT ON TABLE enrollment.members IS 'Pulsemetry 조직 구성원. 관리자 등 웹 사용자는 Cognito 계정과 연결되며, 일반 사용자는 installation을 통해 서비스와 연결된다.';

CREATE TABLE enrollment.teams (
    id         uuid         NOT NULL,
    tenant_id  uuid         NOT NULL,
    name       varchar(100) NOT NULL,
    status     varchar(20)  NOT NULL DEFAULT 'active',
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_teams PRIMARY KEY (id),
    CONSTRAINT fk_teams_tenant FOREIGN KEY (tenant_id) REFERENCES enrollment.tenants (id),
    CONSTRAINT uq_teams_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT ck_teams_status CHECK (status IN ('active', 'archived'))
);

CREATE INDEX ix_teams_tenant ON enrollment.teams (tenant_id);

COMMENT ON TABLE enrollment.teams IS 'tenant 내부에서 구성원과 AI 사용량을 구분하기 위한 팀 또는 부서 단위';

CREATE TABLE enrollment.team_memberships (
    id        uuid        NOT NULL,
    team_id   uuid        NOT NULL,
    member_id uuid        NOT NULL,
    joined_at timestamptz NOT NULL DEFAULT now(),
    left_at   timestamptz,
    CONSTRAINT pk_team_memberships PRIMARY KEY (id),
    CONSTRAINT fk_team_memberships_team FOREIGN KEY (team_id) REFERENCES enrollment.teams (id),
    CONSTRAINT fk_team_memberships_member FOREIGN KEY (member_id) REFERENCES enrollment.members (id)
);

CREATE INDEX ix_team_memberships_team ON enrollment.team_memberships (team_id);
CREATE INDEX ix_team_memberships_member ON enrollment.team_memberships (member_id);

COMMENT ON TABLE enrollment.team_memberships IS '구성원의 팀 소속 관계와 소속 기간';

-- ─────────────────────────────────────────────────────────────────────────────
-- 초대와 설치
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE enrollment.invitations (
    id                   uuid         NOT NULL,
    tenant_id            uuid         NOT NULL,
    target_member_id     uuid         NOT NULL,
    created_by_member_id uuid         NOT NULL,
    -- 원본 초대 코드는 저장하지 않는다. SHA-256 hex 소문자 64자만 담는다 (PLAN.md R4·L11).
    code_hash            varchar(255) NOT NULL,
    used_at              timestamptz,
    expires_at           timestamptz  NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    revoked_at           timestamptz,
    CONSTRAINT pk_invitations PRIMARY KEY (id),
    CONSTRAINT fk_invitations_tenant FOREIGN KEY (tenant_id) REFERENCES enrollment.tenants (id),
    CONSTRAINT fk_invitations_target_member FOREIGN KEY (target_member_id) REFERENCES enrollment.members (id),
    CONSTRAINT fk_invitations_created_by_member FOREIGN KEY (created_by_member_id) REFERENCES enrollment.members (id),
    CONSTRAINT uq_invitations_code_hash UNIQUE (code_hash)
);

CREATE INDEX ix_invitations_tenant ON enrollment.invitations (tenant_id);
CREATE INDEX ix_invitations_expires_at ON enrollment.invitations (expires_at);

COMMENT ON TABLE enrollment.invitations IS 'CLI 설치를 허용하는 일회성 초대 코드. 관리자가 생성하여 이메일 등으로 사용자에게 전달되며, 설치(enrollment)에 한 번만 사용된다.';

CREATE TABLE enrollment.installations (
    id             uuid         NOT NULL,
    tenant_id      uuid         NOT NULL,
    member_id      uuid         NOT NULL,
    invitation_id  uuid         NOT NULL,
    hostname       varchar(255),
    -- 클라이언트는 runtime.GOOS 를 그대로 보낸다(darwin). 서버가 macos 로 정규화해 저장한다.
    platform       varchar(20)  NOT NULL,
    architecture   varchar(30),
    client_version varchar(50),
    status         varchar(20)  NOT NULL DEFAULT 'active',
    last_seen_at   timestamptz,
    revoked_at     timestamptz,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_installations PRIMARY KEY (id),
    CONSTRAINT fk_installations_tenant FOREIGN KEY (tenant_id) REFERENCES enrollment.tenants (id),
    CONSTRAINT fk_installations_member FOREIGN KEY (member_id) REFERENCES enrollment.members (id),
    CONSTRAINT fk_installations_invitation FOREIGN KEY (invitation_id) REFERENCES enrollment.invitations (id),
    CONSTRAINT ck_installations_platform CHECK (platform IN ('windows', 'macos', 'linux')),
    CONSTRAINT ck_installations_status CHECK (status IN ('active', 'revoked'))
);

CREATE INDEX ix_installations_member ON enrollment.installations (member_id);
CREATE INDEX ix_installations_invitation ON enrollment.installations (invitation_id);
CREATE INDEX ix_installations_tenant_status ON enrollment.installations (tenant_id, status);

COMMENT ON TABLE enrollment.installations IS '사용자의 PC에 설치된 Pulsemetry CLI 또는 daemon';

CREATE TABLE enrollment.installation_credentials (
    id              uuid         NOT NULL,
    installation_id uuid         NOT NULL,
    -- installation 의 장기 신원 자격증명(installation_token). 해시만 저장한다.
    credential_hash varchar(255) NOT NULL,
    issued_at       timestamptz  NOT NULL DEFAULT now(),
    last_used_at    timestamptz,
    revoked_at      timestamptz,
    CONSTRAINT pk_installation_credentials PRIMARY KEY (id),
    CONSTRAINT fk_installation_credentials_installation FOREIGN KEY (installation_id) REFERENCES enrollment.installations (id),
    CONSTRAINT uq_installation_credentials_credential_hash UNIQUE (credential_hash)
);

CREATE INDEX ix_installation_credentials_installation ON enrollment.installation_credentials (installation_id);
CREATE INDEX ix_installation_credentials_installation_revoked_at ON enrollment.installation_credentials (installation_id, revoked_at);

COMMENT ON TABLE enrollment.installation_credentials IS 'installation의 장기 신원을 증명하는 자격증명. telemetry token 발급에 사용하며, 원본 토큰은 로컬 기기에만 저장하고 서버에는 해시만 저장한다.';

CREATE TABLE enrollment.telemetry_tokens (
    id              uuid         NOT NULL,
    installation_id uuid         NOT NULL,
    -- OTLP 헤더에 실려 나가는 교체 가능한 토큰. 해시만 저장한다.
    token_hash      varchar(255) NOT NULL,
    issued_at       timestamptz  NOT NULL DEFAULT now(),
    last_used_at    timestamptz,
    revoked_at      timestamptz,
    CONSTRAINT pk_telemetry_tokens PRIMARY KEY (id),
    CONSTRAINT fk_telemetry_tokens_installation FOREIGN KEY (installation_id) REFERENCES enrollment.installations (id),
    CONSTRAINT uq_telemetry_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_telemetry_tokens_installation ON enrollment.telemetry_tokens (installation_id);
CREATE INDEX ix_telemetry_tokens_installation_revoked_at ON enrollment.telemetry_tokens (installation_id, revoked_at);

COMMENT ON TABLE enrollment.telemetry_tokens IS 'installation_credentials를 근거로 발급되는 인증 토큰. installation과 1:N 관계이며 재발급, 만료, 폐기 이력을 관리한다.';

-- ─────────────────────────────────────────────────────────────────────────────
-- manifest 배포
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE enrollment.manifests (
    id                   uuid        NOT NULL,
    tenant_id            uuid        NOT NULL,
    version              int         NOT NULL,
    manifest             jsonb       NOT NULL,
    is_active            boolean     NOT NULL DEFAULT false,
    created_by_member_id uuid        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    activated_at         timestamptz,
    CONSTRAINT pk_manifests PRIMARY KEY (id),
    CONSTRAINT fk_manifests_tenant FOREIGN KEY (tenant_id) REFERENCES enrollment.tenants (id),
    CONSTRAINT fk_manifests_created_by_member FOREIGN KEY (created_by_member_id) REFERENCES enrollment.members (id),
    CONSTRAINT uq_manifests_tenant_version UNIQUE (tenant_id, version)
);

CREATE INDEX ix_manifests_tenant_is_active ON enrollment.manifests (tenant_id, is_active);

-- SCHEMA-DRIFT: dbml 에는 없는 부분 유니크 인덱스.
-- enroll 은 "tenant 의 is_active manifest" 를 단수로 가정한다. 둘 이상이면 어느 것이 내려갈지
-- 비결정적이므로 DB 가 한 개임을 보장한다.
CREATE UNIQUE INDEX ux_manifests_tenant_active ON enrollment.manifests (tenant_id) WHERE is_active;

COMMENT ON TABLE enrollment.manifests IS 'tenant별 수집 및 privacy 정책의 버전 이력. 기존 row는 수정하지 않고 설정 변경 시 새 version을 생성한다.';

-- 이 테이블의 소유는 manifest(정책) 가 아니라 enrollment 도메인이다 (ADR 0008).
-- 정책의 정의가 아니라 installation 의 배포 상태다 — PK 가 (installation_id, manifest_id) 이고
-- applied_at 이 그 installation 이 실제로 적용한 시각이며, installation 이 사라지면 함께 죽는다.
-- 그런데도 "manifest 배포" 섹션에 있는 것은 fk_installation_manifest_assignments_manifest 가
-- manifests(id) 를 참조해서다. "초대와 설치" 섹션으로 DDL 을 앞당기면 FK 대상이 아직 없어
-- 마이그레이션이 실패한다. 위치는 생성 순서 제약이지 소유의 표시가 아니다.
CREATE TABLE enrollment.installation_manifest_assignments (
    installation_id uuid        NOT NULL,
    manifest_id     uuid        NOT NULL,
    assigned_at     timestamptz NOT NULL DEFAULT now(),
    applied_at      timestamptz,
    CONSTRAINT pk_installation_manifest_assignments PRIMARY KEY (installation_id, manifest_id),
    CONSTRAINT fk_installation_manifest_assignments_installation FOREIGN KEY (installation_id) REFERENCES enrollment.installations (id),
    CONSTRAINT fk_installation_manifest_assignments_manifest FOREIGN KEY (manifest_id) REFERENCES enrollment.manifests (id)
);

CREATE INDEX ix_installation_manifest_assignments_manifest ON enrollment.installation_manifest_assignments (manifest_id);

COMMENT ON TABLE enrollment.installation_manifest_assignments IS 'installation에 배포된 manifest 버전과 적용 여부';

-- ─────────────────────────────────────────────────────────────────────────────
-- 계약
-- enrollment API 는 이 테이블들을 쓰지 않는다(PLAN.md R7 — 엔티티도 만들지 않는다).
-- 스키마의 진실원은 Flyway 이므로 dbml 에 있는 테이블은 DDL 에 함께 이식한다.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE enrollment.contracts (
    id                   uuid         NOT NULL,
    tenant_id            uuid         NOT NULL,
    vendor               varchar(20)  NOT NULL,
    contract_type        varchar(30)  NOT NULL,
    name                 varchar(100) NOT NULL,
    contract_no          varchar(100),
    contracted_at        date         NOT NULL,
    starts_at            date         NOT NULL,
    ends_at              date,
    status               varchar(20)  NOT NULL DEFAULT 'active',
    created_by_member_id uuid,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    terminated_at        timestamptz,
    CONSTRAINT pk_contracts PRIMARY KEY (id),
    CONSTRAINT fk_contracts_tenant FOREIGN KEY (tenant_id) REFERENCES enrollment.tenants (id),
    CONSTRAINT fk_contracts_created_by_member FOREIGN KEY (created_by_member_id) REFERENCES enrollment.members (id),
    CONSTRAINT uq_contracts_tenant_contract_no UNIQUE (tenant_id, contract_no),
    CONSTRAINT ck_contracts_vendor CHECK (vendor IN ('anthropic', 'openai', 'google')),
    CONSTRAINT ck_contracts_contract_type CHECK (contract_type IN ('term_commitment', 'token_discount')),
    CONSTRAINT ck_contracts_status CHECK (status IN ('draft', 'active', 'expired', 'terminated'))
);

CREATE INDEX ix_contracts_tenant ON enrollment.contracts (tenant_id);
CREATE INDEX ix_contracts_tenant_vendor ON enrollment.contracts (tenant_id, vendor);
CREATE INDEX ix_contracts_tenant_status ON enrollment.contracts (tenant_id, status);
CREATE INDEX ix_contracts_starts_at_ends_at ON enrollment.contracts (starts_at, ends_at);

COMMENT ON TABLE enrollment.contracts IS 'tenant가 AI 벤더와 체결한 계약. 하나의 tenant는 벤더별·기간별로 여러 계약을 가질 수 있다.';

CREATE TABLE enrollment.contract_term_commitments (
    contract_id        uuid           NOT NULL,
    commitment_months  int            NOT NULL,
    commitment_amount  numeric(18, 4),
    currency           char(3)        NOT NULL DEFAULT 'USD',
    auto_renew         boolean        NOT NULL DEFAULT false,
    created_at         timestamptz    NOT NULL DEFAULT now(),
    updated_at         timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT pk_contract_term_commitments PRIMARY KEY (contract_id),
    CONSTRAINT fk_contract_term_commitments_contract FOREIGN KEY (contract_id) REFERENCES enrollment.contracts (id)
);

COMMENT ON TABLE enrollment.contract_term_commitments IS 'contract_type이 term_commitment인 계약의 상세. contracts와 1:1이며 PK가 곧 FK다.';

CREATE TABLE enrollment.contract_token_discounts (
    id             uuid          NOT NULL,
    contract_id    uuid          NOT NULL,
    model_pattern  varchar(100),
    token_type     varchar(20)   NOT NULL DEFAULT 'all',
    discount_rate  numeric(6, 5) NOT NULL,
    effective_from date          NOT NULL,
    effective_to   date,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_contract_token_discounts PRIMARY KEY (id),
    CONSTRAINT fk_contract_token_discounts_contract FOREIGN KEY (contract_id) REFERENCES enrollment.contracts (id),
    CONSTRAINT uq_contract_token_discounts_contract_model_token_from
        UNIQUE (contract_id, model_pattern, token_type, effective_from),
    CONSTRAINT ck_contract_token_discounts_token_type
        CHECK (token_type IN ('input', 'output', 'cache_read', 'cache_create', 'all'))
);

CREATE INDEX ix_contract_token_discounts_contract ON enrollment.contract_token_discounts (contract_id);
CREATE INDEX ix_contract_token_discounts_contract_effective_from ON enrollment.contract_token_discounts (contract_id, effective_from);

COMMENT ON TABLE enrollment.contract_token_discounts IS 'contract_type이 token_discount인 계약의 상세이자 환산용 메타(비교) 테이블. discount_rate는 정가에 곱하는 배율이다.';

CREATE TABLE enrollment.contract_memberships (
    id          uuid        NOT NULL,
    contract_id uuid        NOT NULL,
    member_id   uuid        NOT NULL,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    released_at timestamptz,
    CONSTRAINT pk_contract_memberships PRIMARY KEY (id),
    CONSTRAINT fk_contract_memberships_contract FOREIGN KEY (contract_id) REFERENCES enrollment.contracts (id),
    CONSTRAINT fk_contract_memberships_member FOREIGN KEY (member_id) REFERENCES enrollment.members (id)
);

CREATE INDEX ix_contract_memberships_contract ON enrollment.contract_memberships (contract_id);
CREATE INDEX ix_contract_memberships_member ON enrollment.contract_memberships (member_id);

COMMENT ON TABLE enrollment.contract_memberships IS 'member가 어떤 계약의 적용을 받는지에 대한 배정 관계와 배정 기간. released_at이 NULL인 row가 현재 유효한 배정이다.';
