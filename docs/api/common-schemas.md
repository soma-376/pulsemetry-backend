# 공통 응답 스키마

[API 길잡이](README.md) · [공통 규칙](common.md) · [공통 스키마](common-schemas.md)


### 제품별 사용 (ADR 0045)

개요·팀 목록·팀 상세에 제품별 사용을 **가산**으로 더했다. 모두 현재 기간이고 같은 snapshot 의 같은 계산기에서 나온다.
관측 제품은 snapshot build 때 복제한 명시 매핑(`dashboard_cache.snapshot_products`, ADR 0044)으로만 카탈로그 제품에 잇는다.


- 순서는 카탈로그 순서이고 매핑 없는 관측(`kind = null`)은 끝이다. 사용량 행이 없는 제품은 넣지 않는다.
- 값은 사용량 null 규칙 그대로다 — 세션 없는 행이 있으면 `sessionCount` null, 의미 프로파일이 섞이면 `totalTokens` null, 단가 없는 행이 있으면 금액 null.
  토큰은 제품 안에서만 더하므로 팀 합계의 토큰이 없어도 제품별 토큰은 있을 수 있다.
- 제품 금액이 모두 있으면 제품 금액의 합은 그 범위(조직·팀)의 금액과 같다.
- `productUsage`: 제품이 없으면 `unavailable`, 금액·토큰이 모두 있으면 `available`, 아니면 `partial`.
- 관측 인원은 좌석 수가 아니다. 좌석 값은 여기에 없다.
- 판정 규칙 판은 이 결정 때 `dashboard-v3`였고(매핑 복제를 더했다) 지금은 `dashboard-v4`다("팀 누적 세션" 절) — 이전 판의 snapshot ID 는 409 `snapshot_expired`다.

### 현재 데이터의 해석

- 완전성의 근거(설치 보고의 수집 구간)가 기간 전체를 덮지 않으면 사용량은 `partial`이고 비교는 `unavailable`이다. 비교 없음은 `disabled`다(아래 "기간 완전성과 비교").
- 서로 다른 토큰 의미 프로파일을 섞거나 필수 토큰 값이 누락되면 합계가 null일 수 있다.
- 환산 비용과 실제 청구액은 별개다. 개요·팀의 실제 청구액은 인보이스 원천이 없어 null이다. 설정의 종량 지출은 벤더 청구 누계(ADR 0050, [기존 §7.2](../dashboard-server-spec.md#72-상태합계미제공-값))다.
- 최근 수신만으로 수집 정상·장애를 확정하지 않는다. unknown/empty를 정상으로 바꾸지 않는다.
- 좌석은 좌석 원장(ADR 0048)의 값이다 — 계약의 구매 수량이 아니다. 원장은 기준 시각으로 다시 세우고 제품 단위로 가용성을 낸다(아래 "좌석 원장 조회"). 회수·복원은 enrollment-api의 명령이다(ADR 0049).
- 기존 설치로의 정책 배포는 capability=false 다. 알림은 켜진 규칙을 이 앱의 주기 작업이 평가한 결과다(아래 "알림"). 기존 설치는 새 정책을 서버가 밀어 넣지 않고
  설치 보고의 응답으로 알고 스스로 받는다(enrollment 명세 §4.5). 관리자가 할 수 있는 것은 아래 "정책 적용 현황과 업데이트 안내"의 확인 요청 메일뿐이다.


### 기간 완전성과 비교 (ADR 0042)

하루(조회 시간대의 자정~다음 자정)는 다음을 모두 만족할 때만 **완전**하다. 확정 시각 = 그날의 끝 + `pulsemetry.dashboard.completeness.settle-after`.

1. 확정 시각이 snapshot 기준 시각 이전이다.
2. 삭제 경계보다 앞선 부분이 없다.
3. 그날 수집해야 했던 설치(그날 끝나기 전에 등록, 시작 전에 폐기되지 않음)가 하나 이상 있다.
4. 그 설치 모두가 등록한 때(그날 안이면 그때)부터 확정 시각까지 **손실 없는 수집 구간**(enrollment 명세 §4.5)으로 빈틈없이 덮였다.
   프로세스가 바뀐 틈·손실 구간·보고가 없는 설치(회사 직결·보고하지 않는 데몬)는 덮지 않는다.
5. 그날부터 확정 시각 사이에 폐기된 설치가 없다.
6. 그날 효력이 있던 수집 정책 판이 모두 `signals.logs`를 수집한다(정책이 없던 날은 완전하지 않다).

판정은 snapshot build 때 한 번 하고 `dashboard_cache.snapshot_complete_days`에 고정한다. 같은 snapshot 의 목록·상세·사용자는 같은 판정을 쓴다.
판정 규칙 판은 이 결정 때 `dashboard-v2`였고 지금은 `dashboard-v4`다("팀 누적 세션" 절) — 이전 판의 snapshot ID 는 409 `snapshot_expired`다.

| 필드 | 규칙 |
| --- | --- |
| `Coverage.status` | 기간의 모든 날짜가 완전하면 `complete`, 관측이 있거나 완전한 날짜가 하나라도 있으면 `partial`, 아니면 `none` |
| `Coverage.observedDays` | 관측이 있거나 완전한 날짜 수. 이 숫자만으로 완전성을 판정하지 않는다 |
| `meta.dataState` | 현재 기간이 `complete`면 `ready`. 그 밖은 기존 규칙(`partial`·`no_data`·`never_observed`) |
| `comparison.status` | 두 기간이 **모두** `complete`일 때만 `available`. 아니면 `unavailable`(`source_not_available`)이고 이전 값은 모두 null |
| `meta.dataThrough` | 현재 기간의 첫날부터 끊김 없이 이어진 완전한 날짜의 마지막 날의 끝(다음 날 자정, UTC). 첫날이 완전하지 않으면 null |
| 일별 추이 `observation` | 완전한 날 `complete`, 관측이 있으나 완전하지 않은 날 `partial`, 그 밖 `unobserved`(값 null) |

- **완전한 기간·날짜에 사용이 없으면 null이 아니라 0이다** — 조직 사용량(`activeUsers`·`sessionCount`·토큰·금액), 팀·미배정의 기간 값,
  대상 팀이 있을 때의 나머지 팀 금액, 일별 추이. 사용이 있으면 기존 null 규칙(의미가 섞인 토큰·가격이 없는 행)을 따른다.
  완전한 기간에 팀 사용이 전혀 없으면 `teamUsage`는 `unavailable`이 아니라 0을 낸다.
- 비교 기간이 완전하고 그 기간에 팀·미배정의 사용이 없었으면 이전 값은 0이다(증감은 "신규").
- 구성원 화면은 이 규칙을 쓰지 않는다(비교가 없다).


## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### Coverage

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/AnalyticsTypes.kt)

<a id="schema-Coverage"></a>

```ts
type Coverage = {
  status: "complete" | "partial" | "none";
  observedDays: number;
};
```

### Tokens

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/AnalyticsTypes.kt)

<a id="schema-Tokens"></a>

```ts
type Tokens = {
  inputUncached: number | null;
  output: number | null;
  cacheRead: number | null;
  cacheWrite: number | null;
  total: number | null;
};
```

### Usage

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/AnalyticsTypes.kt)

<a id="schema-Usage"></a>

```ts
type Usage = {
  activeUsers: number | null;
  sessionCount: number | null;
  tokens: Tokens;
  equivalentCostUsd: string | null;
};
```

### TeamPeriod

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/AnalyticsTypes.kt)

<a id="schema-TeamPeriod"></a>

```ts
type TeamPeriod = {
  activeUsers: number | null;
  equivalentCostUsd: string | null;
};
```

### TopModel

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/AnalyticsTypes.kt)

<a id="schema-TopModel"></a>

```ts
type TopModel = {
  modelId: string;
  displayName: string;
  share: number;
};
```

### AnalyticsMeta

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/AnalyticsFrames.kt)

<a id="schema-AnalyticsMeta"></a>

```ts
type AnalyticsMeta = {
  organizationId: string;
  generatedAt: string;
  dataThrough: string | null;
  currency: "USD";
  startDate: string;
  endDate: string;
  timeZone: "Asia/Seoul";
  dayCount: number;
  dataState: "ready" | "partial" | "no_data" | "never_observed";
  currentCoverage: Coverage;
  pricingVersion: string | null;
  snapshotId: string;
};
```

### Page

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/AnalyticsFrames.kt)

<a id="schema-Page"></a>

```ts
type Page<T> = {
  items: Array<T>;
  totalCount: number;
  nextCursor: string | null;
};
```

### Section

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/AnalyticsFrames.kt)

<a id="schema-Section"></a>

```ts
type Section<T> = {
  availability: "available" | "partial" | "unavailable";
  reason: string | null;
  data: T | null;
};
```

### ProductUsage

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/Products.kt)

<a id="schema-ProductUsage"></a>

```ts
type ProductUsage = {
  kind: string | null;
  displayName: string | null;
  activeUsers: number | null;
  sessionCount: number | null;
  totalTokens: number | null;
  equivalentCostUsd: string | null;
};
```

### ProductRef

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/Products.kt)

<a id="schema-ProductRef"></a>

```ts
type ProductRef = {
  kind: string | null;
  displayName: string | null;
};
```

### Comparison

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-Comparison"></a>

```ts
type Comparison = {
  mode: "prev_week" | "prev_period" | "none";
  startDate: string | null;
  endDate: string | null;
  status: "available" | "unavailable" | "disabled";
  reason: string | null;
  coverage: Coverage | null;
};
```

### Ingest

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-Ingest"></a>

```ts
type Ingest = {
  status: "empty" | "healthy" | "delayed" | "down" | "unknown";
  reason: string | null;
  asOf: string;
  firstObservedAt: string | null;
  lastReceivedAt: string | null;
  windowMinutes: number;
  activeInstallations: number | null;
  observedMembers: number | null;
  eligibleMembers: number | null;
  coverageRatio: number | null;
};
```

### TeamRef

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamRef"></a>

```ts
type TeamRef = {
  teamId: string | null;
  teamName: string;
};
```

### CurrentMeta

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-CurrentMeta"></a>

```ts
type CurrentMeta = {
  organizationId: string;
  generatedAt: string;
  asOf: string;
  snapshotId: string;
  currency: "USD";
  timeZone: "Asia/Seoul";
};
```


<a id="schema-Money"></a>
<a id="schema-Availability"></a>
<a id="schema-Observation"></a>
<a id="schema-ContractStatus"></a>

```ts
type Money = string; // USD decimal 문자열
type Availability = "available" | "partial" | "unavailable";
type Observation = "complete" | "partial" | "unobserved";
type ContractStatus = "active" | "scheduled" | "expired" | "missing";
```

`?`는 키 생략 가능, `| null`은 키를 유지하고 null을 반환함을 뜻한다. 응답 DTO의 Kotlin 기본값은 키 생략을 뜻하지 않는다.
`version`은 JSON 정수이며 증가 방식은 기능별로 다르므로 임의로 1씩 계산하지 않는다. 시각은 별도 표시가 없으면 ISO-8601, 날짜는 YYYY-MM-DD다.
금액 필드의 `string`은 USD decimal 문자열이며 `number`로 형 변환해 계약 정밀도를 바꾸지 않는다.
