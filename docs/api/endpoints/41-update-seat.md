# 41 PATCH `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/{seatId}`

좌석 정보 보정

[전체 API](../README.md) · [좌석 원장·회수·복원](../seats.md)

<!-- endpoint: enrollment-api PATCH /api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/{seatId} -->

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
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Body

```ts
{
  expectedVersion: number;
  memberId?: string | null;
  memberLink?: "automatic"; // memberId와 함께 보내지 않음
  tierId?: string | null;
  note?: string | null;
}
```

## Response

```ts
// SeatSaved
{ seat: Seat; warnings: "exceeds_contracted_seats"[]; provisional: boolean }
```

[SeatSaved 전체 스키마·중첩 타입](../reference/seats.md#schema-SeatSaved)

ETag: "seat-{version}". 상태·원천은 유지한다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/seats.md)를 함께 적용한다.
