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
  sort?: "cost" | "averageDeviation" | "account" | "session" | "token" | "cache" | "lastUsed"; // 기본 cost, 평균 대비 = 개인 비용 / 팀 전체 평균 - 1
  order?: "asc" | "desc"; // 기본 desc
  limit?: number; // 기본 12, 정수 1~100
  offset?: number; // 기본 0, 0 이상 정수, 다음 응답 nextOffset으로 이어 읽기
  snapshotId?: string; // offset > 0이면 필수, 이전 응답 값 유지
}
```

## Response

```ts
// TeamUsersResponse
{
  meta: TeamMeta;
  team: TeamRef;
  summary: TeamUsersSummary;
  users: OffsetPage<TeamUser>;
}
```

[TeamUsersResponse 전체 스키마·중첩 타입](../reference/teams.md#schema-TeamUsersResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/teams.md)를 함께 적용한다.

## 사용자 정렬

- 평균은 페이지가 아닌 선택 팀의 식별된 전체 사용자 기준이며, 미식별 사용자는 제외한다.
- 평균 대비는 양수인 팀 평균을 공통 분모로 쓰므로 비용 원본으로 순서를 비교한다. 표시용 퍼센트를 반올림해 정렬하지 않는다.
- 평균이 확인 불가이거나 응답의 평균이 0이면 모든 평균 대비를 비교 불가로 취급한다.
- 비교 불가 값은 양방향 모두 마지막이며, 동률은 memberId 오름차순이다.
- snapshotId와 기간·팀·정렬 조건을 유지하고 nextOffset으로 이어 읽는다. 정렬 변경 시 offset=0부터 요청한다. cursor 파라미터는 400 unsupported_value로 거부한다. 모든 정렬은 동률이면 memberId 오름차순, null은 양방향 마지막이다.
