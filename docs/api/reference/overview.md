# 개요 화면 API

`GET /api/v1/organizations/{organizationId}/analytics/overview`

브라우저는 동일 출처 BFF `/api/bff/dashboard`를 통해 호출한다. 인증·조직 인가와 기간 쿼리 계약은 유지한다.
프론트와 백엔드는 함께 배포한다. 이 문서는 화면 변경에 맞춘 현재 계약이며 구 `usage/seats/ingest/alerts/waste/productUsage` 응답을 대체한다.

## 요청

`startDate`, `endDate`는 서울 기준 날짜(최대 366일), `timeZone=Asia/Seoul`, `compare=none|prev_week|prev_period`이다.
비교 기본값은 `prev_week`다. 한 요청에서 만든 불변 snapshot을 모든 집계가 공유한다.

## 응답

최상위 키는 `meta`, `comparison`, `summary`, `trend`, `modelMix`, `teamUsage`, `errors`다.
모든 키와 명시적인 null을 유지한다. USD 금액은 10진 문자열, 건수는 정수, 비중은 0~1, 증감률은 백분율이다.

```typescript
type Value<T> = {
  value: T | null;
  status: "complete" | "partial" | "unavailable";
  reasons: string[];
};
type Change<T> = {
  status: "available" | "unavailable" | "disabled";
  reasons: string[];
  previousValue: T | null;
  absoluteChange: T | null; // 감소는 음수; USD도 문자열
  percentChange: number | null;
  change: "increase" | "decrease" | "unchanged" | "new" | null;
};
type Metric<T> = Value<T> & { comparison: Change<T> };
```

| 영역 | 필드 |
|---|---|
| meta | organizationId, generatedAt, dataThrough, currency, startDate, endDate, timeZone, dayCount, dataState, currentCoverage |
| comparison | mode, startDate, endDate, coverage |
| summary | activeUsers: Metric<number>, equivalentCostUsd: Metric<string>, sessionCount: Metric<number>, equivalentCostPerUserUsd: Metric<string>, totalTokens: Metric<number>, sessionsPerUser: Value<number>, equivalentCostPerSessionUsd: Value<string> |
| trend | bucket: "day", points: {date, observation, equivalentCostUsd: Value<string>, totalTokens: Value<number>}[] |
| modelMix | models: {modelId, displayName, equivalentCostUsd: Value<string>, totalTokens: Value<number>, effectiveCostPerMillionTokensUsd: Value<string>, costShare: Value<number>}[] |
| teamUsage | attributionBasis: "event_time", totalTeamCount, topTeams, unassigned |
| errors | {section: "summary"\|"trend"\|"modelMix"\|"teamUsage", code, retryable}[] |

`meta.currentCoverage`와 `comparison.coverage`는 `{status:"complete"|"partial"|"none",observedDays:number}`다.
비교 없음의 날짜·coverage는 null이다. `dataThrough`는 시작일부터 연속으로 완전한 날짜의 끝 시각이다.

`topTeams`는 현재 환산액 내림차순 상위 3팀이며 동률은 teamId 순이다. 금액 미확정 팀은 뒤에 둔다.
각 행은 `teamId, teamName, products, activeUsers:Value<number>, equivalentCostUsd:Metric<string>`이다.
`unassigned`는 같은 값에서 ID·이름을 뺀 형태이며 미배정 사용 행이 없으면 null이다.
`products`는 `{kind:string|null,displayName:string|null}[]`이고 현재 기간에 관측된 제품만 담는다.
나머지 팀 합계, 팀 대표 모델, 제품별 상세 집계, 좌석료, 토큰 성분은 반환하지 않는다.

## 값과 비교 상태

- `complete`: 값이 있고 사유가 없다. 확인된 사용 없음은 0이다.
- `partial`: 값은 있으나 기간 또는 식별 근거가 불완전하다. 사유를 함께 반환한다.
- `unavailable`: 값은 null이고 하나 이상의 사유가 있다. 0으로 대체하지 않는다.
- 비교는 두 기간과 해당 지표의 근거가 모두 완전할 때만 `available`이다.
- 비교 불가 또는 비교 없음이면 이전 값·증감·change는 모두 null이다. 비교 없음은 사유도 빈 배열이다.
- 이전 0, 현재 양수는 `new`, 증감률 null이다. 둘 다 0이면 `unchanged`, 증감률 0이다.
- 구성원 미식별 사용이 섞이면 식별된 인원은 partial이지만 사용자당 금액·세션은 모집단이 달라 null이다.
- 모델 금액이 하나라도 미확정이면 전체 비중은 null이다. 토큰 누락만으로 금액 비중을 감추지 않는다.

| 사유 | 의미 |
|---|---|
| current_period_incomplete | 선택 기간의 수집 근거 불완전 |
| previous_period_incomplete | 비교 기간의 수집 근거 불완전 |
| member_identity_missing | 구성원 미식별 사용 존재 |
| session_identity_missing | 세션 미식별 사용 존재 |
| pricing_missing | 단가 누락 |
| pricing_version_mixed | snapshot의 가격 판 혼재 |
| token_data_incomplete | 토큰 누락 |
| token_semantics_incompatible | 의미 미검증 또는 다른 의미 프로파일의 토큰 |
| zero_denominator | 평균·비중의 분모 0 |
| no_data | 산출에 필요한 관측 값 없음 |

## 빈 기간과 실패

`dataState=no_data|never_observed`면 네 영역이 모두 null이며 errors는 빈 배열이다. 팀 수가 알려져 있어도 팀 요약을 따로 노출하지 않는다.
완전한 기간에 사용이 없으면 `ready`이며 요약·일별 값은 0이다. 0분모 평균은 null이다.

독립 집계의 일시 저장소 장애는 그 영역만 null과 `source_unavailable/retryable=true`로 반환한다.
쿼리 거부는 `query_failed/retryable=false`다. 원문 SQL·호스트·예외 메시지는 응답에 싣지 않는다.
인증·인가·공통 snapshot 생성 실패와 예상하지 못한 프로그램 오류는 기존 HTTP 오류로 전파한다.

프론트는 실패 영역만 오류로 표시하며 페이지 공통 알림과 재시도 버튼 하나를 사용한다.
재시도는 같은 조건의 단일 API 재호출이다. 기존 정상 영역을 스켈레톤으로 덮지 않는다.
새 응답의 실패 영역에 이전 snapshot 값을 끼워 넣지 않는다. HTTP 갱신 실패에서는 마지막 성공 응답과 갱신 실패 안내를 유지한다.
값의 partial/unavailable 자체는 조회 오류가 아니므로 재시도 알림을 띄우지 않는다.

## CSV

화면이 사용하는 같은 응답으로 생성한다. 5개 KPI와 2개 보조 지표, 일별 추이, 상위 4개+기타로 묶은 모델, 화면에 표시되는 상위 팀과 미배정만 내보낸다.
값마다 상태·사유를 유지하며 비교 지표는 비교 상태·사유·이전 값·증감을 함께 기록한다.
실패 영역은 값 행을 만들지 않고 errors 절에 기록한다. 빈 기간에는 메타만 내보낸다.
화면 숫자는 표시용 반올림을 사용하지만 CSV는 원본 숫자/금액을 유지한다. 모델 기타는 화면과 같은 합산값이다.

## 날짜별 수집 미완료 사유 (허브 ADR 0018)

완전성 판정 시 같은 snapshot에 사유를 고정한다. 기존 `current_period_incomplete` / `previous_period_incomplete`와 함께 아래 상세 사유를 보낸다. 비교 기간의 상세 코드는 `previous_` 접두사를 붙인다.

| 코드 | 의미 |
|---|---|
| period_pending | 하루의 끝 + 전송·적재 확정 대기 시간이 지나지 않음 |
| retention_deleted | 삭제 경계 이전 구간 포함 |
| usage_collection_disabled | 사용량 수집 정책이 꺼졌거나 정책 근거 없음 |
| no_eligible_installations | 해당 날짜에 수집할 설치가 없음 |
| installation_revoked | 확정 전에 설치가 폐기돼 마지막 전송 확인 불가 |
| collection_gap | 손실 없는 수집 보고 구간으로 하루를 덮지 못함; 실제 사용량 손실을 단정하지 않음 |

미확정 날짜는 `period_pending`으로 판정하고, 확정 시각이 지난 날짜에 나머지 근거를 판정한다. 현재 기간이 대기뿐이면 ‘집계 중’, 수집 근거 문제를 포함하면 ‘수집 불완전’이다. CSV는 같은 상세 사유를 유지한다. 계약 미입력은 이 판정에 영향을 주지 않는다. snapshot query contract는 `dashboard-v5`이며 이전 계약은 409로 다시 조회한다.

## 계산 가능한 관측 부분합 (허브 ADR 0011)

기존 전체 금액·평균·비중·비교의 null 규칙은 유지한다. 별도 `knownEquivalentCostUsd`, `pricedUsageRows`, `unpricedUsageRows`를 반환한다. 개요 summary/trend point와 팀 metrics에서는 부분합이 `Value<string>`, 팀 trend point에서는 nullable USD 문자열이다. 행 수는 해당 범위의 primary 사용 관측 수다. 미가격 행이 있으면 부분합 사유에 `pricing_missing`을 포함한다. 가격 있는 행이 없거나 가격판 혼재면 부분합도 null이며, 완전 관측한 빈 범위만 0이다. 미관측일을 0으로 대체하지 않는다. 부분합은 전체 사용량·실제 청구액이 아니며 평균·비교·알림에 대신 쓰지 않는다.
