# 58 GET `/v1/auth/oidc/callback/{registrationId}`

IdP 콜백

[전체 API](../README.md) · [로그인·사용자 인증](../auth.md)

<!-- endpoint: enrollment-api GET /v1/auth/oidc/callback/{registrationId} -->

서버: **enrollment-api** · 성공: **302** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/OidcLoginConfig.kt)

## Request

### Path

```ts
{
  registrationId: string;
}
```

### Headers

```http
Cookie: PULSEMETRY_OIDC=<임시 세션>
```

### Query

```ts
{
  code?: string;
  state: string;
  error?: string; // 취소·실패 시 code 대신 반환
}
```

## Response

리다이렉트: code/state 또는 error/state, 본문 없음

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/auth.md)를 함께 적용한다.
