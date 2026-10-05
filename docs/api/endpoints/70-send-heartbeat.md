# 70 POST `/api/v1/installations/{installationId}/heartbeat`

설치 보고

[전체 API](../README.md) · [CLI 설치 등록·배포](../enrollment.md)

<!-- endpoint: enrollment-api POST /api/v1/installations/{installationId}/heartbeat -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/installation/InstallationHeartbeat.kt)

## Request

### Path

```ts
{
  installationId: string;
}
```

### Headers

```http
Authorization: Bearer <pit_installation_token>
Content-Type: application/json
```

### Body

```ts
{
  sent_at: string;
  daemon: { version: string; platform: "darwin" | "linux" | "windows"; architecture: string; run_id: string };
  applied_config_revision: number;
  collection: { mode: "local" | "direct"; receiving_since: string | null; forwarding: boolean; delivered: number; lost: number; pending: number; last_delivered_at: string | null };
}
```

## Response

```ts
// HeartbeatResponse
{
  received_at: string; expected_config_revision: number | null;
  acknowledged_config_revision: number | null; report_interval_seconds: number;
}
```

[HeartbeatResponse 전체 스키마·중첩 타입](../reference/enrollment.md#schema-HeartbeatResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/enrollment.md)를 함께 적용한다.
