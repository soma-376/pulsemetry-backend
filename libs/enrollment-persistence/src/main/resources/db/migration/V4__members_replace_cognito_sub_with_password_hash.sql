-- 인증 계층이 Spring Security 로 확정되면서(ADR 0007) 쓸 자리가 없어진 Cognito 흔적을 걷고,
-- 그 자리에 우리가 직접 보관할 비밀번호 자격증명 자리를 만든다 (ADR 0007 Follow-up ④).
--
-- V1 이 아니라 V4 인 이유: V1 은 공유 RDS 부트스트랩 DDL(ai-telemetry-pipeline/sql/rds/schema.sql)
-- 과의 물리 일치 계약이라 고칠 수 없다. 스키마의 진실원은 Flyway 이므로(ADR 0004·0009) 변경은
-- 신규 마이그레이션으로만 한다. dbml(rdb-schema/dbdiagram.dbml)은 이 파일을 뒤따라 맞춘다.
--
-- ⚠️ DROP COLUMN 은 비가역이다. 이미 배포된 DB 에서 한 번 돌면 값은 백업에서만 복구된다.
-- 기존 행이 그대로 남는지는 MembersCognitoRemovalMigrationTest 가 V3 → V4 를 실제로 넘겨 확인한다.

-- 유니크 제약(uq_members_tenant_cognito_user_sub)과 인덱스(ix_members_cognito_user_sub)를
-- 이름으로 지우지 않는다. PostgreSQL 이 컬럼에 딸린 제약·인덱스를 함께 지우므로, 파이프라인
-- DDL 로 baseline 된 DB 의 자동 생성 이름(members_tenant_id_cognito_user_sub_key 등)도 같은
-- 문장 하나로 정리된다. 이름을 나열하면 그 경로에서만 흔적이 남는다.
ALTER TABLE enrollment.members DROP COLUMN IF EXISTS cognito_user_sub;

-- 사람이 고른 비밀번호는 Spring Security 의 PasswordEncoder(bcrypt·Argon2 같은 salted KDF)로
-- 해싱한다(ADR 0007 Decision). ADR 0003 의 결정론적 SHA-256 은 256비트 난수 비밀 전용이라
-- 여기 쓰지 않는다 — 그래서 이 컬럼은 조회 키가 아니고 인덱스도 두지 않는다.
--
-- NULL 을 허용한다. 초대만 받고 아직 가입하지 않은 구성원(status='invited')과 기존 행이
-- 비밀번호 없이 존재해야 하기 때문이다. 로그인 경로가 서는 시점에 NULL 을 "가입 미완료" 로 읽는다.
-- 255 는 bcrypt(60자)·Argon2(약 100자) 인코딩 결과를 모두 담는다.
ALTER TABLE enrollment.members ADD COLUMN IF NOT EXISTS password_hash varchar(255);

COMMENT ON COLUMN enrollment.members.password_hash IS
	'Spring Security PasswordEncoder 로 해싱한 비밀번호. NULL 은 가입 미완료 (ADR 0007)';

-- 테이블 주석이 아직 Cognito 전제를 서술한다. 결정이 바뀌었으므로 같이 고친다.
COMMENT ON TABLE enrollment.members IS
	'Pulsemetry 조직 구성원. 웹 사용자는 우리가 발급하는 세션 토큰으로 인증하고(ADR 0007), 일반 사용자는 installation을 통해 서비스와 연결된다.';
