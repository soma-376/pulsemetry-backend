-- PROJ-107: 설치와 가입의 초대 소비는 독립이다. 기존 used_at 의미를 바꾸지 않는다.
ALTER TABLE enrollment.invitations ADD COLUMN signup_used_at timestamptz;
CREATE TABLE enrollment.user_sessions (
    id uuid PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES enrollment.members(id),
    manifest_revision integer NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX ix_user_sessions_member ON enrollment.user_sessions(member_id);
CREATE TABLE enrollment.user_refresh_tokens (
    token_hash varchar(64) PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES enrollment.user_sessions(id) ON DELETE CASCADE,
    issued_at timestamptz NOT NULL,
    used_at timestamptz
);
CREATE UNIQUE INDEX ux_user_refresh_active ON enrollment.user_refresh_tokens(session_id) WHERE used_at IS NULL;
CREATE TABLE enrollment.user_authorization_codes (
    code_hash varchar(64) PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES enrollment.members(id),
    redirect_uri varchar(256) NOT NULL,
    code_challenge varchar(43) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz
);
CREATE TABLE enrollment.auth_attempts (
    subject_hash varchar(64) PRIMARY KEY,
    window_started_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    locked_until timestamptz
);
COMMENT ON COLUMN enrollment.invitations.signup_used_at IS '가입 전용 소비 시각. used_at은 설치 전용이다.';
COMMENT ON TABLE enrollment.auth_attempts IS '해시한 계정/IP별 로그인 제한. 원문 이메일/IP를 저장하지 않는다.';
