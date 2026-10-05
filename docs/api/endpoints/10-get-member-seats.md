# 10 GET `/api/v1/organizations/{organizationId}/members/{memberId}/seats`

구성원 좌석

[전체 API](../README.md) · [좌석 원장·회수·복원](../seats.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/members/{memberId}/seats -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/MembersController.kt)

## Request

### Path

```ts
{
  organizationId: string;
  memberId: string;
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
  snapshotId?: string; // 동일 조회 시점 유지
}
```

## Response

```ts
// MemberSeatsResponse
{
  meta: CurrentMeta;
  memberId: string;
  policy: IdlePolicy;
  seats: Array<MemberSeat>;
}
```

[MemberSeatsResponse 전체 스키마·중첩 타입](../reference/seats.md#schema-MemberSeatsResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/seats.md)를 함께 적용한다.
