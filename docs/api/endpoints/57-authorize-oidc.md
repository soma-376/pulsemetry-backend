# 57 GET `/api/v1/auth/oidc/authorize`

SSO 시작

[전체 API](../README.md) · [로그인·사용자 인증](../auth.md)

<!-- endpoint: enrollment-api GET /api/v1/auth/oidc/authorize -->

서버: **enrollment-api** · 성공: **302** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/OidcLoginConfig.kt)

## Request

### Query

```ts
{
  tenant_id: string;
  redirect_uri: string;
  state: string;
  code_challenge: string;
  code_challenge_method: "S256";
  login_hint?: string;
}
```

## Response

리다이렉트: Location + OIDC 임시 쿠키, 본문 없음

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/auth.md)를 함께 적용한다.
