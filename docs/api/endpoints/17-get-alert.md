# 17 GET `/api/v1/organizations/{organizationId}/alerts/{alertId}`

알림 상세

[전체 API](../README.md) · [알림·알림 규칙](../alerts.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/alerts/{alertId} -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/AlertController.kt)

## Request

### Path

```ts
{
  organizationId: string;
  alertId: string;
}
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

## Response

```ts
// AlertResponse
{ meta: CurrentMeta; alert: Alert }
```

[AlertResponse 전체 스키마·중첩 타입](../reference/alerts.md#schema-AlertResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/alerts.md)를 함께 적용한다.
