# 사용자 인증 운영

## 범위와 책임

사람 인증은 **표준 OIDC Authorization Code + PKCE** 제공자가 담당한다(허브 ADR 0008).
Pulsemetry는 사전 등록된 `invited`·`active` 회원만 허용한다. 최초 로그인은 회사 IdP의 검증된 이메일로 sub를 연결하고, 이후에는 `(tenant_id, issuer, sub)`로 식별한다(허브 ADR 0010).
공개 가입·자동 회원 생성은 없으며 회원 역할/조직은 DB가 권위 원천이다.
비밀번호는 IdP에만 존재한다. 구 `/signup`·`/login`·`/cli/authorize`는 410이다.
API 필드·응답은 [Enrollment 명세 §11](enrollment-server-spec.md#11-사용자-인증)을 따른다.

Enrollment(8080)는 OIDC 로그인·자체 토큰 발급·관리 명령, Dashboard(8081)는 자체 JWT·현재 세션 검증을 담당한다.
IdP access/ID token을 서비스 API에 보내지 않는다. OIDC 임시 쿠키 역시 업무 API 인증에 쓰지 않는다.
프론트는 회사 이메일 탐색 → 회사 선택 → OIDC 왕복 → 단회 코드 교환을 사용하며 구 `/api/dev/seed-login`은 삭제했다.
telemetryctl 로그인 배선은 별도 전환해야 한다.
개발·데모 구성은 [Cognito 안내](cognito-dev.md)를 따른다. 기본 테스트는 외부 계정 없이 모의 OIDC로 실행한다.
Cognito는 운영 회원 원장이 아니며 초대/회원 API에서 Cognito 계정을 만들지 않는다(허브 ADR 0010).
운영은 고객사의 OIDC 제공자에 연결한다. 인증 서버 자체를 이 저장소에서 배포하지 않는다.

`tenants.sso_enabled`는 기본 false이며 비활성 회사는 조직 조회·authorize에서 제외한다.
회사별 `oidc_issuer`, `oidc_client_id`, `oidc_client_secret_ref`를 DB에서 읽는다.
`oidc_require_verified_email`은 기본 false이고 개발 Cognito는 true다.
true이면 검증된 ID Token의 비어 있지 않은 email과 boolean email_verified=true가 필요하다.
미제공/false는 invalid_credentials로 종료한다. 이 옵션이 false여도 최초 sub 연결에는 ID Token의 검증된 이메일이 반드시 필요하다. IdP 클레임으로 역할을 변경하지 않는다.

## 배포 전환 순서 — 운영 DB에 자동 실행하지 않는다

1. 고객사 IdP 관리자와 issuer·클라이언트 등록·계정 식별자·접근 정책을 확인한다.
2. confidential OIDC client에 Authorization Code와 S256 PKCE를 설정한다.
   callback을 `https://auth.example.com/v1/auth/oidc/callback/oidc`처럼 정확히 등록한다.
   사용하지 않는 가입·password grant·implicit flow는 비활성화한다.
3. 회사에 접근을 허용할 회원의 이메일·역할을 사전 등록한다. 신규 회원 sub는 NULL로 두며 미리 수집하지 않는다.
   최초 연결은 회사가 검증한 이메일의 현재 소유자를 이 회원으로 인정한다. 이미 연결된 sub는 자동 교체하지 않는다.
4. DB를 백업하고 구/신 앱 혼합 운영을 중지한 뒤 Flyway를 적용한다. V13은 회원 issuer를 회사로 옮기고 sub를 유지한다.
   한 회사에 여러 issuer가 있으면 중단한다. client ID·비밀 참조는 추측하지 않으며 회사 SSO는 비활성으로 시작한다.
5. 회사에 검증한 OIDC 설정을 넣고 SSO를 활성화한다. 비밀 원문은 서버 비밀 맵에 주입한다.
6. enrollment·dashboard를 새 코드로 실행하고 로그인·갱신·로그아웃을 확인한다.
   토큰/state/code/쿠키/secret과 login_hint는 프록시 access log·APM·본문 캡처에서 마스킹한다.

신규 회사 설정 예시(실제 UUID·검증된 issuer/client로 교체, 영향 행 수 확인):

```sql
BEGIN;
UPDATE enrollment.tenants
SET oidc_issuer='https://sso.example.com', oidc_client_id='pulsemetry-backend',
    oidc_client_secret_ref='config:company-a', sso_enabled=true
WHERE id='<tenant UUID>' AND NOT sso_enabled AND oidc_issuer IS NULL;
COMMIT;
```

로그인 시작 시 서버는 입력 이메일의 회원 UUID를 왕복 세션에 고정한다. callback에서는 회사·회원 상태를 다시 확인하고, 최초 연결에만 ID Token의 검증된 이메일이 시작 이메일 및 현재 DB 이메일과 일치하는지 검사한다. sub 저장과 invited→active 전환·인증 코드 발급은 한 트랜잭션이다. 설치 초대 코드는 소비하지 않는다. 힌트 없는 로그인은 기존 연결에만 허용한다.

같은 회사의 sub 중복과 빈 문자열은 DB 제약으로 거부한다. 회원 역할·상태는 이 SQL로 변경하지 않는다.
기존 issuer/client를 교체할 때에는 모든 연결 회원의 sub를 새 발급자 기준으로 재검증하고 해당 회사의 서비스 세션과 단회 코드를 폐기한다.
설정 변경 중 SSO를 끄고 트랜잭션으로 변경한 뒤 다시 활성화한다. 회사 설정이 달라진 진행 중 OIDC 왕복은 거부한다.


## 서버 설정

```yaml
pulsemetry:
  user-auth:
    enabled: true
    issuer: https://auth.example.com
    audience: pulsemetry
    active-kid: key-2026-09
    private-key-file: /run/secrets/user-auth-private.pem
    public-key-files:
      key-2026-09: /run/secrets/user-auth-public.pem
    allowed-origins:
      - https://dashboard.example.com
    allowed-redirect-uris:
      - https://dashboard.example.com/auth/callback
  oidc:
    enabled: true
    allow-insecure-localhost: false
    callback-base-url: https://auth.example.com
    failure-redirect-uri: https://dashboard.example.com/auth/callback
    callback-registration-id: oidc
    client-secrets:
      company-a: ${PULSEMETRY_OIDC_CLIENT_SECRET}
```

운영 비밀은 secret manager/읽기 전용 파일 등으로 주입하고 코드·이미지에 넣지 않는다.
OIDC client secret은 enrollment에만 주며 dashboard에는 자체 JWT 공개키·issuer·audience만 준다.
두 종류의 issuer(외부 IdP / Pulsemetry 자체 JWT)를 혼동하지 않는다.
회사당 인증 서버 하나를 tenants 컬럼으로 관리한다. `config:company-a`는 위 비밀 맵의 키이며 Secrets Manager ARN을 직접 조회하지 않는다. callback은 고정 설정값이며 요청 Host/forwarded에서 만들지 않는다.
임시 세션 유실 시 고정 failure-redirect-uri로 login_expired만 반환한다. 이 주소도 allowed-redirect-uris의 정확한 항목이어야 한다.
인증 성공·실패 핸들러는 저장된 안전한 흐름이 있으면 원래 프론트 주소로 복귀한다. 시작 요청 오류와 요청 가드의 제한·설정 변경 거부는 JSON 오류일 수 있다.
HTTPS가 기본이고 명시한 로컬 예외에서도 HTTP loopback만 허용한다.
서비스 JWT 키와 고정 callback·failure URL 등 필수 서버 설정은 기동 시 검증한다. 회사별 client secret과 discovery는 해당 회사 로그인 요청에서 확인한다. Secret 누락·discovery 장애는 503 `auth_unavailable`이며, discovery 결과는 회사 설정과 Secret이 같을 때 캐시를 재사용한다.

관리 API를 쓸 때는 별도로 `pulsemetry.management.enabled`와 응답 암호화 키도 준비한다.
RSA 2048비트 이상, 개인키 PKCS8·공개키 X509 PEM을 사용한다. 운영 자동 키 생성은 없다.

## 쿠키와 세션

OIDC state/nonce/upstream PKCE 및 최종 복귀 요청을 JDBC 임시 세션에 보관한다.
기본 10분, HttpOnly·SameSite=Lax·HTTPS Secure, Path=/v1/auth/oidc이다.
성공/실패 시 폐기하며 Spring Security 로그인 컨텍스트/IdP 토큰을 장기 저장하지 않는다.
여러 인스턴스도 공유 DB로 왕복할 수 있으며 같은 배포 버전/클라이언트 설정이 필요하다.
Spring Session이 만료 행을 정리한다. 로그인 흐름을 마친 뒤 API 쿠키 세션은 생기지 않는다.

Pulsemetry AT는 300초(+허용 시계 오차 30초), 회전 RT/DB 세션은 절대 30일이다.
refresh는 만료를 늘리지 않으며 소비한 RT 재사용은 새 RT까지 폐기한다. 클라이언트는 refresh를 직렬화한다.
회원/조직 비활성·역할 변경은 다음 AT 검증/refresh부터 확인한다.
계약·manifest·온보딩은 로그인 조건이 아니며 manifest 미설정은 revision=0이다(ADR 0033).

**제한:** 서비스 logout은 Pulsemetry 세션만 종료한다. IdP SSO logout/back-channel logout,
IdP 사용자 비활성화 동기화는 이번 구현에 없다. 긴급 차단 시 **Pulsemetry 회원도 비활성화하거나 세션을 폐기**한다.
IdP만 비활성화하면 이미 발급한 Pulsemetry 세션이 즉시 종료되지는 않는다.

IP별 30회/분 제한을 공유 PostgreSQL로 적용한다. 비밀번호 실패 잠금/MFA 정책은 IdP에서 구성한다.
remoteAddr 기반이므로 ALB/프록시를 붙일 때 신뢰할 프록시와 실제 IP 전달 정책을 별도 검토한다.
JSON API는 인증 실패401·미등록403·제한429·IdP/DB 장애503을 구분한다. 429/503은 Retry-After를 따른다.
OIDC callback의 정상적인 실패 안내는302로 신뢰된 UI에 복귀하므로 메트릭은 HTTP 상태만으로 인증 성공을 판단하지 않는다.
이메일 회사 탐색은 공개 API로 소속 정보 노출 가능성이 있다. 최소 필드·no-store·IP 제한을 적용하되 운영에서 탐색 남용을 감시한다.

## 키 교체와 보존

1. 새 공개키를 모든 검증 인스턴스에 추가하고 구 키도 유지한다.
2. 공개키 전파 후 enrollment의 active-kid와 개인키를 함께 교체한다.
3. 마지막 구 키 발급으로부터 330초 이상 지난 뒤 구 공개키를 제거한다.
4. 유출 시 해당 공개키 제거와 영향 세션 폐기를 함께 수행한다.

만료된 서비스 세션/code/제한 행은 운영 유지보수에서 삭제할 수 있다.
RT 이력은 세션 만료 전 지우지 않는다.

```sql
DELETE FROM enrollment.user_sessions WHERE expires_at < now() - interval '1 day';
DELETE FROM enrollment.user_authorization_codes WHERE expires_at < now() - interval '1 day';
DELETE FROM enrollment.auth_attempts
 WHERE window_started_at < now() - interval '1 day'
   AND (locked_until IS NULL OR locked_until < now() - interval '1 day');
```

## 롤백

V12의 비밀번호 삭제는 비가역이다. **구 비밀번호 버전 앱만 다시 배포하면 안 된다.**
장애 시 신규 OIDC 시작을 닫고 SSO 버전으로 전진 수정하는 것이 기본이다.
정말 구 버전 복귀가 필요하면 별도 승인 아래 백업 복구·신규 쓰기 영향·세션 폐기까지 계획한다.
이 문서는 운영 전환 완료를 뜻하지 않는다.

## 근거

- [Spring Security OIDC 설정](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)
