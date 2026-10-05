# 27 POST `/api/v1/organizations/{organizationId}/invitations/batch`

일괄 초대

[전체 API](../README.md) · [초대·설치 코드](../invitations.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/invitations/batch -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

## Request

### Path

```ts
{
  organizationId: string;
}
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
Content-Type: application/json
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Body

```ts
{
  invitations: Array<{ email: string; teamId: string | null; role: "admin" | "member"; plannedVendorIds?: string[] }>; // 1~100명
}
```

## Response

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

[InvitationsResponse 전체 스키마·중첩 타입](../reference/invitations.md#schema-InvitationsResponse)

행별 issued/already_member/already_invited/rejected. 성공 HTTP가 모든 초대 발급·발송 성공을 의미하지 않는다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/invitations.md)를 함께 적용한다.
