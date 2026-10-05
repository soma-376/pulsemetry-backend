# 67 POST `/v1/enroll`

설치 등록

[전체 API](../README.md) · [CLI 설치 등록·배포](../enrollment.md)

<!-- endpoint: enrollment-api POST /v1/enroll -->

서버: **enrollment-api** · 성공: **201** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/EnrollmentController.kt)

## Request

### Headers

```http
Content-Type: application/json
```

### Body

```ts
{
  code?: string | null; // code 또는 호환 invite에 유효한 코드 필수
  platform?: string | null;
  architecture?: string | null;
  hostname?: string | null;
  client_version?: string | null;
  invite?: string | null; // deprecated
  installer_version?: string | null; // deprecated
  operating_environment?: string | null; // deprecated
  device_id?: string | null; // deprecated, 무시
  tools_detected?: string[] | null; // deprecated, 무시
  platform?: string | null;
  architecture?: string | null;
  hostname?: string | null;
  client_version?: string | null;
}
```

## Response

```ts
// EnrollmentResponse
{
  installation_id: string; installation_token: string; telemetry_token: string; manifest: ManifestPayload;
}
```

[EnrollmentResponse 전체 스키마·중첩 타입](../reference/enrollment.md#schema-EnrollmentResponse)

구버전 invite 등 호환 필드와 검증은 [설치 등록 규칙](../reference/enrollment.md)을 참고한다. 최상위 응답은 정확히 4키.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/enrollment.md)를 함께 적용한다.
