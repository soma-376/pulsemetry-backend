# 62 POST `/v1/auth/logout`

서비스 로그아웃

[전체 API](../README.md) · [로그인·사용자 인증](../auth.md)

<!-- endpoint: enrollment-api POST /v1/auth/logout -->

서버: **enrollment-api** · 성공: **204** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/UserAuthController.kt)

## Request

### Headers

```http
Content-Type: application/json
```

### Body

```ts
{
  refresh_token: string;
}
```

## Response

본문 없음.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/auth.md)를 함께 적용한다.
