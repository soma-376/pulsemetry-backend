# 11 GET `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats`

제품 좌석

[전체 API](../README.md) · [좌석 원장·회수·복원](../seats.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/vendors/{vendorId}/seats -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/MembersController.kt)

## Request

### Path

```ts
{
  organizationId: string;
  vendorId: string;
}
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Query

```ts
{
  limit?: number; // 기본 50, 정수 1~200
  cursor?: string; // 응답 nextCursor
  snapshotId?: string; // 다음 페이지에 이전 응답 값 유지
}
```

## Response

```ts
// VendorSeatsResponse
{
  meta: CurrentMeta;
  vendorId: string;
  ledgerAvailability: string;
  ledgerReason: string | null;
  seats: Page<VendorSeatItem>;
}
```

[VendorSeatsResponse 전체 스키마·중첩 타입](../reference/seats.md#schema-VendorSeatsResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/seats.md)를 함께 적용한다.
