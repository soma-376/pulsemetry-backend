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
./gradlew :apps:dashboard-api:bootJar
node scripts/e2e/dashboard-auth-settings.mjs
```

스크립트는 격리된 PostgreSQL·ClickHouse를 생성하고 실제 frontend를 real 모드로 실행한다.
테스트 전용 계정을 사용하고 종료 시 컨테이너·임시 키를 제거한다. 포트는 API 18081, frontend 15173이다.
로그·스크린샷·판정은 `build/e2e/auth-settings`에 남는다. 현재 범위는 인증·P5 메타, 실제 frontend API 클라이언트의 53개 지표 카탈로그 및 합계 5개·활성 사용자·도입률·커버리지 및 자동화·개발 연동·명령 프롬프트 비율 및 프롬프트 분포·도구 호출 관련 지표 및 API 재시도·429·도구 결정 및 API 오류율·사용량 히트맵 및 컨텍스트 압축 및 MCP 연결·LLM 종료 사유·소요 시간·첫 토큰 지연·승인 대기·즉시 승인·편집 수락률·서브에이전트 활동·훅 차단 수·훅 실행·모델 거부·모델 사용자·토큰 비율·토큰 사용량·무산출 세션·마지막 이벤트·사용 집중도·첫 사용 시간·잔존율·벤더 불일치·비용·서브에이전트 비용 비율·사용자·시간당 비용·모델 단가·비용 이상·약정 소진율 총 53개 지표다. 공통 49개는 owner/admin 양쪽에서, 비용 이상 1개는 admin에서, 모델 거부·벤더 불일치·약정 소진율 3개는 owner에서 검증한다. 정규화 테스트 포인트를 ClickHouse에 직접 적재하며 ingest 경로·전체 화면 및 PROJ-156 수용 E2E를 대체하지 않는다. 미구현 지표의 쿼리 단위 오류도 결과에 기록한다.

약정 소진율은 owner의 필터 없는 전사 범위에서만 조회한다. 날짜만 있는 from/to는 요청 시간대 자정으로 해석한다. USD 이외 약정 통화는 422이며 날짜 범위는 기존 366일 제한을 따른다. P5 약정 게이지의 실제 렌더도 smoke에서 확인한다.

설치 목록은 `GET /v1/installations`이며 owner와 `X-Audit-Reason`이 필수다. 사유는 URL 인코딩한 10~500자 문자열로 전달한다. `limit`(1~500), `cursor`, `inactive_days`, `team_id`, `platform`, `status`를 지원한다. 설치 이메일은 도메인만 노출한다. 미관측 설치의 무활동 기간은 생성일부터 계산한다. 요청당 후보 5,000개 초과는 422이므로 필터로 범위를 좁힌다. 기존 frontend 설치 카드는 감사 헤더를 전달하지 않으므로 현재 smoke는 실제 API 클라이언트의 명시적 감사 조회만 검증하며 카드 렌더 검증은 아니다.

세션 상관 조회는 `GET /v1/sessions/{key}/events`이며 owner와 `X-Audit-Reason`이 필수다. `lookup=session_id|request_id|call_id|installation_id`, `from`, `to`, `limit`(1~500), `cursor`를 지원한다. 기본 기간은 최근 7일이다. 다중 세션 일치는 422, 미발견은 404다. 다음 페이지에는 동일한 검색 키·기간 식과 감사 사유를 전달한다. 커서가 상대 기간의 해석 결과를 고정한다. API의 ended_at은 마지막 관측 시각이다. smoke는 실제 opsApi.events 및 eventDetails 변환을 검증하며 SessionSearch 전체 화면 검증은 별도다.

## 시나리오 정의 조회

`GET /v1/scenarios`와 `GET /v1/scenarios/{scenario_id}`는 로그인한 owner/admin에게 46개 정의를 제공한다. 목록은 `category`, `availability`, `target_page`, `q`를 지원한다. 상세의 `params_schema`는 frontend 폼과 S1-3 서버 입력 검증에서 공통으로 사용한다. S1-3 외 판정 규칙·실행 계획과 실행 목록·저장 API는 후속 작업이다.

동일 smoke는 owner/admin의 실제 `scenarioApi`로 46개 목록·상세와 지표 메타 일치를 검증하고 S1-3 폼 검증 함수를 실행한다. 결과의 `verifiedScenarios`에서 확인한다. 카탈로그 화면 전체 렌더는 포함하지 않으며, S1-3 실행 결과 검증 범위는 아래와 같다.

## S1-3 실행 워커

`POST /v1/scenarios/S1-3/runs`에 `{"params":{"from":"now-28d","to":"now","moving_avg_days":7,"spike_threshold_pct":200},"price_basis":"list"}`를 보내면 202와 실행 ID를 반환한다. `Location`을 2초 간격으로 조회하고, 취소는 `POST /v1/scenario-runs/{id}/cancel`로 요청한다. `wait=true`는 최대 20초까지만 기다린다.

워커는 기본 활성화이며 `pulsemetry.dashboard.worker-enabled=false`로 해당 인스턴스의 claim을 중단할 수 있다. 접수는 계속 가능하므로 유지보수 시 활성 실행 3개 상한에 유의한다. queued는 DB에 남고 running lease가 만료되면 다음 워커 claim 시 실패 처리된다. 재시도는 새 실행 요청으로 한다. 취소는 실행 중인 DB 조회의 즉시 중단을 보장하지 않으며 결과 저장을 차단한다.

현재 S1-3만 실행 가능하다. 다른 available/partial 카탈로그 항목은 아직 501을 반환한다. availability는 데이터 산출 가능성을 뜻하며 실행 구현 상태와 다르다.

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
