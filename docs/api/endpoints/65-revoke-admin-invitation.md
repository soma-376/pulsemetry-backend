# 65 POST `/v1/invitations/{id}/revoke`

관리자 키 초대 취소

[전체 API](../README.md) · [초대·설치 코드](../invitations.md)

<!-- endpoint: enrollment-api POST /v1/invitations/{id}/revoke -->

서버: **enrollment-api** · 성공: **204** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/InvitationAdminController.kt)

## Request

### Path

```ts
{
  id: string;
}
```

### Headers

```http
X-Admin-Token: <서버 관리자 키>
```

## Response

본문 없음.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/invitations.md)를 함께 적용한다.
