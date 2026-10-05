# 53 PUT `/api/v1/organizations/{organizationId}/collection-policy`

수집·조직 정책 저장

[전체 API](../README.md) · [수집 정책·조직 정책 설정](../collection-policy.md)

<!-- endpoint: enrollment-api PUT /api/v1/organizations/{organizationId}/collection-policy -->

서버: **enrollment-api** · 성공: **200** · [구현](../../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

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
Content-Type: application/json
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Body

```ts
{
  expectedVersion: number; // 현재 manifest 판, 최초 0
  collectRawContent?: boolean;
  expectedSettingsVersion?: number; // 아래 두 설정 중 하나를 보내면 필수
  reclaimIdleDays?: 7 | 14 | 30 | 60;
  aggregateRetentionMonths?: 12 | 24 | 36 | null; // null 무기한
}
```

## Response

```ts
// PolicySaved
{
  version: number; collectRawContent: boolean | null; confirmedAt: string | null;
  application: "future_enrollments";
  existingInstallationsUpdated: false;

  reclaimIdleDays: 7 | 14 | 30 | 60 | null;
  aggregateRetentionMonths: 12 | 24 | 36 | null;
  settingsVersion: number;
  settingsUpdatedAt: string | null;
  cleanupOperationId: string | null;
}
```

[PolicySaved 전체 스키마·중첩 타입](../reference/collection-policy.md#schema-PolicySaved)

보낸 필드만 변경. 기존 설치로 원격 적용하지 않는다.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/collection-policy.md)를 함께 적용한다.
