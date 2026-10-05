# 50 PATCH `/api/v1/organizations/{organizationId}/settings/alert-rules/{ruleId}`

알림 규칙 변경

[전체 API](../README.md) · [알림·알림 규칙](../alerts.md)

<!-- endpoint: enrollment-api PATCH /api/v1/organizations/{organizationId}/settings/alert-rules/{ruleId} -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/AlertRuleController.kt)

## Request

### Path

```ts
{
  organizationId: string;
  ruleId: string;
}
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
Content-Type: application/json
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Body

```ts
{
  expectedVersion: number;
  enabled: boolean;
}
```

## Response

```ts
// AlertRule
{
  ruleId: string;
  version: number;
  enabled: boolean;
  availability: "available" | "partial" | "unavailable";
  reason: string | null;
  threshold: AlertThreshold;
  evaluationWindow: string;
  comparisonWindow: string | null;
}
```

[AlertRule 전체 스키마·중첩 타입](../reference/alerts.md#schema-AlertRule)

지원 불가 규칙 활성화는 422 alert_rule_unavailable.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/alerts.md)를 함께 적용한다.
