# 63 GET `/v1/auth/me`

현재 사용자

[전체 API](../README.md) · [로그인·사용자 인증](../auth.md)

<!-- endpoint: enrollment-api GET /v1/auth/me -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/CurrentUserController.kt)

## Request

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: 유효한 서비스 사용자 세션. 엔드포인트별 소유권 검사는 기능 규칙을 따른다.

## Response

```ts
// CurrentUser
{
  memberId: string; organizationId: string; organizationName: string;
  email: string; displayName: string; role: "admin" | "member";
}
```

[CurrentUser 전체 스키마·중첩 타입](../reference/auth.md#schema-CurrentUser)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/auth.md)를 함께 적용한다.
