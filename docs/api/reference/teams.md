# 팀 분석·관리 — 기능 규칙·공유 스키마

[API 목록](../teams.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

### 팀 누적 세션 (ADR 0042)

팀 목록·팀 상세·미배정의 `trend[].cumulativeSessionCount`는 **기간 시작일부터 그날까지 그 팀에서 관측된 고유 세션 수**다.

- 세션 키는 기간 `sessionCount`와 같은 `(product, session_id_namespace, session_id)`다. 여러 날에 걸친 세션은 **처음 관측된 날**에 한 번만 더한다.
  같은 세션의 이벤트가 두 팀에 귀속되면(이벤트 시점 소속) 팀마다 따로 센다. 날짜는 조회 시간대의 자정 경계다.
- 값은 시작일부터 그날까지 **모든 날이 완전**(위 판정)하고 그 팀의 **세션 없는 사용 행이 없을 때만** 있다. 한 번 끊기면 그 뒤의 완전한 날도 null이다 —
  끊긴 뒤의 값을 앞의 누적에 이어 붙이지 않는다. 사용이 없는 완전한 날은 앞날의 값 그대로다(첫날이면 0).
- 마지막 날의 값이 있으면 그 기간의 팀 `metrics.sessionCount.value`와 같다. 기간 세션 수를 날짜로 나누거나 보간해 만들지 않는다.
- 같은 snapshot 의 사용량 행에서 계산하므로 목록과 상세가 같다. 이 규칙을 더하면서 판정 규칙 판을 `dashboard-v6`로 올렸다 — 이전 판의 snapshot ID 는 409 `snapshot_expired`다.


## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### TeamModelUsage

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamModelUsage"></a>

```ts
type TeamModelUsage = {
  status: "complete" | "partial" | "unavailable";
  reasons: string[];
  modelId: string;
  displayName: string;
  equivalentCostUsd: string | null;
  totalTokens: number | null;
};
```

### TeamModelMix

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamModelMix"></a>

```ts
type TeamModelMix = {
  sessionMixAvailable: boolean;
  sessionMixReason: string;
  models: Array<TeamModelUsage>;
};
```

### TeamTrendPoint

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamTrendPoint"></a>

```ts
type TeamTrendPoint = {
  status: "complete" | "partial" | "unavailable";
  reasons: string[];
  date: string;
  observation: "complete" | "partial" | "unobserved";
  equivalentCostUsd: string | null;
  totalTokens: number | null;
  cumulativeSessionCount: number | null;
};
```

### TeamAnalytics

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamAnalytics"></a>

```ts
type TeamAnalytics = {
  teamId: string | null;
  teamName: string;
  metrics: TeamMetrics;
  modelMix: Section<TeamModelMix>;
  trend: Array<TeamTrendPoint>;
  products: Array<ProductUsage>;
};
```

### ScatterModel

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-ScatterModel"></a>

```ts
type ScatterModel = {
  status: "complete" | "partial" | "unavailable";
  reasons: string[];
  modelId: string;
  displayName: string;
  equivalentCostUsd: string | null;
  totalTokens: number | null;
  usingTeamCount: number | null;
};
```

### ModelScatter

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-ModelScatter"></a>

```ts
type ModelScatter = {
  teamUsageCostShareThreshold: number;
  models: Array<ScatterModel>;
};
```

### TeamsResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamsResponse"></a>

```ts
type TeamsResponse = {
  meta: TeamMeta;
  comparison: Comparison;
  attributionBasis: string;
  totals: TeamMetrics;
  sort: string;
  teams: OffsetPage<TeamAnalytics>;
  unassigned: TeamAnalytics;
  modelScatter: Section<ModelScatter>;
};
```

### TeamDetailResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamDetailResponse"></a>

```ts
type TeamDetailResponse = {
  meta: TeamMeta;
  comparison: Comparison;
  team: TeamAnalytics;
};
```

### TeamUser

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamUser"></a>

```ts
type TeamUser = {
  memberId: string;
  account: string;
  usage: TeamUserUsage;
  mainModel: ModelRef | null;
  cacheHitRatio: Value<number>;
  lastUsedAt: string | null;
};
```

### ModelRef

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-ModelRef"></a>

```ts
type ModelRef = {
  modelId: string;
  displayName: string;
};
```

### TeamUsersSummary

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamUsersSummary"></a>

```ts
type TeamUsersSummary = {
  usage: TeamSummaryUsage;
  averageEquivalentCostUsd: string | null;
  cacheHitRatio: Value<number>;
  unidentifiedEquivalentCostUsd: string | null;
};
```

### TeamUsersResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamUsersResponse"></a>

```ts
type TeamUsersResponse = {
  meta: TeamMeta;
  team: TeamRef;
  summary: TeamUsersSummary;
  users: OffsetPage<TeamUser>;
};
```

### DirectoryTeam

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-DirectoryTeam"></a>

```ts
type DirectoryTeam = {
  teamId: string;
  teamName: string;
  version: number;
  memberCount: number; // 현재 소속된 활성·정지 구성원의 고유 인원 수(0 이상 정수)
};
```

`memberCount`는 조회 시점에 해당 팀에 소속된 현재 로스터(`active`, `suspended`)를 전체 명단에서 센다.
사용량 조회 기간·구성원 목록 페이지 크기와 무관하며, 초대 대기자·과거 소속·미래 소속은 포함하지 않는다.
팀 이름과 같이 현재 값이므로 페이지 기준 `snapshotId`가 같아도 소속 변경 뒤 재조회하면 달라질 수 있다.
프론트엔드는 배포 순서상 이 가산 필드가 없는 응답을 받을 때 인원 확인 불가로 표시하며 `0`으로 대신하지 않는다.

### TeamDirectoryResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamDirectoryResponse"></a>

```ts
type TeamDirectoryResponse = {
  meta: CurrentMeta;
  teams: Page<DirectoryTeam>;
};
```


## 팀 변경·분석 규칙

팀 이름은 trim 후 1~100자이며 활성 팀 이름은 대소문자를 무시하고 중복을 검사한다.
DELETE는 보관과 현재 소속 해제를 수행한다. 과거 이벤트의 팀 귀속은 바꾸지 않는다.
분석 상세·팀 사용자의 teamId는 UUID 외에 `unassigned`를 지원한다. 현재 팀 선택지에는 미배정 항목이 없다.
기간과 비교가 같아도 현재 팀 디렉터리와 과거 분석 팀 목록은 다를 수 있다.

<a id="schema-TeamSaved"></a>

```ts
type TeamSaved = { teamId: string; teamName: string; version: number };
```

```json
{"teamId":"10000000-0000-0000-0000-000000000001","teamName":"플랫폼","version":1791158400000}
```


## 연관 스키마

- [AnalyticsMeta](../common-schemas.md#schema-AnalyticsMeta)
- [Comparison](../common-schemas.md#schema-Comparison)
- [CurrentMeta](../common-schemas.md#schema-CurrentMeta)
- [Ingest](../common-schemas.md#schema-Ingest)
- [Page](../common-schemas.md#schema-Page)
- [ProductUsage](../common-schemas.md#schema-ProductUsage)
- [Section](../common-schemas.md#schema-Section)
- [TeamRef](../common-schemas.md#schema-TeamRef)
- [Usage](../common-schemas.md#schema-Usage)

## 전체 JSON 응답 예시

[TeamsResponse 예시](../examples/teams-response.example.json)는 가상 데이터이며 현재 DTO의 전체 키를 포함한다. 실서버 응답 캡처나 운영 검증 결과가 아니다.

## 화면 값과 사유

<a id="schema-TeamMeta"></a>

```ts
type TeamMeta = { organizationId:string; startDate:string; endDate:string; timeZone:string; dayCount:number; dataThrough:string|null; dataState:string; currentCoverage:Coverage; snapshotId:string };
type Value<T> = { value:T|null; status:"complete"|"partial"|"unavailable"; reasons:string[] };
type Metric<T> = Value<T> & { comparison:{status:"available"|"unavailable"|"disabled"; reasons:string[]; previousValue:T|null; absoluteChange:T|null; percentChange:number|null; change:"increase"|"decrease"|"unchanged"|"new"|null} };
type TeamMetrics = {
 activeUsers:Value<number>; equivalentCostUsd:Metric<string>; totalTokens:Metric<number>; sessionCount:Metric<number>;
 equivalentCostPerUserUsd:Metric<string>; equivalentCostPerSessionUsd:Value<string>; tokensPerUser:Value<number>;
 sessionsPerUser:Value<number>; tokensPerSession:Value<number>; effectiveCostPerMillionTokensUsd:Value<string>;
};
type TeamUserUsage = { sessionCount:Value<number>; totalTokens:Value<number>; equivalentCostUsd:Value<string> };
type TeamSummaryUsage = TeamUserUsage & { activeUsers:Value<number> };
```

값과 증감은 개요와 같은 판정기를 사용한다. 분모가 0이면 값을 비우고 `zero_denominator`를 반환한다. 미식별 사용량을 식별 인원으로 나누지 않는다. 기간·수집·단가·세션·토큰 결손은 상세 사유를 유지한다. 모델의 상태는 좌표와 팀 수의 근거, 추이 상태는 날짜별 값과 누적 세션 근거를 표현한다. 저장소 요청 실패는 HTTP 오류이며 지표 상태로 숨기지 않는다.

## 계산 가능한 관측 부분합 (허브 ADR 0011)

기존 전체 금액·평균·비중·비교의 null 규칙은 유지한다. 별도 `knownEquivalentCostUsd`, `pricedUsageRows`, `unpricedUsageRows`를 반환한다. 개요 summary/trend point와 팀 metrics에서는 부분합이 `Value<string>`, 팀 trend point에서는 nullable USD 문자열이다. 행 수는 해당 범위의 primary 사용 관측 수다. 미가격 행이 있으면 부분합 사유에 `pricing_missing`을 포함한다. 가격 있는 행이 없으면 부분합도 null이며, 완전 관측한 빈 범위만 0이다. 미관측일을 0으로 대체하지 않는다. 부분합은 전체 사용량·실제 청구액이 아니며 평균·비교·알림에 대신 쓰지 않는다.
