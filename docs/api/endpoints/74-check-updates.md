# 74 GET `/api/v1/check-updates`

데몬 업데이트 확인

[전체 API](../README.md) · [CLI 설치 등록·배포](../enrollment.md)

<!-- endpoint: enrollment-api GET /api/v1/check-updates -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/update/DaemonUpdateCheck.kt)

## Request

### Query

```ts
{
  current_version: string; // v 접두사 없는 SemVer
  platform: string;
  architecture: string;
}
```

## Response

```ts
// UpdateCheckResponse
{ latest_version: string; update_available: boolean }
```

[UpdateCheckResponse 전체 스키마·중첩 타입](../reference/enrollment.md#schema-UpdateCheckResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/enrollment.md)를 함께 적용한다.
