# 25 POST `/api/v1/organizations/{organizationId}/member-team-assignments`

팀 일괄 배정

[전체 API](../README.md) · [구성원 조회·편집](../members.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/member-team-assignments -->

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
  assignments: Array<{ memberId: string; teamId: string | null; expectedVersion: number }>; // 1~100명
}
```

## Response

```ts
// TeamAssignmentSaved
{
  effectiveAt: string;
  members: Array<{ memberId: string; teamId: string | null; version: number }>;
}
```

[TeamAssignmentSaved 전체 스키마·중첩 타입](../reference/members.md#schema-TeamAssignmentSaved)

전체 검증 후 원자적으로 적용한다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/members.md)를 함께 적용한다.
