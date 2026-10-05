# 알림·알림 규칙

[API 길잡이](README.md) · [공통 규칙](common.md) · [공통 스키마](common-schemas.md)

## 엔드포인트

<a id="endpoint-dashboard-api-16"></a>

### 알림 목록

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/alerts -->

```http
GET /api/v1/organizations/{organizationId}/alerts
```

서버: **dashboard-api** · 성공: **200** · [구현](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/AlertController.kt)

**Path**

```ts
{
  organizationId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

```ts
{
  status?: "unacknowledged" | "acknowledged" | "all"; // 기본 unacknowledged
  category?: "security" | "cost"; // 생략: 전체
  limit?: number; // 기본 20, 정수 1~100
  cursor?: string; // 응답 nextCursor
  snapshotId?: string; // 다음 페이지에 이전 응답 값 유지
}
```

**Body**

본문 없음.

**Response**

```ts
// AlertsResponse
{
  meta: CurrentMeta;
  evaluation: { availability: "available" | "unavailable"; reason: string | null;
    asOf: string | null;
    rules: { ruleId: string; enabled: boolean; evaluatedAt: string | null; status: "evaluated" | "not_evaluated" | "failed" | null;
      reason: string | null; windowStart: string | null; windowEnd: string | null }[] };
  alerts: Page<Alert>;
}
```

[AlertsResponse 전체 스키마·중첩 타입](alerts.md#schema-AlertsResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-dashboard-api-17"></a>

### 알림 상세

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/alerts/{alertId} -->

```http
GET /api/v1/organizations/{organizationId}/alerts/{alertId}
```

서버: **dashboard-api** · 성공: **200** · [구현](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/AlertController.kt)

**Path**

```ts
{
  organizationId: string;
  alertId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

본문 없음.

**Response**

```ts
// AlertResponse
{ meta: CurrentMeta; alert: Alert }
```

[AlertResponse 전체 스키마·중첩 타입](alerts.md#schema-AlertResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-50"></a>

### 알림 규칙 변경

<!-- endpoint: enrollment-api PATCH /api/v1/organizations/{organizationId}/settings/alert-rules/{ruleId} -->

```http
PATCH /api/v1/organizations/{organizationId}/settings/alert-rules/{ruleId}
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/AlertRuleController.kt)

**Path**

```ts
{
  organizationId: string;
  ruleId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
Content-Type: application/json
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

```ts
{
  expectedVersion: number;
  enabled: boolean;
}
```

**Response**

```ts
// AlertRule
{
  ruleId: string;
  version: number;
  enabled: boolean;
  availability: "available" | "partial" | "unavailable";
  reason: string | null;
  threshold: AlertThreshold;
  evaluationWindow: string;
  comparisonWindow: string | null;
}
```

[AlertRule 전체 스키마·중첩 타입](alerts.md#schema-AlertRule)

지원 불가 규칙 활성화는 422 alert_rule_unavailable.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-51"></a>

### 알림 확인

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/alerts/{alertId}/acknowledge -->

```http
POST /api/v1/organizations/{organizationId}/alerts/{alertId}/acknowledge
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/AlertRuleController.kt)

**Path**

```ts
{
  organizationId: string;
  alertId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
Content-Type: application/json
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

```ts
{
  expectedVersion: number;
}
```

**Response**

```ts
// AlertAcknowledgement
{ alertId: string; version: number; acknowledgedAt: string; acknowledgedBy: string }
```

[AlertAcknowledgement 전체 스키마·중첩 타입](alerts.md#schema-AlertAcknowledgement)

Idempotency-Key를 사용하지 않는다. 기존 확인 결과를 유지한다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.


### 알림 규칙 (허브 ADR 0008, ADR 0051의 평가·확인)

PATCH /settings/alert-rules/{ruleId}는 {expectedVersion, enabled}를 받아 현재 규칙을 반환한다. 같은 조직 행을 잠가 직렬화하고 owner·admin만 수정한다.
값이 바뀔 때만 판이 1 오르며, 미저장 규칙은 꺼짐·판 0이다. 같은 값이면 판 그대로다.

규칙 응답은 ruleId·version·enabled·availability·reason·threshold(value, unit)·evaluationWindow·comparisonWindow다.

| 규칙 | 켤 수 있는 조건 | 아니면 reason |
| --- | --- | --- |
| spend_spike | 조직의 설치가 수집 구간을 보고한 적이 있다 | completeness_not_available |
| quota_exceeded | 검증된 관측이 없어 켤 수 없다 | source_not_available |
| product_not_registered | 보관되지 않은 등록 제품이 하나 이상이다 | registered_products_not_configured |

- product_not_registered의 임계값은 1 events, 창은 rolling_24_hours다. 켜진 뒤 등록 제품이 모두 삭제되면 평가를 건너뛰지만 규칙을 끌 수 있다.
- 켤 수 없는 규칙을 켜면 422 alert_rule_unavailable(필드 enabled, details.reason), 판 충돌은 409 version_conflict다.
- model_not_allowed·tool_unapproved는 폐기한 규칙이므로 수정은 404다. PUT /settings/alert-lists/{listId}도 제공하지 않는다.
- 이전 목록·알림·확인 기록은 보존하며 새 제품 규칙으로 자동 변환하지 않는다.

- **알림 확인**(`POST /alerts/{alertId}/acknowledge`): 알림은 dashboard-api 의 평가 기록(`dashboard_cache.alerts`)이고, 이 명령이 그 표를 읽어 그 조직의 임계값에 이른 알림인지 본다
  (아니면 404 `not_found` — UUID 가 아닌 ID 도 같다). `expectedVersion` 은 알림의 지금 판이다(다르면 409 `version_conflict` — 그 사이 묶음이 늘었다).
  이미 확인한 알림은 처음 확인한 기록을 그대로 돌려준다(멱등). 확인을 되돌리는 명령은 없다. 기록은 `enrollment.alert_acknowledgements` 다.


### 알림 규칙 (허브 ADR 0008)

설정의 alertRules는 활성 규칙 spend_spike·quota_exceeded·product_not_registered만 제공한다. 명령과 같은 AlertRules 판정으로 켜짐·판·가용성을 반환한다.
보관되지 않은 managed_vendors.kind가 등록 기준이며, 제품 규칙은 등록이 없으면 unavailable·registered_products_not_configured다.
계약이 만료되거나 계약 상세가 없어도 등록은 유지된다. alertLists는 응답하지 않는다.
product_not_registered는 1 events·rolling_24_hours다. 기존 비용 규칙의 임계값·창과 capabilities.editAlertRules는 그대로다.

### 알림 (ADR 0051 [기존 §5](../dashboard-server-spec.md#5-운영과-로컬-실행)·[기존 §6](../dashboard-server-spec.md#6-검증과-구현-한계))

켜진 규칙을 이 앱의 주기 작업(`pulsemetry.dashboard.alerts.evaluation-interval`)이 조직 단위로 선점해 평가하고, 결과를 RDS `dashboard_cache.alerts`·`alert_evaluations`에
캐시 계정으로 쓴다(snapshot 정리 대상이 아니다). 분석 원본·enrollment 는 읽기만 한다. 확인은 enrollment-api 의 명령이고 기록은 `enrollment.alert_acknowledgements` 다.

| 규칙 | 평가 | 평가하지 않는 사유 |
| --- | --- | --- |
| `spend_spike` | 확정 대기가 지난 날 D 마다 D 로 끝나는 7일 대 그 앞 7일. 개요와 같은 snapshot·같은 환산 비용. 증가율 ≥ 0.4 면 (급증, D) 알림 하나 | `period_incomplete`(두 기간 중 하나가 완전하지 않음), `cost_not_available`, `no_previous_spend`, `period_not_settled` |
| `product_not_registered` | active·mapped 로그의 model.response.usage·primary 행에서 product를 vendor_catalog_observed_products로 매핑한 제품이 조직 등록 밖이면 알림 | 등록이 없으면 registered_products_not_configured. 매핑 없는 product·unknown은 판정 대상에서 제외 |
| `quota_exceeded` | 켤 수 없다(근거 없음) | — |

- 24시간 규칙은 대상(카탈로그 제품 ID)마다 위반을 시간순으로 묶는다. 마지막 위반에서 24시간 안의 위반은 같은 묶음이고, 24시간 조용하면 묶음이 닫힌다(`status` `open`→`closed`).
  묶음의 위반 수가 임계값(1) 이상이면 알림이다. 묶음이 늘면 알림의 `version` 이 오른다. 같은 사건은 다시 평가해도 하나다.
- 평가는 지난 평가가 끝난 시각부터 확정 대기(`completeness.settle-after`) 전까지를 이어서 읽는다. 켠 뒤 처음은 급증은 켠 날의 전날, 24시간 규칙은 켠 시각의 24시간 전부터다.
- 알림에는 무엇(제품 ID·제품명, 급증의 두 기간 비용)·언제·누가(구성원 ID·계정)·몇 건만 싣는다. 본문·마스킹된 값은 싣지 않는다.

새 제품 알림의 subject는 카탈로그 제품 ID이며 summary는 productId·productName·events·threshold다. 공급자·모델로 계약 제품이나 개인 결제를 추정하지 않는다. 이전 규칙의 알림과 확인 이력은 원래 식별자로 유지한다.

**개요 `alerts`** — 조회 기간과 무관한 지금의 미확인 수. 미확인 = 임계값에 이른 알림 중 확인 기록이 없는 것.

| 값 | 규칙 |
| --- | --- |
| `availability`·`reason` | 켠 규칙마다 지금 판의 평가 기록이 있으면 `available`·null. 켠 규칙 가운데 지금 판의 평가 기록이 없는 것이 있으면 `unavailable`·`evaluation_pending` — 첫 평가 회차 도중이나 규칙을 다시 켠 직후도 그 규칙이 평가될 때까지다(평가하지 않은 규칙을 0건으로 읽히게 하지 않는다). 켠 규칙이 없고 평가 기록도 없으면 `unavailable`·`evaluation_not_configured`, 켠 규칙이 없어도 앞의 평가 기록이 있으면 `available` |
| `asOf` | 마지막 평가 시각. 평가 기록이 없으면 응답 시각 |
| `unacknowledgedTotal`·`security`·`cost` | 미확인 수. `security` = 미등록 제품과 이전 모델·도구 알림 이력, `cost` = 급증·한도. `total = security + cost`. unavailable 이면 null |

**목록·단건** (가산 — 조직 분석 권한):


- `status`·`category` 가 허용 밖이면 400. cursor 는 같은 토큰·같은 필터의 것이어야 한다.
- 규칙마다의 `not_evaluated` 는 "0건"이 아니다 — 화면은 사유를 보여 준다.


<a id="schema-AlertsResponse"></a>
<a id="schema-Alert"></a>

```ts
// GET O/alerts?status=unacknowledged(기본)|acknowledged|all&category=security|cost&limit=20(최대 100)&cursor&snapshotId
type AlertsResponse = {
  meta: CurrentMeta;                       // snapshotId 는 현재 상태 토큰
  evaluation: { availability: "available" | "unavailable"; reason: string | null;  // 개요 alerts 와 같은 판정
    asOf: string | null;                   // 마지막 평가 시각. 평가 기록이 없으면 null — evaluation_pending 이어도 앞선 평가가 있으면 그 시각
    rules: { ruleId: string; enabled: boolean; evaluatedAt: string | null; status: "evaluated" | "not_evaluated" | "failed" | null;
      reason: string | null; windowStart: string | null; windowEnd: string | null }[] };
  alerts: Page<Alert>;                     // 최근 발생 순(occurredAt 내림차순)
};
type Alert = {
  alertId: string; version: number;        // 확인 명령의 expectedVersion
  ruleId: string; category: "security" | "cost"; status: "open" | "closed";
  occurredAt: string; lastSeenAt: string; windowStart: string; windowEnd: string;
  subject: string | null; eventCount: number | null; memberCount: number | null;   // 급증은 null
  members: { memberId: string; account: string | null }[];
  summary: Record<string, unknown>;        // 급증: currentStartDate·currentEndDate·previousStartDate·previousEndDate·currentCostUsd·previousCostUsd·increaseRatio·threshold
                                           // 모델·도구: model|tool·events·threshold
  acknowledgement: { acknowledgedAt: string; acknowledgedBy: string } | null;
};
// GET O/alerts/{alertId} → { meta, alert: Alert }. 없거나 다른 조직의 알림은 404
```

## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### AlertThreshold

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-AlertThreshold"></a>

```ts
type AlertThreshold = {
  value: number;
  unit: string;
};
```

### AlertRule

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-AlertRule"></a>

```ts
type AlertRule = {
  ruleId: string;
  version: number;
  enabled: boolean;
  availability: "available" | "partial" | "unavailable";
  reason: string | null;
  threshold: AlertThreshold;
  evaluationWindow: string;
  comparisonWindow: string | null;
};
```


<a id="schema-AlertAcknowledgement"></a>

```ts
type AlertAcknowledgement = { alertId: string; version: number; acknowledgedAt: string; acknowledgedBy: string };
```

규칙 변경 예시:

```json
{"expectedVersion":0,"enabled":true}
```

알림 확인은 별도 멱등 키 저장을 사용하지 않으며 이미 확인한 결과를 반환한다. expectedVersion 검사는 유지한다.


<a id="schema-AlertResponse"></a>

```ts
type AlertResponse = { meta: CurrentMeta; alert: Alert };
```

현재 평가하는 summary 구조는 ruleId로 구분한다. 과거 폐기 규칙의 알림은 기존 summary를 보존한다.

<a id="schema-ProductAlertSummary"></a>
<a id="schema-SpendAlertSummary"></a>

```ts
type ProductAlertSummary = { productId: string; productName: string; events: number; threshold: number };
type SpendAlertSummary = {
  currentStartDate: string; currentEndDate: string; previousStartDate: string; previousEndDate: string;
  currentCostUsd: string; previousCostUsd: string; increaseRatio: number; threshold: number;
};
```

## 연관 스키마

- [CurrentMeta](common-schemas.md#schema-CurrentMeta)
- [Page](common-schemas.md#schema-Page)

## 직렬화·검증 근거

- [AlertService.kt](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/alert/AlertService.kt)
- [AlertEvaluator.kt](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/alert/AlertEvaluator.kt)
