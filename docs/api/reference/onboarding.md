# 최초 온보딩 — 기능 규칙·공유 스키마

[API 목록](../onboarding.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

## 온보딩

[기존 §12](../../enrollment-server-spec.md#12-조직-관리-api)와 같은 조직 경로·Bearer 인증·관리 기능 설정을 사용한다.
필수 조건은 **수집 여부를 명시적으로 저장 + 활성 벤더 하나 이상 등록**이다.
플랜·좌석·단가는 선택이며 팀·초대도 건너뛸 수 있다.
기본 manifest의 수집=false만으로 관리자가 선택을 완료했다고 간주하지 않는다.
초안·현재 단계는 저장하지 않는다. 저장한 정책·벤더·팀·초대는 남고, 조회 결과로 재개 단계를 결정한다.

| 메서드·경로 | 요청 | 성공 |
| --- | --- | --- |
| `GET /onboarding` | 없음 | 200 OnboardingState |
| `PUT /collection-policy` | `{expectedVersion,collectRawContent?,reclaimIdleDays?,aggregateRetentionMonths?,expectedSettingsVersion?}` | 200 PolicySaved |
| `POST /onboarding/complete` | `{}`, Idempotency-Key | 200 OnboardingState |
| `GET /invitations` | limit=20(1~100), cursor, status?, memberStatus? | 200 InvitationPage |
| `POST /invitations/{invitationId}/reissue` | `{}`, Idempotency-Key | 200 ReissuedInvitation |

### 상태와 완료

온보딩 페이지 진입 시 `GET /onboarding`을 호출한다.

```ts
// 쿼리 파라미터 없음
{}
```

응답(200):

<a id="schema-OnboardingState"></a>

```ts
type OnboardingState = {
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
};
```

필수 조건을 모두 충족한 뒤 `POST /onboarding/complete`를 호출한다.

```jsonc
// 요청 본문
{}
```

응답(200)은 위 `OnboardingState`와 같은 형태이며, `completed=true`와 최초 `completedAt`을 반환한다.

초기 manifest가 없으면 policy.version=0, collectRawContent=null이다.
프롬프트와 응답 플래그가 서로 달라도 collectRawContent=null로 반환해 다시 선택하게 한다.
confirmed는 저장 완료 여부이며 수집 허용 여부가 아니다. false를 저장해도 confirmed=true다.
벤더 수는 직접 등록한 활성 벤더만 센다. 관측으로 발견된 공급자만으로 선택 완료 처리하지 않는다.
완료 전 필수 조건이 부족하면 409 `onboarding_incomplete`다. 완료 후 재호출은 기존 완료 시각을 유지한다.
완료 시각은 `tenants.onboarding_completed_at`에 저장하고 `tenants.onboarding_completed`는 시각의 유무를 계산하는 생성 컬럼이다.
완료는 과거 완료 사실이다. 이후 모든 벤더를 제거해도 완료 시각을 지우지 않으며 canComplete는 현재 조건을 나타낸다.

호출 흐름:

1. 로그인 → `/api/v1/auth/me`로 조직 확인 → `GET /onboarding`.
2. `PUT /collection-policy`로 선택 저장.
3. dashboard 카탈로그에서 제품 선택 → `POST /vendors`에 kind·displayName만 보내도 등록 가능.
4. 팀·초대는 원하는 경우 저장.
5. `POST /onboarding/complete` 성공 후 개요 이동.



## JSON 응답 예시 — OnboardingState

```json
{
  "organizationId": "10000000-0000-0000-0000-000000000001",
  "completed": false,
  "completedAt": null,
  "policy": {
    "confirmed": false,
    "confirmedAt": null,
    "version": 0,
    "collectRawContent": null
  },
  "selectedVendorCount": 0,
  "canComplete": false,
  "nextStep": "collection"
}
```
