# 15 GET `/api/v1/organizations/{organizationId}/installations`

설치 현황

[전체 API](../README.md) · [설치 현황·업데이트 안내](../installations.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/installations -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/SettingsController.kt)

## Request

### Path

```ts
{
  organizationId: string;
}
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Query

```ts
{
  policyStatus?: "applied" | "outdated" | "unknown"; // 생략: 전체
  limit?: number; // 기본 20, 정수 1~100
  cursor?: string; // 응답 nextCursor
  snapshotId?: string; // 다음 페이지에 이전 응답 값 유지
}
```

## Response

```ts
// InstallationsResponse
{
  meta: CurrentMeta;
  desiredPolicyVersion: number;
  installations: Page<InstallationRow>;
}
```

[InstallationsResponse 전체 스키마·중첩 타입](../reference/installations.md#schema-InstallationsResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/installations.md)를 함께 적용한다.
