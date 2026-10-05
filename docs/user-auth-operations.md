# 사용자 인증 운영

## 범위와 책임

사람 인증은 **표준 OIDC Authorization Code + PKCE** 제공자가 담당한다(허브 ADR 0008).
Pulsemetry는 사전 등록된 `invited`·`active` 회원만 허용한다. 최초 로그인은 회사 IdP의 검증된 이메일로 sub를 연결하고, 이후에는 `(tenant_id, issuer, sub)`로 식별한다(허브 ADR 0010).
공개 가입·자동 회원 생성은 없으며 회원 역할/조직은 DB가 권위 원천이다.
비밀번호는 IdP에만 존재한다.
API 필드·응답은 [사용자 인증 API](api/auth.md)를 따른다.

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
4. DB를 백업하고 구/신 앱 혼합 운영을 중지한 뒤 Flyway를 적용한다. V29은 회원 issuer를 회사로 옮기고 sub를 유지한다.
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
    rate-limit:          # 생략하면 둘 다 60초 30회
      entry:
        requests: 30
        window: 60s
      session:
        requests: 30
        window: 60s
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

요청 제한은 둘로 나뉘며 상태는 PostgreSQL `enrollment.auth_attempts`에 공유한다(ADR 0052).

| 범주 | 요청 | 단위 | 설정 키(기본) |
| --- | --- | --- | --- |
| 진입 | 회사 탐색·OIDC 인가·콜백·코드 교환, 아래 자격 보유가 아닌 `/v1/auth/*` 전부 | remoteAddr | `rate-limit.entry.requests`·`window`(30회·60초) |
| 자격 보유 | RT 갱신·로그아웃·`GET /v1/manifest`·`GET /v1/auth/me` | 세션 | `rate-limit.session.requests`·`window`(30회·60초) |

토큰이 없거나 형식이 틀렸거나 모르는 토큰은 진입 한도로 센다. 자격 보유 요청의 429는 RT를 소비하기 전에 나므로
클라이언트는 `Retry-After` 뒤에 같은 RT로 다시 요청한다. 한도는 고정 창이며 요청 수는 1 이상, 창은 1초 이상이어야 기동한다.

조정 기준:
- 진입 한도는 회사 탐색·인가 코드 추측을 막는 값이다. 올리기 전에 서버가 프록시 주소만 보는지 확인한다 —
  그렇다면 배포 전체가 한 버킷이다. 정상 로그인이 창마다 한도를 넘는 것이 확인될 때만 올리고.
- 세션 한도는 클라이언트 하나(브라우저 탭·CLI)의 요청량이다. 대시보드는 AT 수명(5분)마다 갱신하고 화면마다 현재 사용자를 읽는다.
  정상 클라이언트가 이 한도를 넘으면 한도보다 먼저 클라이언트의 중복 요청(동시 갱신·반복 조회)을 줄인다.
- 낮추면 같은 창 안의 정상 요청도 429가 된다. 실서버 E2E처럼 한 주소에서 로그인을 반복하는 작업은 진입 한도를 존중해 간격을 둔다.

인증 실패 401, 제한 429, 인프라/서명 오류 503을 구분하고 429/503의 Retry-After를 따른다.
로그·APM의 request body와 Authorization 수집은 인증 경로에서 비활성화한다.

```sql
DELETE FROM enrollment.user_sessions WHERE expires_at < now() - interval '1 day';
DELETE FROM enrollment.user_authorization_codes WHERE expires_at < now() - interval '1 day';
DELETE FROM enrollment.auth_attempts
 WHERE window_started_at < now() - interval '1 day'
   AND (locked_until IS NULL OR locked_until < now() - interval '1 day');
```

## 롤백

V28의 비밀번호 삭제는 비가역이다. **구 비밀번호 버전 앱만 다시 배포하면 안 된다.**
장애 시 신규 OIDC 시작을 닫고 SSO 버전으로 전진 수정하는 것이 기본이다.
정말 구 버전 복귀가 필요하면 별도 승인 아래 백업 복구·신규 쓰기 영향·세션 폐기까지 계획한다.
이 문서는 운영 전환 완료를 뜻하지 않는다.

## 근거

- [Spring Security OIDC 설정](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)

## manifest 재동기화

`GET /v1/manifest`는 사용자 RT를 회전한다. CDN/API Gateway에서 이 경로를 캐시하거나 재시도하지 않도록 한다.
503/409로 트랜잭션이 실패하면 RT 소비는 롤백된다. 연결 종료 등 응답 유실은 성공 여부를 알 수 없으므로 재로그인한다.
로컬 파일·키링의 적용 완료를 서버 성공과 동일시하지 않는다. OTLP `ptt_` 갱신과 사용자 RT 회전은 별개다.

### 빌드에 필요한 계약

enrollment-api 빌드는 telemetryctl의 `contracts/enrollment-manifest.schema.json`을 jar에 넣는다(ADR 0019).
`PULSEMETRY_CONTRACTS_DIR`은 원본 `contracts` 디렉터리의 절대 경로이고, 생략하면 형제 `../telemetryctl/contracts`를 읽는다.
파일이 없으면 `:apps:enrollment-api:processResources`가 실패한다.
런타임에는 jar에 포함된 스키마를 쓰므로 telemetryctl 체크아웃이나 네트워크가 필요 없다.

이미지 빌드는 `enrollment-api` target에만 named context를 전달한다.
나머지 target은 그 빌드 스테이지를 거치지 않으므로 컨텍스트가 필요 없다.

```sh
docker buildx build --target enrollment-api \
  --build-context telemetry-contracts=../telemetryctl/contracts -t <repo>:<tag> --load .
```

CI의 PR 검증과 develop 배포는 telemetryctl 기본 브랜치를 체크아웃해 그 `contracts`를 쓴다(ref를 고정하지 않는다).
계약 테스트는 telemetryctl 기본 브랜치에 있는 `enrollment-envelope`·`enrollment-manifest` 두 스키마만 원본으로 읽는다. 사용자 토큰 봉투·AT 클레임·재조회 봉투의 오라클은
[인증 API](api/auth.md)와 [manifest 재조회](api/enrollment.md)의 표다 — 원격 기본 브랜치에 없는 스키마를 읽게 하면 CI가 죽는다.
