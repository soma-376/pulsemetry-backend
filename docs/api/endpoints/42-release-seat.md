# 42 POST `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/{seatId}/release`

수동 좌석 해제

[전체 API](../README.md) · [좌석 원장·회수·복원](../seats.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/{seatId}/release -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

## Request

### Path

```ts
{
  organizationId: string;
  vendorId: string;
  seatId: string;
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
  expectedVersion: number;
}
```

## Response

```ts
// SeatSaved
{ seat: Seat; warnings: "exceeds_contracted_seats"[]; provisional: boolean }
```

[SeatSaved 전체 스키마·중첩 타입](../reference/seats.md#schema-SeatSaved)

ETag: "seat-{version}". assigned 상태만 가능.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/seats.md)를 함께 적용한다.
