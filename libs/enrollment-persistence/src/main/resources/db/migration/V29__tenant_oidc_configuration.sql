-- 회사당 OIDC 연결 하나. 기존 회원의 issuer와 sub를 보존한다(허브 ADR 0013).
DO $$ BEGIN
    IF EXISTS (SELECT tenant_id FROM enrollment.members WHERE oidc_issuer IS NOT NULL
               GROUP BY tenant_id HAVING count(DISTINCT oidc_issuer) > 1) THEN
        RAISE EXCEPTION '한 회사에 여러 OIDC issuer가 있습니다. 명시적으로 정리한 뒤 다시 이관하세요.';
    END IF;
END $$;

ALTER TABLE enrollment.tenants
    ADD COLUMN oidc_issuer varchar(512),
    ADD COLUMN oidc_client_id varchar(255),
    ADD COLUMN oidc_client_secret_ref varchar(255),
    ADD COLUMN sso_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN oidc_require_verified_email boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_tenants_oidc_values CHECK (
        (oidc_issuer IS NULL OR length(btrim(oidc_issuer)) > 0) AND
        (oidc_client_id IS NULL OR length(btrim(oidc_client_id)) > 0) AND
        (oidc_client_secret_ref IS NULL OR oidc_client_secret_ref ~ '^config:[A-Za-z0-9_-]+$')),
    ADD CONSTRAINT ck_tenants_sso_configuration CHECK (
        NOT sso_enabled OR (oidc_issuer IS NOT NULL AND oidc_client_id IS NOT NULL AND oidc_client_secret_ref IS NOT NULL));

UPDATE enrollment.tenants t SET oidc_issuer=source.issuer
FROM (SELECT tenant_id,min(oidc_issuer) AS issuer FROM enrollment.members
      WHERE oidc_issuer IS NOT NULL GROUP BY tenant_id) source WHERE t.id=source.tenant_id;

ALTER TABLE enrollment.members
    DROP CONSTRAINT ck_members_oidc_pair,
    DROP CONSTRAINT uq_members_tenant_oidc,
    DROP COLUMN oidc_issuer,
    ADD CONSTRAINT ck_members_oidc_subject CHECK (oidc_subject IS NULL OR length(btrim(oidc_subject)) > 0),
    ADD CONSTRAINT uq_members_tenant_oidc_subject UNIQUE (tenant_id,oidc_subject);

COMMENT ON COLUMN enrollment.tenants.oidc_issuer IS '회사 인증 서버의 issuer. URL을 정확히 비교한다.';
COMMENT ON COLUMN enrollment.tenants.oidc_client_secret_ref IS '백엔드 비밀 맵의 config:<키> 참조. 비밀 원문을 저장하지 않는다.';
COMMENT ON COLUMN enrollment.tenants.sso_enabled IS '회사별 로그인 허용. 기존 서비스 세션의 폐기는 별도 관리 작업이다.';
