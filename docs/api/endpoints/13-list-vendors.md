# 13 GET `/api/v1/organizations/{organizationId}/vendors`

등록 제품 목록

[전체 API](../README.md) · [등록 제품·벤더 계약](../vendors.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/vendors -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/SettingsController.kt)

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

### Query

```ts
{
  limit?: number; // 기본 20, 정수 1~100
  cursor?: string; // 응답 nextCursor
  snapshotId?: string; // 다음 페이지에 이전 응답 값 유지
}
```

## Response

```ts
// VendorsResponse
{
  meta: CurrentMeta;
  vendors: Page<Vendor>;
}
```

[VendorsResponse 전체 스키마·중첩 타입](../reference/vendors.md#schema-VendorsResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/vendors.md)를 함께 적용한다.
