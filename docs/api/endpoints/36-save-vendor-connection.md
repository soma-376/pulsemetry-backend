# 36 PUT `/api/v1/organizations/{organizationId}/vendors/{vendorId}/connection`

연결 저장

[전체 API](../README.md) · [벤더 연결·동기화](../vendor-connections.md)

<!-- endpoint: enrollment-api PUT /api/v1/organizations/{organizationId}/vendors/{vendorId}/connection -->

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
Content-Type: application/json
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Body

```ts
{
  expectedVersion: number; // 최초 0
  settings: Record<string, string>;
  credential: string;
}
```

## Response

```ts
// ConnectionResponse
{ seatSource: SeatSource }
```

[ConnectionResponse 전체 스키마·중첩 타입](../reference/vendor-connections.md#schema-ConnectionResponse)

ETag: "connection-{version}". credential 원문을 응답하지 않는다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/vendor-connections.md)를 함께 적용한다.
