# 64 POST `/v1/invitations`

관리자 키 초대

[전체 API](../README.md) · [초대·설치 코드](../invitations.md)

<!-- endpoint: enrollment-api POST /v1/invitations -->

서버: **enrollment-api** · 성공: **201** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/InvitationAdminController.kt)

## Request

### Headers

```http
X-Admin-Token: <서버 관리자 키>
Content-Type: application/json
```

### Body

```ts
{
  tenant_id: string;
  created_by_member_id: string;
  email: string;
  display_name?: string | null;
  expires_in_hours?: number | null; // 생략/null: 기본 72, 1~720
}
```

## Response

```ts
// AdminInvitation
{
  invitation_id: string; code: string; expires_at: string;
  install_commands: { windows: string; unix: string };
}
```

[AdminInvitation 전체 스키마·중첩 타입](../reference/invitations.md#schema-AdminInvitation)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/invitations.md)를 함께 적용한다.
