# 71 GET `/windows`

설치 스크립트

[전체 API](../README.md) · [CLI 설치 등록·배포](../enrollment.md)

<!-- endpoint: enrollment-api GET /windows -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/BootstrapController.kt)

## Request

### Query

```ts
{
  code: string;
}
```

## Response

text/plain;charset=UTF-8 — 설치 스크립트

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/enrollment.md)를 함께 적용한다.
