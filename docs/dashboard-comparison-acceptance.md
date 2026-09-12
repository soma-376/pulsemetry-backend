# PROJ-156 전후 비교 시나리오 의미 대조

대상은 S4-4·S5-6·S6-3·S8-6·S8-7이다. 참고 개요 §6, 사용자 확정 사항, 실행 코드와 기존 DB/E2E 검증을 대조했다. 이 문서는 관측 비교의 구현 검토 결과이며 교육 효과·정책 실효성·모델 품질을 입증한 보고서가 아니다.

## 계약과 실제 결과

| 시나리오 | 기간·선택 범위 | 실제 비교 | 해석 한계 |
|---|---|---|---|
| S4-4 교육 효과 전후 비교 | 현지 기준일 전후 window_weeks; 선택 팀 범위 | active_users, prompts_per_session p50, edit_acceptance_rate, mcp_connections의 기간 전체 값 | 교육 참여·구성원 변화·업무 배정을 통제하지 않는다. MCP는 연결 이벤트 관측이며 고유 도구 수나 활용 숙련도가 아니다. |
| S5-6 정책 위반 용도 | 입력 from/to 사이의 pivot_date로 분할; owner 감사 | config/hook 결정의 tool_rejections 건수 | 전후 길이가 다를 수 있다. 건수 차이를 발생률 변화로 보거나 사용자 결정·미수집 주체를 정책 거절에 포함하지 않는다. |
| S6-3 모델 버전 드리프트 | 사용자 확정 전후 4주; 선택 models; owner 감사 | api_error_rate, tool_failure_rate, prompts_per_session p50, stop_reason별 llm_stop_reasons 건수 | 선택 모델 집합의 합산 비교이며 각 모델 버전의 독립 효과가 아니다. 모델명이 없는 이벤트는 모델 필터로 제외된다. 종료 사유 건수 차이는 비율 변화나 품질 저하 판정이 아니다. |
| S8-6 정책 실효성 | 현지 기준일 전후 window_weeks; owner 감사 | tool_rejections, hook_blocking, gate_wait_ms p50 | S5-6과 달리 config/hook 결정으로 제한하지 않는다. 대기 시간은 중앙값이며 S4-8의 p90 임계값 판정과 다르다. 정책 준수·생산성 개선을 확정하지 않는다. |
| S8-7 챔피언 프로그램 | 사용자 확정 전후 4주; 선택 팀 범위 | adoption_rate, prompts_per_session p50, usage_concentration | 개인 챔피언 목록과 인과 효과를 제공하지 않는다. 집중도는 익명 상위 10%의 토큰 점유율이며 개인 기여도가 아니다. |

## 공통 처리

- DashboardScenarioInput.kt가 입력 시점에 기간을 고정한다. 이전 기간은 [compare_from, pivot), 이후는 [pivot, to)로 기준일을 중복 집계하지 않는다. 달력 주와 현지 자정을 사용하므로 DST 전후의 실제 초 수는 같지 않을 수 있다.
- DashboardScenarioRuns.kt의 runComparisonScenario가 기간 전체 table 집계를 비교한다. 일별 비율이나 일별 중앙값을 평균 내지 않는다. S6-3의 종료 사유만 stop_reason 차원으로 나눈다.
- DashboardComparisonFindings.kt는 두 기간에 공개 가능한 유한 수치가 있고 이후 기간이 종료됐을 때만 차이를 제공한다. delta=after-before이며 변화가 0이면 finding을 만들지 않는다. 이전 값 0에 대한 증가 백분율을 만들지 않는다.
- 한쪽 소집단·미관측·미완료 기간이면 판정하지 않는다. findings가 비었다는 사실은 효과 없음·정책 준수·문제 없음의 증명이 아니다. 프레임의 null과 observation_complete를 함께 확인해야 한다.
- 단위는 프레임의 필드 설정에 따른다. p50은 prompts_per_session에서 프롬프트 수, gate_wait_ms에서 ms이고 비율 delta는 비율의 절대 차이다. 비율 차이를 상대 증가율로 읽지 않는다.

## 기존 검증 근거

검증 파일은 apps/dashboard-api/src/test/kotlin/com/team376/pulsemetry/dashboard/ 아래에 있다.

| 파일·테스트 | 확인한 경계 |
|---|---|
| DashboardScenarioInputTest: 전후 비교는 현지 자정과 DST를 보존하고 52주를 각각 고정한다 | 현지 날짜·DST·각 기간 상한 |
| DashboardAuthTest: 교육 전후 비교는 기준일을 중복하지 않고 기간 전체 프롬프트 분포를 비교한다 | 이전 p50=1, 이후 p50=2, delta=1 |
| DashboardAuthTest: 정책 전후 비교는 owner 감사와 거절 수 및 대기 중앙값을 연결한다 | 거절 5→10, 대기 중앙값 차이 200ms, 감사·현재 역할 |
| DashboardAuthTest: 정책 비교는 영 기준값과 감소를 백분율 추정 없이 처리한다 | 0 기준·감소·동일 값 |
| DashboardAuthTest: 전후 비교는 한쪽 소집단 미관측과 미완료 기간을 판정하지 않는다 | 한쪽 4명·빈 기간·미완료·52주 |
| DashboardAuthTest: 정책 용도 비교는 config hook만 집계하고 owner 감사와 기간을 보존한다 | 결정 주체 필터·불균등 기간·기준일 경계 |
| DashboardAuthTest: 정책 용도 비교는 소집단과 사용자 결정만 있는 기간을 추정하지 않는다 | 사용자 결정 제외·소집단·빈 데이터 |
| DashboardAuthTest: 드리프트 비교는 선택 모델과 종료 사유별 전후 건수를 보존한다 | 선택 밖 모델 제외·종료 사유 5→10·owner 감사 |
| DashboardAuthTest: 챔피언 비교는 전후 프롬프트와 사용 집중도를 비교하고 개인을 반환하지 않는다 | 프롬프트 1→2·집중도 변화·설치 식별자 미반환 |
| DashboardAuthTest: 고정 4주 비교는 소집단 미관측과 미완료 기간을 판정하지 않는다 | S6-3/S8-7 소집단·미관측·미완료 |

최신 런타임 86a2a29의 dashboard 테스트 266건과 E2E가 통과했다. scripts/e2e/dashboard-ingest.mjs는 위 5개 시나리오를 실제 frontend 클라이언트로 실행하고 결과 화면으로 이동한다. 동적 현재 시점 fixture에는 이전 기간 미관측 또는 이후 기간 미완료가 포함되므로, 양쪽 기간의 유효한 수치 차이는 위 DB 테스트가 근거다. P3 실행은 공통 request에 감사 사유를 전달한 경로이며 일반 시작 폼의 감사 입력 완료를 뜻하지 않는다.

## 판정과 후속 범위

이 5개 시나리오의 기간·집계 통계·판정 조건·관측 한계에 대한 소스 및 기존 테스트 대조를 완료했다. 새 런타임 결함이나 추가 판정 공식을 도입할 근거는 발견하지 못했다. 전체 제품 수용은 별개이며 일반 UI 감사 입력, 실제 비교 화면의 라벨·빈 값·마스킹 시각 확인, 나머지 시나리오 의미 대조와 미지원 distribution의 정의는 남아 있다.
