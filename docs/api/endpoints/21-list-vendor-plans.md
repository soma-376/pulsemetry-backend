# 21 GET `/api/v1/vendor-catalog/{vendorId}/plans`

제품 플랜 조회

[전체 API](../README.md) · [벤더·플랜 카탈로그](../vendor-catalog.md)

<!-- endpoint: dashboard-api GET /api/v1/vendor-catalog/{vendorId}/plans -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/VendorCatalogController.kt)

## Request

### Path

```ts
{
  vendorId: string;
}
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

## Response

```ts
// VendorPlansResponse
{
  catalogVersion: string; vendor: CatalogVendor;
  plans: Array<{ id: string; displayName: string; billing: string; separateUsageBilling: boolean }>;
}
```

[VendorPlansResponse 전체 스키마·중첩 타입](../reference/vendor-catalog.md#schema-VendorPlansResponse)

vendorId는 등록 UUID가 아니라 카탈로그 제품 ID다(예: claude_team).

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/vendor-catalog.md)를 함께 적용한다.
