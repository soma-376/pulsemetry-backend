# 자동 시간 추이 계약

[개요](overview.md) · [팀](teams.md) · [ADR 0077](../../adr/0077-자동-추이는-발생-시각으로-일괄-집계하고-지표별-누적값을-제공한다.md)

## 요청과 호환성

개요·팀 목록·팀 상세만 `bucket?: "day" | "auto"`를 받는다. 생략 또는 day는 기존 응답과 집계를 유지한다. 다른 값은 400 `unsupported_value`다. 사용자 목록에는 bucket을 추가하지 않는다.

자동 snapshot의 `query_contract`는 `dashboard-auto-trend-v1`, 기존 일별은 `dashboard-v7`이다. 팀 목록·상세에서 요청 모드와 snapshot이 다르거나 snapshot이 만료되면 409 `snapshot_expired`로 재조회를 요구한다. 자동 목록의 다음 페이지·상세는 `bucket=auto`와 같은 snapshotId를 유지한다. 사용자 목록은 전달된 snapshot의 계약과 발생 시각 상한을 계승한다.

KST 조회 시작에 맞춰 전체 기간을 6~12개로 계획한다. 아래 자동 버킷 2판 정책을 적용하고, 응답은 현재 기준 effectiveEnd 이전 구간만 포함한다. 원본 source_time으로 집계하며 일별 값을 분배하거나 보간하지 않는다.

## 공통 metadata

```ts
type TrendAggregation = {
  mode: "auto";
  policyVersion: "calendar-buckets-v2";
  intervalSeconds: number | null;
  intervalMonths: 1 | null;
  effectiveEnd: string;
  valueMode: "interval" | "cumulative";
  rangeStart: string; // ISO 8601 instant, 조회 시작 KST 자정
  rangeEnd: string; // ISO 8601 instant, 조회 종료 다음 날 KST 자정
  asOf: string;
};
type PendingCollectionPolicy = {
  policyVersion: "proj-315-pending";
  asOf: string; // 같은 snapshot 기준 시각
  status: "pending";
  annotations: Array<{
    scope: "period";
    rangeStart: string;
    rangeEnd: string;
    teamId: null;
    reasons: ["policy_integration_pending"];
    evidenceRefs: [];
  }>;
};
```

자동 응답의 `meta.trendAggregation`과 `meta.collectionPolicy`는 필수다. 개요 자동 응답에는 `meta.snapshotId`도 필수이며 기존 일별 개요에는 추가하지 않는다. 팀 응답의 기존 snapshotId는 유지한다. 자동 snapshot을 계승한 팀 사용자 응답도 목록·상세와 동일한 trendAggregation(valueMode=cumulative)·collectionPolicy를 반환한다. `mode=auto`이며 개요 valueMode는 interval, 팀은 cumulative다.

`asOf`는 snapshot 생성 시 고정한 이벤트 시각 상한이다. 현재·비교 기간의 사용량과 관측 metadata를 같은 원본 선택에서 `source_time < asOf`로 제한한다. 수신 시각에 따른 과거 데이터 복원은 아니다. summary·추이·목록·상세·사용자·CSV는 같은 입력을 사용한다.

pending collectionPolicy는 PROJ-315 정책이 아직 연결되지 않았음을 나타낸다. 기간 전체 annotation 하나를 제공하며 수집이 완전하다는 뜻이 아니다. 이 사유를 비용·토큰의 결손으로 복제하지 않는다. 실제 정책 인계는 동일 snapshot에 고정된 정책 버전·asOf·영향 범위·사유·근거 참조를 전달해야 한다. 정확한 시간·대상이 없으면 기간 annotation으로 유지한다.

PROJ-316의 최근 168시간 손실은 서버 확인 시각 기준 페이로드 수다. source_time별 이벤트·토큰 손실로 바꾸거나 버킷·팀에 균등 배분하지 않는다.

## 자동 point

```ts
type Value<T> = {
  value: T | null;
  status: "complete" | "partial" | "unavailable";
  reasons: string[];
};
type AutoPoint = {
  bucketStart: string;
  bucketEnd: string;
  temporalState: "elapsed" | "in_progress";
  usageRows: number | null; // 해당 버킷의 비누적 사용 행 수
  pricedUsageRows: number | null;
  unpricedUsageRows: number | null;
};
type AutoOverviewTrend = {
  bucket: "auto";
  points: Array<AutoPoint & {
    equivalentCostUsd: Value<string>;
    knownEquivalentCostUsd: Value<string>;
    totalTokens: Value<number>;
  }>;
};
type AutoTeamTrendPoint = AutoPoint & {
  cumulativeEquivalentCostUsd: Value<string>;
  cumulativeKnownEquivalentCostUsd: Value<string>;
  cumulativeTotalTokens: Value<number>;
  cumulativeSessionCount: Value<number>;
};
```

개요는 `trend={bucket:"auto",points:[...]}`이며 각 값·환산 행 수는 해당 버킷의 범위다. 팀은 기존 `team.trend` 배열 자리에 AutoTeamTrendPoint 배열을 제공하며 누적 지표·환산 행 수는 조회 시작부터 해당 버킷까지다. 양쪽 모두 usageRows만은 비누적이다. 자동 point에 일별 date·observation이나 팀의 평문 일별 비용·토큰 필드를 섞지 않는다.

원래 계획 종료가 asOf 이하이면 elapsed, 현재 집계 중이면 in_progress다. 현재 구간의 bucketEnd는 effectiveEnd로 자른다. 미래 구간은 반환하지 않으며 전체 미래 기간은 빈 배열이다. 지난 빈 구간은 null/unavailable/no_data와 행 수 0을 반환하고, 이미 확인된 팀 누적값은 유지한다.

정확한 버킷 시작 시각에는 길이 0인 새 구간을 반환하지 않는다.

금액은 최소 소수 6자리를 보존하는 정확한 USD Decimal 문자열이며 더 정밀한 값은 자르지 않는다. 기록된 명시적 0은 complete인 0이다. 기록이 없다는 이유만으로 0을 만들지 않는다. 중간 빈 버킷의 개요 값은 no_data이며 팀 누적은 앞의 확인된 값을 유지한다. 진행 상태는 지표의 complete/partial/unavailable과 독립이다.

미환산이 섞이면 전체 금액은 null/unavailable/pricing_missing이고 계산 가능한 부분합은 partial/pricing_missing이다. 전부 미환산이면 부분합도 null이다. 세션 ID 결손은 session_identity_missing으로 세션만 unavailable로 만들며 유효한 비용·토큰 상태에 전파하지 않는다. 토큰 결손과 비호환 의미는 token_data_incomplete·token_semantics_incompatible로 해당 지표를 제한한다. 앞의 지표 결손은 뒤의 정상 버킷으로 사라지지 않는다.

세션은 `(팀, product, session namespace, session ID)`별 기간 내 첫 관측 버킷에서 한 번만 누적한다. 같은 세션의 반복 관측은 중복하지 않고 product·namespace·팀이 다른 같은 ID는 각각 센다. 과거 최종 누적 또는 오늘의 마지막 미도래 전 누적은 같은 팀 기간 합계와 일치한다. 미환산이 있으면 확인된 금액 부분합도 별도로 일치한다.

## 화면·CSV 계약

자동 mode를 요청·캐시 키에 포함하고 페이지 전체에 같은 snapshot·간격·시간축을 유지한다. 최대 12개, 연속·비중복 반개구간, 후보 간격과 마지막 절단을 검증한다. 팀 서버 누적값에 다시 누적 변환을 적용하지 않는다.

개요 좌표는 bucketStart, 팀은 bucketEnd다. 진행 중 팀 값은 asOf까지만 표시한다. 마지막 짧은 구간도 실제 시간 길이를 사용한다. 툴팁·키보드 탐색·접근성 설명은 KST 전체 범위와 실제 간격을 제공한다. 현재 날짜 문자열 대신 temporalState를 사용한다.

일별·자동 모두 팀 선택 탭은 비용·토큰이다. 세션 API·내부 집계·개요·표·CSV 세션 열은 유지한다. chart_trend는 현재 선택한 비용·토큰 축만 내보낸다. CSV는 mode·intervalSeconds·timeZone·asOf·bucketStart·bucketEnd·temporalState·지표 상태·사유와 원래 Decimal 문자열을 보존하고 null은 빈 셀이다. 사용자 추가 조회 중 snapshot 만료 시 전체 내보내기를 새 snapshot으로 다시 시작한다.

## 현재 상태와 활성화 gate

pending adapter와 [명시 이벤트 fixture](../examples/auto-trend.fixture.json)는 개발·회귀 검증용이다. PROJ-315의 실제 정책 원천·영향 범위·근거 참조 통합을 검증한 증거를 대신하지 않는다.

사용자 배포의 `NEXT_PUBLIC_AUTO_TREND_ENABLED` 기본값은 false다. backend 선배포 후 PROJ-315 통합 및 실제 API 개요·팀·CSV 검증을 통과한 배포에서만 true로 빌드한다. 복구는 PROJ-316 알림 파서·PROJ-318 탭 구성을 유지한 호환 frontend를 false로 다시 빌드·배포한다. [검증·배포 인수](../../auto-trend-operations.md)를 따른다.

## 자동 버킷 2판

기존 버킷·미래 표시·metadata 규칙을 허브 ADR 0033에 따라 대체한다.

전체 선택 기간의 계획은 최소 6개·최대 12개다. 간격은 1일=3시간, 2일=6시간, 3~6일=12시간, 7~12일=1일, 13~24일=2일, 25~36일=3일, 37~84일=7일, 85~168일=14일, 169~366일=달력 1개월이다. KST 조회 시작을 기준으로 경계를 만든다. 월 경계는 원래 시작일에 N개월을 더하고 해당 월의 마지막 날로 보정한다. 13번째 짧은 구간은 12번째에 합친다.

간격은 전체 선택 기간으로 정하고 표시 종료는 effectiveEnd=max(rangeStart,min(rangeEnd,asOf))다. 시작이 effectiveEnd 이상인 버킷은 반환하지 않는다. 마지막 끝은 effectiveEnd로 자른다. 원래 계획 종료보다 asOf가 이르면 in_progress, 아니면 elapsed다. 정각에는 길이가 0인 다음 구간을 만들지 않는다. 미래 전체 기간은 빈 배열이며 이미 지난 빈 구간의 값은 no_data로 보존한다. 최소 6개는 전체 계획에만 적용되며 현재까지 응답은 0~12개다.

meta.trendAggregation은 policyVersion=calendar-buckets-v2, intervalSeconds(고정 간격은 초·월은 null), intervalMonths(월은 1·고정 간격은 null), effectiveEnd를 제공한다. rangeStart/rangeEnd는 전체 선택 기간을 유지한다. 차트 축의 끝은 effectiveEnd이며 구간의 정확한 끝을 툴팁과 CSV에 표시한다. 월 구간을 30일로 환산하지 않는다. 자동 snapshot 계약은 dashboard-auto-trend-v2이며 이전 자동 계약과 혼용하지 않는다. 일별 모드와 PROJ-315 활성화 gate는 유지한다.
