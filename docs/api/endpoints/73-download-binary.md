# 73 GET `/bin/{filename}`

바이너리 다운로드

[전체 API](../README.md) · [CLI 설치 등록·배포](../enrollment.md)

<!-- endpoint: enrollment-api GET /bin/{filename} -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/BinaryController.kt)

## Request

### Path

```ts
{
  filename: string;
}
```

## Response

application/octet-stream — 바이너리

Content-Disposition: attachment; filename="...". 허용 파일·릴리스 해시를 검사한다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/enrollment.md)를 함께 적용한다.
