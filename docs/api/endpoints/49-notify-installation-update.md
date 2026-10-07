# 49 POST `/api/v1/organizations/{organizationId}/installation-update-notifications`

설치 업데이트 안내 접수

[전체 API](../README.md) · [설치 현황·업데이트 안내](../installations.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/installation-update-notifications -->

서버: **enrollment-api** · 성공: **202** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

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
Content-Type: application/json
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Body

```ts
{
  installationIds: string[]; // UUID 1~100, 중복 불가
  expectedPolicyVersion: number; // 현재 manifest 판, 1 이상
}
```

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

메일 기능 필요. Location 반환. 메일 전송 결과와 정책 적용은 별개다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/installations.md)를 함께 적용한다.
