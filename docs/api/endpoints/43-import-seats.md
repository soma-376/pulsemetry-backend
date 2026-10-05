# 43 POST `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/import`

좌석 CSV 가져오기

[전체 API](../README.md) · [좌석 원장·회수·복원](../seats.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/import -->

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
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Body

```ts
{
  mode: "preview" | "apply";
  csv: string; // UTF-8 CSV 내용, 파일 업로드가 아닌 JSON 문자열
}
```

## Response

```ts
// SeatImportResponse
{ import: SeatImport; provisional: boolean }
```

[SeatImportResponse 전체 스키마·중첩 타입](../reference/seats.md#schema-SeatImportResponse)

preview도 Idempotency-Key 필수. apply 행 오류는 422와 details.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/seats.md)를 함께 적용한다.
