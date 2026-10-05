# 69 GET `/v1/manifest`

manifest 재조회

[전체 API](../README.md) · [CLI 설치 등록·배포](../enrollment.md)

<!-- endpoint: enrollment-api GET /v1/manifest -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/ManifestResyncController.kt)

## Request

### Headers

```http
Authorization: Bearer <urt_refresh_token>
```

권한: 유효한 사용자 RT. 요청이 RT를 소비·회전하므로 캐시·프리페치·자동 재시도를 금지한다.

## Response

```ts
// ManifestResyncResponse
{
  manifest: ManifestPayload; access_token: string; refresh_token: string; token_type: "Bearer"; expires_in: number;
}
```

[ManifestResyncResponse 전체 스키마·중첩 타입](../reference/enrollment.md#schema-ManifestResyncResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/enrollment.md)를 함께 적용한다.
