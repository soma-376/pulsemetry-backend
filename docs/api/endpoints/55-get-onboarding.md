# 55 GET `/api/v1/organizations/{organizationId}/onboarding`

온보딩 상태

[전체 API](../README.md) · [최초 온보딩](../onboarding.md)

<!-- endpoint: enrollment-api GET /api/v1/organizations/{organizationId}/onboarding -->

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
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

## Response

```ts
// OnboardingState
{
  organizationId: string;
  completed: boolean;
  completedAt: string | null;
  policy: {
    confirmed: boolean; confirmedAt: string | null;
    version: number; collectRawContent: boolean | null;
  };
  selectedVendorCount: number;
  canComplete: boolean;
  nextStep: "collection" | "vendors" | "team" | "complete";
}
```

[OnboardingState 전체 스키마·중첩 타입](../reference/onboarding.md#schema-OnboardingState)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/onboarding.md)를 함께 적용한다.
