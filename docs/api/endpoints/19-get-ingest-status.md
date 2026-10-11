# 19 GET `/api/v1/organizations/{organizationId}/ingest-status`

현재 수집 상태

[전체 API](../README.md) · [수집 상태](../ingest-status.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/ingest-status -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/IngestStatusController.kt)

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

## Response

```ts
// IngestStatusResponse
{
  organizationId: string; status: "empty" | "healthy" | "delayed" | "down" | "unknown";
  reason: string | null; asOf: string; lastReceivedAt: string | null; windowMinutes: number;
  activeInstallations: number | null; observedMembers: number | null; eligibleMembers: number | null;
  coverageRatio: number | null; coverageTargetMembers: number | null; coverageObservedMembers: number | null;
}
```

[IngestStatusResponse 전체 스키마·중첩 타입](../reference/ingest-status.md#schema-IngestStatusResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/ingest-status.md)를 함께 적용한다.
