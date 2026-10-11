# 12 GET `/api/v1/organizations/{organizationId}/settings`

설정 첫 화면

[전체 API](../README.md) · [설정 화면](../settings.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/settings -->

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

## Response

```ts
// SettingsResponse
{
  meta: CurrentMeta;
  ingest: Ingest;
  capabilities: SettingsCapabilities;
  summary: SettingsSummary;
  catalog: Catalog;
  vendors: Page<Vendor>;
  collectionPolicy: CollectionPolicy;
  policyRollout: PolicyRollout;
  alertRules: Array<AlertRule>;
}
```

[SettingsResponse 전체 스키마·중첩 타입](../reference/settings.md#schema-SettingsResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/settings.md)를 함께 적용한다.

### JSON 예시

[전체 응답 예시](../examples/settings-response.example.json) — 가상 데이터이며 실서버 응답 캡처가 아니다.
