# 32 PATCH `/api/v1/organizations/{organizationId}/vendors/{vendorId}`

제품 이름 변경

[전체 API](../README.md) · [등록 제품·벤더 계약](../vendors.md)

<!-- endpoint: enrollment-api PATCH /api/v1/organizations/{organizationId}/vendors/{vendorId} -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

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
Content-Type: application/json
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Body

```ts
{
  expectedVersion: number;
  displayName: string;
}
```

## Response

```ts
// VendorSavedResponse
{
  meta: CurrentMeta;
  vendor: {
    vendorId: string; displayName: string; kind: string; source: string; version: number;
    firstSeenAt: null; lastSeenAt: null; activeUsers7d: null; activeUsers30d: null;
    observation: "unobserved"; state: "configured" | "needs_review";
    contractStatus: ContractStatus; contract: VendorContract | null;
    meteredMonthToDate: { availability: "unavailable"; reason: "source_not_available"; data: null };
    checks: []; seatSource: SeatSource;
  };
}
```

[VendorSavedResponse 전체 스키마·중첩 타입](../reference/vendors.md#schema-VendorSavedResponse)

ETag: "vendor-{version}". 계약은 유지한다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/vendors.md)를 함께 적용한다.
