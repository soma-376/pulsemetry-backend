# 34 DELETE `/api/v1/organizations/{organizationId}/vendors/{vendorId}/contract`

계약 제거

[전체 API](../README.md) · [등록 제품·벤더 계약](../vendors.md)

<!-- endpoint: enrollment-api DELETE /api/v1/organizations/{organizationId}/vendors/{vendorId}/contract -->

서버: **enrollment-api** · 성공: **204** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

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
If-Match: "vendor-{version}"
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

## Response

본문 없음.

If-Match: "vendor-{version}" 필수.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/vendors.md)를 함께 적용한다.
