# 47 POST `/api/v1/organizations/{organizationId}/operations/{operationId}/targets/{targetId}/confirm`

관리자 조치 확인

[전체 API](../README.md) · [비동기 작업](../operations.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/operations/{operationId}/targets/{targetId}/confirm -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

## Request

### Path

```ts
{
  organizationId: string;
  operationId: string;
  targetId: string;
}
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

## Response

```ts
// OperationResponse
{
  operationId: string;
  kind: "seat_reclaim" | "seat_restore" | "installation_notification" | "retention_cleanup" | "seat_sync";
  status: "pending" | "running" | "awaiting_admin_action" | "succeeded" | "partially_failed" | "failed";
  createdAt: string;
  completedAt: string | null;
  results: {
    targetId: string;
    status: "pending" | "awaiting_admin_action" | "succeeded" | "failed";
    reason: string | null;
    action: string | null;
  }[];
  canRestore: boolean;
  restoreUntil: string | null;
  retention: {
    status: "running" | "incomplete" | "logically_deleted" | "failed";
    requestedBefore: string; deletedBefore: string | null;
    startedAt: string; finishedAt: string | null;
  } | null;
}
```

[OperationResponse 전체 스키마·중첩 타입](../reference/operations.md#schema-OperationResponse)

요청한 관리자가 외부 조치를 수행한 뒤 확인한다. targetId는 좌석 ID.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/operations.md)를 함께 적용한다.
