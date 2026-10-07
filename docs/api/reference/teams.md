# 팀 분석·관리 — 기능 규칙·공유 스키마

[API 목록](../teams.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

### 팀 누적 세션 (ADR 0042)

팀 목록·팀 상세·미배정의 `trend[].cumulativeSessionCount`는 **기간 시작일부터 그날까지 그 팀에서 관측된 고유 세션 수**다.

- 세션 키는 기간 `sessionCount`와 같은 `(product, session_id_namespace, session_id)`다. 여러 날에 걸친 세션은 **처음 관측된 날**에 한 번만 더한다.
  같은 세션의 이벤트가 두 팀에 귀속되면(이벤트 시점 소속) 팀마다 따로 센다. 날짜는 조회 시간대의 자정 경계다.
- 값은 시작일부터 그날까지 **모든 날이 완전**(위 판정)하고 그 팀의 **세션 없는 사용 행이 없을 때만** 있다. 한 번 끊기면 그 뒤의 완전한 날도 null이다 —
  끊긴 뒤의 값을 앞의 누적에 이어 붙이지 않는다. 사용이 없는 완전한 날은 앞날의 값 그대로다(첫날이면 0).
- 마지막 날의 값이 있으면 그 기간의 팀 `current.sessionCount`와 같다. 기간 세션 수를 날짜로 나누거나 보간해 만들지 않는다.
- 같은 snapshot 의 사용량 행에서 계산하므로 목록과 상세가 같다. 이 규칙을 더하면서 판정 규칙 판을 `dashboard-v4`로 올렸다 — 이전 판의 snapshot ID 는 409 `snapshot_expired`다.


## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### TeamModelUsage

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamModelUsage"></a>

```ts
type TeamModelUsage = {
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
  current: Usage | null;
  previous: Usage | null;
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
  meta: AnalyticsMeta;
  comparison: Comparison;
  ingest: Ingest;
  attributionBasis: string;
  totals: UsagePair;
  sort: string;
  teams: Page<TeamAnalytics>;
  unassigned: TeamAnalytics;
  modelScatter: Section<ModelScatter>;
};
```

### TeamDetailResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamDetailResponse"></a>

```ts
type TeamDetailResponse = {
  meta: AnalyticsMeta;
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
  usage: Usage;
  mainModel: ModelRef | null;
  cache: CacheUsage;
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

### CacheUsage

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-CacheUsage"></a>

```ts
type CacheUsage = {
  readTokens: number | null;
  eligibleInputTokens: number | null;
  hitRatio: number | null;
};
```

### TeamUsersSummary

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamUsersSummary"></a>

```ts
type TeamUsersSummary = {
  usage: Usage | null;
  averageEquivalentCostUsd: string | null;
  cacheReadTokens: number | null;
  cacheEligibleInputTokens: number | null;
  cacheHitRatio: number | null;
  unidentifiedEquivalentCostUsd: string | null;
};
```

### TeamUsersResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)

<a id="schema-TeamUsersResponse"></a>

```ts
type TeamUsersResponse = {
  meta: AnalyticsMeta;
  team: TeamRef;
  summary: TeamUsersSummary;
  users: Page<TeamUser>;
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
};
```

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
- [UsagePair](overview.md#schema-UsagePair)

## 전체 JSON 응답 예시

[TeamsResponse 예시](../examples/teams-response.example.json)는 가상 데이터이며 현재 DTO의 전체 키를 포함한다. 실서버 응답 캡처나 운영 검증 결과가 아니다.
