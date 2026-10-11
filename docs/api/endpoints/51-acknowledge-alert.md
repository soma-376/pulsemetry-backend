# 51 POST `/api/v1/organizations/{organizationId}/alerts/{alertId}/acknowledge`

알림 확인

[전체 API](../README.md) · [알림·알림 규칙](../alerts.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/alerts/{alertId}/acknowledge -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/AlertRuleController.kt)

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
Content-Type: application/json
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
// AlertAcknowledgement
{ alertId: string; version: number; acknowledgedAt: string; acknowledgedBy: string }
```

[AlertAcknowledgement 전체 스키마·중첩 타입](../reference/alerts.md#schema-AlertAcknowledgement)

Idempotency-Key를 사용하지 않는다. 기존 확인 결과를 유지한다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/alerts.md)를 함께 적용한다.
