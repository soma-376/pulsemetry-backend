# 46 POST `/api/v1/organizations/{organizationId}/seat-reclaims/{operationId}/restore`

회수 복원 접수

[전체 API](../README.md) · [좌석 원장·회수·복원](../seats.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/seat-reclaims/{operationId}/restore -->

서버: **enrollment-api** · 성공: **202** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

## Request

### Path

```ts
{
  organizationId: string;
  operationId: string;
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

Location 반환. 완료된 회수의 복원 가능 여부와 기한을 검사한다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/seats.md)를 함께 적용한다.
