# 66 POST `/v1/inquiries`

도입 문의 접수

[전체 API](../README.md) · [도입 문의](../inquiries.md)

<!-- endpoint: enrollment-api POST /v1/inquiries -->

서버: **enrollment-api** · 성공: **201** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/inquiry/InquiryController.kt)

## Request

### Headers

```http
Content-Type: application/json
```

### Body

```ts
{
  company: string; // trim 후 1~100자
  email: string;
}
```

## Response

```ts
// InquiryReceipt
{ inquiryId: string; status: "received"; receivedAt: string }
```

[InquiryReceipt 전체 스키마·중첩 타입](../reference/inquiries.md#schema-InquiryReceipt)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/inquiries.md)를 함께 적용한다.
