# 22 POST `/api/v1/organizations/{organizationId}/teams`

팀 생성

[전체 API](../README.md) · [팀 분석·관리](../teams.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/teams -->

서버: **enrollment-api** · 성공: **201** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

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
  teamName: string; // trim 후 1~100자
}
```

## Response

```ts
// TeamSaved
{ teamId: string; teamName: string; version: number }
```

[TeamSaved 전체 스키마·중첩 타입](../reference/teams.md#schema-TeamSaved)

Location: 생성한 팀 경로

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/teams.md)를 함께 적용한다.
