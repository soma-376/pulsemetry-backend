# 로그인·사용자 인증

[API 길잡이](README.md) · [공통 규칙](common.md) · [공통 스키마](common-schemas.md)

## 엔드포인트

<a id="endpoint-enrollment-api-56"></a>

### 이메일로 회사 탐색

<!-- endpoint: enrollment-api POST /v1/auth/organizations -->

```http
POST /v1/auth/organizations
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/LoginDiscoveryController.kt)

**Path**

없음.

**Headers**

```http
Content-Type: application/json
```

**Query**

없음.

**Body**

```ts
{
  email: string;
}
```

**Response**

```ts
// LoginOrganizations
{ organizations: Array<{ organizationId: string; organizationName: string }> }
```

[LoginOrganizations 전체 스키마·중첩 타입](auth.md#schema-LoginOrganizations)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-57"></a>

### SSO 시작

<!-- endpoint: enrollment-api GET /v1/auth/oidc/authorize -->

```http
GET /v1/auth/oidc/authorize
```

서버: **enrollment-api** · 성공: **302** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/OidcLoginConfig.kt)

**Path**

없음.

**Headers**

필수 인증 헤더 없음.

**Query**

```ts
{
  tenant_id: string;
  redirect_uri: string;
  state: string;
  code_challenge: string;
  code_challenge_method: "S256";
  login_hint?: string;
}
```

**Body**

본문 없음.

**Response**

리다이렉트: Location + OIDC 임시 쿠키, 본문 없음

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-58"></a>

### IdP 콜백

<!-- endpoint: enrollment-api GET /v1/auth/oidc/callback/{registrationId} -->

```http
GET /v1/auth/oidc/callback/{registrationId}
```

서버: **enrollment-api** · 성공: **302** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/OidcLoginConfig.kt)

**Path**

```ts
{
  registrationId: string;
}
```

**Headers**

```http
Cookie: PULSEMETRY_OIDC=<임시 세션>
```

**Query**

```ts
{
  code?: string;
  state: string;
  error?: string; // 취소·실패 시 code 대신 반환
}
```

**Body**

본문 없음.

**Response**

리다이렉트: code/state 또는 error/state, 본문 없음

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-59"></a>

### 서비스 토큰 교환

<!-- endpoint: enrollment-api POST /v1/auth/token -->

```http
POST /v1/auth/token
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/UserAuthController.kt)

**Path**

없음.

**Headers**

```http
Content-Type: application/json
```

**Query**

없음.

**Body**

```ts
{
  code: string;
  redirect_uri: string;
  code_verifier: string;
}
```

**Response**

```ts
// UserTokens
{ access_token: string; refresh_token: string; token_type: "Bearer"; expires_in: number }
```

[UserTokens 전체 스키마·중첩 타입](auth.md#schema-UserTokens)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-60"></a>

### 서비스 토큰 교환

<!-- endpoint: enrollment-api POST /v1/auth/cli/token -->

```http
POST /v1/auth/cli/token
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/UserAuthController.kt)

**Path**

없음.

**Headers**

```http
Content-Type: application/json
```

**Query**

없음.

**Body**

```ts
{
  code: string;
  redirect_uri: string;
  code_verifier: string;
}
```

**Response**

```ts
// UserTokens
{ access_token: string; refresh_token: string; token_type: "Bearer"; expires_in: number }
```

[UserTokens 전체 스키마·중첩 타입](auth.md#schema-UserTokens)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-61"></a>

### 토큰 갱신

<!-- endpoint: enrollment-api POST /v1/auth/refresh -->

```http
POST /v1/auth/refresh
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/UserAuthController.kt)

**Path**

없음.

**Headers**

```http
Content-Type: application/json
```

**Query**

없음.

**Body**

```ts
{
  refresh_token: string;
}
```

**Response**

```ts
// UserTokens
{ access_token: string; refresh_token: string; token_type: "Bearer"; expires_in: number }
```

[UserTokens 전체 스키마·중첩 타입](auth.md#schema-UserTokens)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-62"></a>

### 서비스 로그아웃

<!-- endpoint: enrollment-api POST /v1/auth/logout -->

```http
POST /v1/auth/logout
```

서버: **enrollment-api** · 성공: **204** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/UserAuthController.kt)

**Path**

없음.

**Headers**

```http
Content-Type: application/json
```

**Query**

없음.

**Body**

```ts
{
  refresh_token: string;
}
```

**Response**

본문 없음.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-63"></a>

### 현재 사용자

<!-- endpoint: enrollment-api GET /v1/auth/me -->

```http
GET /v1/auth/me
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/CurrentUserController.kt)

**Path**

없음.

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: 유효한 서비스 사용자 세션. 엔드포인트별 소유권 검사는 아래 규칙을 따른다.

**Query**

없음.

**Body**

본문 없음.

**Response**

```ts
// CurrentUser
{
  memberId: string; organizationId: string; organizationName: string;
  email: string; displayName: string; role: "admin" | "member";
}
```

[CurrentUser 전체 스키마·중첩 타입](auth.md#schema-CurrentUser)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-64"></a>

### 폐기된 비밀번호 인증

<!-- endpoint: enrollment-api POST /v1/auth/signup -->

```http
POST /v1/auth/signup
```

서버: **enrollment-api** · 성공: **410** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/UserAuthController.kt)

**Path**

없음.

**Headers**

필수 인증 헤더 없음.

**Query**

없음.

**Body**

본문 없음.

**Response**

```ts
// PublicError
{ error: string; message: string }
```

[PublicError 전체 스키마·중첩 타입](common.md#schema-PublicError)

password_auth_disabled. 신규 호출자는 OIDC를 사용한다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-65"></a>

### 폐기된 비밀번호 인증

<!-- endpoint: enrollment-api POST /v1/auth/login -->

```http
POST /v1/auth/login
```

서버: **enrollment-api** · 성공: **410** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/UserAuthController.kt)

**Path**

없음.

**Headers**

필수 인증 헤더 없음.

**Query**

없음.

**Body**

본문 없음.

**Response**

```ts
// PublicError
{ error: string; message: string }
```

[PublicError 전체 스키마·중첩 타입](common.md#schema-PublicError)

password_auth_disabled. 신규 호출자는 OIDC를 사용한다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-66"></a>

### 폐기된 비밀번호 인증

<!-- endpoint: enrollment-api POST /v1/auth/cli/authorize -->

```http
POST /v1/auth/cli/authorize
```

서버: **enrollment-api** · 성공: **410** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/UserAuthController.kt)

**Path**

없음.

**Headers**

필수 인증 헤더 없음.

**Query**

없음.

**Body**

본문 없음.

**Response**

```ts
// PublicError
{ error: string; message: string }
```

[PublicError 전체 스키마·중첩 타입](common.md#schema-PublicError)

password_auth_disabled. 신규 호출자는 OIDC를 사용한다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.


## 사용자 인증

`pulsemetry.user-auth.enabled=true`와 인증 키 설정이 필요하다.
로컬에서는 Compose가 키를 준비하고, `:apps:enrollment-api:bootRun --args="--spring.profiles.active=local"`이
인증·관리 기능을 활성화한다. Cognito A·B의 회사 OIDC 설정만 Compose가 공개 JSON으로 시딩한다. 회원 sub는 최초 SSO에서 연결하며 실제 Secret만 [호스트 환경변수로 준비](../cognito-dev.md)한다.
`local`에 개발 Cognito OIDC 설정을 포함한다. 서버는 키를 생성하지 않는다(ADR 0031).
키 설정 상세는 [사용자 인증 운영](../user-auth-operations.md)을 따른다.

### 로그인 페이지 — 이메일 회사 탐색

`POST /v1/auth/organizations` (`user-auth.enabled=true`, `oidc.enabled=true`)

요청 본문:

```json
{ "email": "owner@seed-a.example.test" }
```

응답(200):

```json
{
  "organizations": [
    { "organizationId": "1b59ab21-1788-35e0-bfd7-23baa88a35b4", "organizationName": "시드 A · 정상 사용" }
  ]
}
```

전체 이메일을 trim·소문자로 정규화한다. `invited`·`active` 회원, 활성 회사, 활성화된 회사 OIDC 설정이 있는 회사만 반환한다. sub가 NULL이어도 조회되며 정규화한 이메일이 같은 회원이 여러 명이면 제외한다. issuer/client/secret 참조는 tenants에서 읽고 비밀 원문은 서버 설정에서 주입한다.
미등록은 빈 배열이며 1개면 바로 SSO 시작, 여러 개면 사용자가 회사를 선택한다. member ID·issuer·sub·secret은 반환하지 않는다.
**경로 탐색일 뿐 인증이 아니다.** 세션·토큰 발급, 이메일 자동 연결, 회원 생성이 없다.
400 invalid_request, 429 rate_limited, 503 auth_unavailable; no-store와 IP 30회/분 제한을 적용한다.
소속 회사 정보가 공개 탐색되는 위험은 남으므로 운영 모니터링이 필요하다.

### 로그인 페이지 — SSO 시작

`GET /v1/auth/oidc/authorize`

`pulsemetry.oidc.enabled=true`도 필요하다. 비밀번호를 Pulsemetry로 보내지 않고 브라우저를 이 주소로 이동한다.
조직별로 서버에 등록한 IdP만 사용한다. 신규 회원·조직을 자동 생성하지 않으며 미연결 회원만 검증된 회사 이메일로 연결한다.

쿼리 파라미터(모두 필수, URL 인코딩):

```text
tenant_id=<사전 등록된 조직 UUID>
redirect_uri=http://localhost:3000/auth/callback
state=<클라이언트가 생성·보관하는 예측 불가능한 값, 16~256자>
code_challenge=<BASE64URL(SHA256(code_verifier)), 패딩 없이 43자>
code_challenge_method=S256
```

응답(302, JSON 본문 없음):

```http
Cache-Control: no-store
Referrer-Policy: no-referrer
Set-Cookie: PULSEMETRY_OIDC=<opaque>; Path=/v1/auth/oidc; HttpOnly; SameSite=Lax; Secure
Location: https://sso.example.com/authorize?...
```

운영은 HTTPS + Secure 쿠키이며 local 프로필의 loopback HTTP에서만 Secure가 빠진다.
복귀 주소는 `pulsemetry.user-auth.allowed-redirect-uris`의 정확한 주소만 허용한다.
CLI는 명시적 포트가 있는 `http://127.0.0.1:<port>/callback` 또는 IPv6 loopback을 허용한다.
userinfo·query·fragment가 붙은 주소, 와일드카드 호스트, 임의 외부 주소는 허용하지 않는다.
서버가 IdP에 보내는 state·nonce·PKCE는 클라이언트의 state·PKCE와 **별도**다.

선택 쿼리 `login_hint`에 이메일을 보내면 앞뒤 공백 제거·소문자 정규화 후 IdP 인증 요청에 전달한다.
미전달도 허용하며, 전달 시 단일 값·이메일 형식·최대 254자·제어 문자 없음 조건을 검사한다.
전달된 이메일은 해당 회사의 `invited`·`active` 회원인지 서버에서 확인하고 회원 UUID와 정규화 이메일을 왕복 세션에 저장한다. 미등록·정지·중복 이메일이면 403으로 IdP 이동 전에 거부한다. 최초 연결에는 이 대상 회원과 ID Token의 검증된 이메일 일치가 필요하다. 미전달 로그인은 기존 sub 연결에만 허용한다.

### IdP callback → 프론트 callback 페이지

`GET /v1/auth/oidc/callback/{registrationId}` (개발 등록 이름: `cognito`)

IdP가 호출하는 경로다. 프론트에서 직접 code를 만들거나 이 경로로 토큰을 POST하지 않는다.

쿼리 파라미터:

```text
code=<IdP authorization code>
state=<백엔드가 생성한 OIDC state>
# IdP가 추가하는 iss/session_state 등의 표준 필드가 있을 수 있다.
```

서버는 임시 쿠키의 원래 요청과 state, IdP 서명·issuer·audience·만료·nonce 및 PKCE를 검증한다.
이미 연결된 회원은 검증된 `(tenant_id, issuer, sub)`와 활성 상태가 일치해야 한다. 시작 시 선택한 회원이 있으면 동일 회원이어야 한다.
미연결 회원은 왕복 세션에 고정한 대상 회원의 회사·현재 이메일·상태를 DB 잠금 아래 다시 검사한다. ID Token의 `email_verified=true`와 정규화 이메일이 시작 이메일 및 현재 DB 이메일과 일치할 때만 sub를 저장하고 `invited`를 `active`로 전환한다. 회사의 `oidc_require_verified_email=false`도 최초 연결에는 예외가 아니다.
sub 충돌·다른 sub로의 교체·미등록·정지·중복 이메일은 거부한다. 연결·활성화·코드 발급은 함께 커밋/롤백한다. 회원 UUID·역할·설치 초대는 변경하지 않는다.

성공 응답(302):

```http
Cache-Control: no-store
Location: http://localhost:3000/auth/callback?code=uac_<one-time-code>&state=<client-state>
Set-Cookie: PULSEMETRY_OIDC=; Path=/v1/auth/oidc; Max-Age=0; HttpOnly; SameSite=Lax; Secure
```

프론트는 보관한 state와 일치하는지 먼저 확인하고 [기존 §11.3](../enrollment-server-spec.md#113-callback-페이지--서비스-토큰-교환)에서 code를 교환한다.
`uac_`는 Pulsemetry 전용 **60초·1회용** 코드이며 IdP code와 다르다. AT/RT는 URL에 싣지 않는다.
OIDC 왕복용 JDBC 세션은 10분, 완료·실패 시 폐기한다. 이 쿠키로 업무 API를 인증하지 않는다.

실패도 **서버에 저장된 안전한 복귀 주소**로 반환한다(302):

```http
Location: http://localhost:3000/auth/callback?error=member_not_allowed&state=<client-state>
Cache-Control: no-store
```

`error`는 login_cancelled(IdP access_denied), member_not_allowed(사전 등록/활성 조건 불충족),
invalid_credentials(서명·nonce·state 등 검증 실패), auth_unavailable(IdP/인프라 장애) 중 하나다.
IdP의 error_description·토큰·입력 redirect_uri를 그대로 반사하지 않는다. 프론트는 오류도 state 확인 후 표시한다.
임시 쿠키/세션 유실 시 신뢰할 요청을 복원할 수 없으므로 `oidc.failure-redirect-uri`로만 아래처럼 반환한다:

```http
Location: http://localhost:3000/auth/callback?error=login_expired
```

이 고정 주소는 user-auth.allowed-redirect-uris의 정확한 항목이어야 하며 시작 시 검증한다.
미설정 환경은 JSON 오류를 반환한다. 잘못된 로그인 시작 요청과 IP 제한은 JSON 4xx/5xx이며 임의 리다이렉트를 하지 않는다.

### callback 페이지 — 서비스 토큰 교환

`POST /v1/auth/token` (`POST /v1/auth/cli/token`도 같은 계약)

요청 본문:

```json
{
  "code": "uac_<one-time-code>",
  "redirect_uri": "http://localhost:3000/auth/callback",
  "code_verifier": "<로그인 시작 전 생성해 보관한 PKCE verifier>"
}
```

`redirect_uri`는 시작 요청과 정확히 같아야 한다. verifier는 RFC 7636의 43~128자 unreserved 문자열이다.
토큰 요청은 JSON이고 쿠키/IdP access token을 요구하지 않는다.

응답(200):

```json
{
  "access_token": "<Pulsemetry JWT>",
  "refresh_token": "urt_<opaque refresh token>",
  "token_type": "Bearer",
  "expires_in": 300
}
```

### 공통 — 로그인 유지·로그아웃·현재 사용자

`POST /v1/auth/refresh`

```jsonc
// 요청 본문
{ "refresh_token": "<current refresh token>" }
```

```jsonc
// 응답(200). refresh token도 새 값으로 회전한다.
{
  "access_token": "<new JWT>",
  "refresh_token": "<new refresh token>",
  "token_type": "Bearer",
  "expires_in": 300
}
```

`POST /v1/auth/logout`

```jsonc
// 요청 본문
{ "refresh_token": "<current refresh token>" }
```

```text
# 응답(204)
(본문 없음)
```

현재 Pulsemetry 세션만 폐기한다. IdP 브라우저 SSO 세션을 로그아웃시키지는 않는다.

`GET /v1/auth/me`

```http
Authorization: Bearer <access_token>
```

```jsonc
// 응답(200)
{
  "memberId": "3a7166fd-f38e-4d03-90ba-6ce9a07bf712",
  "organizationId": "0f9c4ba3-c488-4245-af0d-00a81c94f00f",
  "organizationName": "Pulsemetry",
  "email": "hong@example.com",
  "displayName": "홍길동",
  "role": "admin"
}
```

AT 유효기간은 5분, 세션의 절대 유효기간은 30일이다.
refresh는 RT를 회전시키며 이미 소비한 RT를 재사용하면 해당 세션 전체가 폐기된다.
동시 refresh를 클라이언트에서 하나로 합친다. IdP 토큰을 업무 API Bearer로 사용하지 않는다.

### 폐기된 가입·비밀번호 로그인

`POST /v1/auth/signup`, `POST /v1/auth/login`, `POST /v1/auth/cli/authorize`는 폐기됐다.
요청 본문을 파싱하지 않으며 다음을 반환한다. 공개 가입/비밀번호 API는 없다.

```jsonc
// 응답(410)
{ "error": "auth_method_removed", "message": "사용자 인증 요청을 처리할 수 없습니다." }
```

초대 코드는 CLI **설치용**이다. 가입 소비 상태는 새로 기록하지 않는다.
기존 `signup_used_at`/`signupUsedAt`은 이력 호환용으로 남으며 초대의 used 판정은 설치 소비만 따른다.

### 공통 오류와 적용 범위

<a id="schema-AuthError"></a>

```ts
type AuthError = {
  error: "invalid_request" | "invalid_credentials" | "member_not_allowed"
       | "auth_method_removed" | "rate_limited" | "auth_unavailable";
  message: string;
};
```

`token_type`은 `Bearer`, `expires_in`은 300이다. `refresh_token`은 `urt_` 뒤에 32바이트 난수의 base64url(패딩 없음, 43자)이다.
`access_token`은 RS256 JWT다(헤더 `typ: JWT`, `kid`). 클레임은 아래 열 개뿐이다 — 서버의 계약 테스트(`ManifestResyncApiTest`)가 이 표를 오라클로 쓴다.

| 클레임 | 값 |
| --- | --- |
| `iss` · `aud` | 설정의 `pulsemetry.user-auth.issuer` · `audience` |
| `sub` | 구성원 ID(UUID) |
| `tenant_id` | 조직 ID(UUID) |
| `role` | `owner` · `admin` · `member` |
| `sid` | 세션 ID(UUID) |
| `manifest_revision` | 세션이 기억하는 manifest 판(정수 ≥ 0, 활성 manifest가 없으면 0) |
| `iat` · `exp` | 발급·만료 시각(초). `exp − iat`는 300 |
| `jti` | 토큰마다 다른 ID |

인증 오류는 `{error: string, message: string}`이다.
요청 제한은 PostgreSQL의 공유 상태로 적용한다(ADR 0052). 회사 탐색·OIDC 인가·콜백·코드 교환은 IP 기준 `rate-limit.entry`, RT 갱신·로그아웃·manifest 재조회·현재 사용자 조회는 세션 기준 `rate-limit.session`이다. 기본은 각각 60초 30회이며, 429는 RT 소비 전에 반환한다. `Retry-After` 뒤에 재시도한다. 비밀번호 검증과 실패 잠금은 IdP가 담당한다.


## JSON 응답 스키마

<a id="schema-LoginOrganizations"></a>
<a id="schema-UserTokens"></a>
<a id="schema-CurrentUser"></a>

```ts
type LoginOrganizations = { organizations: Array<{ organizationId: string; organizationName: string }> };
type UserTokens = { access_token: string; refresh_token: string; token_type: "Bearer"; expires_in: number };
type CurrentUser = {
  memberId: string; organizationId: string; organizationName: string;
  email: string; displayName: string; role: "admin" | "member";
};
```

회사 탐색은 계정 존재나 입력 이메일을 인증한 것이 아니다. 결과가 없으면 `organizations: []`다.
/me는 owner를 admin으로 표시한다. 보호된 업무 API의 조직·현재 역할 검사는 서버에서 다시 수행한다.


## JSON 응답 예시 — LoginOrganizations

```json
{
  "organizations": []
}
```
