# 설정 화면

[API 길잡이](README.md) · [공통 규칙](common.md) · [공통 스키마](common-schemas.md)

## 엔드포인트

<a id="endpoint-dashboard-api-12"></a>

### 설정 첫 화면

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/settings -->

```http
GET /api/v1/organizations/{organizationId}/settings
```

서버: **dashboard-api** · 성공: **200** · [구현](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/SettingsController.kt)

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

없음.

**Body**

본문 없음.

**Response**

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

[SettingsResponse 전체 스키마·중첩 타입](settings.md#schema-SettingsResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.


## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### SettingsCapabilities

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-SettingsCapabilities"></a>

```ts
type SettingsCapabilities = {
  editContracts: boolean;
  editCollectionPolicy: boolean;
  editAlertRules: boolean;
  notifyInstallations: boolean;
};
```

### MeteredSummary

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-MeteredSummary"></a>

```ts
type MeteredSummary = {
  equivalentCostUsd: string | null;
  actualBilledUsd: string | null;
};
```

### SettingsSummary

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-SettingsSummary"></a>

```ts
type SettingsSummary = {
  configuredVendors: number;
  unconfiguredVendors: number;
  monthlySeatFeeUsd: string | null;
  contractedSeats: number | null;
  activeSeats7d: number | null;
  assignedSeats: number | null;
  meteredMonthToDate: Section<MeteredSummary>;
  detectedProducts: Array<DetectedProduct>;
  unmappedObservations: UnmappedObservations | null;
};
```

### DetectedProduct

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-DetectedProduct"></a>

```ts
type DetectedProduct = {
  kind: string;
  displayName: string;
  state: string;
  firstSeenAt: string | null;
  lastSeenAt: string | null;
  activeUsers7d: number | null;
  activeUsers30d: number | null;
  observation: "complete" | "partial" | "unobserved";
};
```

### UnmappedObservations

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-UnmappedObservations"></a>

```ts
type UnmappedObservations = {
  observedProducts: Array<string>;
  firstSeenAt: string;
  lastSeenAt: string;
  activeUsers7d: number | null;
  activeUsers30d: number | null;
  observation: "complete" | "partial" | "unobserved";
};
```

### CatalogKind

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-CatalogKind"></a>

```ts
type CatalogKind = {
  kind: string;
  displayName: string;
};
```

### CatalogPlan

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-CatalogPlan"></a>

```ts
type CatalogPlan = {
  planId: string;
  kind: string;
  displayName: string;
  billing: string;
  separateUsageBilling: boolean;
};
```

### Catalog

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-Catalog"></a>

```ts
type Catalog = {
  kinds: Array<CatalogKind>;
  plans: Array<CatalogPlan>;
};
```

### SettingsResponse

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-SettingsResponse"></a>

```ts
type SettingsResponse = {
  meta: CurrentMeta;
  ingest: Ingest;
  capabilities: SettingsCapabilities;
  summary: SettingsSummary;
  catalog: Catalog;
  vendors: Page<Vendor>;
  collectionPolicy: CollectionPolicy;
  policyRollout: PolicyRollout;
  alertRules: Array<AlertRule>;
};
```


## 화면에서 이어지는 기능

설정 첫 조회는 등록 제품 목록 첫 페이지·정책·적용 현황·알림 규칙을 함께 반환한다.
추가 조회·변경의 정식 위치는 [제품·계약](vendors.md), [벤더 연결](vendor-connections.md), [좌석](seats.md),
[수집 정책](collection-policy.md), [설치 현황](installations.md), [알림](alerts.md)이다.
조회 기간 필터는 설정 첫 조회에 적용되지 않는다. 저장 응답만으로 화면 전체를 대체하지 말고 관련 조회를 갱신한다.


## 연관 스키마

- [AlertRule](alerts.md#schema-AlertRule)
- [CollectionPolicy](collection-policy.md#schema-CollectionPolicy)
- [CurrentMeta](common-schemas.md#schema-CurrentMeta)
- [Ingest](common-schemas.md#schema-Ingest)
- [Page](common-schemas.md#schema-Page)
- [PolicyRollout](installations.md#schema-PolicyRollout)
- [Section](common-schemas.md#schema-Section)
- [Vendor](vendors.md#schema-Vendor)

## 전체 JSON 응답 예시

[SettingsResponse 예시](examples/settings-response.example.json)는 가상 데이터이며 현재 DTO의 전체 키를 포함한다. 실서버 응답 캡처나 운영 검증 결과가 아니다.
