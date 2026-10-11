# 20 GET `/api/v1/vendor-catalog`

카탈로그 검색

[전체 API](../README.md) · [벤더·플랜 카탈로그](../vendor-catalog.md)

<!-- endpoint: dashboard-api GET /api/v1/vendor-catalog -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/VendorCatalogController.kt)

## Request

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Query

```ts
{
  q?: string; // 최대 200자
  limit?: number; // 기본 20, 정수 1~100
  cursor?: string; // 응답 nextCursor
}
```

## Response

```ts
// VendorCatalogResponse
{ catalogVersion: string; items: CatalogVendor[]; totalCount: number; nextCursor: string | null }
```

[VendorCatalogResponse 전체 스키마·중첩 타입](../reference/vendor-catalog.md#schema-VendorCatalogResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/vendor-catalog.md)를 함께 적용한다.
