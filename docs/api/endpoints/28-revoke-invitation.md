# 28 POST `/api/v1/organizations/{organizationId}/invitations/{invitationId}/revoke`

초대 취소

[전체 API](../README.md) · [초대·설치 코드](../invitations.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/invitations/{invitationId}/revoke -->

서버: **enrollment-api** · 성공: **204** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

## Request

### Path

```ts
{
  organizationId: string;
  invitationId: string;
}
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

## Response

본문 없음.

미발송 메일 취소. 발송된 메일의 코드는 폐기된다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/invitations.md)를 함께 적용한다.
