# 자동 시간 추이 검증·배포 인수

[자동 API 계약](api/reference/auto-trends.md)과 [ADR 0077](adr/0077-자동-추이는-발생-시각으로-일괄-집계하고-지표별-누적값을-제공한다.md)을 따른다. backend는 기존 day 경로를 유지하고 호환 frontend의 빌드 설정으로 사용자 자동 요청을 제어한다.

## 검증 기준

| 영역 | 필요한 증거 |
|---|---|
| 간격·경계 | 1·2·3·7·8·15·16·30·31·60·61·180·181·366일, KST 자정, 마지막 짧은 구간, asOf 직전·정각·직후 |
| 입력·합계 | 현재·비교의 같은 source 선택, 미래 이벤트 제외, 개요 버킷 합계와 summary, 팀 마지막 유효 누적과 metrics 일치 |
| 누적 | 같은 세션의 반복·product/namespace/팀 구별·미배정·ID 결손, 새 기록 없는 중간 구간, 지표별 결손 전파 |
| 값 | 명시 0과 기록 없음 구별, 부분·전부 미환산, 비호환 토큰 의미, Decimal 문자열 보존 |
| API·CSV | 생략/day 회귀, unsupported bucket 400, 모드 불일치·만료 409, 페이지·상세·사용자 같은 snapshot, 만료 시 CSV 전체 재시작 |
| 화면 | 전체 계획 6~12개·현재 표시 0~12개, 실제 시간 길이·KST 툴팁·키보드 탐색, 비용·토큰 탭, 세션 데이터·CSV 유지, 누적 이중 합산 없음 |
| 효율 | 팀·버킷 수에 비례하는 반복 SQL 없음 |
| PROJ-315 통합 | 실제 정책 버전·asOf·영향 범위·사유·근거 참조가 같은 snapshot에 고정되고 값 공개 정책과 일치 |
| PROJ-316 연계 | 보고 공백만으로 값 숨기지 않음, 168시간 페이로드 손실을 시간 버킷·팀·이벤트·토큰으로 배분하지 않음 |

[공통 fixture](api/examples/auto-trend.fixture.json)는 명시적인 이벤트 시각·금액·세션과 기대 자동 point를 포함한다. 전체 응답 캡처가 아니며 다른 summary·비교 필드는 실제 API 검증에서 확인한다. 일별 fixture를 나누거나 세션 수를 비례 배분하지 않는다.

backend에서 `./gradlew :apps:dashboard-api:test`를 실행한다. frontend에서는 `npm run format:check`, `npm run lint`, `npm run typecheck`, `npm test`, `npm run test:browser:mock`을 실행한다. 실제 API의 개요·팀·CSV E2E를 별도로 실행하고 명령·대상 빌드·결과·미실행 항목을 검증 보고에 남긴다. mock 성공을 실제 API 또는 배포 검증으로 기록하지 않는다.

## 사용자 활성화

1. backend의 day/auto 호환 API를 먼저 배포하고 양쪽 요청을 smoke 검증한다.
2. PROJ-315의 실제 원천 정책과 공통 annotation 통합 검증 증거를 확인한다. `proj-315-pending`이나 빈 evidenceRefs fixture는 통합 증거가 아니다.
3. 실제 API로 개요·팀 목록·상세·사용자·CSV를 같은 snapshot에 묶어 검증한다. 기존 세션 지표와 PROJ-316 알림·수집 헤더도 호환성을 확인한다.
4. 위 증거가 있는 배포에서만 `NEXT_PUBLIC_AUTO_TREND_ENABLED=true`로 frontend를 빌드·배포한다. 기본값은 false이며 런타임 환경 변수만 바꿔 기존 빌드가 전환됐다고 판단하지 않는다.

개발·mock 환경의 auto 검증은 허용한다. PROJ-316 알림 기능 전체 완료를 자동 집계 활성화의 일괄 선행 조건으로 삼지 않는다.

## 복구

PROJ-316 알림 파서와 PROJ-318 비용·토큰 탭 구성을 유지하는 호환 frontend를 `NEXT_PUBLIC_AUTO_TREND_ENABLED=false`로 다시 빌드·배포한다. 요청이 생략/day로 전환되고 일별 화면·CSV와 기존 세션 데이터가 유지되는지 확인한다.

호환성이 없는 과거 frontend로 되돌리지 않는다. 기존 backend day 경로·세션 API를 유지하므로 원본 데이터 복구·마이그레이션 역적용은 필요하지 않다. 기존 자동 snapshot을 day 요청에 재사용하지 않고 새 day snapshot을 받는다.

## 현재 상태

PROJ-315 공통 원천 정책은 pending adapter로 명시한다. 사용자 자동 활성화는 그 실제 정책의 통합 검증 전까지 비활성이다. 이 문서는 실행·배포 절차이며 로컬 테스트 통과나 운영 배포 완료를 선언하지 않는다.

## 자동 버킷 2판

기존 버킷·미래 표시·metadata 규칙을 허브 ADR 0033에 따라 대체한다.

전체 선택 기간의 계획은 최소 6개·최대 12개다. 간격은 1일=3시간, 2일=6시간, 3~6일=12시간, 7~12일=1일, 13~24일=2일, 25~36일=3일, 37~84일=7일, 85~168일=14일, 169~366일=달력 1개월이다. KST 조회 시작을 기준으로 경계를 만든다. 월 경계는 원래 시작일에 N개월을 더하고 해당 월의 마지막 날로 보정한다. 13번째 짧은 구간은 12번째에 합친다.

간격은 전체 선택 기간으로 정하고 표시 종료는 effectiveEnd=max(rangeStart,min(rangeEnd,asOf))다. 시작이 effectiveEnd 이상인 버킷은 반환하지 않는다. 마지막 끝은 effectiveEnd로 자른다. 원래 계획 종료보다 asOf가 이르면 in_progress, 아니면 elapsed다. 정각에는 길이가 0인 다음 구간을 만들지 않는다. 미래 전체 기간은 빈 배열이며 이미 지난 빈 구간의 값은 no_data로 보존한다. 최소 6개는 전체 계획에만 적용되며 현재까지 응답은 0~12개다.

meta.trendAggregation은 policyVersion=calendar-buckets-v2, intervalSeconds(고정 간격은 초·월은 null), intervalMonths(월은 1·고정 간격은 null), effectiveEnd를 제공한다. rangeStart/rangeEnd는 전체 선택 기간을 유지한다. 차트 축의 끝은 effectiveEnd이며 구간의 정확한 끝을 툴팁과 CSV에 표시한다. 월 구간을 30일로 환산하지 않는다. 자동 snapshot 계약은 dashboard-auto-trend-v2이며 이전 자동 계약과 혼용하지 않는다. 일별 모드와 PROJ-315 활성화 gate는 유지한다.
