# 44 POST `/api/v1/organizations/{organizationId}/seat-reclaims/preview`

회수 미리보기

[전체 API](../README.md) · [좌석 원장·회수·복원](../seats.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/seat-reclaims/preview -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

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
  seats: Array<{ seatAssignmentId: string; expectedVersion: number }>; // 1~100, 중복 불가
}
```

## Response

```ts
// ReclaimPreview
{
  previewId: string; expiresAt: string;
  eligibleSeatAssignmentIds: string[];
  rejected: { seatAssignmentId: string; reason: string }[];
  estimatedMonthlySavingsUsd: string | null;
  savingsEffectiveAt: null;
  resultingUnallocatedSeats: number | null;
  savingsBasis: "contract_unit_price" | null;
  targets: { seatAssignmentId: string; vendorId: string; method: "vendor_control" | "admin_action" }[];
}
```

[ReclaimPreview 전체 스키마·중첩 타입](../reference/seats.md#schema-ReclaimPreview)

미리보기 유효기간 5분. 대상·버전은 실행 시 다시 검사한다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/seats.md)를 함께 적용한다.
