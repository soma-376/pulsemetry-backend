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

JWT 키 원본·최초 생성·버전 고정과 파일 공급의 공통 결정은 [허브 ADR 0022](../../docs/adr/0022-user-jwt-key-provisioning.md), dev EKS 전용 실행·배포 경계는 [허브 ADR 0023](../../docs/adr/0023-dev-backend-eks-only.md)을 따른다. [infra ADR 0030](../../infra/docs/adr/0030-dev-user-jwt-keys-from-secrets-manager.md)의 EKS 전달 방식과 [EKS 수동 배포 런북](../../infra/docs/runbooks/dev-eks-manual-deployment.md)을 함께 읽는다. infra에는 Pod Identity·ASCP/CSI 파일 공급 코드가 있다. 이 사실은 Secret 최초 등록·선택 버전·실제 mount 권한·AWS 배포·인증 기능 검증의 완료를 뜻하지 않는다.

`tenants.sso_enabled`는 기본 false이며 비활성 회사는 조직 조회·authorize에서 제외한다.
회사별 `oidc_issuer`, `oidc_client_id`, `oidc_client_secret_ref`를 DB에서 읽는다.
`oidc_require_verified_email`은 기본 false이고 개발 Cognito는 true다.
true이면 검증된 ID Token의 비어 있지 않은 email과 boolean email_verified=true가 필요하다.
미제공/false는 invalid_credentials로 종료한다. 이 옵션이 false여도 최초 sub 연결에는 ID Token의 검증된 이메일이 반드시 필요하다. IdP 클레임으로 역할을 변경하지 않는다.

## 배포 전환 순서 — 운영 DB에 자동 실행하지 않는다

1. 고객사 IdP 관리자와 issuer·클라이언트 등록·계정 식별자·접근 정책을 확인한다.
2. confidential OIDC client에 Authorization Code와 S256 PKCE를 설정한다.
   callback을 `https://auth.example.com/api/v1/auth/oidc/callback/oidc`처럼 정확히 등록한다.
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

서비스 JWT 키는 환경별로 고정한 Secrets Manager ARN 두 개에 별도로 보관한다. 개인키 Secret은 RSA 2048비트 이상 **PKCS8 PEM**, 공개키 Secret은 **`kid → X.509 PEM` JSON 키링**이다. AWS 관리형 `aws/secretsmanager`로 암호화하며 dev EKS는 기존 두 원본과 선택한 `VersionId`를 유지한다. ECS 제거를 이유로 키를 다시 만들지 않는다. 공개키도 이미지에 포함하지 않고 런타임 파일로 공급한다.

앱은 Secrets Manager를 조회하지 않는다. dev EKS의 Pod Identity·ASCP/CSI 공급자가 선택 버전의 공개키 JSON을 kid별 PEM 파일로 풀고 앱의 `public-key-files`에는 **kid → 파일 경로**를 전달한다. enrollment에 개인키·공개키를, dashboard에 공개키만 공급하고 telemetry-ingest에는 JWT Secret 권한을 추가하지 않는다. PEM 원문을 일반 환경변수·Spring 설정·문서·로그·output에 넣지 않는다.
OIDC client secret은 enrollment에만 주며 dashboard에는 자체 JWT 공개키·issuer·audience만 준다.
두 종류의 issuer(외부 IdP / Pulsemetry 자체 JWT)를 혼동하지 않는다.

### 최초 공급과 배포 기록

1. 명시적인 초기화 작업에서 최초 RSA 키쌍과 `kid`를 만든다. 앱 시작이나 일반 재배포에서는 생성하지 않는다.
2. 같은 키쌍의 개인키·공개키 Secret을 등록하고 형식·RSA 크기·실제 서명/검증·활성 kid 대응을 검증한다. 두 Secret 등록은 한 트랜잭션이 아니므로, 하나만 성공했으면 같은 키쌍으로 나머지를 복구한다. 부분 실패를 새 키 생성으로 덮지 않는다.
3. 두 등록과 검증이 끝난 뒤 개인키·공개키의 정확한 `VersionId`, `active-kid`, 공개키 파일 맵을 하나의 배포 기록으로 확정한다. 앱 설정에는 경로·kid만 전달하며 `AWSCURRENT` 변경을 자동 반영하지 않는다.
4. 선택한 두 버전을 공급하고 앱 실행 UID로 파일 읽기를 확인한 뒤 새 인스턴스를 시작한다. 기동 성공과 실제 발급·검증 성공을 각각 확인한다. 활성·롤백용 버전에는 staging label을 유지하고 Secret 보존·삭제 책임은 infra 런북을 따른다.

이 순서는 공급자 구현의 수락 조건이다. 현재 backend의 파일 로더가 Secret 등록·버전 기록·파일 접근 권한 설정을 수행한다는 뜻은 아니다. 앱은 기동 때 파일을 한 번 읽으므로 파일만 바꾸거나 staging label만 이동해도 실행 중인 키가 바뀌지 않는다.

### 로컬 Compose 키와 배포 키

로컬 `dev-seed`의 `prepareAuthKeys()`는 `build/dev-auth/private.pem`·`public.pem`이 모두 없을 때만 RSA 키쌍을 만들고 기존 파일을 보존한다. 둘 중 하나만 있으면 자동 교체하지 않고 실패한다. 앱의 local 프로필은 이 경로와 `kid=local`을 사용한다. 로컬 준비 도구는 개발 fixture이며 Secrets Manager 등록·운영 키 생성·버전 공급·운영 파일 권한의 증거가 아니다. 배포 환경에는 local 프로필을 켜지 않는다.

### Dashboard 검증 설정

Dashboard의 활성화는 별도다. `PULSEMETRY_USER_AUTH_ENABLED=true`와 자체 JWT의 issuer·audience·공개키 파일 맵·허용 origin을 준비한다. 발급자가 쓴 issuer와 audience에 정확히 맞춘다. dashboard에는 private-key-file·active-kid·OIDC client secret을 주입하지 않는다.

```yaml
pulsemetry:
  user-auth:
    enabled: true
    issuer: https://auth.example.com
    audience: pulsemetry
    public-key-files:
      key-2026-09: /run/secrets/user-auth-public.pem
    allowed-origins:
      - https://dashboard.example.com
```

위 맵의 `key-2026-09`가 JWT header의 실제 `kid`다. dashboard `application.yaml`의 `PULSEMETRY_USER_AUTH_PUBLIC_KEY_FILE`은 `public-key-files.local` 경로로만 매핑된다. 파일 경로를 바꿔도 맵의 키는 `local`로 남으며 enrollment의 active-kid가 다른 경우 그 한 변수만으로 인증할 수 없다. 배포 설정 YAML 또는 아래처럼 `SPRING_APPLICATION_JSON`의 맵으로 실제 `kid`를 명시한다. 공개키는 런타임에 읽을 수 있는 X509 PEM 파일로 준비하고, 발급자의 개인키는 enrollment에만 둔다.

```json
{"pulsemetry":{"user-auth":{"public-key-files":{"key-2026-09":"/run/secrets/user-auth-public.pem"}}}}
```

이 JSON은 키 맵만 추가하므로 enabled·issuer·audience·allowed-origins는 위 YAML 또는 대응 환경변수로 함께 전달한다. `kid`는 대소문자까지 정확히 일치시킨다. 읽을 수 없는 파일·빈 파일·잘못된 PEM·빈 유효 키 맵·RSA 2048비트 미만은 활성 시 기동 실패 조건이다. 빈 경로 항목은 현재 코드가 먼저 제외하므로 다른 유효한 키가 남으면 그 항목만으로 기동이 실패하지 않는다. 공급자는 기대하는 kid의 경로 누락·빈 항목도 배포 전에 거부해야 한다. 원격 JWKS를 자동 조회하지 않으며 키 교체 후에는 모든 검증 인스턴스를 새 파일·맵으로 다시 기동한다.

`SecurityConfig.dashboardAuthenticator()`는 서명을 확인한 뒤 `UserAccessVerifier.verify()`가 현재 세션·회원·조직을 확인한다. dashboard의 source RDS 계정에 `enrollment.user_sessions`, `enrollment.members`, `enrollment.tenants` SELECT와 schema USAGE가 필요하다. 세션 폐기·만료·회원 또는 조직 비활성·현재 역할 불일치는 다음 조회부터 거부한다. DB 장애는 401 자격 오류로 바꾸지 않고 dashboard 503 `unavailable`로 응답한다. dashboard는 세션·RT·인증 제한 행을 쓰지 않는다.

`GET /api/v1/auth/me`는 `CurrentUserController.me()`가 있는 enrollment로 전달한다. dashboard는 업무 조회에서 AT를 검증할 뿐 로그인·갱신·로그아웃·현재 사용자 API를 옮겨 받지 않는다. 전체 경로·method와 OPTIONS 전달은 [허브 dashboard 계약](../../docs/contracts/dashboard-api.md)을 따른다. Dashboard CORS의 allowed-origins도 실제 브라우저 origin에 맞추며 issuer URL과 origin은 서로 다른 설정이다.

### Enrollment OIDC 설정 검증

회사당 인증 서버 하나를 tenants 컬럼으로 관리한다. `config:company-a`는 위 비밀 맵의 키이며 Secrets Manager ARN을 직접 조회하지 않는다. callback은 고정 설정값이며 요청 Host/forwarded에서 만들지 않는다.
임시 세션 유실 시 고정 failure-redirect-uri로 login_expired만 반환한다. 이 주소도 allowed-redirect-uris의 정확한 항목이어야 한다.
인증 성공·실패 핸들러는 저장된 안전한 흐름이 있으면 원래 프론트 주소로 복귀한다. 시작 요청 오류와 요청 가드의 제한·설정 변경 거부는 JSON 오류일 수 있다.
HTTPS가 기본이고 명시한 로컬 예외에서도 HTTP loopback만 허용한다.
서비스 JWT 키와 고정 callback·failure URL 등 필수 서버 설정은 기동 시 검증한다. 회사별 client secret과 discovery는 해당 회사 로그인 요청에서 확인한다. Secret 누락·discovery 장애는 503 `auth_unavailable`이며, discovery 결과는 회사 설정과 Secret이 같을 때 캐시를 재사용한다.

관리 API를 쓸 때는 별도로 `pulsemetry.management.enabled`와 응답 암호화 키도 준비한다.
RSA 2048비트 이상, 개인키 PKCS8·공개키 X509 PEM을 사용한다. 운영 자동 키 생성은 없다.

`UserAuthConfig.userJwt()`는 키 파일을 읽고 `UserJwt` 생성자는 issuer·audience·active-kid의 비어 있지 않음, 유효 공개키 맵, RSA 크기를 검사한다. enrollment에서는 `publicKeys[activeKid]`와 개인키의 RSA modulus 일치도 기동 시 검사한다. 이 검사는 Secret 버전의 대응이나 서명/검증 왕복을 대신하지 않는다. dashboard는 개인키가 없는 `UserJwt.verifier()`이므로 발급자의 키쌍과 비교할 수 없으며, 올바른 PEM을 엉뚱한 kid에 등록한 경우 실제 토큰 검증에서 드러날 수 있다.

### JWT 키 공급의 수락 조건

다음은 실제 키 공급·배포의 수락 조건이며 이번 문서 변경의 통과 결과가 아니다. CSI 공급 코드나 성공한 health 응답만으로 충족했다고 판단하지 않는다.

| 시나리오 | 기대 결과와 확인 지점 |
| --- | --- |
| 개인키/필수 공개키 누락·빈 파일·읽기 불가 | 공급자는 배포 기록의 모든 파일이 앱 UID로 읽히는지 확인하고 실패 시 앱 시작을 막는다. 활성 backend가 실제 읽는 파일의 누락·빈 내용·읽기 실패도 기동 실패다 |
| 잘못된 PEM·RSA 2048비트 미만 | 공급 전 검증에서 거부한다. `UserJwt.privateKey()`·`publicKey()`의 파싱과 생성자의 크기 검사도 기동을 실패시킨다 |
| 개인키/공개키 불일치·활성 kid 미등록 | 공급자는 선택 버전의 실제 서명/검증과 kid 대응을 확인하고 거부한다. enrollment 생성자는 활성 kid의 modulus 불일치·미등록을 거부하지만 dashboard는 발급자의 개인키와 비교하지 않는다 |
| dashboard 맵의 잘못된 kid·다른 공개키 | 공급자는 기대하는 전체 kid와 파일·버전 대응을 검사한다. 실제 enrollment 발급 AT가 모든 enrollment·dashboard 검증 인스턴스에서 검증되고, 모르는 kid·다른 키의 서명은 거부되는지 확인한다 |
| 두 Secret 중 하나만 등록 | 배포 참조를 확정하거나 앱을 시작하지 않는다. 같은 키쌍으로 누락 등록을 복구하고 두 VersionId를 검증한 뒤 진행한다 |
| 공개키 전파·signer 전환의 일부 인스턴스 실패 | 해당 단계에서 멈춘다. 모든 검증 인스턴스의 신·구 키 준비 전 signer를 바꾸지 않고, 마지막 구 키 발급 시각을 전체 signer 기준으로 기록한다 |
| 교체와 롤백 | 아래 순서로 신·구 토큰 검증·최소 330초 경계·구 키 제거·보존 버전 복구를 확인한다. 실제 실행 중인 모든 verifier/signer를 포함하며, 철거 전에 남아 있는 ECS 인스턴스도 제외하지 않는다 |

## 쿠키와 세션

OIDC state/nonce/upstream PKCE 및 최종 복귀 요청을 JDBC 임시 세션에 보관한다.
기본 10분, HttpOnly·SameSite=Lax·HTTPS Secure, Path=/api/v1/auth/oidc이다.
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

dev EKS의 모든 replica와 철거 전에 실제로 남아 있는 ECS verifier/signer를 대상으로 한다. 문서의 EKS 전용 계약만으로 구 인스턴스가 종료됐다고 판단하지 않는다.

1. 고정 ARN의 새 값 버전을 등록·검증하고 배포 기록을 준비한다. 새 공개키와 구 공개키를 함께 담은 공개키 VersionId를 모든 enrollment·dashboard 검증 인스턴스에 적용한다. 각 인스턴스의 신·구 키 검증 성공을 확인한다.
2. 공개키 전파가 끝난 뒤 모든 enrollment signer의 개인키 VersionId와 active-kid를 함께 전환한다. 일부 전환이면 구 공개키를 유지하며 중단 지점을 복구한다.
3. **모든 signer 중 마지막 구 키 발급 시각**으로부터 AT 300초+허용 시계 오차 30초인 330초 이상 기다린 뒤 구 공개키를 제거한 새 공개키 버전을 모든 검증 인스턴스에 적용한다. 시작 시각이나 첫 signer 전환 시각부터 세지 않는다.
4. 활성 및 롤백에 필요한 개인키·공개키 버전은 staging label로 보존하고 배포 기록을 유지한다. 자동 회전·자동 reload는 도입하지 않는다.

롤백은 보존한 VersionId의 개인키·active-kid·공개키 파일 맵을 한 묶음으로 복구한다. 구 공개키가 이미 제거됐으면 먼저 모든 검증 인스턴스에 신·구 공개키를 다시 적용·검증한 뒤 구 signer로 되돌린다. 롤백 직전 새 키로 발급한 AT도 있으므로 새 공개키는 마지막 새 키 발급 후 330초 이상 유지한다. 같은 ARN의 최신 버전이나 `AWSPREVIOUS`만으로 롤백 대상을 추측하지 않는다.

유출 시 해당 공개키 제거와 영향 세션 폐기를 함께 수행한다. 유출된 개인키는 가용성 복구를 이유로 롤백에 쓰지 않는다.

만료된 서비스 세션/code/제한 행은 운영 유지보수에서 삭제할 수 있다.
RT 이력은 세션 만료 전 지우지 않는다.

요청 제한은 둘로 나뉘며 상태는 PostgreSQL `enrollment.auth_attempts`에 공유한다(ADR 0052).

| 범주 | 요청 | 단위 | 설정 키(기본) |
| --- | --- | --- | --- |
| 진입 | 회사 탐색·OIDC 인가·콜백·코드 교환, 아래 자격 보유가 아닌 `/api/v1/auth/*` 전부 | remoteAddr | `rate-limit.entry.requests`·`window`(30회·60초) |
| 자격 보유 | RT 갱신·로그아웃·`GET /api/v1/manifest`·`GET /api/v1/auth/me` | 세션 | `rate-limit.session.requests`·`window`(30회·60초) |

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

`GET /api/v1/manifest`는 사용자 RT를 회전한다. CDN/API Gateway에서 이 경로를 캐시하거나 재시도하지 않도록 한다.
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

CI의 PR 검증과 develop 이미지 게시 workflow는 telemetryctl 기본 브랜치를 체크아웃해 그 `contracts`를 쓴다(ref를 고정하지 않는다). dev CI는 이미지 공급만 하며 EKS 배포는 infra 런북에 따른 별도 수동 작업이다.
계약 테스트는 telemetryctl 기본 브랜치에 있는 `enrollment-envelope`·`enrollment-manifest` 두 스키마만 원본으로 읽는다. 사용자 토큰 봉투·AT 클레임·재조회 봉투의 오라클은
[인증 API](api/auth.md)와 [manifest 재조회](api/enrollment.md)의 표다 — 원격 기본 브랜치에 없는 스키마를 읽게 하면 CI가 죽는다.
