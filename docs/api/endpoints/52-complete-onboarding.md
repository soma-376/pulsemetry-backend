# 52 POST `/api/v1/organizations/{organizationId}/onboarding/complete`

온보딩 완료

[전체 API](../README.md) · [최초 온보딩](../onboarding.md)

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/onboarding/complete -->

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
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
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

필수 조건 미충족은 409 onboarding_incomplete.

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/onboarding.md)를 함께 적용한다.
