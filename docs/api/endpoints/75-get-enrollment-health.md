# 75 GET `/v1/healthz`

생존 확인

[전체 API](../README.md) · [공통 HTTP 규칙](../common.md)

<!-- endpoint: enrollment-api GET /v1/healthz -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/HealthController.kt)

## Response

```ts
// EnrollmentHealthResponse
{ status: "ok" | "degraded"; checks: { database: "ok" | "down" } }
```

[EnrollmentHealthResponse 전체 스키마](../common.md#schema-EnrollmentHealthResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../common.md)를 함께 적용한다.

DB 확인 실패는 503 + status=degraded, checks.database=down이다. Dashboard 생존 확인과 본문이 다르다.
