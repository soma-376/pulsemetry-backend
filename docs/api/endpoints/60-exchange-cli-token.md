# 60 POST `/api/v1/auth/cli/token`

서비스 토큰 교환

[전체 API](../README.md) · [로그인·사용자 인증](../auth.md)

<!-- endpoint: enrollment-api POST /api/v1/auth/cli/token -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/UserAuthController.kt)

## Request

### Headers

```http
Content-Type: application/json
```

### Body

```ts
{
  code: string;
  redirect_uri: string;
  code_verifier: string;
}
```

## Response

```ts
// UserTokens
{ access_token: string; refresh_token: string; token_type: "Bearer"; expires_in: number }
```

[UserTokens 전체 스키마·중첩 타입](../reference/auth.md#schema-UserTokens)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/auth.md)를 함께 적용한다.
