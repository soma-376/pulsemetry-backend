# 24 DELETE `/api/v1/organizations/{organizationId}/teams/{teamId}`

팀 보관

[전체 API](../README.md) · [팀 분석·관리](../teams.md)

<!-- endpoint: enrollment-api DELETE /api/v1/organizations/{organizationId}/teams/{teamId} -->

서버: **enrollment-api** · 성공: **204** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

## Request

### Path

```ts
{
  organizationId: string;
  teamId: string;
}
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
If-Match: "team-{version}"
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

## Response

본문 없음.

If-Match: "team-{version}" 필수. 현재 배정 해제, 과거 분석 귀속 유지.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/teams.md)를 함께 적용한다.
