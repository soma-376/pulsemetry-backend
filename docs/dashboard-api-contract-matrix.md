# Dashboard QRY 계약 추적표

이 표는 현재 코드의 명시적 지원 분기를 정리한 구현 인벤토리다. 모든 지표·차원·필터 조합이 E2E 검증됐다는 뜻이 아니다.

- 기준: `docs/reference/pulsemetry_api_spec.yaml` Query.limit / frame_type와 런타임 `metrics.json`.
- 집계·프레임·권한 구현: `apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/DashboardQuery.kt`.
- 실제 DB 회귀 테스트: `apps/dashboard-api/src/test/kotlin/com/team376/pulsemetry/dashboard/DashboardAuthTest.kt`.
- 실제 frontend 클라이언트·수집 검증: `scripts/e2e/dashboard-auth-settings.mjs`, `scripts/e2e/dashboard-ingest.mjs`.
- scalar/table 기본 집계 53개 연결. 아래 표는 추가 형식과 상위 N의 명시적 지원을 구분한다. 입력 기간·차원·원천·권한 제약은 별도로 적용한다.

| 지표 | 기본 형식 | timeseries | distribution | limit 초과 기타 재집계 |
|---|---|---|---|---|
| telemetry_coverage | scalar | 연결 | 501 | 연결 |
| active_users | timeseries | 연결 | 501 | 연결 |
| adoption_rate | scalar | 연결 | 501 | 연결 |
| sessions | timeseries | 연결 | 501 | 연결 |
| active_time | timeseries | 연결 | 501 | 연결 |
| automation_ratio | scalar | 연결 | 501 | 연결 |
| prompts_per_session | distribution | 연결 | 연결 | 연결 |
| command_prompt_ratio | scalar | 연결 | 501 | 연결 |
| lines_of_code | timeseries | 연결 | 501 | 연결 |
| commits | timeseries | 연결 | 501 | 연결 |
| pull_requests | timeseries | 연결 | 501 | 연결 |
| integration_depth | scalar | 연결 | 501 | 연결 |
| cost | timeseries | 연결 | 501 | 연결 |
| cost_anomaly | scalar | 연결 | 501 | 연결 |
| cost_per_active_user | scalar | 연결 | 501 | 연결 |
| cost_per_user_hour | scalar | 연결 | 501 | 연결 |
| tokens | timeseries | 연결 | 501 | 연결 |
| cache_read_ratio | scalar | 연결 | 501 | 연결 |
| input_output_ratio | scalar | 연결 | 501 | 연결 |
| subagent_cost_ratio | scalar | 연결 | 501 | 연결 |
| model_users | table | 연결 | 501 | 연결 |
| model_unit_price | table | 연결 | 501 | 연결 |
| contract_commitment_burn | scalar | 연결 | 501 | 일반 group_by 금지; 계약별 제한 별도 |
| compactions | timeseries | 연결 | 501 | 연결 |
| compaction_reduction | scalar | 연결 | 501 | 연결 |
| edit_acceptance_rate | scalar | 연결 | 501 | 연결 |
| auto_approval_ratio | scalar | 연결 | 501 | 연결 |
| gate_wait_ms | distribution | 연결 | 연결 | 연결 |
| tool_rejections | timeseries | 연결 | 501 | 연결 |
| rubber_stamp_ratio | scalar | 연결 | 501 | 연결 |
| mcp_connections | timeseries | 연결 | 501 | 연결 |
| mcp_failure_ratio | scalar | 연결 | 501 | 연결 |
| subagent_activity | timeseries | 연결 | 501 | 연결 |
| api_error_rate | scalar | 연결 | 501 | 연결 |
| api_retry_attempts | scalar | 연결 | 501 | 연결 |
| rate_limit_events | timeseries | 연결 | 501 | 연결 |
| tool_calls | timeseries | 연결 | 501 | 연결 |
| tool_failure_rate | scalar | 연결 | 501 | 연결 |
| read_tool_density | distribution | 연결 | 연결 | 연결 |
| turn_duration_ms | distribution | 연결 | 연결 | 연결 |
| llm_ttft_ms | distribution | 연결 | 연결 | 연결 |
| llm_duration_ms | distribution | 연결 | 연결 | 연결 |
| llm_stop_reasons | table | 연결 | 501 | 연결 |
| hook_executions | timeseries | 연결 | 501 | 연결 |
| hook_blocking | timeseries | 연결 | 501 | 연결 |
| refusals | timeseries | 연결 | 501 | 연결 |
| vendor_account_mismatch | scalar | 연결 | 501 | 해당 없음(group_by 금지) |
| usage_heatmap | table | 연결 | 501 | 연결 |
| usage_concentration | table | 연결 | 연결 | 연결 |
| onboarding_ttfu | distribution | 연결 | 연결 | 연결 |
| onboarding_retention | table | 501 | 501 | 미연결 |
| abandoned_session_ratio | scalar | 연결 | 501 | 연결 |
| session_last_event | table | 연결 | 501 | 연결 |

## 상위 N 검증 기준

- 기타 재집계 지원 지표는 50개다. 현재 기간 값으로 상위 그룹을 선택하고 비교 기간에 같은 선택을 적용한다. 숨겨진 수치는 순위 선택에 사용하지 않는다.
- 합산 불가 비율·고유 인원·백분위수는 원본 재집계를 유지한다. 나머지 지표의 상위 N은 자동 합산으로 대체하지 않는다.
- 이번 토큰 검증: table/scalar/timeseries 비교, 두 차원(model/type), metrics 원천·누적 제외·종류 필터, 소집단과 기타 마스킹, frontend 동률 선택·기타 합계.

## 남은 계약 확인

- 미연결 상위 N 지표의 전체 기간 순위와 기타 집계 의미를 결정하고 구현한다.
- distribution은 지표별 분포 정의가 필요하다. 현재 501 분기를 모든 숫자 지표에 임의 히스토그램을 붙여 대체하지 않는다.
- onboarding_retention의 timeseries 표현, 366일을 넘는 계약 기간, W3.3 주소 테이블과 개인정보 감사 흐름은 추가 대조가 필요하다.
- 22개 operation과 46개 시나리오의 상세 수용 조건·운영 복구 추적표는 별도 보완 대상이다.

- 거부·훅 추가 검증: 거부 owner 인가·두 차원 기타, 훅 table/scalar/timeseries 비교·전체 세션 분모·기타 세션 중복 제거, 두 지표의 소집단 마스킹과 frontend 클라이언트 변환.

- 세션 분포 추가 검증: 기간 전체 p50 순위, 기타 p50/p90·히스토그램 원본 재계산, 비교 그룹 고정, 다중 팀 같은 이벤트 중복 제거, 무관한 이벤트로 소집단 해제 방지.

- 인원·설치 추가 검증: 기간 전체 고유 인원·설치 기준 순위, 날짜·팀 중복 제거, 비교 그룹 고정, 활성 시간 양수·음수 상쇄와 기타 소집단. 커버리지 분모는 기존 현재 범위의 활성 설치 수를 유지한다.

- 도입률 기타 분모는 현재·비교 기간에서 기타로 합쳐지는 팀들의 현재 활성 구성원 합집합이다. 미관측 구성원도 포함하고 두 기간에 동일한 분모를 적용한다. 상위 선택은 기간 전체 도입률을 기준으로 한다.

- 서브에이전트 활동: 기간 전체 고유 agent_id로 순위를 정하고 기타의 식별자·도구 호출을 원본에서 중복 제거한다. 실제 도구 호출 구성원으로 소집단을 판단한다. 일반 그룹 상위 N 미연결은 1개이며, group_by 금지 2개는 이 수에 포함하지 않는다. 약정 소진율의 내부 계약별 결과·기간 제한 검토는 별도 유지한다.

- 무산출 세션 비율: 기간 전체 세션 비율로 상위를 선택한다. 기타의 설치·제품·세션을 합친 뒤 산출 여부를 재판정하고 마지막 로그 날짜에 배치한다. 유효 로그 세션 구성원 수와 기존 마스킹 기준을 함께 적용한다.

- 첫 사용 시간: 기간 전체 p50으로 상위를 선택하고 기타의 설치별 최초 관측으로 분위수와 히스토그램을 다시 계산한다. 기타 팀 간 같은 설치는 한 번만 포함하고 과거 이력·생성 시각·소집단 기준을 유지한다.

- 사용 집중도: 기간 전체 사용자별 토큰 점유율로 순위를 정한다. 기타 팀의 중복 관측을 제거하고 같은 사람의 사용량을 합쳐 상위 10% 점유율과 로렌츠 곡선을 다시 계산한다.

- 비용 이상: scalar와 같은 마지막 관측일 증가율로 상위를 선택한다. 기타의 일별 비용을 다시 집계하고 이동평균·증가율을 계산하며 기준일 누락·영 평균·소집단 처리를 유지한다.

- 마지막 세션 이벤트: 현재 기간 세션 수로 상위 팀을 선택하고 기타의 설치·제품·세션별 마지막 로그를 다시 선택한다. limit는 요청 팀 그룹에 적용하며 파생 last_event 유형은 잘라내지 않는다.
