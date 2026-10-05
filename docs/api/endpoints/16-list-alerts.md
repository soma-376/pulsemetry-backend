# 16 GET `/api/v1/organizations/{organizationId}/alerts`

알림 목록

[전체 API](../README.md) · [알림·알림 규칙](../alerts.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/alerts -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/AlertController.kt)

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
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Query

```ts
{
  status?: "unacknowledged" | "acknowledged" | "all"; // 기본 unacknowledged
  category?: "security" | "cost"; // 생략: 전체
  limit?: number; // 기본 20, 정수 1~100
  cursor?: string; // 응답 nextCursor
  snapshotId?: string; // 다음 페이지에 이전 응답 값 유지
}
```

## Response

```ts
// AlertsResponse
{
  meta: CurrentMeta;
  evaluation: { availability: "available" | "unavailable"; reason: string | null;
    asOf: string | null;
    rules: { ruleId: string; enabled: boolean; evaluatedAt: string | null; status: "evaluated" | "not_evaluated" | "failed" | null;
      reason: string | null; windowStart: string | null; windowEnd: string | null }[] };
  alerts: Page<Alert>;
}
```

[AlertsResponse 전체 스키마·중첩 타입](../reference/alerts.md#schema-AlertsResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/alerts.md)를 함께 적용한다.
