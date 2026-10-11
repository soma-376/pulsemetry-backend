# 30 POST `/api/v1/organizations/{organizationId}/members/{memberId}/installation-invitations`

활성 회원 설치 코드

[전체 API](../README.md) · [초대·설치 코드](../invitations.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/members/{memberId}/installation-invitations -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

## Request

### Path

```ts
{
  organizationId: string;
  memberId: string;
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
  expectedVersion: number;
}
```

## Response

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

[InstallationInvitation 전체 스키마·중첩 타입](../reference/invitations.md#schema-InstallationInvitation)

가입용 코드가 아니다. 미사용 설치 코드들을 대체한다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/invitations.md)를 함께 적용한다.
