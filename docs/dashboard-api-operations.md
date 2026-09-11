# Dashboard API 실행

현재 제공 범위와 미구현 항목은 [진행 기록](dashboard-api-progress.md)을 따른다.

## 설정

`./gradlew :apps:dashboard-api:bootRun`은 기본 8081 포트를 사용한다.

| 설정 | 의미 |
|---|---|
| `PULSEMETRY_DB_URL`, `PULSEMETRY_DB_USERNAME`, `PULSEMETRY_DB_PASSWORD` | PostgreSQL 연결 |
| `pulsemetry.dashboard.tenant-id` | 필수 고정 tenant UUID |
| `pulsemetry.dashboard.issuer` | JWT issuer |
| `pulsemetry.dashboard.active-kid` | 활성 키 ID |
| `pulsemetry.dashboard.private-key-file` | RSA 2048비트 이상 PKCS8 PEM 경로 |
| `pulsemetry.dashboard.public-key-files.<kid>` | 허용된 X509 PEM 공개키 경로 |
| `pulsemetry.dashboard.allowed-origins` | 실제 frontend origin 목록 |
| `pulsemetry.dashboard.clickhouse-url` | 기본 `http://127.0.0.1:8123` |

Spring 설정 파일이나 `SPRING_APPLICATION_JSON`으로 전달한다. 키 본문을 저장소에 넣지 않는다.
웹 audience는 `pulsemetry-dashboard`이며 CLI와 세션 종류까지 구분한다. 웹 세션은 8시간, RT는 발급하지 않는다.
키 교체 시 기존 공개키는 최소 8시간 30초 유지한다. CLI의 기존 330초 겹침만으로는 웹 토큰을 보존할 수 없다.

## 스키마

enrollment의 기존 Flyway 이력과 dashboard 이력은 분리된다. 앱이 enrollment 적용을 완료한 뒤
`db/dashboard`를 `dashboard.flyway_schema_history`에 적용한다. ClickHouse 스키마 생성은 ingest의 소유이며 dashboard는 수행하지 않는다.

## 실제 브라우저 smoke

형제 frontend의 의존성과 Playwright Chromium, JDK 25, Docker가 준비된 환경에서 실행한다.

```sh
./gradlew :apps:dashboard-api:bootJar :apps:telemetry-ingest:bootJar
node scripts/e2e/dashboard-auth-settings.mjs
```

스크립트는 격리된 PostgreSQL·ClickHouse를 생성하고 실제 frontend를 real 모드로 실행한다.
테스트 전용 계정을 사용하고 종료 시 컨테이너·임시 키를 제거한다. 포트는 API 18081, frontend 15173, ingest 14316이다.
로그·스크린샷·판정은 `build/e2e/auth-settings`에 남는다. 현재 범위는 인증·P5 메타, 실제 frontend API 클라이언트의 53개 지표 카탈로그 및 합계 5개·활성 사용자·도입률·커버리지 및 자동화·개발 연동·명령 프롬프트 비율 및 프롬프트 분포·도구 호출 관련 지표 및 API 재시도·429·도구 결정 및 API 오류율·사용량 히트맵 및 컨텍스트 압축 및 MCP 연결·LLM 종료 사유·소요 시간·첫 토큰 지연·승인 대기·즉시 승인·편집 수락률·서브에이전트 활동·훅 차단 수·훅 실행·모델 거부·모델 사용자·토큰 비율·토큰 사용량·무산출 세션·마지막 이벤트·사용 집중도·첫 사용 시간·잔존율·벤더 불일치·비용·서브에이전트 비용 비율·사용자·시간당 비용·모델 단가·비용 이상·약정 소진율 총 53개 지표다. 공통 49개는 owner/admin 양쪽에서, 비용 이상 1개는 admin에서, 모델 거부·벤더 불일치·약정 소진율 3개는 owner에서 검증한다. 53개 지표 fixture는 정규화 포인트를 ClickHouse에 직접 적재한다. 별도의 실제 OTLP logs·metrics·traces 검증도 포함하지만 전체 신호·화면 및 PROJ-156 수용 E2E를 대체하지 않는다. 미구현 지표의 쿼리 단위 오류도 결과에 기록한다.

약정 소진율은 owner의 필터 없는 전사 범위에서만 조회한다. 날짜만 있는 from/to는 요청 시간대 자정으로 해석한다. USD 이외 약정 통화는 422이며 날짜 범위는 기존 366일 제한을 따른다. P5 약정 게이지의 실제 렌더도 smoke에서 확인한다.

설치 목록은 `GET /v1/installations`이며 owner와 `X-Audit-Reason`이 필수다. 사유는 URL 인코딩한 10~500자 문자열로 전달한다. `limit`(1~500), `cursor`, `inactive_days`, `team_id`, `platform`, `status`를 지원한다. 설치 이메일은 도메인만 노출한다. 미관측 설치의 무활동 기간은 생성일부터 계산한다. 요청당 후보 5,000개 초과는 422이므로 필터로 범위를 좁힌다. 기존 frontend 설치 카드는 감사 헤더를 전달하지 않으므로 현재 smoke는 실제 API 클라이언트의 명시적 감사 조회만 검증하며 카드 렌더 검증은 아니다.

세션 상관 조회는 `GET /v1/sessions/{key}/events`이며 owner와 `X-Audit-Reason`이 필수다. `lookup=session_id|request_id|call_id|installation_id`, `from`, `to`, `limit`(1~500), `cursor`를 지원한다. 기본 기간은 최근 7일이다. 다중 세션 일치는 422, 미발견은 404다. 다음 페이지에는 동일한 검색 키·기간 식과 감사 사유를 전달한다. 커서가 상대 기간의 해석 결과를 고정한다. API의 ended_at은 마지막 관측 시각이다. smoke는 실제 opsApi.events 및 eventDetails 변환을 검증하며 SessionSearch 전체 화면 검증은 별도다.

## 시나리오 정의 조회

`GET /v1/scenarios`와 `GET /v1/scenarios/{scenario_id}`는 로그인한 owner/admin에게 46개 정의를 제공한다. 목록은 `category`, `availability`, `target_page`, `q`를 지원한다. 상세의 `params_schema`는 frontend 폼과 시나리오 서버 입력 검증에서 공통으로 사용한다. S1-1·S1-3·S1-4·S1-5·S4-1·S4-2·S4-8·S7-1의 실행 계획과 실행 목록·저장 API를 제공한다. 다른 시나리오의 실행 계획은 후속 작업이다.

동일 smoke는 owner/admin의 실제 `scenarioApi`로 46개 목록·상세와 지표 메타 일치를 검증하고 S1-3 폼 검증 함수를 실행한다. 결과의 `verifiedScenarios`에서 확인한다. 카탈로그 화면 전체 렌더는 포함하지 않으며, S1-3 실행 결과 검증 범위는 아래와 같다.

## S1-3 실행 워커

`POST /v1/scenarios/S1-3/runs`에 `{"params":{"from":"now-28d","to":"now","moving_avg_days":7,"spike_threshold_pct":200},"price_basis":"list"}`를 보내면 202와 실행 ID를 반환한다. `Location`을 2초 간격으로 조회하고, 취소는 `POST /v1/scenario-runs/{id}/cancel`로 요청한다. `wait=true`는 최대 20초까지만 기다린다.

워커는 기본 활성화이며 `pulsemetry.dashboard.worker-enabled=false`로 해당 인스턴스의 claim을 중단할 수 있다. 접수는 계속 가능하므로 유지보수 시 활성 실행 3개 상한에 유의한다. queued는 DB에 남고 running lease가 만료되면 다음 워커 claim 시 실패 처리된다. 재시도는 새 실행 요청으로 한다. 취소는 실행 중인 DB 조회의 즉시 중단을 보장하지 않으며 결과 저장을 차단한다.

현재 S1-1, S1-3, S1-4, S1-5, S4-1, S4-2, S4-8, S7-1을 실행할 수 있다. 다른 available/partial 카탈로그 항목은 아직 501을 반환한다. availability는 데이터 산출 가능성을 뜻하며 실행 구현 상태와 다르다.

E2E 스크립트는 각 외부 명령을 60초로 제한한다. `result.json`은 최신 시도의 상태이며 이전 성공은 `last-success.json`에 보관한다. S1-3 실행·폴링·취소와 실측 판정은 실제 frontend 클라이언트로 검증했다. 2026-09-11 Docker 재시작 후 backend `cd057dc` / frontend `52f7cb1`에서 owner/admin의 결과 화면 판정 표시까지 통과했다. 스크린샷은 `owner-scenario.png`, `admin-scenario.png`다. W1.3·W2.5의 결과 미연결 안내는 남아 있어 모든 위젯의 시각화 완료를 검증한 것은 아니다.


S1-3 실행은 팀별 비용 조회를 포함해 4단계이며 cost 결과에 모델별 일 시계열과 팀별 기간 합계 table 프레임을 함께 반환한다. W1.3의 실제 막대그래프와 데이터 표는 owner/admin E2E에서 확인했다. W2.5의 frontend 매핑은 아직 미연결이다.


## 실행 이력 조회

`GET /v1/scenario-runs`는 `scenario_id`, `status`, `created_by`, `limit`(1~500, 기본 50), `cursor`를 지원한다. 최신순이며 owner는 tenant 내 실행, admin은 현재 팀 권한으로 읽을 수 있는 본인 실행만 반환한다. 다음 페이지에는 같은 필터를 유지한다. 팀 권한·사용자·필터가 바뀌면 커서를 버리고 첫 페이지부터 조회한다. 응답은 결과 프레임 없는 요약이며 total은 null이다. 실제 frontend 이력 화면의 목록·저장 열기·삭제 흐름은 아래 smoke로 검증한다.


## 저장 리포트와 삭제

`POST /v1/scenario-runs/{id}/save`에 `{"name":"비용 분석","note":"메모","time_mode":"fixed"}`를 보내 완료된 실행을 보관한다. name은 1~100자, note는 최대 2,000자다. relative 모드는 원래 params에 now 상대식이 있어야 하며 frontend에서 열 때 새 실행을 요청한다. fixed는 기존 결과를 연다.

`GET /v1/saved-reports`는 limit(기본 50, 최대 500)과 cursor로 최신순 목록을 반환한다. admin은 원본 실행에 대한 현재 팀 권한이 필요하다. `DELETE /v1/saved-reports/{saved_id}`는 현재 실행 조회 권한을 가진 저장자 또는 owner에게 허용하며 원본 실행은 유지한다. `DELETE /v1/scenario-runs/{id}`는 queued/running 또는 저장 항목이 연결된 실행에 409를 반환한다. 저장 항목을 먼저 삭제한 후 종료된 실행을 삭제할 수 있다. 삭제 성공은 204다.

실제 frontend smoke는 owner/admin의 저장 대화상자·이력 화면·고정 결과 열기·상대 기간 새 실행·삭제 대화상자를 확인한다. 삭제 취소 시 요청 없음, 확인 시 단일 DELETE와 행 제거, 저장 항목 삭제 후 원본 결과 보존도 검증한다. 화면은 `build/e2e/auth-settings/{owner,admin}-history.png` 및 `{owner,admin}-history-deleted.png`에 남는다. ingest 전체 경로는 이 검증에 포함하지 않는다.


## 비용 그룹 상위 N

`/v1/query`의 `cost` 쿼리에 `group_by`와 `limit`을 지정하면 그룹 초과 시 상위 N 및 `__other__`를 받는다. 예: `{"ref_id":"A","metric_id":"cost","group_by":["model"],"frame_type":"table","limit":5}`는 최대 6개 그룹을 반환한다. 현재 기간의 공개 가능한 비용 합계로 선택하며, 마스킹 대상 그룹은 순위 점수 0을 사용한다. 비교 기간도 같은 그룹 구성을 사용한다. 나머지 인원과 비용은 원본에서 다시 계산하므로 나머지가 5명 미만이면 수치가 null이다. 다른 지표에는 아직 이 동작을 적용하지 않았다.


## 합계·건수 그룹 상위 N

비용에 더해 `sessions`, `active_time`, `lines_of_code`, `commits`, `pull_requests`, `tool_calls`, `rate_limit_events`, `tool_rejections`, `usage_heatmap`, `compactions`, `mcp_connections`, `llm_stop_reasons`, `hook_blocking`도 상위 N과 `__other__`를 지원한다. 현재 기간 합계로 선택하고, 현재 또는 비교 기간에 마스킹된 그룹은 순위 점수 0을 사용한다. 복수 차원은 조합 단위로 선택하며 비교 기간에도 같은 조합을 유지한다.

누적 포인트 제외·도구 성공 필터·인원 중복 제거는 나머지 원본 재집계에도 적용한다. 히트맵의 weekday/hour 기본 168칸은 유지하며, limit=100을 명시하면 최대 100칸과 나머지 합계 한 그룹을 반환한다. 비율·백분위·고유 인원 등 다른 지표의 나머지 처리는 아직 미구현이다.


## 비율 그룹 상위 N

`automation_ratio`, `integration_depth`, `command_prompt_ratio`, `tool_failure_rate`, `api_retry_attempts`, `auto_approval_ratio`, `api_error_rate`, `compaction_reduction`, `mcp_failure_ratio`, `rubber_stamp_ratio`, `edit_acceptance_rate`, `cache_read_ratio`, `input_output_ratio`에도 상위 N·`__other__`를 지원한다.

선택 기준은 현재 기간의 분자 합계 ÷ 분모 합계다. 일별 비율을 단순 합산하거나 평균하지 않는다. 분모가 0이거나 현재/비교 기간 마스킹 대상인 그룹의 순위 점수는 0이다. 결과의 영 분모는 기존처럼 null이며, 나머지는 원본을 합쳐 분자·분모와 보호 인원을 다시 계산한다. 비용/인원·비용/시간·백분위·코호트 등 별도 계산 지표는 아직 이 목록에 포함되지 않는다.


## 비용 파생 지표 상위 N

`cost_per_active_user`, `cost_per_user_hour`, `model_unit_price`, `subagent_cost_ratio`에도 상위 N·`__other__`를 지원한다. 순위는 현재 기간 전체의 비용/사용자, 비용/시간, 비용/토큰, 서브에이전트 비용/전체 비용으로 정한다. 시계열 요청도 기간 전체를 별도로 집계하여 사용자 수를 중복 제거하므로 일별 사용자 수나 단가를 단순 합산하지 않는다.

나머지는 원본에서 동일 가격 기준·팀 범위로 다시 집계한다. 비교 기간은 같은 상위 그룹을 사용하며 마스킹 및 영 분모 null은 유지한다. 시계열 상위 선택용 조회도 기존 요청 시간 예산 안에서 실행한다. 고유 인원·백분위·코호트 등 나머지 지표는 아직 별도 확장이 필요하다.


## 모델 사용자·소요 시간 상위 N

`model_users`, `llm_duration_ms`, `turn_duration_ms`, `llm_ttft_ms`, `gate_wait_ms`도 상위 N·`__other__`를 지원한다. 모델 사용자는 기간 전체의 고유 사용자 수, 시간 지표는 기간 전체 p50이 큰 순서로 선택한다. 시계열도 일별 사용자 수나 백분위수를 더하지 않고 별도의 기간 집계로 선택한다.

나머지의 사용자와 백분위수는 원본에서 다시 계산한다. 기존 TTFT 로그 우선 선택, 오류·음수·누락 관측 제외, 최소 5명 마스킹과 비교 기간 그룹 고정을 유지한다. onboarding_ttfu와 세션별 분포·코호트 등 별도 계산 지표는 아직 이 확장 범위에 포함하지 않는다.


## 실제 수집부터 frontend까지 검증

`dashboard-ingest.mjs`는 smoke 마지막 admin 세션에서 실제 telemetry-ingest 앱을 같은 격리 DB에 연결한다. 토큰 해시 등 인증 fixture만 PostgreSQL에 만들고, 검증 대상 텔레메트리 행은 `/v1/logs`, `/v1/metrics`, `/v1/traces`로만 전송한다. 검증 항목은 잘못된 토큰 401, 자기신고 신원 덮어쓰기, 팀 as-of 보강, 동일 요청 재전송 중복 제거, frontend 실제 클라이언트의 4명 마스킹과 5명 공개 집계다. `result.json.verifiedIngest`와 `ingestJarSha256`, `ingest.log`에 증거를 남긴다. metrics는 delta 비용 1~5 USD의 합계 15와 cumulative 999의 제외를, traces는 TTFT 100~500ms의 p50=300/p90=500을 검증한다. 세 신호 모두 잘못된 인증을 거부하며 동일 바이트를 두 번 전송해도 저장 행은 logs 5개, metrics 10개, spans 5개다(아래 시나리오용 비용 로그를 포함하면 logs 10개, 전체 25행). 토큰 발급/enroll, 다른 벤더·이벤트 종류, 다른 시나리오와 전체 화면 검증은 이 범위에 포함되지 않는다.


## 실제 수집 데이터의 S1-3 실행

기존 smoke가 끝난 후 이번 실행 전용 ClickHouse 테이블을 비워 직접 주입 데이터를 제거한다. 이후 OTLP로만 도구 호출·비용 로그, 비용 metrics, TTFT traces를 전송한다. 비용 로그는 5명이 1~5 USD씩 사용하고 1명만 attempt=2인 고정 데이터다. 실제 frontend 클라이언트로 S1-3을 시작하고 queued→succeeded, 모델·팀 비용 각각 15 USD, 재시도 비율 0.2와 retry_cost 판정을 확인한다. `/runs/{id}` 화면의 판정 제목과 W1.3 팀별 비용 $15.00을 검증하고 `admin-ingest-scenario.png`를 저장한다. `result.json.verifiedIngest.scenario`가 실행 ID와 검증 범위를 담는다. 이동평균 급증과 모델 비중 변화의 실제 수집 시계열, 다른 시나리오와 저장·재실행의 수집 기반 확장은 아직 범위 밖이다.


## S1-5 컨텍스트 사용 검토

`POST /v1/scenarios/S1-5/runs`는 from/to/team_ids와 `io_ratio_threshold`(기본 10)를 받는다. 입력/출력 토큰 비율, 압축 횟수, 압축 감소율을 일별 시계열로 조회하는 3단계 실행이다. P2와 W2.6/W2.9를 반환한다. 현재 역할·팀 권한을 각 조회와 결과 저장 전에 재검증하며 기존 큐·취소·저장 API를 공유한다.

첨부 명세에는 지표·임계값 입력만 있고 상세 판정식은 없으므로 다음 운영 규칙을 명시적으로 채택했다: 유효 입력/출력 비율이 임계값을 **초과**하면 `high_io_ratio` 정보 판정을 만든다. 임계값과 같거나 이하, 분모 0, 결측, 마스킹에서는 판정하지 않는다. 모든 판정에 관측 한계를 포함하며 `availability=partial`을 유지한다. 첨부량·@멘션·원문은 관측하지 않으므로 과다 첨부 사실이나 낭비로 단정하지 않는다.

실제 OTLP api_request의 입력 200/출력 10을 5명에게 수집한 E2E는 비율 20, 기본 임계값 10, 비동기 완료 및 실제 frontend 판정 화면을 확인한다. 압축 지표 값과 임계값 경계·소집단·분모 0은 DB 통합 테스트에서 검증한다. 증거는 `verifiedIngest.contextScenario`와 `admin-ingest-context-scenario.png`다.

현재 frontend는 input_output_ratio를 W2.6에 연결하지 않아 별도 표를 표시한다. 또한 표의 ratio 단위 포맷이 20을 2,000으로 표시한다. 판정 근거 ratio=20은 정상이며 이 E2E는 모든 위젯·숫자 포맷의 완성을 의미하지 않는다.


## S7-1 에이전트 활동 검토

`POST /v1/scenarios/S7-1/runs`는 from/to/team_ids와 `failure_threshold`(기본 0.05, 0~1)를 받는다. tool_failure_rate, tool_calls, subagent_activity, cost 순서의 일별 시계열 4단계이며 P2/W2.10/W2.5를 반환한다. S1-5와 임계값 시나리오 실행기를 공유하되 명시적으로 허용한 시나리오만 실행한다. 각 단계와 저장 직전 현재 권한을 확인한다.

첨부 명세에 상세 판정식이 없어 `tool_failure_rate > failure_threshold`를 정보성 검토 규칙으로 명시했다. 동률·미달·소집단·성공 여부 미관측은 판정하지 않는다. `high_tool_failure_rate` 근거에 실제 비율, 임계값과 관측 한계를 담는다. 도구 호출의 실패는 에이전트 태스크 완료·성공률을 직접 측정하지 않으며, 원래 카탈로그의 available 상태와 제목을 유지하되 이 한계를 응답에 명시한다.

실제 OTLP 도구 호출 5건(실패 1건, agent_id 부여)과 비용 15 USD로 실행한다. 실제 frontend 클라이언트와 결과 화면에서 실패율 0.2·호출 5건·비용 15 USD·판정 제목을 검증하고 `verifiedIngest.agentScenario`, `admin-ingest-agent-scenario.png`에 기록한다.


## S1-1 팀별 예산 비교

`POST /v1/scenarios/S1-1/runs`는 from/to/team_ids와 필수 `budget_by_team`을 받는다. 예: `{"params":{"from":"now-28d","to":"now","budget_by_team":{"팀 UUID":{"usd":100}}}}`. 각 팀은 양수 `usd` 또는 `tokens_m` 중 정확히 하나를 지정한다. 예산은 요청 조회 기간 전체에 해당한다고 해석하며 일할 계산하지 않는다.

예산이 입력된 팀만 대상으로 tokens, cost, adoption_rate의 기간 전체 table을 조회하는 3단계다. 현재 팀 접근 권한과 선택 범위를 검증하며 다른 팀 예산은 거부한다. 결과 필터에도 실제 비교 팀만 반영한다. tokens는 이벤트 원천의 input/output/cache_read/cache_create 관측 합계, cost는 실행 가격 기준을 적용한 이벤트 비용이며 metrics를 더하지 않는다.

첨부의 상세 판정식 공백을 보완해 관측 사용량이 입력 예산을 초과하면 `budget_exceeded` warning을 만든다. USD는 비용, tokens_m은 토큰 합계/1,000,000과 비교한다. 근거에 팀·단위·관측량·예산·비율을 담는다. 비율이 표현 범위를 넘으면 ratio만 null이고 초과 판정은 유지한다. 동률·미달·미관측·마스킹은 경고하지 않으며 미관측을 0이나 미사용 예산으로 추정하지 않는다. 실제 청구액, 예산 배분 적정성, 조직 내 불균형을 단정하지 않는다.

실제 수집 E2E는 비용 15 USD/예산 7.5 USD=2배, 토큰 1,050/예산 0.0005백만 토큰=2.1배를 실행·결과 화면에서 확인한다. `verifiedIngest.budgetScenario`와 `admin-ingest-budget-{usd,tokens}.png`가 증거다.


## S4-2 무산출 세션 검토

`POST /v1/scenarios/S4-2/runs`는 from/to/team_ids를 받는다. abandoned_session_ratio, session_last_event, api_error_rate를 일별 시계열로 조회하는 3단계이며 P2/W2.2/W2.10을 반환한다. 서버 지표의 기존 세션 연결·마지막 이벤트 판정·집단 마스킹 규칙을 사용한다.

첨부에 상세 판정식이 없어 `abandoned_session_ratio > 0`을 정보성 관측 안내로 명시했다. 산출 이벤트(accept·LoC·commit·PR)가 관측되지 않은 세션이 있다는 의미이며 세션 종료나 사용자의 대화 포기를 확정하지 않는다. 산출 비율 0, 소집단, 미관측은 판정하지 않는다. 기간 밖 산출이나 수집되지 않은 산출은 확인할 수 없다는 한계를 응답에 포함한다.

실제 수집 E2E는 산출 없는 5개 세션으로 비율 1과 판정 화면을 확인한다. `verifiedIngest.abandonedScenario`와 `admin-ingest-abandoned-scenario.png`를 남긴다. 산출이 있는/없는 세션 혼합(비율 0.5), 전부 산출 있음, 4명 마스킹, 미관측은 DB 테스트로 검증한다.

현재 frontend는 실행 결과 중 하나라도 마스킹되면 판정 제목·근거를 함께 숨긴다. 따라서 마지막 이벤트 유형별 인원이 5명 미만이면 전체 무산출 비율이 공개돼도 판정은 비공개일 수 있다. E2E는 로그의 시각과 event.sequence를 명시해 마지막 이벤트 유형이 5명 동일 집단인 공개 경로를 검증한다.


## S1-4 캐시 사용 검토

`POST /v1/scenarios/S1-4/runs`는 from/to/team_ids를 받는다. tokens, cache_read_ratio, prompts_per_session의 일별 시계열 3단계이며 P2/W2.6을 반환한다. 프롬프트 수는 원문이 아닌 user_prompt 이벤트 수다.

상세 판정식이 없는 첨부를 보완해 유효한 양수 분모의 캐시 읽기 비율이 정확히 0일 때 `no_cache_reads` 정보 안내를 제공한다. 캐시 읽기가 있거나 분모 0·불완전 토큰·마스킹이면 안내하지 않는다. partial 상태를 유지하고, 원문 유사도·반복 여부·캐싱 가능성·절감 효과를 확인하지 못한다는 한계를 응답에 명시한다.

실제 OTLP 토큰 1,050과 캐시 비율 0을 실행·판정 화면에서 검증한다. `verifiedIngest.cacheScenario`, `admin-ingest-cache-scenario.png`에 증거를 남긴다. S4-1 확장에서 실제 user_prompt를 추가해 세션별 프롬프트 p50=2도 수집 경로에서 확인한다.


## S4-1 세션 프롬프트 사용 검토

`POST /v1/scenarios/S4-1/runs`는 from/to/team_ids를 받는다. prompts_per_session(p50/p90)과 tokens를 일별 시계열로 조회하는 2단계이며 P2/W2.2를 반환한다.

상세 판정식 공백을 보완해 세션별 프롬프트 p50>1이면 `multiple_prompts_per_session` 정보 안내를 제공한다. p50=1·미관측·마스킹은 안내하지 않는다. 근거 필드는 ratio가 아닌 p50이며 정상적인 여러 차례 대화일 수 있다는 한계를 포함한다. 원문 유사도나 같은 문장 반복, API 재시도 횟수를 계산하지 않는다.

실제 수집 fixture에 5개 세션마다 user_prompt 2건씩 추가했다. 각 로그는 시각과 event.sequence를 명시하고 마지막 api_request보다 앞 순서를 사용한다. 총 30회 전송 후 중복 제거 저장 행은 로그 20개·메트릭 10개·스팬 5개=35개다. 실행/화면의 p50=2·토큰 1,050과 판정을 `verifiedIngest.promptScenario`, `admin-ingest-prompts-scenario.png`에 기록한다. 기존 S1-4 프롬프트 값도 더 이상 미관측이 아니다.


## S4-8 승인 대기 검토

`POST /v1/scenarios/S4-8/runs`는 from/to/team_ids와 필수 배열 wait_thresholds_min을 받는다. gate_wait_ms(p50/p90), tool_rejections, usage_heatmap을 일별로 조회하는 3단계이며 P2/W2.7을 반환한다.

일별 p90을 분으로 환산해 각 입력 임계값보다 큰 경우 정보성 high_gate_wait 판정을 제공한다. 예: p90=120,000ms, 임계값 [1,2,3]이면 1분 초과 안내만 나온다. 음수·비숫자는 400이며 빈 배열은 지표만 조회한다. 미관측·마스킹은 판정하지 않는다. tool_gate 관측은 Plan 전용 대기나 생산성 손실을 의미하지 않는다.

실제 OTLP tool.blocked_on_user 스팬 5건의 시작/종료 시각에서 대기 120,000ms를 수집하고, 실제 frontend 실행·결과 화면까지 검증한다. `verifiedIngest.gateScenario`와 `admin-ingest-gate-scenario.png`가 증거다. fixture에는 거부 이벤트가 없어 tool_rejections는 미관측이다. usage_heatmap은 이 실행에서 일별 사용량 차트로 제공한다.
