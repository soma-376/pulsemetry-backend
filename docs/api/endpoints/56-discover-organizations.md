# 56 POST `/v1/auth/organizations`

이메일로 회사 탐색

[전체 API](../README.md) · [로그인·사용자 인증](../auth.md)

<!-- endpoint: enrollment-api POST /v1/auth/organizations -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/LoginDiscoveryController.kt)

## Request

### Headers

```http
Content-Type: application/json
```

### Body

```ts
{
  email: string;
}
```

## Response

```ts
// LoginOrganizations
{ organizations: Array<{ organizationId: string; organizationName: string }> }
```

[LoginOrganizations 전체 스키마·중첩 타입](../reference/auth.md#schema-LoginOrganizations)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/auth.md)를 함께 적용한다.
