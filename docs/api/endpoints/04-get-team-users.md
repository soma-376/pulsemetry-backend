# 04 GET `/api/v1/organizations/{organizationId}/analytics/teams/{teamId}/users`

팀 사용자

[전체 API](../README.md) · [팀 분석·관리](../teams.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/analytics/teams/{teamId}/users -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/TeamsController.kt)

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
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Query

```ts
{
  startDate: string; // 필수 YYYY-MM-DD
  endDate: string; // 필수, 종료일 포함, 1~366일
  timeZone?: "Asia/Seoul"; // 기본 Asia/Seoul
  limit?: number; // 기본 12, 정수 1~100
  cursor?: string; // 응답 nextCursor
  snapshotId?: string; // 다음 페이지에 이전 응답 값 유지
}
```

## Response

```ts
// TeamUsersResponse
{
  meta: AnalyticsMeta;
  team: TeamRef;
  summary: TeamUsersSummary;
  users: Page<TeamUser>;
}
```

[TeamUsersResponse 전체 스키마·중첩 타입](../reference/teams.md#schema-TeamUsersResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/teams.md)를 함께 적용한다.
