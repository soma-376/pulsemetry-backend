# PROJ-156 distribution 지원 범위 결정안

상태: 현재 프론트엔드에 필요한 분포 지원 완료. 지원8개/미지원45개를 유지하며, 미지원 지표의 확장은 추가 요구가 생기면 정의한다.

## 현재 지원 8개

| metric_id | 분포 표본·요약 | distribution 추가 프레임 |
|---|---|---|
| prompts_per_session | 설치·제품·세션별 관측 프롬프트 수의 p50/p90 | 1, 2–3, 4–7, 8–15, 16+ 버킷별 count |
| read_tool_density | 세션별 읽기 도구 호출 수의 p50/p90 | 별도 히스토그램 없음 |
| gate_wait_ms | 유효 tool_gate 대기 시간의 p50/p90, ms | 별도 히스토그램 없음 |
| turn_duration_ms | 유효 turn 소요 시간의 p50/p90, ms | 별도 히스토그램 없음 |
| llm_ttft_ms | 유효 첫 토큰 지연의 p50/p90, ms; 요청 로그 우선·스팬 대체 | 별도 히스토그램 없음 |
| llm_duration_ms | 유효 LLM 소요 시간의 p50/p95/p99, ms | 별도 히스토그램 없음 |
| onboarding_ttfu | 설치 생성→첫 관측 간격의 p50/p90, s | <1h, 1h–1d, 1d–7d, 7d–30d, 30d+ 버킷별 count |
| usage_concentration | 익명 상위 10% 토큰 점유율 요약 | population_share, lorenz_cumulative, usage_share 곡선 |

참고 개요 §2는 분위수 필드 또는 bucket/count를 허용한다. distribution이라고 모든 지표에 히스토그램이 있어야 하는 것은 아니다. 비교 시 대응하는 _compare 필드가 추가되며, 소집단 마스킹은 수치와 곡선/버킷에도 적용된다. 일반 차원·원천·기간·권한 제약은 그대로 적용한다.

## 미지원 45개

아래 지표의 기본 집계가 미구현이라는 뜻이 아니다. 유효한 요청에서 frame_type=distribution을 명시한 경우 results[ref_id].status=501, error.error=metric_not_implemented를 반환한다. 인증·입력 오류는 이 처리보다 먼저 거부될 수 있다. JSON 배치 응답의 개별 결과 상태와 HTTP 상태를 구분한다.

- abandoned_session_ratio
- active_time
- active_users
- adoption_rate
- api_error_rate
- api_retry_attempts
- auto_approval_ratio
- automation_ratio
- cache_read_ratio
- command_prompt_ratio
- commits
- compaction_reduction
- compactions
- contract_commitment_burn
- cost
- cost_anomaly
- cost_per_active_user
- cost_per_user_hour
- edit_acceptance_rate
- hook_blocking
- hook_executions
- input_output_ratio
- integration_depth
- lines_of_code
- llm_stop_reasons
- mcp_connections
- mcp_failure_ratio
- model_unit_price
- model_users
- onboarding_retention
- pull_requests
- rate_limit_events
- refusals
- rubber_stamp_ratio
- session_last_event
- sessions
- subagent_activity
- subagent_cost_ratio
- telemetry_coverage
- tokens
- tool_calls
- tool_failure_rate
- tool_rejections
- usage_heatmap
- vendor_account_mismatch

## 명세에서 필요한 구분

- cost 분포를 확장한다면 호출별 비용, 사람별 기간 비용, 팀별 합계, 일별 합계 중 어느 것을 표본으로 삼는지 먼저 정해야 한다. 이들은 서로 다른 분포다.
- 비율 지표에서는 개별 관측의 비율 분포와 기간의 합계 분자/분모가 다르다. 기존 비율 하나를 임의 표본으로 복제하지 않는다.
- llm_stop_reasons, session_last_event, tool_calls(action)의 범주별 건수는 현재 table과 라벨로 제공한다. 명세의 일반적인 “분포”라는 단어만으로 숫자 히스토그램 요구를 추정하지 않는다.
- 기간별 비용·사람별 사용량 등 새 표본을 선택하면 단위·버킷 경계·누락/0 처리·개인정보 최소 집단·상위 N/기타 재집계·비교 기간 동작을 함께 정해야 한다.

## 선택 가능한 완료 조건

1. 현재 8개를 이번 티켓의 distribution 지원 범위로 확정한다. 나머지 45개는 명시적 미지원으로 유지하고, 이 문서와 지표 지원표를 인수 범위로 사용한다. 기존 구현의 검증 근거는 유지한다.
2. 추가 지표를 지정한다. 각 지표의 표본과 요약/버킷 정의를 확정한 뒤 구현·DB 회귀·frontend 연동 검증을 추가한다. 지정되지 않은 45개 전체를 임의 의미로 구현하지 않는다.

후속 소스 대조에서 현재 frontend는 prompts_per_session(Teams.tsx)·gate_wait_ms(TeamDetails.tsx)를 명시적으로 요청하고, S3-2 시나리오는 usage_concentration 분포를 사용함을 확인했다. 모두 현재 지원 대상이므로 45개 확장 결정을 연동 완료의 필수 차단 조건으로 두었던 설명을 정정한다. UI 감사 입력·비교/잔존율 라벨 결함은 frontend acdc626에서 수정하고 실제 E2E로 검증했다. 해당 검증의 한계는 dashboard-acceptance-matrix.md를 참조한다.

## 대조 근거

DashboardQuery.kt의 sessionMetrics·durationMetrics·usage_concentration 지원 분기와 docs/dashboard-api-contract-matrix.md의 53개 행을 대조해 8개 지원/45개 미지원을 확인했다. 프레임 구성의 실제 분위수·버킷·곡선 필드도 확인했다. 런타임 변경 없이 작성했으며, 직전 86a2a29의 dashboard 266건/E2E를 새로 실행했다고 보고하지 않는다.
