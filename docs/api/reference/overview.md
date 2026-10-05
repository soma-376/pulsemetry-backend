# 개요 — 기능 규칙·공유 스키마

[API 목록](../overview.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

### 개요 페이지

`GET /analytics/overview`

쿼리 파라미터:


응답:


`teamUsage`는 상위 팀·나머지 팀·미배정으로 구분하며 이벤트 시점 소속으로 집계한다.
팀 간 이동한 구성원이나 여러 도구를 쓰는 구성원을 조직 활성 사용자 수에서 중복 계산하지 않는다.


## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### OverviewResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-OverviewResponse"></a>

```ts
type OverviewResponse = {
  meta: Meta;
  comparison: Comparison;
  ingest: Ingest;
  usage: UsagePair;
  seats: Seats;
  alerts: Alerts;
  trend: Trend;
  modelMix: ModelMix;
  waste: Waste;
  teamUsage: TeamUsage;
  productUsage: ProductUsageSection;
};
```

### ProductUsageSection

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-ProductUsageSection"></a>

```ts
type ProductUsageSection = {
  availability: "available" | "partial" | "unavailable";
  reason: string | null;
  products: Array<ProductUsage>;
};
```

### Meta

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-Meta"></a>

```ts
type Meta = {
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
};
```

### UsagePair

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-UsagePair"></a>

```ts
type UsagePair = {
  current: Usage | null;
  previous: Usage | null;
};
```

### Seats

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-Seats"></a>

```ts
type Seats = {
  availability: "available" | "partial" | "unavailable";
  reason: string | null;
  scopeVendorIds: Array<string>;
  allocationMethod: string | null;
  current: SeatPeriod | null;
  previous: SeatPeriod | null;
  reclaimEstimate: ReclaimEstimate | null;
  reclaimCandidates: number | null;
};
```

### SeatPeriod

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-SeatPeriod"></a>

```ts
type SeatPeriod = {
  contractedSeats: number;
  activeSeats: number | null;
  monthlyFeeUsd: string;
  allocatedFeeUsd: string;
  equivalentCostUsd: string;
  efficiency: number | null;
};
```

### ReclaimEstimate

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-ReclaimEstimate"></a>

```ts
type ReclaimEstimate = {
  idleSeats: number;
  monthlySavingsUsd: string;
  efficiencyAfterReclaim: number | null;
};
```

### Alerts

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-Alerts"></a>

```ts
type Alerts = {
  availability: "available" | "partial" | "unavailable";
  reason: string | null;
  asOf: string;
  unacknowledgedTotal: number | null;
  security: number | null;
  cost: number | null;
};
```

### Trend

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-Trend"></a>

```ts
type Trend = {
  bucket: string;
  points: Array<TrendPoint>;
};
```

### TrendPoint

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-TrendPoint"></a>

```ts
type TrendPoint = {
  date: string;
  observation: "complete" | "partial" | "unobserved";
  equivalentCostUsd: string | null;
  allocatedSeatCostUsd: string | null;
  totalTokens: number | null;
};
```

### ModelMix

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-ModelMix"></a>

```ts
type ModelMix = {
  availability: "available" | "partial" | "unavailable";
  reason: string | null;
  models: Array<ModelShare>;
};
```

### ModelShare

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-ModelShare"></a>

```ts
type ModelShare = {
  modelId: string;
  displayName: string;
  equivalentCostUsd: string | null;
  totalTokens: number | null;
  effectiveCostPerMillionTokensUsd: string | null;
};
```

### Waste

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-Waste"></a>

```ts
type Waste = {
  availability: "available" | "partial" | "unavailable";
  reason: string | null;
  methodologyVersion: string | null;
  totalMonthlyEquivalentCostUsd: string | null;
  items: Array<WasteItem>;
};
```

### WasteItem

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-WasteItem"></a>

```ts
type WasteItem = {
  kind: string;
  availability: "available" | "partial" | "unavailable";
  reason: string | null;
  currentEquivalentCostUsd: string | null;
  previousEquivalentCostUsd: string | null;
  monthlyEquivalentCostUsd: string | null;
  previousMonthlyEquivalentCostUsd: string | null;
  rate: number | null;
  rateDefinition: string | null;
};
```

### TeamUsage

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-TeamUsage"></a>

```ts
type TeamUsage = {
  availability: "available" | "partial" | "unavailable";
  reason: string | null;
  ranking: string;
  attributionBasis: string;
  totalTeamCount: number;
  topTeams: Array<TopTeam>;
  otherTeams: OtherTeams;
  unassigned: Unassigned;
};
```

### TopTeam

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-TopTeam"></a>

```ts
type TopTeam = {
  teamId: string;
  teamName: string;
  current: TeamPeriod;
  previous: TeamPeriod | null;
  topModel: TopModel | null;
  products: Array<ProductRef>;
};
```

### OtherTeams

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-OtherTeams"></a>

```ts
type OtherTeams = {
  count: number;
  currentEquivalentCostUsd: string | null;
  previousEquivalentCostUsd: string | null;
};
```

### Unassigned

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt)

<a id="schema-Unassigned"></a>

```ts
type Unassigned = {
  current: TeamPeriod;
  previous: TeamPeriod | null;
  products: Array<ProductRef>;
};
```


## 연관 스키마

- [Comparison](../common-schemas.md#schema-Comparison)
- [Coverage](../common-schemas.md#schema-Coverage)
- [Ingest](../common-schemas.md#schema-Ingest)
- [ProductRef](../common-schemas.md#schema-ProductRef)
- [ProductUsage](../common-schemas.md#schema-ProductUsage)
- [TeamPeriod](../common-schemas.md#schema-TeamPeriod)
- [TopModel](../common-schemas.md#schema-TopModel)
- [Usage](../common-schemas.md#schema-Usage)

## 전체 JSON 응답 예시

[OverviewResponse 예시](../examples/overview-response.example.json)는 가상 데이터이며 현재 DTO의 전체 키를 포함한다. 실서버 응답 캡처나 운영 검증 결과가 아니다.
