# 등록 제품·벤더 계약 — 기능 규칙·공유 스키마

[API 목록](../vendors.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

### 계약 기간 상태 (`contractStatus`)

설정·벤더 목록·상세와 등록/정정 응답의 vendor에 `contractStatus: missing | scheduled | active | expired`를 반환한다.
기존 `state`는 유지한다(active만 configured, 나머지는 needs_review). 상태는 DB에 저장하지 않고
서울 시간의 조회 기준일과 계약 기간으로 계산한다. 페이지네이션은 동일 snapshot의 기준 시각을 사용한다.

| 값 | 의미 |
| --- | --- |
| missing | contract=null, 계약 미입력 |
| scheduled | 오늘이 effectiveFrom 이전 |
| active | 시작일 이후이며 종료일 당일까지. 종료일 null은 상한 없음 |
| expired | 오늘이 effectiveTo 이후 |

만료되어도 등록·계약 원문·이력은 보존하며 자동 삭제·해지·갱신하지 않는다. 기존 금액·좌석은
마지막 계약 정보로 표시한다. 합계는 contractStatus=active인 계약만 포함한다. 만료·시작 예정·미입력은 제외하며 UI에 제외 건수를 표시한다. 유효 계약이 없으면 월 계약액과 좌석 수는 0이다. 유효 계약 자체의 필요한 값이 누락되면 해당 합계는 null이다. 이는 유효 계약 기준 합계이며 실제 전체 지출이나 자동 해지·갱신을 의미하지 않는다.
갱신 등록은 지원 범위 밖이며 기존 PUT은 시작일을 보존하는 입력 정정이다.

## 등록 제품과 계약 정정

설정 `/settings`, `/vendors`, `/vendors/{vendorId}`는 조직에 등록한 제품만 반환한다.
ID는 관리 API와 같은 vendorId UUID다. 공급자 관측·기존 기간 약정으로 행을 추가하지 않는다.
계약 정정 후에는 새로운 snapshot으로 재조회한다. 기존 cursor는 그 조회 기준 시각을 유지한다.
합계는 contractStatus=active인 계약만 포함한다. 만료·시작 예정·미입력은 제외하며 UI에 제외 건수를 표시한다. 유효 계약이 없으면 월 계약액과 좌석 수는 0이다. 유효 계약 자체의 필요한 값이 누락되면 해당 합계는 null이다. 이는 유효 계약 기준 합계이며 실제 전체 지출이나 자동 해지·갱신을 의미하지 않는다.

### 식별자와 화면별 조회

| 값 | 의미 | 사용하는 곳 |
| --- | --- | --- |
| provider | 공급사 ID. 예: anthropic, openai | 카탈로그 표시·검색 |
| kind / 카탈로그 id | 제품 종류. 예: claude_team, openai_biz | 등록 요청·플랜 목록 조회 |
| vendorId | 조직이 등록한 제품의 UUID | 상세 조회·이름/계약 수정·삭제 |
| planId | 해당 제품 안의 플랜 ID | 계약 저장. 다른 제품의 플랜을 전용하지 않음 |
| version | 조회 당시 등록의 변경 버전 | 관리 명령의 expectedVersion 또는 If-Match |

개요의 사용량·추이는 `/analytics/overview`, 현재 등록 제품·좌석 계약은 `/settings`와 `/vendors`에서 읽는다.
개요와 설정의 계약 정보는 같은 원천이다. `/settings`에는 기간 필터를 적용하지 않는다.
활성 manifest 가 없는 조직(최초 수집 정책을 저장하기 전 — enrollment 명세 §13.2)의 `/settings` 는 404 `not_found` 다 — 조회할 설정이 없다. 화면은 이것을 조회 실패가 아니라 "정책 저장 전"으로 다룬다.
계약 저장 후에는 설정뿐 아니라 개요의 계약 조회도 갱신해야 한다.

### 상태·합계·미제공 값

- 계약이 없으면 `contract=null`, `state=needs_review`다. 등록 행 자체는 남는다.
- 오늘(서울)이 계약 시작일~종료일 안이면 configured다. 종료일 null은 상한 없음이며 기간 밖은 needs_review다.
- `summary.monthlySeatFeeUsd`와 `contractedSeats`: 합계는 contractStatus=active인 계약만 포함한다. 만료·시작 예정·미입력은 제외하며 UI에 제외 건수를 표시한다. 유효 계약이 없으면 월 계약액과 좌석 수는 0이다. 유효 계약 자체의 필요한 값이 누락되면 해당 합계는 null이다. 이는 유효 계약 기준 합계이며 실제 전체 지출이나 자동 해지·갱신을 의미하지 않는다.
- 단가 0의 유효 계약은 무료로 입력된 값이며 미입력과 다르다. 표시할 때 null은 `-`, 숫자 0은 0으로 구분한다.
- **종량 지출**(`meteredMonthToDate`, ADR 0050): 벤더 청구 누계 — 커넥터가 벤더 비용·지출 API에서 읽어 저장한 값만이다. 환산 비용·계약액·좌석 단가로 채우지 않는다.
  지금은 Claude Enterprise(`usage_cost` — 이번 달(서울) 사용 비용, 할인 뒤·크레딧 전)와 Cursor Enterprise(`usage_spend` — 벤더의 이번 청구 주기 on-demand 지출)뿐이다.
  vendor마다 `Section<{startDate, endDate, equivalentCostUsd, actualBilledUsd, billingKind, finalized, source, fetchedAt}>`(뒤의 넷은 가산)이고 `equivalentCostUsd`는 null이다(환산 비용은 개요·팀의 제품별 사용).
  가용성: 청구를 구현하지 않은 플랜 `billing_not_supported`, 연결 없음 `billing_source_not_connected`, 지금 기간의 누계 없음 `billing_sync_pending`·`billing_sync_failing`(unavailable),
  마지막 읽기 실패 `billing_sync_failing`·읽은 지 `seats.stale-after` 넘음 `billing_sync_outdated`(partial). 달력 달 기간은 이번 달 시작에서 시작한 누계만 지금 값이다. 현재 값이다(snapshot에 고정하지 않는다).
  `source`가 `seed`면 개발 시드다(실제 청구의 증거가 아니다). `finalized=false`면 벤더가 고칠 수 있는 진행 중 기간이다.
  **조직 합계**(`summary.meteredMonthToDate`)는 모든 등록 제품이 값을 갖고 기간(`startDate`·`endDate`)이 같을 때만 더한다 — 한 제품이라도 없으면 그 사유로 partial·금액 null,
  기간이 다르면 `billing_periods_differ`, 등록 제품이 없으면 unavailable `not_applicable`. `equivalentCostUsd`는 null이다.
- 등록 제품의 firstSeenAt/lastSeenAt·activeUsers7d/30d·observation은 아래 [기존 §7.3](../../dashboard-server-spec.md#73-관측-지표-adr-0044)의 관측 지표다. 좌석 배정·청구의 근거가 아니다.
  활성 좌석 합계(`summary.activeSeats7d`)와 제품별 좌석(`seats`)은 좌석 원장에서 센다("좌석 원장 조회"). 계약 좌석에서 관측 사용자 수를 빼서 미사용 좌석이나 절감액을 만들지 않는다.
- 계약 없는 제품도 표시 이름을 바꿀 수 있다. 계약 비우기·제품 삭제·정정의 저장 규칙은 Enrollment 명세 §12를 따른다.

목록 페이지는 같은 snapshotId로 이어 읽고, 저장 후에는 첫 페이지부터 새 snapshot으로 읽는다.
관리 요청의 `version_conflict`와 조회의 `snapshot_expired`는 별개다. 전자는 사용자 입력과 최신 값을 확인하고,
후자는 기존 페이지를 섞지 않고 첫 페이지부터 조회한다.

### 관측 지표 (ADR 0044)

관측 제품(분석 행의 `product`)은 카탈로그의 명시 매핑 `enrollment.vendor_catalog_observed_products`로만 카탈로그 제품(`kind`)에 잇는다.
지금 매핑: `claude_code` → `claude_team`, `codex` → `openai_biz`. `unknown`과 매핑 없는 관측은 어떤 등록 제품에도 넣지 않는다. 공급사·모델 이름으로 추정하지 않는다.
매핑은 "그 제품이 다루는 도구가 관측됐다"만 말한다 — 그 사용이 그 좌석 계약으로 청구된다는 뜻이 아니다.

| 필드 | 규칙 |
| --- | --- |
| 관측 행 | 활성 로그 관측(`record_status = 'active'`, `signal = 'log'`) 중 source_time < 기준 시각, 삭제 경계 뒤 |
| `firstSeenAt`·`lastSeenAt` | 그 제품으로 매핑되는 관측 행의 source_time 최솟값·최댓값. 없으면 null |
| `activeUsers7d`·`activeUsers30d` | 기준일(서울) **전날까지**의 7·30일 창에 관측이 있는 서로 다른 구성원. 창의 모든 날이 완전(ADR 0042)하면 정확한 수(0 포함), 아니면 센 수가 있을 때만 그 수이고 0은 null |
| `observation` | 30일 창이 완전하면 `complete`, 아니면 관측이 있을 때 `partial`, 없으면 `unobserved`. 매핑 없는 카탈로그 제품(Cursor·Copilot·Gemini 등)은 늘 모두 null·`unobserved` |

`/settings`의 `summary`에 두 필드를 더했다(가산).


- **기준 시각에 고정한다.** `/settings`(와 `snapshotId` 없는 상세)가 새 기준 시각을 낼 때 관측 지표를 한 번 계산해 `dashboard_cache.vendor_observation_sets`·`vendor_observations`(RDS 캐시 V3)에 고정한다.
  같은 `snapshotId`의 `/vendors` 다음 페이지와 `/vendors/{vendorId}?snapshotId=`는 그 값을 읽는다 — 목록·상세·첫 화면이 같다.
  고정이 없는 기준 시각(만료·정리·고정 도입 전에 발급된 토큰)은 409 `snapshot_expired`다. 고정은 snapshot 정리 작업이 같은 기한으로 지운다.
- 관측은 원천 계정으로 읽는다: 분석 행, 매핑, 완전성 근거(설치·수집 구간·정책 판).


### 계약 기간 상태 (`contractStatus`)

설정·벤더 목록·상세와 등록/정정 응답의 vendor에 `contractStatus: missing | scheduled | active | expired`를 반환한다.
기존 `state`는 유지한다(active만 configured, 나머지는 needs_review). 상태는 DB에 저장하지 않고
서울 시간의 조회 기준일과 계약 기간으로 계산한다. 페이지네이션은 동일 snapshot의 기준 시각을 사용한다.

| 값 | 의미 |
| --- | --- |
| missing | contract=null, 계약 미입력 |
| scheduled | 오늘이 effectiveFrom 이전 |
| active | 시작일 이후이며 종료일 당일까지. 종료일 null은 상한 없음 |
| expired | 오늘이 effectiveTo 이후 |

만료되어도 등록·계약 원문·이력은 보존하며 자동 삭제·해지·갱신하지 않는다. 기존 금액·좌석은
마지막 계약 정보로 표시한다. 합계는 contractStatus=active인 계약만 포함한다. 만료·시작 예정·미입력은 제외하며 UI에 제외 건수를 표시한다. 유효 계약이 없으면 월 계약액과 좌석 수는 0이다. 유효 계약 자체의 필요한 값이 누락되면 해당 합계는 null이다. 이는 유효 계약 기준 합계이며 실제 전체 지출이나 자동 해지·갱신을 의미하지 않는다.
갱신 등록은 지원 범위 밖이며 기존 PUT은 시작일을 보존하는 입력 정정이다.


<a id="schema-ContractWrite"></a>

```ts
type ContractWrite = {
  planId: string; effectiveFrom: string; effectiveTo: string | null;
  termNote: string | null;
  tiers: { label: string; seats: number; monthlyFeePerSeatUsd: Money }[];
};
```

## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### VendorTier

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-VendorTier"></a>

```ts
type VendorTier = {
  tierId: string;
  label: string;
  seats: number;
  monthlyFeePerSeatUsd: string;
};
```

### VendorContract

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-VendorContract"></a>

```ts
type VendorContract = {
  version: number;
  planId: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  termNote: string | null;
  tiers: Array<VendorTier>;
  monthlySeatFeeUsd: string | null;
  confirmedAt: string;
  confirmedBy: string;
};
```

### MeteredPeriod

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-MeteredPeriod"></a>

```ts
type MeteredPeriod = {
  startDate: string;
  endDate: string;
  equivalentCostUsd: string | null;
  actualBilledUsd: string | null;
  billingKind: string | null;
  finalized: boolean | null;
  source: string | null;
  fetchedAt: string | null;
};
```

### VendorCheck

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-VendorCheck"></a>

```ts
type VendorCheck = {
  code: string;
  severity: string;
};
```

### Vendor

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-Vendor"></a>

```ts
type Vendor = {
  vendorId: string;
  displayName: string;
  kind: string;
  source: string;
  version: number;
  firstSeenAt: string | null;
  lastSeenAt: string | null;
  activeUsers7d: number | null;
  activeUsers30d: number | null;
  observation: "complete" | "partial" | "unobserved";
  state: string;
  contract: VendorContract | null;
  contractStatus: ContractStatus;
  meteredMonthToDate: Section<MeteredPeriod>;
  checks: Array<VendorCheck>;
  seatSource: SeatSource;
  seats: Section<VendorSeats>;
};
```

### VendorSeats

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-VendorSeats"></a>

```ts
type VendorSeats = {
  assigned: number;
  contracted: number | null;
  unallocated: number | null;
};
```

### VendorsResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-VendorsResponse"></a>

```ts
type VendorsResponse = {
  meta: CurrentMeta;
  vendors: Page<Vendor>;
};
```

### VendorResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-VendorResponse"></a>

```ts
type VendorResponse = {
  meta: CurrentMeta;
  vendor: Vendor;
};
```


## 저장 응답과 조회 응답의 차이

관리 서버의 저장 직후 응답에는 vendor.seats가 없다. 관측 필드(firstSeenAt·lastSeenAt·activeUsers7d·activeUsers30d)는 null,
observation은 unobserved, meteredMonthToDate는 unavailable/source_not_available/data=null, checks는 빈 배열이다.
좌석·사용량을 다시 표시하려면 Dashboard에서 조회한다. 이 차이를 맞추기 위한 런타임 변경은 하지 않는다.

<a id="schema-VendorSavedResponse"></a>

```ts
type VendorSavedResponse = {
  meta: CurrentMeta;
  vendor: {
    vendorId: string; displayName: string; kind: string; source: string; version: number;
    firstSeenAt: null; lastSeenAt: null; activeUsers7d: null; activeUsers30d: null;
    observation: "unobserved"; state: "configured" | "needs_review";
    contractStatus: ContractStatus; contract: VendorContract | null;
    meteredMonthToDate: { availability: "unavailable"; reason: "source_not_available"; data: null };
    checks: []; seatSource: SeatSource;
  };
};
```

등록·계약 입력의 kind는 활성 카탈로그 제품 ID, planId는 그 제품의 플랜 ID다. displayName·등급 label은 trim 후 1~100자,
termNote는 최대 1,000자다. tiers는 1~3개(복수 등급 불가 제품은 1개), seats는 1~9007199254740991 정수다.
monthlyFeePerSeatUsd는 음수 없는 십진 문자열(소수점 이하 최대 12자리)이며 월 합계도 9007199254740991을 넘을 수 없다.
최초 계약 시작일은 서울 기준 오늘 이전일 수 없다. 종료일은 시작일 이전일 수 없고, 새 과거 종료일로 바꿀 수 없다.
동일 kind의 활성 제품 중복은 409 vendor_already_registered다. 계약 변경 시 등급 ID는 새로 발급되므로 이전 등급 ID를 계속 쓰지 않는다.

등록 요청 예시:

```json
{"kind":"claude_team","displayName":"개발팀 Claude"}
```


## 연관 스키마

- [ContractStatus](../common-schemas.md#schema-ContractStatus)
- [CurrentMeta](../common-schemas.md#schema-CurrentMeta)
- [Money](../common-schemas.md#schema-Money)
- [Page](../common-schemas.md#schema-Page)
- [SeatSource](vendor-connections.md#schema-SeatSource)
- [Section](../common-schemas.md#schema-Section)

## 직렬화·검증 근거

- [ManagementStore.kt](../../../libs/enrollment-persistence/src/main/kotlin/com/team376/pulsemetry/persistence/enrollment/management/ManagementStore.kt)
