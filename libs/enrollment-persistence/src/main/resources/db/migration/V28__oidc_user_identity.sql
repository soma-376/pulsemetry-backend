-- PROJ-186: 사람의 비밀번호는 IdP가 소유한다. 기존 회원/설치 데이터는 보존한다.
ALTER TABLE enrollment.members
    ADD COLUMN oidc_issuer varchar(512),
    ADD COLUMN oidc_subject varchar(255),
    ADD CONSTRAINT ck_members_oidc_pair CHECK (
        (oidc_issuer IS NULL AND oidc_subject IS NULL) OR
        (oidc_issuer IS NOT NULL AND oidc_subject IS NOT NULL AND
         length(btrim(oidc_issuer)) > 0 AND length(btrim(oidc_subject)) > 0)),
    ADD CONSTRAINT uq_members_tenant_oidc UNIQUE (tenant_id, oidc_issuer, oidc_subject),
    DROP COLUMN password_hash;

COMMENT ON COLUMN enrollment.members.oidc_issuer IS '사전 등록된 OIDC issuer. URL 문자열을 정확히 비교한다.';
COMMENT ON COLUMN enrollment.members.oidc_subject IS '해당 OIDC client에 발급되는 sub. 이메일로 추정하지 않는다.';

-- 비밀번호로 발급한 자격은 SSO 재인증을 요구한다. 설치 토큰은 폐기하지 않는다.
UPDATE enrollment.user_sessions SET revoked_at = now() WHERE revoked_at IS NULL;
UPDATE enrollment.user_authorization_codes SET used_at = now() WHERE used_at IS NULL;

-- Spring Session JDBC: OIDC 왕복에만 사용하는 10분 임시 상태. 업무 API 인증과 분리한다.
CREATE TABLE enrollment.oidc_login_sessions (
    primary_id char(36) NOT NULL PRIMARY KEY,
    session_id char(36) NOT NULL UNIQUE,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(100)
);
CREATE INDEX ix_oidc_login_sessions_expiry ON enrollment.oidc_login_sessions(expiry_time);
CREATE INDEX ix_oidc_login_sessions_principal ON enrollment.oidc_login_sessions(principal_name);
CREATE TABLE enrollment.oidc_login_sessions_attributes (
    session_primary_id char(36) NOT NULL REFERENCES enrollment.oidc_login_sessions(primary_id) ON DELETE CASCADE,
    attribute_name varchar(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);
