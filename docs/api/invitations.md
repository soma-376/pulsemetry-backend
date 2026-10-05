# 초대·설치 코드

[API 길잡이](README.md) · [공통 규칙](common.md) · [공통 스키마](common-schemas.md)

## 엔드포인트

<a id="endpoint-enrollment-api-27"></a>

### 일괄 초대

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/invitations/batch -->

```http
POST /api/v1/organizations/{organizationId}/invitations/batch
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

**Path**

```ts
{
  organizationId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
Content-Type: application/json
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

```ts
{
  invitations: Array<{ email: string; teamId: string | null; role: "admin" | "member"; plannedVendorIds?: string[] }>; // 1~100명
}
```

**Response**

```ts
// InvitationsResponse
{
  results: {
    email: string; invitationId: string | null;
    status: "issued" | "already_member" | "already_invited" | "rejected";
    reason: string | null; expiresAt: string | null; code: string | null;
    delivery: Delivery | null;
  }[];
}
```

[InvitationsResponse 전체 스키마·중첩 타입](invitations.md#schema-InvitationsResponse)

행별 issued/already_member/already_invited/rejected. 성공 HTTP가 모든 초대 발급·발송 성공을 의미하지 않는다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-28"></a>

### 초대 취소

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/invitations/{invitationId}/revoke -->

```http
POST /api/v1/organizations/{organizationId}/invitations/{invitationId}/revoke
```

서버: **enrollment-api** · 성공: **204** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

**Path**

```ts
{
  organizationId: string;
  invitationId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

본문 없음.

**Response**

본문 없음.

미발송 메일 취소. 발송된 메일의 코드는 폐기된다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-29"></a>

### 초대 재발급

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/invitations/{invitationId}/reissue -->

```http
POST /api/v1/organizations/{organizationId}/invitations/{invitationId}/reissue
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

**Path**

```ts
{
  organizationId: string;
  invitationId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

본문 없음.

**Response**

```ts
// ReissuedInvitation
{
  invitationId: string; replacesInvitationId: string;
  code: string; expiresAt: string;
  delivery: Delivery;
}
```

[ReissuedInvitation 전체 스키마·중첩 타입](invitations.md#schema-ReissuedInvitation)

기존 코드 폐기와 새 코드 발급이 원자적이다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-30"></a>

### 활성 회원 설치 코드

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/members/{memberId}/installation-invitations -->

```http
POST /api/v1/organizations/{organizationId}/members/{memberId}/installation-invitations
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

**Path**

```ts
{
  organizationId: string;
  memberId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
Content-Type: application/json
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

```ts
{
  expectedVersion: number;
}
```

**Response**

```ts
// InstallationInvitation
{
  invitationId: string;
  memberId: string;
  replacesInvitationIds: string[];
  code: string; expiresAt: string;
  delivery: Delivery;
}
```

[InstallationInvitation 전체 스키마·중첩 타입](invitations.md#schema-InstallationInvitation)

가입용 코드가 아니다. 미사용 설치 코드들을 대체한다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-54"></a>

### 초대 목록

<!-- endpoint: enrollment-api GET /api/v1/organizations/{organizationId}/invitations -->

```http
GET /api/v1/organizations/{organizationId}/invitations
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

**Path**

```ts
{
  organizationId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

```ts
{
  limit?: number; // 기본 20, 정수 1~100
  cursor?: string; // 응답 nextCursor
  status?: "pending" | "expired" | "used" | "revoked"; // 생략: 전체
  memberStatus?: "invited" | "active" | "suspended";
}
```

**Body**

본문 없음.

**Response**

```ts
// InvitationPage
{
  items: {
    invitationId: string; email: string; role: string;
    createdAt: string; expiresAt: string;
    installationUsedAt: string | null; signupUsedAt: string | null;
    revokedAt: string | null;
    status: "pending" | "expired" | "used" | "revoked";
    memberId: string;
    memberStatus: "invited" | "active" | "suspended";
    team: { teamId: string; teamName: string } | null;
    memberVersion: number;
    plannedVendorIds: string[];
    delivery: Delivery;
  }[];
  nextCursor: string | null;
}
```

[InvitationPage 전체 스키마·중첩 타입](invitations.md#schema-InvitationPage)

cursor는 UUID. Dashboard snapshot 목록과 다르게 실시간 조회한다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-67"></a>

### 관리자 키 초대

<!-- endpoint: enrollment-api POST /v1/invitations -->

```http
POST /v1/invitations
```

서버: **enrollment-api** · 성공: **201** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/InvitationAdminController.kt)

**Path**

없음.

**Headers**

```http
X-Admin-Token: <서버 관리자 키>
Content-Type: application/json
```

**Query**

없음.

**Body**

```ts
{
  tenant_id: string;
  created_by_member_id: string;
  email: string;
  display_name?: string | null;
  expires_in_hours?: number | null; // 생략/null: 기본 72, 1~720
}
```

**Response**

```ts
// AdminInvitation
{
  invitation_id: string; code: string; expires_at: string;
  install_commands: { windows: string; unix: string };
}
```

[AdminInvitation 전체 스키마·중첩 타입](invitations.md#schema-AdminInvitation)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-68"></a>

### 관리자 키 초대 취소

<!-- endpoint: enrollment-api POST /v1/invitations/{id}/revoke -->

```http
POST /v1/invitations/{id}/revoke
```

서버: **enrollment-api** · 성공: **204** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/InvitationAdminController.kt)

**Path**

```ts
{
  id: string;
}
```

**Headers**

```http
X-Admin-Token: <서버 관리자 키>
```

**Query**

없음.

**Body**

본문 없음.

**Response**

본문 없음.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.


### `POST /v1/invitations` 요청·응답

요청 본문:

| 필드 | 필수 | 설명 |
|---|---|---|
| `tenant_id` | 필수 | 초대를 발급할 조직 |
| `created_by_member_id` | 필수 | 발급자. 그 tenant 의 **활성** owner·admin 이어야 한다 |
| `email` | 필수 | 초대 대상. 앞뒤 공백은 정리된다. 같은 tenant 에 이미 있으면 그 member 를 대상으로 삼고, 없으면 `role=member`·`status=invited` 로 새로 만든다 |
| `display_name` | 선택 | 새로 만들어지는 member 의 표시 이름 |
| `expires_in_hours` | 선택 | 생략하면 `pulsemetry.invitation.default-ttl-hours`(기본 72). 1~720(30일, 잠정 상한 — 팀 확정 대상) 범위를 벗어나면 400 `invalid_request` |

```json
{
  "tenant_id": "0f9c…", "created_by_member_id": "3a71…",
  "email": "hong@example.com", "display_name": "홍길동", "expires_in_hours": 72
}
```

응답(201):

```json
{
  "invitation_id": "b21e…",
  "code": "ABCD-EFGH-JKMN",
  "expires_at": "2026-08-12T00:00:00Z",
  "install_commands": {
    "windows": "irm 'https://get.../windows?code=ABCD-EFGH-JKMN' | iex",
    "unix": "curl -fsSL 'https://get.../unix?code=ABCD-EFGH-JKMN' | sh"
  }
}
```

`expires_at` 은 ISO-8601 UTC 다.

**원본 `code` 는 이 응답에서 딱 한 번만 나간다.** DB 에는 해시만 있어 다시 볼 방법이 없고,
그래서 재조회 API 를 두지 않는다. 관리자가 이 응답을 잃으면 새로 발급해야 한다.

`POST /v1/invitations/{id}/revoke`는 같은 `X-Admin-Token` 헤더를 사용한다.

```ts
// 요청 본문과 쿼리 파라미터 없음
{}
```

```text
# 응답(204)
(본문 없음)
```


초대는 최대 100명, role은 `admin`·`member`, `teamId`는 UUID 또는 null이다.
발급 결과의 `status`는 코드 발급만 말한다. **발급은 발송이 아니다** — 초대 메일의 상태는 `delivery`가 따로 말한다(ADR 0038).
메일 기능(`pulsemetry.mail.enabled`)이 켜져 있으면 발급과 같은 트랜잭션에서 초대 메일을 outbox에 적재하고(`delivery.status=queued`), 발송 작업이 SMTP로 보낸다(ADR 0037).
메일에는 조직 이름·설치 코드·만료 시각, 회사 SSO 로그인 주소(`pulsemetry.management.invitation-accept-url`), 설치 명령([기존 §2.1](../enrollment-server-spec.md#21-post-v1invitations-요청응답)의 `install_commands`와 같은 형태)이 담긴다. 로그인 주소와 제목에는 코드가 없다.
SSO 통합 후 메일의 로그인 안내는 회사 로그인 주소만 사용하며 초대 코드를 붙이지 않는다. 다음 가입 관련 필드는 과거 이력 호환용이다.
메일은 그 코드에 남은 용도만 안내한다(ADR 0055) — 가입 권한이 없으면 수락 링크를, 설치를 마쳤으면 설치 명령을 싣지 않는다. 가입 권한이 없는 코드의 메일 제목은 `Pulsemetry 설치 코드`다.
메일 기능이 꺼져 있으면 `delivery`는 `{status:"not_sent", reason:"mail_disabled"}`다. **적재되지 않은 메일을 `queued`로 내지 않는다.** 그때는 `issued`의 코드를 관리자가 직접 전달한다.
`sent`는 SMTP 서버가 메시지를 받았다는 뜻이고 수신함 도착을 뜻하지 않는다. 실패 코드는 `recipient_rejected` · `message_rejected` · `invalid_address`(재시도하지 않음),
`recipient_deferred` · `smtp_deferred` · `smtp_auth_failed` · `smtp_unavailable` · `send_error`(재시도), `outcome_unknown`이다.
같은 멱등 키의 재시도는 저장된 응답을 그대로 돌려주므로 `delivery`도 최초 시점의 값이고 메일은 한 통이다. 최신 발송 상태는 목록([기존 §13.3](../enrollment-server-spec.md#133-초대-목록재발급))에서 본다.
초대 취소(`revoke`)는 그 초대의 아직 보내지 않은 메일을 취소한다(`cancelled`). 이미 나간 메일은 되돌리지 못하고, 그 안의 코드는 폐기돼 쓸 수 없다.
신규 초대의 기본 만료는 72시간이다. 기존 초대가 있으면 `already_invited`이며 기존 원본 코드를 재조회하지 않는다.
만료만 된 초대도 기존 초대다 — 재발급([기존 §13.3](../enrollment-server-spec.md#133-초대-목록재발급))으로 살린다.
초대가 취소(`revoke`)돼 남은 초대가 없는 대기자(`invited`)는 다시 초대할 수 있다. 새 구성원을 만들지 않고 **같은 `memberId`**에
새 초대를 발급하며(`issued`), 요청의 `teamId`·`role`을 그 구성원에 적용하고 version을 올린다. 취소한 코드는 되살아나지 않는다.

### 활성 구성원 설치 코드 (ADR 0055)

이미 합류한 구성원(`active`)이 새 PC 등에 설치할 코드를 관리자가 다시 낸다. 일괄 초대의 `already_member`는 그대로다.

<a id="schema-InstallationInvitation"></a>

```ts
type InstallationInvitation = {
  invitationId: string;
  memberId: string;
  replacesInvitationIds: string[]; // 이 명령이 폐기한 그 구성원의 설치 전용 초대
  code: string; expiresAt: string;
  delivery: Delivery;              // 새 초대의 메일
};
```

`expectedVersion`은 그 구성원의 version(구성원 목록의 `version`)이다. 다르면 409 `version_conflict`다. 이 명령은 구성원을 바꾸지 않으므로 version은 그대로다.
정지 구성원은 409 `member_suspended`, 아직 합류하지 않은 구성원(`invited`)은 409 `member_not_active`다 — 일괄 초대·재발급([기존 §13.3](../enrollment-server-spec.md#133-초대-목록재발급))으로 코드를 받는다.
없는 구성원·다른 조직 구성원은 404 `not_found`다.
새 초대는 기존 초대의 소비 상태를 옮기지 않는다. 72시간 만료이고, 발급 시각을 가입 소비 시각으로 기록해 **설치 전용임을 표시한다**. 비밀번호 가입 API는 모든 코드에 410 `auth_method_removed`를 반환한다.
설치(`POST /v1/enroll`)는 다른 초대와 같이 한 번 소비한다. 활성 구성원의 설치는 구성원 상태를 바꾸지 않는다.
그 구성원의 남은 설치 전용 초대(미폐기, 설치 미소비, 가입 소비)는 같은 트랜잭션에서 폐기하고 아직 나가지 않은 메일을 취소한다.
가입 권한이 남은 초대는 건드리지 않는다. 같은 멱등 키의 재시도는 최초 코드와 발송 상태를 그대로 돌려주고 메일은 한 통이다.

벤더 kind/plan은 dashboard-api의 [벤더 카탈로그](../dashboard-server-spec.md#3-벤더와-플랜-카탈로그)에서 얻는다.
카탈로그 `id`를 kind로 보내며 조직에 등록된 `vendorId` UUID와 혼동하지 않는다.
공통 공급사·제품·플랜은 `enrollment.vendor_catalog_vendors`·`vendor_catalog_products`·`vendor_catalog_plans`에 저장한다(ADR 0035).
신규 등록과 계약 저장은 DB의 활성 제품·플랜만 허용한다. `active=false`는 새 선택을 막으며 기존 계약 이력을 지우지 않는다.
조직별 단가와 좌석 수는 계속 `vendor_contract_versions.contract`에 저장한다.
contract를 생략하거나 null로 보내면 벤더만 등록하고 `state=needs_review`, `contract=null`을 반환한다.
계약은 이후 `PUT /vendors/{vendorId}/contract`로 추가한다. PUT에는 완전한 contract가 필요하다.
tiers는 allowsSeatTiers=true이면 1~3개, false이면 1개다. seats는 양의 정수,
월 단가는 0 이상 USD decimal(최대 소수 12자리)이다.
서버가 tierId·월 합계·confirmedAt·confirmedBy를 결정한다.
조직별 활성 등록은 제품 kind당 하나이며 중복 등록은 409 `vendor_already_registered`다.
전체 삭제 후 같은 제품을 새 UUID로 등록할 수 있으며 이전 등록 이력은 보존한다.
새 계약의 시작일은 조직의 오늘부터이며 종료일은 시작일 이상 또는 null이다.
기존 계약 PUT은 입력 정보 정정이다. 서버가 저장된 effectiveFrom을 유지하고 요청의 시작일로 바꾸지 않는다.
종료일은 오늘과 기존 시작일 이상이어야 한다. 이미 저장된 과거 종료일은 변경 없이 유지할 수 있다.
과거 시작일 때문에 정정을 거절하지 않는다. 새 버전의 월액을 계산하고 이전 버전·사용량 원본은 보존한다.
PATCH는 표시 이름만 바꾸고 계약을 그대로 보존한다. 신규 계약/실제 조건 갱신/누적 지출 재계산을 의미하지 않는다.
계약 변경·해제는 버전 이력으로 남는다. API 삭제는 실제 벤더 구독 해지·요금 환불을 의미하지 않는다.

- 계약 비우기(`DELETE .../contract`): 등록 UUID를 유지하고 새 버전에 contract=null을 저장한다. 목록에서는 needs_review다.
- 제품 삭제(`DELETE .../{vendorId}`): 등록을 archived로 보관하고 현재 목록에서 제외한다. 이력 행을 삭제하지 않는다.
- 표시 이름 PATCH: 등록 version과 변경 이력은 증가하지만 기존 계약 JSON과 계약의 confirmedAt/confirmedBy는 유지한다.
- 계약 PUT: 새 계약 version·confirmedAt/confirmedBy와 월 금액을 저장한다. 이전 행의 내용과 변경자·시각은 그대로 남는다.
- 비운 계약을 다시 입력하는 것은 기존 계약 정정이 아닌 새 계약 입력이다. 새 시작일 검증을 적용한다.

변경 이력 저장은 구현돼 있지만 이력 목록 조회 API·화면은 현재 범위에 없다.
`enrollment.contracts`의 기존 기간 약정과 새 좌석 계약을 합산하지 않으며, 좌석 계약값으로 기간별 지출을 누적하는 테이블도 추가하지 않는다.


### 초대 목록·재발급

`GET /invitations`

```ts
// 쿼리 파라미터
{
  limit?: number; // 기본 20, 1~100
  cursor?: string;
}
```

응답(200):

<a id="schema-InvitationPage"></a>

```ts
type InvitationPage = {
  items: {
    invitationId: string; email: string; role: string;
    createdAt: string; expiresAt: string;
    installationUsedAt: string | null; signupUsedAt: string | null;
    revokedAt: string | null;
    status: "pending" | "expired" | "used" | "revoked";
    memberId: string;
    memberStatus: "invited" | "active" | "suspended";
    team: { teamId: string; teamName: string } | null;
    memberVersion: number;
    plannedVendorIds: string[];
    delivery: Delivery; // [기존 §12](../enrollment-server-spec.md#12-조직-관리-api). 이 초대의 메일 발송 상태
  }[];
  nextCursor: string | null;
};
```

`POST /invitations/{invitationId}/reissue`

```jsonc
// 요청 본문
{}
```

<a id="schema-ReissuedInvitation"></a>

```ts
// 응답(200)
type ReissuedInvitation = {
  invitationId: string; replacesInvitationId: string;
  code: string; expiresAt: string;
  delivery: Delivery; // 새 초대의 메일
};
```

목록은 invitationId 오름차순이며 다음 요청에는 nextCursor를 그대로 보낸다.
cursor는 UUID다. 실시간 목록으로 snapshot 일관성을 보장하지 않는다. 코드 원문·해시는 목록에 포함하지 않는다.
상태 우선순위는 revoked → 설치 소비 완료인 used → expired → pending이다.
`signupUsedAt`은 과거 이력 호환 필드이며 상태 판단에는 사용하지 않는다.

재발급은 만료 여부와 관계없이 아직 폐기되지 않고 설치에 미사용인 초대에만 허용한다.
기존 코드를 즉시 폐기하고 새 ID·코드·72시간 만료를 만든다. 두 작업은 원자적이다.
기존 계정·설치·세션은 삭제하지 않는다. 가입 소비 시각은 과거 이력 호환 필드다.
폐기됐거나 설치에 소비한 초대는 409 `invitation_unavailable`이다. 그런 활성 구성원에게 설치 코드가 필요하면 [기존 §12](../enrollment-server-spec.md#12-조직-관리-api) "활성 구성원 설치 코드"다.
같은 멱등 키의 재시도는 최초 새 코드를 재전달한다. 재시도 응답 저장에는 [기존 §12](../enrollment-server-spec.md#12-조직-관리-api)의 암호화를 사용한다.
재발급은 새 초대의 메일을 같은 트랜잭션에서 적재하고, 폐기한 초대의 아직 보내지 않은 메일을 취소한다. 이미 나간 메일 뒤의 재발급은 새 메일을 한 통 더 보낸다.
같은 멱등 키의 재시도는 메일을 다시 만들지 않는다.

목록의 `delivery`는 그 초대의 현재 발송 상태다. 메일을 적재한 적 없는 초대(메일 기능을 켜기 전의 초대, 관리자 키 경로의 초대)는
`not_sent`이고 `reason`은 메일이 켜져 있으면 `not_queued`, 꺼져 있으면 `mail_disabled`다.


<a id="schema-InvitationsResponse"></a>
<a id="schema-Delivery"></a>

```ts
type InvitationsResponse = {
  results: {
    email: string; invitationId: string | null;
    status: "issued" | "already_member" | "already_invited" | "rejected";
    reason: string | null; expiresAt: string | null; code: string | null;
    delivery: Delivery | null; // issued인 항목에만 있다
  }[];
};
// 초대 메일의 발송 상태. 발급(status)과 별개의 사실이다 (ADR 0038).
type Delivery = {
  status: "queued" | "sending" | "sent" | "failed" | "cancelled" | "not_sent";
  reason: "mail_disabled" | "not_queued" | null; // not_sent일 때만 값이 있다
  queuedAt: string | null;      // 적재 시각
  lastAttemptAt: string | null; // 마지막 발송 시도 시각
  sentAt: string | null;        // SMTP 서버가 받은 시각. sent일 때만 값이 있다
  failureCode: string | null;   // 실패 분류 코드. 재시도 대기(queued) 중에는 마지막 시도의 사유
  attempts: number;
};
```

<a id="schema-AdminInvitation"></a>

```ts
type AdminInvitation = {
  invitation_id: string; code: string; expires_at: string;
  install_commands: { windows: string; unix: string };
};
```

관리자 키 경로는 운영용이고 웹의 일괄 초대와 인증·필드명이 다르다. 서버 관리자 키를 브라우저에 전달하지 않는다.
일괄 초대 plannedVendorIds의 의미·검증은 [구성원 문서](members.md)를 따른다.


## JSON 응답 예시 — InvitationPage

```json
{
  "items": [],
  "nextCursor": null
}
```

## 직렬화·검증 근거

- [OnboardingStore.kt](../../libs/enrollment-persistence/src/main/kotlin/com/team376/pulsemetry/persistence/enrollment/management/OnboardingStore.kt)
- [ManagementStore.kt](../../libs/enrollment-persistence/src/main/kotlin/com/team376/pulsemetry/persistence/enrollment/management/ManagementStore.kt)
