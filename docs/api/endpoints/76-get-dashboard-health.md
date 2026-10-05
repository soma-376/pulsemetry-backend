# 76 GET `/v1/healthz`

생존 확인

[전체 API](../README.md) · [공통 HTTP 규칙](../common.md)

<!-- endpoint: dashboard-api GET /v1/healthz -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/HealthController.kt)

## Response

```ts
// HealthResponse
{ status: "ok" }
```

[HealthResponse 전체 스키마·중첩 타입](../common.md#schema-HealthResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../common.md)를 함께 적용한다.
