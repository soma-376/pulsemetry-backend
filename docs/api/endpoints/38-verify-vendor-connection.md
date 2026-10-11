# 38 POST `/api/v1/organizations/{organizationId}/vendors/{vendorId}/connection/verify`

연결 검증

[전체 API](../README.md) · [벤더 연결·동기화](../vendor-connections.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/vendors/{vendorId}/connection/verify -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/VendorConnectionController.kt)

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

## Response

```ts
// ConnectionResponse
{ seatSource: SeatSource }
```

[ConnectionResponse 전체 스키마·중첩 타입](../reference/vendor-connections.md#schema-ConnectionResponse)

Idempotency-Key를 요구하지 않는다. check.status로 검증 결과를 판단한다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/vendor-connections.md)를 함께 적용한다.
