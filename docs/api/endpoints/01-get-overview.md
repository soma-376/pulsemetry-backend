# 01 GET `/api/v1/organizations/{organizationId}/analytics/overview`

개요 조회

[전체 API](../README.md) · [개요](../overview.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/analytics/overview -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/OverviewController.kt)

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
  startDate: string; // 필수 YYYY-MM-DD
  endDate: string; // 필수, 종료일 포함, 1~366일
  timeZone?: "Asia/Seoul"; // 기본 Asia/Seoul
  compare?: "prev_week" | "prev_period" | "none"; // 기본 prev_week
}
```

## Response

```ts
// OverviewResponse
{
  meta: Meta;
  comparison: Comparison;
  ingest: Ingest;
  usage: UsagePair;
  seats: Seats;
  alerts: Alerts;
  trend: Trend;
  modelMix: ModelMix;
  waste: Waste;
  teamUsage: TeamUsage;
  productUsage: ProductUsageSection;
}
```

[OverviewResponse 전체 스키마·중첩 타입](../reference/overview.md#schema-OverviewResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/overview.md)를 함께 적용한다.

### JSON 예시

[전체 응답 예시](../examples/overview-response.example.json) — 가상 데이터이며 실서버 응답 캡처가 아니다.
