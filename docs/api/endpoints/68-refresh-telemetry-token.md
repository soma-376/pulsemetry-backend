# 68 POST `/api/v1/installations/telemetry-token`

수집 토큰 재발급

[전체 API](../README.md) · [CLI 설치 등록·배포](../enrollment.md)

<!-- endpoint: enrollment-api POST /api/v1/installations/telemetry-token -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/TelemetryTokenController.kt)

## Request

### Headers

```http
Authorization: Bearer <pit_installation_token>
```

## Response

```ts
// TelemetryTokenResponse
{ installation_id: string; telemetry_token: string }
```

[TelemetryTokenResponse 전체 스키마·중첩 타입](../reference/enrollment.md#schema-TelemetryTokenResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/enrollment.md)를 함께 적용한다.
