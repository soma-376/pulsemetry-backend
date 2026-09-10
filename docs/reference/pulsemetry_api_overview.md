# Pulsemetry 대시보드 API — 개요 문서

기계용 명세는 `pulsemetry_api_spec.yaml`(OpenAPI 3.1)이고, 이 문서는 사람이 읽는 개요다. 다음을 담는다.

1. 설계 원칙과 Grafana 차용 근거
2. 공통 규약(인증·시간·필터·페이지네이션·에러·응답 프레임)
3. 엔드포인트 목록
4. 지표 카탈로그(`metric_id` 53개) — 정의, 원천 컬럼, 가용성
5. 참조 SQL(집계 쿼리 초안)
6. 시나리오 카탈로그(46개)와 실행 모델
7. 추적표(위젯 → 엔드포인트 → metric_id → ClickHouse/RDB 컬럼)
8. 부록 A — 필요한 스키마·어댑터 변경 / 부록 B — 가정 목록

용어는 `pulsemetry_research_notes.md`(지표 ID A-1…H-4, 시나리오 ID S1-1…S8-7)와 `pulsemetry_claude_design_prompt.md`(위젯 ID W1.0…W3.4, L1…L5)를 그대로 쓴다.

---

## 1. 설계 원칙과 Grafana 차용 근거

「Grafana의 API 호출 분석 아티팩트」가 실측한 패턴 중 무엇을 왜 가져왔는지.

| Grafana에서 관찰된 사실 | 채택 | 우리 설계 | 근거·변형 이유 |
|---|---|---|---|
| 패널당 요청 1개, target 여러 개를 `queries[]`에 `refId`로 묶어 `POST /api/ds/query` | ✅ | `POST /v1/query` + `queries[].ref_id`, 응답 `results[ref_id]` | 위젯이 본선+비교선+분모를 한 왕복으로 받는다. 요청 수 = 보이는 위젯 수 |
| `from`/`to`는 브라우저가 `now-30m`을 epoch ms로 치환해 보냄 | ✅ 변형 | `from`/`to`에 상대식을 **그대로** 보내고 서버가 해석, 응답에 `resolved_from/to` | 저장된 시나리오·공유 URL이 열릴 때마다 상대식으로 재실행돼야 하므로 해석 주체를 서버로 옮김 |
| `step = max(intervalMs, range/maxDataPoints)`, 응답 `calculatedMinStep` | ✅ | `interval`(선택) + `max_data_points`, 응답 `meta.resolved_interval` | 위젯 폭이 해상도 상한을 정한다. 상한 초과는 422 `query_too_wide` |
| DataFrame: `schema.fields[]`(라벨은 필드에) + `data.values[][]` 열 지향 | ✅ | 동일 구조. group_by 키는 `fields[].labels` | 시리즈가 늘면 행이 아니라 프레임이 는다. 차트 라이브러리가 열 배열을 그대로 소비 |
| `meta.executedQueryString` | ✅ | `meta.executed_sql` (owner/admin) | 디버깅 출발점. SQL 노출은 권한 게이트 |
| `meta.custom.resultType` matrix/vector | ✅ 변형 | `frame_type ∈ {timeseries, table, distribution, scalar}` | 히트맵·분포·KPI가 Prometheus 결과형과 다름 |
| URL의 `requestId`·`ds_type`은 서버가 쓰지 않음 | ✅ 명시 | `X-Request-Id`는 클라이언트 추적용, 서버는 `request_id`를 새로 발급 | 같은 오해를 문서로 차단 |
| 화면 밖 패널은 쿼리하지 않음 → 위치가 비용 | ✅ 규칙 | 뷰포트 지연 로딩, 무거운 위젯 하단 배치(디자인 프롬프트 §5) | 요청 예산 = 보이는 위젯 수 + 커버리지 1 |
| 비율 쿼리는 분모를 방어(`or vector(0)`, `clamp_min`) | ✅ 규칙 | 비율 지표는 `numerator`/`denominator` 동봉, 분모 0 → 값 `null` | NoData 상태가 "0%"로 오독되지 않게 |
| 규칙 API·어노테이션 API, `__panelId__` 연결 | ❌ | 알림 없음. 임계 판정은 시나리오 `findings[]` | 1차 범위에 알림·어노테이션 없음. 스무딩·`for`·플래핑 문제를 통째로 회피 |
| 5초 refresh, 4개 시계 불일치 | ❌ 변형 | 수동 새로고침 기본, 자동은 5분 이상 | 수집 주기 60초·배치 적재라 초 단위 갱신 무의미 |

## 2. 공통 규약

### 2-1. 인증·권한

- `Authorization: Bearer <access_token>` — `POST /v1/auth/login`(`enrollment.members.email` + `password_hash`, ADR 0007)에서 발급. **가정 A4**.
- 역할: `members.role` — `owner`(전 페이지), `admin`(P1·P2 자기 팀·P4·P5 읽기), `member`(로그인 불가).
- 팀 범위: `admin`은 `team_memberships(left_at IS NULL)` 기준 자기 팀만. 범위 밖 `team_ids` → 403.
- 개인 식별 조회(`member_ids` 필터, `GET /v1/sessions/{id}/events`, 이메일 전체 표시): `X-Audit-Reason`(10–500자) 필수, 감사 로그(조회자·시각·대상·사유) 기록.
- 기존 `X-Admin-Token`(enrollment-api 운영자 통로)은 이 API가 받지 않는다.

### 2-2. 시간 범위

- `from`/`to`: ISO-8601 또는 상대식 `now`, `now-<n><unit>`(s·m·h·d·w·M), 경계 정렬 `/d` `/w` `/M`. 예 `now-7d/d`.
- `tz`: 버킷 경계 타임존, 기본 `tenants.timezone`(Asia/Seoul). ClickHouse `ts`는 UTC이므로 `toStartOfInterval(ts, INTERVAL …, {tz})`로 버킷.
- `compare`: `previous_period`(같은 길이 직전 구간) / `previous_week`(7일 전 같은 구간). 비교 시리즈는 `*_compare` 필드.
- 해상도: `interval` ∈ 1h·6h·1d·1w·1M. 생략 시 `max(1h, range / max_data_points)`를 위 집합에서 올림. 점 개수 > 1000 → 422.

### 2-3. 전역 필터 → SQL 조건

| 파라미터 | 원천 | SQL |
|---|---|---|
| `team_ids[]` | `enrollment.teams.id` | `hasAny(team_ids_as_of, {team_ids})` — group_by=team 은 `ARRAY JOIN team_ids_as_of AS team_id` (다중 소속은 각 팀에 중복 계상, 팀 합 ≥ 전체) |
| `products[]` | — | `product IN {products}` |
| `models[]` | — | 로그/스팬 `JSONExtractString(raw_json,'payload','model')`, 메트릭 `JSONExtractString(raw_json,'point','attrs','model')` |
| `member_ids[]` | `enrollment.members.id` | API가 `installations.member_id` 로 설치 목록 해석 → `installation_id IN {installation_ids}` (**가정 A3**) |
| `price_basis` | `contract_token_discounts` | §4 `cost` 참조 |

구성원 해석: ClickHouse에는 member 컬럼이 없다(ADR 0017 규칙 7). API가 RDB에서 `installations(id, member_id)`를 읽어 `{inst_member:Map(String,String)}` 파라미터로 넘기고, SQL은 `member_key = coalesce(nullIf({inst_member}[installation_id], ''), installation_id)` 로 사람 단위 distinct를 센다. 대안(권장, 부록 A-4)은 PostgreSQL 소스 ClickHouse dictionary.

### 2-4. 페이지네이션

목록 API(`/meta/members`, `/installations`, `/scenario-runs`, `/saved-reports`, `/sessions/{id}/events`)는 `limit`(≤500) + 불투명 `cursor`, 응답 `next_cursor`(없으면 null), `total`(비싸면 null).

### 2-5. 에러 형식

enrollment 서버 계약 `{"error","message"}`에 `request_id`를 더한다. 예외 원문은 싣지 않는다.

| HTTP | error | 상황 |
|---|---|---|
| 400 | `invalid_request` / `group_by_not_allowed` | 시간식·파라미터 오류 / 지표가 허용하지 않는 차원(예 `refusals`×`team`) |
| 401 | `unauthorized` | 토큰 없음·무효(구분하지 않음) |
| 403 | `forbidden` / `audit_reason_required` | 팀 범위 밖 / 감사 사유 없음 |
| 404 | `not_found` | |
| 409 | `scenario_unavailable` / `conflict` | 준비 중 시나리오 실행 / 종료된 실행 취소 |
| 422 | `query_too_wide` | 점 개수 상한 초과 |
| 429 | `run_limit_exceeded` | 테넌트 동시 실행 3개 초과 |
| 504 | `query_timeout` | ClickHouse 30초 초과 |

### 2-6. 응답 프레임

```json
{
  "request_id": "…", "resolved_from": "…", "resolved_to": "…",
  "coverage": { "active_installations": 128, "active_members": 154, "ratio": 0.831, "last_ingested_at": "…" },
  "results": {
    "A": { "status": 200, "frames": [ {
        "schema": {
          "ref_id": "A", "metric_id": "cost", "frame_type": "timeseries",
          "fields": [
            { "name": "time", "type": "time", "config": { "interval_ms": 86400000 } },
            { "name": "cost_usd", "type": "number", "labels": { "team_id": "…", "team_name": "플랫폼" },
              "config": { "unit": "USD", "group_size": 12, "suppressed": false } }
          ],
          "meta": { "resolved_interval": "1d", "definition": "…", "caveat": "청구액 아님",
                    "source_columns": ["enriched_events.ts", "raw_json.payload.cost_usd"], "executed_sql": "…",
                    "suppressed_groups": [], "data_quality": [] }
        },
        "data": { "values": [ [1756566000000, …], [412.18, …] ] }
    } ] }
  }
}
```

- `coverage`는 모든 응답에 동봉(H-1 고정 캡션).
- 마스킹: group의 활성 사용자 `group_size < 5` → 값 `null`, `config.suppressed=true`, `meta.suppressed_groups[]`.
- 분위수 프레임(`distribution`): 필드 `p50`, `p90`, `p95`, `p99`, `count`, 또는 히스토그램 `bucket`/`count`.

## 3. 엔드포인트 목록

| operationId | 메서드·경로 | 용도 | 호출 위젯 |
|---|---|---|---|
| AUTH-LOGIN | `POST /v1/auth/login` | 로그인 | 로그인 화면 |
| ME | `GET /v1/me` | 사용자·역할·팀 범위 | 셸 |
| QRY | `POST /v1/query` | 지표 조회(위젯 단위, `queries[]`) | W1.0–W3.3 전부 |
| META-METRICS | `GET /v1/meta/metrics` | 지표 카탈로그(정의·허용 차원·컬럼) | 위젯 ⓘ 툴팁, group_by 옵션 |
| META-FILTERS | `GET /v1/meta/filters` | 팀·제품·모델·프리셋 일괄 | 필터 바 |
| META-TEAMS | `GET /v1/meta/teams` | 팀 목록+인원 | 필터 바, P5 |
| META-MEMBERS | `GET /v1/meta/members` | 구성원 목록 | P5, P3 |
| META-MODELS | `GET /v1/meta/models` | 관측 모델 목록 | 필터 바 |
| META-CONTRACTS | `GET /v1/meta/contracts` | 계약·할인·약정 | P5 |
| META-MANIFESTS | `GET /v1/meta/manifests` | 수집 정책 배포 상태 | P5, W3.2 |
| INSTALL-LIST | `GET /v1/installations` | 설치·커버리지·무활동 | W3.2, S1-7 |
| SESSION-EVENTS | `GET /v1/sessions/{session_id}/events` | 세션 상관 조회(감사) | W3.4 |
| SCN-LIST | `GET /v1/scenarios` | 시나리오 카탈로그 46 | L1 |
| SCN-GET | `GET /v1/scenarios/{id}` | 파라미터 스키마·판정 규칙 | L2 |
| SCN-RUN | `POST /v1/scenarios/{id}/runs` | 실행(202) | L3 |
| RUN-GET | `GET /v1/scenario-runs/{run_id}` | 상태·결과 폴링 | L3, L4 |
| RUN-LIST | `GET /v1/scenario-runs` | 이력 | L1, L5 |
| RUN-CANCEL | `POST /v1/scenario-runs/{run_id}/cancel` | 취소 | L3 |
| RUN-DELETE | `DELETE /v1/scenario-runs/{run_id}` | 이력 삭제 | L5 |
| RUN-SAVE | `POST /v1/scenario-runs/{run_id}/save` | 저장 리포트 생성 | L5 |
| SAVED-LIST | `GET /v1/saved-reports` | 저장 목록 | L1, L5 |
| SAVED-DELETE | `DELETE /v1/saved-reports/{id}` | 저장 삭제 | L5 |

## 4. 지표 카탈로그 (`metric_id`)

가용성: **가능** / **부분** — `META-METRICS.availability`. 원천 표기: `ee.` = `enriched_events` 컬럼, `raw.` = `raw_json` 경로, `rdb.` = enrollment 테이블. "SQL" 열은 §5 템플릿 번호.

### 4-1. 도입·사용

| metric_id | 지표 | 정의 | 원천 | group_by | 가용성 | SQL |
|---|---|---|---|---|---|---|
| `telemetry_coverage` | H-1 | 기간 내 이벤트 있는 설치 수 ÷ `installations.status='active'`; 활성 설치 ÷ 활성 구성원 | `ee.installation_id`, `rdb.installations`, `rdb.members` | product | 가능 | Q22 |
| `active_users` | A-1 | 1순위 `active_time.total{type=user}` 합>0 인 사람 수; 폴백 이벤트≥1(가정 A2) | `raw.point.name/value/attrs.type`, `ee.installation_id` | team, product | 가능 | Q1 |
| `adoption_rate` | A-2 | `active_users`(팀) ÷ 팀 활성 인원 | + `rdb.team_memberships`, `rdb.members.status` | team | 가능 | Q2 |
| `sessions` | A-3 | `sum(value)` name=`claude_code.session.count` | `raw.point.attrs.start_type` | team, product, start_type | 가능 | Q3 |
| `active_time` | A-4 | `sum(value)` name=`claude_code.active_time.total` (초) | `raw.point.attrs.type` | team, product, type | 가능 | Q4 |
| `automation_ratio` | A-4 | cli ÷ (user+cli) | 동상 | team | 가능 | Q4 |
| `prompts_per_session` | A-5 | 세션당 `type='user_prompt'` 수 분포(p50/p90, 히스토그램) | `raw.type`, `raw.envelope.session_id` | team, product | 가능 | Q5 |
| `command_prompt_ratio` | A-5 | `payload.command_name IS NOT NULL` 프롬프트 비율 | `raw.payload.command_name` | team | 가능 | Q5 |

### 4-2. 산출

| metric_id | 지표 | 정의 | 원천 | group_by | 가용성 | SQL |
|---|---|---|---|---|---|---|
| `lines_of_code` | B-1 | `sum(value)` name=`claude_code.lines_of_code.count` | `raw.point.attrs.type(added/removed)`, `attrs.model` | team, type, model | 가능 | Q6 |
| `commits` | B-2 | `sum(value)` name=`claude_code.commit.count` | `raw.point.name` | team | 가능 | Q6 |
| `pull_requests` | B-2 | `sum(value)` name=`claude_code.pull_request.count` | `raw.point.name` | team | 가능 | Q6 |
| `integration_depth` | B-3 | (commits+pull_requests) ÷ sessions{start_type=fresh} | 위 조합 | team | 가능 | Q6 |

### 4-3. 비용·토큰

| metric_id | 지표 | 정의 | 원천 | group_by | 가용성 | SQL |
|---|---|---|---|---|---|---|
| `cost` | C-1 | `source=events`(기본): `sum(payload.cost_usd)` type=`llm_call` (두 제품, `cost_source` reported/estimated). `source=metrics`: `sum(value)` name=`claude_code.cost.usage`. `price_basis=contract`: × `discount_rate`(가정 A5) | `raw.payload.cost_usd/cost_source/model/source`, `raw.point.attrs.*`, `rdb.contract_token_discounts` | team, product, model, query_source, agent_name, skill_name, plugin_name, mcp_server, speed, effort | 가능 | Q7 |
| `cost_anomaly` | S1-3 | 일별 비용 ÷ 직전 N일 이동평균 − 1 | 동상 | team, agent_name, query_source | 가능 | Q21 |
| `cost_per_active_user` | C-4 | cost ÷ active_users | 조합 | team | 가능 | Q1+Q7 |
| `cost_per_user_hour` | C-4 | cost ÷ (active_time{user}/3600) | 조합 | team | 가능 | Q4+Q7 |
| `tokens` | C-2 | `source=events`: `sum(payload.tokens.*)`; `source=metrics`: `sum(value)` name=`claude_code.token.usage` by `attrs.type` | `raw.payload.tokens.{input,output,cache_read,cache_create}`, `raw.point.attrs.type/model` | team, product, model, type, query_source, agent_name | 가능 | Q8 |
| `cache_read_ratio` | C-2 | cache_read ÷ (input+cache_read+cache_create) | 동상 | team, model | 가능 | Q8 |
| `input_output_ratio` | S1-5 | input ÷ output | 동상 | team, model | 가능 | Q8 |
| `subagent_cost_ratio` | C-3 | cost{query_source=subagent} ÷ cost | `raw.payload.source` 또는 `raw.point.attrs.query_source` | team | 가능 | Q7 |
| `model_users` | E-1 | 모델별 사람 수 distinct | `raw.payload.model`, `ee.installation_id` | model | 가능 | Q1 변형 |
| `model_unit_price` | S8-2 | cost ÷ tokens(billable) by model | 조합 | model | 가능 | Q7+Q8 |
| `contract_commitment_burn` | H-2 | Σ계약비용 ÷ `commitment_amount` (계약 기간 내) | `rdb.contract_term_commitments`, `rdb.contract_memberships` | — | 가능 | Q7+RDB |
| `compactions` | C-5 | `count()` lifecycle kind=`compaction` | `raw.payload.kind`, `raw.payload.attrs.trigger/success` | team, trigger | 가능 | Q16 |
| `compaction_reduction` | C-5 | 1 − Σtokens_after ÷ Σtokens_before | `raw.payload.tokens_before/tokens_after` | team | 가능 | Q16 |

### 4-4. 권한·신뢰·마찰

| metric_id | 지표 | 정의 | 원천 | group_by | 가용성 | SQL |
|---|---|---|---|---|---|---|
| `edit_acceptance_rate` | D-1 | accept ÷ (accept+reject), `attrs.source` ∈ user_* 만 | `raw.point.name=claude_code.code_edit_tool.decision`, `attrs.decision/source/language/tool_name` | team, language, tool_name | 가능 | Q9 |
| `auto_approval_ratio` | D-2 | `decided_by ∈ (config, hook)` ÷ 전체 tool_decision | `raw.payload.decided_by` | team, decided_by, tool_name | 가능 | Q11 |
| `gate_wait_ms` | D-4 | span `tool_gate` `blocked_on_user_ms` p50/p90 | `raw.type='tool_gate'`, `raw.payload.blocked_on_user_ms/decision/decided_by` | team, decision, decided_by | 가능(트레이스) | Q10 |
| `tool_rejections` | D-5 | `count()` tool_decision decision=reject | `raw.payload.decision/decided_by/tool_name` | team, decided_by, tool_name | 가능 | Q11 |
| `rubber_stamp_ratio` | S5-7 | accept 중 `blocked_on_user_ms < threshold_ms`(기본 2000) 비율 | `raw.payload.blocked_on_user_ms/decision` | team | 가능 | Q24 |

### 4-5. 기능 채택

| metric_id | 지표 | 정의 | 원천 | group_by | 가용성 | SQL |
|---|---|---|---|---|---|---|
| `mcp_connections` | E-4 | `count()` lifecycle kind=`mcp_connection` | `raw.payload.attrs.server_name/status/transport_type/server_scope/is_plugin` | team, server_name, server_scope, transport_type, is_plugin | 가능 | Q15 |
| `mcp_failure_ratio` | E-4 | status≠connected ÷ 전체 | 동상 | server_name | 가능 | Q15 |
| `subagent_activity` | E-5 | distinct `agent_id` 수, agent_id 있는 tool_call 비율 | `raw.payload.agent_id/parent_agent_id` | team | **부분**(subagent_completed 없음) | Q14 변형 |

### 4-6. 품질·성능

| metric_id | 지표 | 정의 | 원천 | group_by | 가용성 | SQL |
|---|---|---|---|---|---|---|
| `api_error_rate` | F-1 | llm_call{error_type≠null} ÷ llm_call 전체 | `raw.type='llm_call'`, `raw.payload.error_type/status_code/model` | team, product, model, status_code | **부분**(retries_exhausted 없음) | Q12 |
| `api_retry_attempts` | F-1 | attempt ≥ 2 인 llm_call 비율·수 | `raw.payload.attempt` | team, model | 가능 | Q12 |
| `rate_limit_events` | S2-1 | llm_call status_code=429 수 | `raw.payload.status_code` | team, product, hour | 가능 | Q20 |
| `tool_calls` | F-2 | `count()` type=`tool_call` | `raw.payload.tool_name/tool_kind/action/error_type/mcp_server/success` | team, tool_name, tool_kind, action, error_type, mcp_server | 가능 | Q14 |
| `tool_failure_rate` | F-2 | success=false ÷ (success 판정 있는 호출) | `raw.payload.success` | team, tool_name | 가능 | Q14 |
| `read_tool_density` | S7-2 | 세션당 action ∈ (read, search, fetch) 호출 수 p50/p90 | `raw.payload.action`, `raw.envelope.session_id` | team | 가능 | Q14 변형 |
| `turn_duration_ms` | F-3 | span `turn` `attrs.duration_ms` p50/p90 | `raw.type='turn'`, `raw.payload.attrs.duration_ms` | product | 가능(트레이스) | Q13 |
| `llm_ttft_ms` | F-3 | `ttft_ms` p50/p90 (로그 llm_call 우선, 없으면 스팬 llm_request) | `raw.payload.ttft_ms` | product, model | 가능 | Q13 |
| `llm_duration_ms` | F-3 | `duration_ms` p50/p95/p99 | `raw.payload.duration_ms` | product, model | 가능 | Q13 |
| `llm_stop_reasons` | S6-3 | llm_call/llm_response `stop_reason` 분포 | `raw.payload.stop_reason` | model, stop_reason | 가능 | Q12 변형 |

### 4-7. 거버넌스·보안

| metric_id | 지표 | 정의 | 원천 | group_by | 가용성 | SQL |
|---|---|---|---|---|---|---|
| `hook_executions` | G-1 | `count()` span `hook`; 훅 스팬 있는 세션 비율 | `raw.type='hook'`, `raw.payload.attrs.hook_event/num_hooks/duration_ms` | hook_event | **부분**(detailed beta tracing 전제, hook_registered 없음) | Q18 |
| `hook_blocking` | G-1 | Σ `attrs.num_blocking` | `raw.payload.attrs.num_blocking` | hook_event | 부분 | Q18 |
| `refusals` | G-2 | `count()` llm_response stop_reason=refusal. **team 분해 금지** | `raw.type='llm_response'`, `raw.payload.stop_reason/refusal_category/model` | category, model, product | **부분**(server_fallback_hop 미보존 → 홉 중복 캡션) | Q17 |
| `vendor_account_mismatch` | H-3 | `identity.vendor_email` 도메인/주소 ≠ `members.email` 인 설치 수 | `raw.envelope.identity.vendor_email`, `rdb.members.email` | — | 가능 | Q23 |

### 4-8. 패턴·코호트

| metric_id | 지표 | 정의 | 원천 | group_by | 가용성 | SQL |
|---|---|---|---|---|---|---|
| `usage_heatmap` | S2-1 | hour×weekday 별 user_prompt 수(또는 tokens) | `ee.ts`, `raw.type` | team, hour, weekday | 가능 | Q19 |
| `usage_concentration` | S3-2 | 사람별 토큰 합 정렬 → 상위 10% 점유율, 로렌츠 곡선 점(익명) | `raw.payload.tokens`, `ee.installation_id` | team | 가능 | Q26 |
| `onboarding_ttfu` | S3-3 | `installations.created_at` → 첫 이벤트 `min(ts)` 간격 분포 | `rdb.installations.created_at`, `ee.ts` | team, platform | 가능 | Q27 |
| `onboarding_retention` | S3-3 | 코호트(첫 이벤트 주) 별 n주차 잔존율 | `ee.ts`, `ee.installation_id` | team | 가능 | Q27 |
| `abandoned_session_ratio` | S4-2 | 산출(accept·LoC·commit·PR) 없이 끝난 세션 비율 | `raw.point.name/attrs.decision`, `raw.envelope.session_id` | team | 가능 | Q25 |
| `session_last_event` | S4-2 | 세션 마지막 이벤트 유형 분포(api_error / llm_request 긴 대기 / 기타) | `raw.type`, `raw.payload.error_type`, `raw.sequence` | team | 가능 | Q25 |

## 5. 참조 SQL (ClickHouse, 집계 쿼리 초안)

파라미터는 ClickHouse 명명 파라미터 `{name:Type}`. 모든 쿼리는 아래 공통 CTE 위에서 돈다. `FINAL`은 ReplacingMergeTree 재적재 중복 제거를 위해 필수(V1 DDL 주석).

```sql
-- Q0. 공통 CTE ─ 모든 템플릿의 머리
WITH
  base AS (
    SELECT event_id, ts, installation_id, signal, product, team_ids_as_of, raw_json
    FROM enriched_events FINAL
    WHERE tenant_id = {tenant:String}
      AND ts >= {from:DateTime} AND ts < {to:DateTime}
      AND (empty({team_ids:Array(String)}) OR hasAny(team_ids_as_of, {team_ids:Array(String)}))
      AND (empty({products:Array(String)}) OR product IN {products:Array(String)})
      AND (empty({installation_ids:Array(String)}) OR installation_id IN {installation_ids:Array(String)})
  ),
  -- 메트릭 데이터포인트를 평평하게. attrs 값은 전부 String (MetricEvents.kt)
  metric_points AS (
    SELECT ts, installation_id, product, team_ids_as_of,
      JSONExtractString(raw_json, 'envelope', 'session_id')          AS session_id,
      JSONExtractString(raw_json, 'point', 'name')                    AS name,
      JSONExtractFloat(raw_json, 'point', 'value')                    AS value,
      JSONExtractInt(raw_json, 'point', 'aggregation_temporality')    AS temporality,
      JSONExtractString(raw_json, 'point', 'attrs', 'type')           AS attr_type,
      JSONExtractString(raw_json, 'point', 'attrs', 'model')          AS attr_model,
      JSONExtractString(raw_json, 'point', 'attrs', 'query_source')   AS attr_query_source,
      JSONExtractString(raw_json, 'point', 'attrs', 'agent.name')     AS attr_agent_name,
      JSONExtractString(raw_json, 'point', 'attrs', 'skill.name')     AS attr_skill_name,
      JSONExtractString(raw_json, 'point', 'attrs', 'plugin.name')    AS attr_plugin_name,
      JSONExtractString(raw_json, 'point', 'attrs', 'mcp_server.name') AS attr_mcp_server,
      JSONExtractString(raw_json, 'point', 'attrs', 'speed')          AS attr_speed,
      JSONExtractString(raw_json, 'point', 'attrs', 'effort')         AS attr_effort,
      JSONExtractString(raw_json, 'point', 'attrs', 'start_type')     AS attr_start_type,
      JSONExtractString(raw_json, 'point', 'attrs', 'decision')       AS attr_decision,
      JSONExtractString(raw_json, 'point', 'attrs', 'source')         AS attr_source,
      JSONExtractString(raw_json, 'point', 'attrs', 'language')       AS attr_language
    FROM base
    WHERE signal = 'metric'
      AND temporality != 2   -- 가정 A1: delta 만 합산. cumulative 포인트 수는 data_quality 로 보고
  ),
  -- 로그·스팬. 없는 값은 NULL (ADR 0017 규칙 3) → Nullable 추출
  events AS (
    SELECT ts, installation_id, product, team_ids_as_of, signal,
      JSONExtractString(raw_json, 'type')                              AS type,
      JSONExtractString(raw_json, 'envelope', 'session_id')            AS session_id,
      JSONExtractString(raw_json, 'turn_id')                           AS turn_id,
      JSONExtractString(raw_json, 'call_id')                           AS call_id,
      JSONExtract(raw_json, 'sequence', 'Nullable(Int64)')             AS sequence,
      JSONExtractString(raw_json, 'payload', 'model')                  AS model,
      JSONExtractString(raw_json, 'payload', 'source')                 AS query_source,
      JSONExtract(raw_json, 'payload', 'cost_usd', 'Nullable(Float64)') AS cost_usd,
      JSONExtractString(raw_json, 'payload', 'cost_source')            AS cost_source,
      JSONExtract(raw_json, 'payload', 'tokens', 'input',        'Nullable(Int64)') AS tok_input,
      JSONExtract(raw_json, 'payload', 'tokens', 'output',       'Nullable(Int64)') AS tok_output,
      JSONExtract(raw_json, 'payload', 'tokens', 'cache_read',   'Nullable(Int64)') AS tok_cache_read,
      JSONExtract(raw_json, 'payload', 'tokens', 'cache_create', 'Nullable(Int64)') AS tok_cache_create,
      JSONExtract(raw_json, 'payload', 'duration_ms', 'Nullable(Int64)')  AS duration_ms,
      JSONExtract(raw_json, 'payload', 'ttft_ms', 'Nullable(Int64)')      AS ttft_ms,
      JSONExtract(raw_json, 'payload', 'attempt', 'Nullable(Int64)')      AS attempt,
      JSONExtractString(raw_json, 'payload', 'stop_reason')            AS stop_reason,
      JSONExtractString(raw_json, 'payload', 'error_type')             AS error_type,
      JSONExtract(raw_json, 'payload', 'status_code', 'Nullable(Int64)')  AS status_code,
      JSONExtractString(raw_json, 'payload', 'refusal_category')       AS refusal_category,
      JSONExtractString(raw_json, 'payload', 'tool_name')              AS tool_name,
      JSONExtractString(raw_json, 'payload', 'tool_kind')              AS tool_kind,
      JSONExtractString(raw_json, 'payload', 'action')                 AS action,
      JSONExtract(raw_json, 'payload', 'success', 'Nullable(Bool)')    AS success,
      JSONExtractString(raw_json, 'payload', 'mcp_server')             AS mcp_server,
      JSONExtractString(raw_json, 'payload', 'agent_id')               AS agent_id,
      JSONExtractString(raw_json, 'payload', 'decision')               AS decision,
      JSONExtractString(raw_json, 'payload', 'decided_by')             AS decided_by,
      JSONExtract(raw_json, 'payload', 'blocked_on_user_ms', 'Nullable(Int64)') AS blocked_on_user_ms,
      JSONExtractString(raw_json, 'payload', 'kind')                   AS lifecycle_kind,
      JSONExtract(raw_json, 'payload', 'tokens_before', 'Nullable(Int64)') AS tokens_before,
      JSONExtract(raw_json, 'payload', 'tokens_after',  'Nullable(Int64)') AS tokens_after,
      JSONExtract(raw_json, 'payload', 'length', 'Nullable(Int64)')    AS prompt_length,
      JSONExtractString(raw_json, 'payload', 'command_name')           AS command_name,
      JSONExtractRaw(raw_json, 'payload', 'attrs')                     AS attrs_json,   -- lifecycle/turn/hook 의 attrs
      JSONExtractString(raw_json, 'envelope', 'identity', 'vendor_email') AS vendor_email
    FROM base
    WHERE signal IN ('log', 'span')
  )
SELECT 1
```

사람 단위 키(가정 A3): `coalesce(nullIf({inst_member:Map(String,String)}[installation_id], ''), installation_id) AS member_key`.
빈 문자열 주의: 신원 컬럼은 non-nullable이라 없는 값이 `''`이다(EnrichedEventRow KDoc). `installation_id = ''` 행은 구성원 귀속 불가 → `data_quality`에 비율 보고.

```sql
-- Q1. active_users (A-1)  frame_type=scalar|timeseries, group_by=team
--   1순위: active_time{user} > 0.  폴백(가정 A2): 기간 내 이벤트 1건 이상.
SELECT team_id, count() AS active_users
FROM (
  SELECT member_key, team_id
  FROM (
    SELECT coalesce(nullIf({inst_member:Map(String,String)}[installation_id], ''), installation_id) AS member_key,
           team_ids_as_of, value
    FROM metric_points
    WHERE name = 'claude_code.active_time.total' AND attr_type = 'user'
  ) ARRAY JOIN team_ids_as_of AS team_id          -- group_by=team 일 때만. 전체는 ARRAY JOIN 제거
  GROUP BY member_key, team_id
  HAVING sum(value) > 0
)
GROUP BY team_id;

-- Q1-fallback (metrics 신호가 꺼진 테넌트): 이벤트 1건 이상
SELECT team_id, uniqExact(coalesce(nullIf({inst_member:Map(String,String)}[installation_id], ''), installation_id)) AS active_users
FROM base ARRAY JOIN team_ids_as_of AS team_id
WHERE installation_id != ''
GROUP BY team_id;
```

```sql
-- Q2. adoption_rate (A-2) 분모 — PostgreSQL (enrollment)
SELECT t.id AS team_id, t.name, count(DISTINCT m.id) AS active_member_count
FROM enrollment.teams t
JOIN enrollment.team_memberships tm ON tm.team_id = t.id AND tm.left_at IS NULL
JOIN enrollment.members m ON m.id = tm.member_id AND m.status = 'active'
WHERE t.tenant_id = :tenant AND t.status = 'active'
GROUP BY t.id, t.name;
-- adoption_rate = Q1.active_users / active_member_count (분모 0 → null). group_size = active_users, < 5 → suppressed
```

```sql
-- Q3. sessions (A-3) by start_type, interval
SELECT toStartOfInterval(ts, INTERVAL 1 day, {tz:String}) AS t, attr_start_type AS start_type, sum(value) AS sessions
FROM metric_points
WHERE name = 'claude_code.session.count'
GROUP BY t, start_type ORDER BY t;

-- Q4. active_time (A-4) / automation_ratio
SELECT team_id,
       sumIf(value, attr_type = 'user') AS user_sec,
       sumIf(value, attr_type = 'cli')  AS cli_sec,
       if(user_sec + cli_sec = 0, NULL, cli_sec / (user_sec + cli_sec)) AS automation_ratio
FROM metric_points ARRAY JOIN team_ids_as_of AS team_id
WHERE name = 'claude_code.active_time.total'
GROUP BY team_id;
```

```sql
-- Q5. prompts_per_session (A-5) distribution + command_prompt_ratio
WITH per_session AS (
  SELECT session_id, count() AS prompts,
         countIf(command_name != '') AS command_prompts
  FROM events WHERE type = 'user_prompt' AND session_id != '(unknown)'
  GROUP BY session_id
)
SELECT count() AS sessions,
       quantilesExact(0.5, 0.9)(prompts) AS p50_p90,
       sum(command_prompts) / sum(prompts) AS command_prompt_ratio,
       -- 히스토그램 버킷 1 · 2-3 · 4-7 · 8-15 · 16+
       countIf(prompts = 1) AS b1, countIf(prompts BETWEEN 2 AND 3) AS b2_3,
       countIf(prompts BETWEEN 4 AND 7) AS b4_7, countIf(prompts BETWEEN 8 AND 15) AS b8_15, countIf(prompts >= 16) AS b16
FROM per_session;
```

```sql
-- Q6. lines_of_code / commits / pull_requests / integration_depth (B-1, B-2, B-3), interval=1w
SELECT toStartOfInterval(ts, INTERVAL 1 week, {tz:String}) AS t,
       sumIf(value, name = 'claude_code.lines_of_code.count' AND attr_type = 'added')   AS loc_added,
       sumIf(value, name = 'claude_code.lines_of_code.count' AND attr_type = 'removed') AS loc_removed,
       sumIf(value, name = 'claude_code.commit.count')       AS commits,
       sumIf(value, name = 'claude_code.pull_request.count') AS pull_requests,
       sumIf(value, name = 'claude_code.session.count' AND attr_start_type = 'fresh') AS fresh_sessions,
       if(fresh_sessions = 0, NULL, (commits + pull_requests) / fresh_sessions) AS integration_depth
FROM metric_points
GROUP BY t ORDER BY t;
```

```sql
-- Q7. cost (C-1) — source=events (기본, 두 제품 공통) / price_basis
--   {discounts:Array(Tuple(String, Float64))} = API 가 RDB contract_token_discounts 에서 읽은 (model_pattern, discount_rate)
--   token_type='all' 이고 effective_from <= 기간 <= effective_to 인 행만 (가정 A5). 토큰 종류별 할인은 부록 A-5.
SELECT toStartOfInterval(ts, INTERVAL 1 day, {tz:String}) AS t,
       sum(cost_usd) AS cost_usd_list,
       sum(cost_usd * arrayFirst(x -> position(model, x.1) > 0, {discounts:Array(Tuple(String, Float64))}).2) AS cost_usd_contract,
       countIf(cost_source = 'reported') AS reported_calls,
       countIf(cost_source = 'estimated') AS estimated_calls
FROM events
WHERE type = 'llm_call' AND cost_usd IS NOT NULL
GROUP BY t ORDER BY t;
-- arrayFirst 가 매칭 실패 시 (('',0)) 을 돌려주므로 API 는 discounts 끝에 폴백 ('', 1.0) 을 붙인다.

-- Q7-metrics. source=metrics (귀속 차원: agent_name / skill_name / plugin_name / mcp_server / speed / effort) — claude_code 만
SELECT attr_query_source AS query_source, attr_agent_name AS agent_name, sum(value) AS cost_usd
FROM metric_points
WHERE name = 'claude_code.cost.usage'
GROUP BY query_source, agent_name ORDER BY cost_usd DESC LIMIT {limit:UInt32};

-- subagent_cost_ratio (C-3) = sumIf(value, attr_query_source='subagent') / sum(value) on name='claude_code.cost.usage'
```

```sql
-- Q8. tokens (C-2) source=events + cache_read_ratio + input_output_ratio
SELECT toStartOfInterval(ts, INTERVAL 1 day, {tz:String}) AS t,
       sum(tok_input) AS input, sum(tok_output) AS output,
       sum(tok_cache_read) AS cache_read, sum(tok_cache_create) AS cache_create,
       if(input + cache_read + cache_create = 0, NULL, cache_read / (input + cache_read + cache_create)) AS cache_read_ratio,
       if(output = 0, NULL, input / output) AS input_output_ratio
FROM events WHERE type = 'llm_call'
GROUP BY t ORDER BY t;
-- source=metrics: sumIf(value, attr_type='cacheRead') 등 on name='claude_code.token.usage' (attrs.type 값은 input/output/cacheRead/cacheCreation)
```

```sql
-- Q9. edit_acceptance_rate (D-1) — 사람 결정만 분모
SELECT attr_language AS language,
       sumIf(value, attr_decision = 'accept') AS accepts,
       sumIf(value, attr_decision = 'reject') AS rejects,
       if(accepts + rejects = 0, NULL, accepts / (accepts + rejects)) AS acceptance_rate
FROM metric_points
WHERE name = 'claude_code.code_edit_tool.decision'
  AND attr_source IN ('user_temporary', 'user_permanent', 'user_reject', 'user_abort')   -- 자동승인(config/hook) 제외
GROUP BY language ORDER BY accepts + rejects DESC;
```

```sql
-- Q10. gate_wait_ms (D-4) distribution by decision — 평균 금지, 분위수만
SELECT decision,
       count() AS gates,
       quantilesExact(0.5, 0.9)(blocked_on_user_ms) AS p50_p90
FROM events
WHERE type = 'tool_gate' AND blocked_on_user_ms IS NOT NULL
GROUP BY decision;
```

```sql
-- Q11. tool_rejections (D-5) / auto_approval_ratio (D-2)
SELECT decided_by, tool_name,
       countIf(decision = 'reject') AS rejections,
       count() AS decisions
FROM events WHERE type = 'tool_decision'
GROUP BY decided_by, tool_name ORDER BY rejections DESC LIMIT {limit:UInt32};
-- auto_approval_ratio = countIf(decided_by IN ('config','hook')) / count()
```

```sql
-- Q12. api_error_rate (F-1) by status_code, interval=1h / api_retry_attempts
SELECT toStartOfInterval(ts, INTERVAL 1 hour, {tz:String}) AS t,
       count() AS calls,
       countIf(error_type != '') AS errors,
       if(calls = 0, NULL, errors / calls) AS error_rate,
       countIf(attempt >= 2) AS retried_calls,
       sumMap([toString(coalesce(status_code, 0))], [toUInt64(error_type != '')]) AS errors_by_status   -- (keys[], values[]) 튜플
FROM events WHERE type = 'llm_call'
GROUP BY t ORDER BY t;
-- 주의: api_request 는 성공 요청만, api_error 는 시도(attempt) 단위 → "시도 실패율"이다 (지표보고서 F-1). caveat 로 표기.
```

```sql
-- Q13. llm_ttft_ms / llm_duration_ms / turn_duration_ms (F-3)
SELECT model,
       quantilesExact(0.5, 0.9)(ttft_ms)             AS ttft_p50_p90,
       quantilesExact(0.5, 0.95, 0.99)(duration_ms)  AS dur_p50_p95_p99,
       count() AS calls
FROM events
WHERE type = 'llm_call' AND error_type = '' AND duration_ms IS NOT NULL
GROUP BY model;
-- turn_duration_ms: FROM events WHERE type='turn' → JSONExtract(attrs_json, 'duration_ms', 'Nullable(Int64)')
```

```sql
-- Q14. tool_calls / tool_failure_rate (F-2) by tool_name / error_type / action
SELECT tool_name, tool_kind, action,
       count() AS calls,
       countIf(success = false) AS failures,
       countIf(success IS NOT NULL) AS judged,
       if(judged = 0, NULL, failures / judged) AS failure_rate,
       topK(5)(error_type) AS top_error_types
FROM events WHERE type = 'tool_call'
GROUP BY tool_name, tool_kind, action ORDER BY calls DESC LIMIT {limit:UInt32};
-- read_tool_density (S7-2): 세션당 countIf(action IN ('read','search','fetch')) 의 quantilesExact(0.5,0.9)
-- subagent_activity (E-5 부분): uniqExact(agent_id) WHERE agent_id != '', countIf(agent_id != '')/count()
```

```sql
-- Q15. mcp_connections / mcp_failure_ratio (E-4)
SELECT JSONExtractString(attrs_json, 'server_name')    AS server_name,
       JSONExtractString(attrs_json, 'server_scope')   AS server_scope,
       JSONExtractString(attrs_json, 'transport_type') AS transport_type,
       JSONExtractString(attrs_json, 'is_plugin')      AS is_plugin,        -- "True"/"False" (Python str 표기, Normalized.kt KDoc)
       count() AS connections,
       countIf(JSONExtractString(attrs_json, 'status') != 'connected') AS failures,
       if(connections = 0, NULL, failures / connections) AS failure_ratio,
       uniqExact(session_id) AS sessions
FROM events
WHERE type = 'lifecycle' AND lifecycle_kind = 'mcp_connection'
GROUP BY server_name, server_scope, transport_type, is_plugin ORDER BY connections DESC;
-- 가정 A7: status 의 성공값은 'connected' (Claude Code 문서: connected/failed/disconnected). 실제 값 집합은 골든 fixture 로 확인.
```

```sql
-- Q16. compactions / compaction_reduction (C-5)
SELECT toStartOfInterval(ts, INTERVAL 1 day, {tz:String}) AS t,
       JSONExtractString(attrs_json, 'trigger') AS trigger,
       count() AS compactions,
       if(sum(tokens_before) = 0, NULL, 1 - sum(tokens_after) / sum(tokens_before)) AS reduction
FROM events WHERE type = 'lifecycle' AND lifecycle_kind = 'compaction'
GROUP BY t, trigger ORDER BY t;
```

```sql
-- Q17. refusals (G-2) — group_by=team 금지 (전사 단위)
SELECT if(refusal_category = '', 'unspecified', refusal_category) AS category, model, count() AS refusals
FROM events WHERE type = 'llm_response' AND stop_reason = 'refusal'
GROUP BY category, model;
-- caveat: server_fallback_hop 이 보존되지 않아 한 턴이 홉+최종 2건으로 셀 수 있음 (ClaudeCodeLogs.kt 주석)
```

```sql
-- Q18. hook_executions / hook_blocking (G-1) — detailed beta tracing 전제
SELECT JSONExtractString(attrs_json, 'hook_event') AS hook_event,
       count() AS executions,
       sum(JSONExtract(attrs_json, 'num_blocking', 'Nullable(Int64)')) AS blocking,
       uniqExact(session_id) AS sessions_with_hooks
FROM events WHERE type = 'hook'
GROUP BY hook_event;
-- 커버리지 = sessions_with_hooks / (SELECT uniqExact(session_id) FROM events)
```

```sql
-- Q19. usage_heatmap (S2-1) hour × weekday, 값 = user_prompt 수
SELECT toHour(ts, {tz:String}) AS hour, toDayOfWeek(ts, 0, {tz:String}) AS weekday, count() AS prompts
FROM events WHERE type = 'user_prompt'
GROUP BY hour, weekday;

-- Q20. rate_limit_events (S2-1/S2-2)
SELECT toStartOfInterval(ts, INTERVAL 1 hour, {tz:String}) AS t, count() AS rate_limited
FROM events WHERE type = 'llm_call' AND status_code = 429
GROUP BY t ORDER BY t;
```

```sql
-- Q21. cost_anomaly (S1-3) — 일별 비용 vs 직전 N일 이동평균
WITH daily AS (
  SELECT toStartOfDay(ts, {tz:String}) AS d, sum(cost_usd) AS cost
  FROM events WHERE type = 'llm_call' AND cost_usd IS NOT NULL
  GROUP BY d
)
SELECT d, cost,
       avg(cost) OVER (ORDER BY d ROWS BETWEEN 7 PRECEDING AND 1 PRECEDING) AS moving_avg,   -- 7 = moving_avg_days (윈도 프레임에는 파라미터 치환 불가 → 서버가 문자열로 조립)
       if(moving_avg IS NULL OR moving_avg = 0, NULL, cost / moving_avg - 1) AS deviation
FROM daily ORDER BY d;
-- findings: deviation >= spike_threshold_pct/100 인 d.  top_mover 는 Q7-metrics 를 스파이크일/기준기간으로 두 번 실행해 비중 차이 계산.
```

```sql
-- Q22. telemetry_coverage (H-1)
SELECT uniqExactIf(installation_id, installation_id != '') AS active_installations, max(ts) AS last_ingested_at
FROM base;
-- 분모 (PostgreSQL):
SELECT count(*) FILTER (WHERE i.status = 'active') AS installations_active,
       (SELECT count(*) FROM enrollment.members m WHERE m.tenant_id = :tenant AND m.status = 'active') AS members_active
FROM enrollment.installations i WHERE i.tenant_id = :tenant;
```

```sql
-- Q23. vendor_account_mismatch (H-3) — 벤더가 준 이메일이 등록 이메일과 다른 설치
--   {inst_email:Map(String,String)} = installations.id → members.email (API 가 RDB 에서 구성)
SELECT installation_id,
       anyLast(vendor_email) AS vendor_email,
       {inst_email:Map(String,String)}[installation_id] AS registered_email
FROM events
WHERE vendor_email != ''
GROUP BY installation_id
HAVING lower(vendor_email) != lower(registered_email);
-- 응답에는 도메인만 노출(X-Audit-Reason 없을 때). 프라이버시 규칙 §2-1.
```

```sql
-- Q24. rubber_stamp_ratio (S5-7)
SELECT countIf(decision = 'accept' AND blocked_on_user_ms < {threshold_ms:UInt32}) AS instant_accepts,
       countIf(decision = 'accept') AS accepts,
       if(accepts = 0, NULL, instant_accepts / accepts) AS ratio
FROM events WHERE type = 'tool_gate' AND decided_by = 'user' AND blocked_on_user_ms IS NOT NULL;
```

```sql
-- Q25. abandoned_session_ratio / session_last_event (S4-2)
WITH outputs AS (
  SELECT session_id
  FROM metric_points
  WHERE (name = 'claude_code.code_edit_tool.decision' AND attr_decision = 'accept')
     OR name IN ('claude_code.lines_of_code.count', 'claude_code.commit.count', 'claude_code.pull_request.count')
  GROUP BY session_id HAVING sum(value) > 0
),
last_ev AS (
  SELECT session_id, argMax(type, (ts, coalesce(sequence, 0))) AS last_type,
         argMax(error_type, (ts, coalesce(sequence, 0))) AS last_error
  FROM events WHERE signal = 'log' AND session_id != '(unknown)'
  GROUP BY session_id
)
SELECT count() AS sessions,
       countIf(session_id NOT IN outputs) AS abandoned,
       abandoned / sessions AS abandoned_ratio,
       sumMap([multiIf(last_error != '', 'api_error', last_type = 'llm_call', 'llm_call', last_type)], [1]) AS last_event_dist
FROM last_ev;
```

```sql
-- Q26. usage_concentration (S3-2) — 익명 분포만 (개인 식별 없음)
WITH per_person AS (
  SELECT coalesce(nullIf({inst_member:Map(String,String)}[installation_id], ''), installation_id) AS member_key,
         sum(tok_input + tok_output + tok_cache_read + tok_cache_create) AS billable
  FROM events WHERE type = 'llm_call' GROUP BY member_key
)
SELECT count() AS people,
       sum(billable) AS total,
       arraySum(arraySlice(arrayReverseSort(groupArray(billable)), 1, greatest(1, toUInt32(ceil(count() * 0.1))))) / total AS top10_share,
       arrayCumSum(arraySort(groupArray(billable))) AS lorenz_cumulative   -- 클라이언트가 /total 로 정규화
FROM per_person;
```

```sql
-- Q27. onboarding_ttfu / onboarding_retention (S3-3)
--   {inst_created:Map(String,DateTime)} = installations.id → created_at (RDB)
SELECT installation_id,
       min(ts) AS first_event_at,
       dateDiff('hour', {inst_created:Map(String,DateTime)}[installation_id], first_event_at) AS ttfu_hours
FROM base WHERE installation_id != '' GROUP BY installation_id;
-- retention: 코호트 = toStartOfWeek(first_event_at); n주차 잔존 = 해당 주에 이벤트 있는 installation / 코호트 크기
```

```sql
-- Q28. SESSION-EVENTS — 세션 상관 조회 (감사 로그 후)
SELECT event_id, ts, signal, product, installation_id, team_ids_as_of, raw_json
FROM enriched_events FINAL
WHERE tenant_id = {tenant:String}
  AND JSONExtractString(raw_json, 'envelope', 'session_id') = {session_id:String}
ORDER BY ts, JSONExtract(raw_json, 'sequence', 'Nullable(Int64)')
LIMIT {limit:UInt32} OFFSET {offset:UInt32};
-- lookup=request_id: WHERE JSONExtractString(raw_json,'payload','request_id') = {key} 로 session_id 를 먼저 찾는다.
-- 성능: raw_json 전문 스캔 → 부록 A-1 의 materialized 컬럼 session_id 가 필요하다.
```

## 6. 시나리오 카탈로그와 실행 모델

### 6-1. 실행 모델

1. `POST /v1/scenarios/{id}/runs` — `params_schema` 검증(400) → `availability` 검사(409) → 동시 실행 상한(429) → `dashboard.scenario_runs` 행 생성(`status=queued`) → 202 + `Location` + `Retry-After: 2`.
2. 워커: `metric_ids` 순서대로 §5 템플릿 실행(`progress.step`) → `findings_rules` 평가 → `result{target_page, applied_filters, highlight_widgets, findings, frames}` 저장 → `status=succeeded`.
3. 클라이언트: `GET /v1/scenario-runs/{run_id}` 폴링. 완료 시 `target_page`로 이동, `applied_filters`를 필터 바에 적용, `highlight_widgets` 강조, `frames`로 위젯을 즉시 그림(재조회 없음).
4. 저장: `POST …/save` → `dashboard.saved_reports`. `time_mode=relative`면 열 때 같은 `params`로 새 run 을 만든다.

### 6-2. 카탈로그 (46)

`availability`: 가능(available) / 부분(partial) / 준비 중(unavailable — UI 비활성, 실행 409). `highlight_widgets`는 디자인 프롬프트 위젯 ID.

| scenario_id | 제목 | category | 가용성 | target_page | highlight_widgets | metric_ids | 주요 params |
|---|---|---|---|---|---|---|---|
| S1-1 | 부서별 토큰 할당 불균형 | cost | available | P1 | W1.3, W1.6 | tokens, cost, adoption_rate | from,to,team_ids,**budget_by_team**(입력, 가정 S1) |
| S1-2 | 모델 티어 미스매치 | cost | available | P2 | W2.5, W2.2 | tokens, cost, prompts_per_session, tool_calls | from,to,team_ids,premium_model_patterns |
| S1-3 ⭐ | 비용 스파이크·폭주 | cost | available | P1 | W1.2, W1.3, W2.5 | cost, cost_anomaly, api_retry_attempts | from,to,moving_avg_days,spike_threshold_pct |
| S1-4 ⭐ | 반복 프롬프트→캐싱 | cost | partial(유사도 불가) | P2 | W2.6 | tokens, cache_read_ratio, prompts_per_session | from,to,team_ids |
| S1-5 ⭐ | 컨텍스트 과다 첨부 | cost | partial(@멘션 없음) | P2 | W2.6, W2.9 | input_output_ratio, compactions, compaction_reduction | from,to,team_ids,io_ratio_threshold |
| S1-6 | 모델·effort 낭비 | cost | available | P2 | W2.5, W2.6 | cost(source=metrics, effort/speed), tokens, sessions | from,to,team_ids |
| S1-7 ⭐ | 유휴 라이선스·좀비 시트 | cost | available | P3 | W3.2 | telemetry_coverage, active_users, cost_per_active_user | as_of,inactive_days |
| S2-1 ⭐ | 오전/오후 사용량 변동 | usage_pattern | available | P2 | W2.3 | usage_heatmap, rate_limit_events, session_last_event | from,to,team_ids |
| S2-2 ⭐ | Rate Limit 상시 도달 | usage_pattern | partial(retries_exhausted 없음) | P3 | W3.3, W2.6 | rate_limit_events, tokens, api_retry_attempts | from,to,team_ids |
| S2-3 | 요일·스프린트 주기 | usage_pattern | available | P2 | W2.3, W2.4 | usage_heatmap, sessions, llm_duration_ms, rate_limit_events | from,to,team_ids,**sprint_dates** |
| S3-1 | 직군별 채택 격차 | adoption | partial(직군 없음→팀) | P1 | W1.6, W1.1 | active_users, adoption_rate, prompts_per_session, tool_calls, mcp_connections | from,to |
| S3-2 ⭐ | 파워유저 집중 vs 롱테일 | adoption | available(익명) | P2 | W2.5, W2.8 | usage_concentration, tool_calls, tokens | from,to,team_ids |
| S3-3 ⭐ | 온보딩 정착 | adoption | available | P3 | W3.2 | onboarding_ttfu, onboarding_retention, active_time | cohort_from,cohort_to,team_ids |
| S3-4 ⭐ | 부서별 활용 격차 | adoption | available | P1 | W1.6, W1.5 | sessions, active_time, lines_of_code, adoption_rate | from,to |
| S3-5 | 고급 기능 활용률 | adoption | partial(스킬·플러그인 없음) | P2 | W2.8, W2.10 | mcp_connections, subagent_cost_ratio, command_prompt_ratio, tool_failure_rate | from,to,team_ids |
| S4-1 | 재시도·프롬프트 반복률 | productivity | available | P2 | W2.2 | prompts_per_session, tokens | from,to,team_ids |
| S4-2 | 대화 포기율 | productivity | available | P2 | W2.2, W2.10 | abandoned_session_ratio, session_last_event, api_error_rate | from,to,team_ids |
| S4-3 | 코드 수용률·revert | productivity | partial(revert 불가) | P2 | W2.7, W2.4 | edit_acceptance_rate, lines_of_code, commits, pull_requests | from,to,team_ids,language |
| S4-4 ⭐ | 교육 효과 전후 비교 | productivity | available | P2 | W2.0, W2.2, W2.8 | active_users, prompts_per_session, edit_acceptance_rate, mcp_connections | **pivot_date**,window_weeks,team_ids |
| S4-5 ⭐ | 템플릿 활용률 | productivity | partial(스킬 없음) | P2 | W2.8, W2.2 | command_prompt_ratio, prompts_per_session | from,to,command_names |
| S4-6 | 유즈케이스 분포 | productivity | partial(주제 분류 불가) | P2 | W2.8 | tool_calls(action), lines_of_code | from,to,team_ids |
| S4-7 | 성과 상관분석 | productivity | **unavailable**(외부 DORA) | P1 | — | — | — |
| S4-8 ⭐ | Plan 응답 대기 시간 | productivity | available(팀 단위) | P2 | W2.7 | gate_wait_ms, tool_rejections, usage_heatmap | from,to,team_ids,wait_thresholds_min |
| S5-1 ⭐ | 민감정보 유출 | security | **unavailable**(원문 미취급) | P3 | — | hook_blocking(대리) | — |
| S5-2 | 코드·문서 외부 유출 | security | partial | P3 | W3.2 | mcp_connections, read_tool_density | from,to |
| S5-3 | 고객 데이터 취급 | security | **unavailable**(원문) | P3 | — | — | — |
| S5-4 | 섀도우 AI | security | partial | P3 | W3.3, W3.2 | rate_limit_events, vendor_account_mismatch, mcp_connections, active_users | from,to |
| S5-5 ⭐ | 인젝션·탈옥 시도 | security | partial(전사 단위) | P3 | W3.3 | refusals, hook_blocking, tool_rejections | from,to,probe_window_min,probe_count |
| S5-6 | 정책 위반 용도 | security | partial | P3 | W3.3 | tool_rejections(config/hook) | from,to,pivot_date |
| S5-7 | 무검증 반출(러버스탬프) | security | available | P3 | W3.3 | rubber_stamp_ratio, auto_approval_ratio, pull_requests | from,to,threshold_ms |
| S6-1 | 품질 피드백 루프 | quality | partial(설문 없음) | P2 | W2.7, W2.2 | refusals, edit_acceptance_rate, prompts_per_session | from,to,models |
| S6-2 | 환각 신고 | quality | **unavailable**(외부 채널) | P3 | — | — | — |
| S6-3 | 모델 버전 드리프트 | quality | available | P3 | W3.1 | api_error_rate, tool_failure_rate, prompts_per_session, llm_stop_reasons | **pivot_date**,models |
| S6-4 | 레이턴시·에러율·장애 | quality | available | P3 | W3.1 | llm_duration_ms, llm_ttft_ms, api_error_rate, active_users | from,to,models |
| S6-5 | 리트라이 스톰 | quality | partial(고갈 없음) | P3 | W3.1 | api_retry_attempts, cost | from,to |
| S7-1 | 에이전트 태스크 성공률 | governance | available | P2 | W2.10, W2.5 | tool_failure_rate, tool_calls, subagent_activity, cost | from,to,team_ids,failure_threshold |
| S7-2 ⭐ | 과도한 데이터 접근 | governance | partial | P3 | W3.2, W3.3 | read_tool_density, mcp_connections, auto_approval_ratio | from,to,density_threshold |
| S7-3 | 에이전트 정책 위반·에스컬레이션 | governance | available | P3 | W3.3 | tool_rejections, hook_blocking, gate_wait_ms | from,to |
| S7-4 ⭐ | 감사 로그·리텐션 거버넌스 | governance | partial(retention_sweep·auth 없음) | P3 | W3.2, W3.4 | telemetry_coverage, hook_executions, mcp_connections | from,to |
| S8-1 | ROI·경영 보고 | strategy | partial(재무 조인 없음) | P1 | W1.1, W1.5, W1.2 | sessions, tokens, active_time, lines_of_code, commits, pull_requests, cost_per_active_user | from,to |
| S8-2 ⭐ | 예산 수립·비용 예측 | strategy | available | P1 | W1.2, W1.4 | cost, tokens, model_unit_price, cache_read_ratio, onboarding_retention | from(≥3개월),to,growth_model |
| S8-3 | 신규 모델 A/B | strategy | available(설문 축 제외) | P3 | W3.1, W2.5 | prompts_per_session, cost, llm_duration_ms, api_error_rate | from,to,model_a,model_b |
| S8-4 | 벤더 종속 | strategy | partial | P1 | W1.4 | tokens(product/model), cost | from,to |
| S8-5 ⭐ | 도구 중복·통합 | strategy | partial | P1 | W1.1, W1.4 | active_users(product), cost_per_active_user(product), tool_calls | from,to |
| S8-6 | 정책 실효성 | strategy | available | P3 | W3.3 | tool_rejections, hook_blocking, gate_wait_ms | pivot_date,window_weeks |
| S8-7 ⭐ | 챔피언 프로그램 | strategy | partial(개인 리스트 미제공) | P2 | W2.0, W2.2 | adoption_rate, prompts_per_session, usage_concentration | pivot_date,team_ids |

## 7. 추적표 (위젯 → 엔드포인트 → metric_id → 컬럼)

`raw.`는 `enriched_events.raw_json` 안의 경로, `ee.`는 물리 컬럼, `rdb.`는 enrollment 테이블. 모든 QRY 행은 공통으로 `ee.tenant_id`, `ee.ts`, `ee.team_ids_as_of`, `ee.product`, `ee.installation_id`를 쓴다.

| 위젯 | operationId | metric_id | 응답 필드 | 원천 컬럼 |
|---|---|---|---|---|
| 커버리지 바 | QRY | telemetry_coverage | active_installations, active_members, ratio, last_ingested_at | ee.installation_id, ee.ts, rdb.installations.status, rdb.members.status |
| W1.1 | QRY | active_users | active_users(+compare) | raw.point.name, raw.point.attrs.type, raw.point.value, ee.installation_id → rdb.installations.member_id |
| W1.1 | QRY | adoption_rate | ratio, numerator, denominator | 위 + rdb.team_memberships.left_at, rdb.members.status |
| W1.1 W1.2 W1.3 | QRY | cost | cost_usd_list, cost_usd_contract | raw.payload.cost_usd, raw.payload.cost_source, raw.payload.model, rdb.contract_token_discounts.{model_pattern,discount_rate,token_type,effective_from,effective_to} |
| W1.1 | QRY | cost_per_active_user | value | cost ÷ active_users |
| W1.1 W2.1 | QRY | sessions | sessions | raw.point.name=claude_code.session.count, raw.point.attrs.start_type |
| W1.1 W1.5 W2.4 | QRY | lines_of_code | loc_added, loc_removed | raw.point.name=claude_code.lines_of_code.count, raw.point.attrs.type, raw.point.attrs.model |
| W1.4 W2.6 | QRY | tokens | input, output, cache_read, cache_create | raw.payload.tokens.{input,output,cache_read,cache_create} / raw.point.attrs.type,model |
| W1.4 | QRY | model_users | users | raw.payload.model, ee.installation_id → member |
| W1.5 W2.4 | QRY | commits, pull_requests, integration_depth | commits, pull_requests, integration_depth | raw.point.name=claude_code.commit.count / pull_request.count / session.count(start_type=fresh) |
| W1.6 | QRY | adoption_rate(team, 1w) | ratio | 상동 |
| W1.7 W2.7 | QRY | edit_acceptance_rate | accepts, rejects, acceptance_rate | raw.point.name=claude_code.code_edit_tool.decision, raw.point.attrs.{decision,source,language,tool_name} |
| W1.7 W2.7 | QRY | auto_approval_ratio | ratio, numerator, denominator | raw.type=tool_decision, raw.payload.decided_by |
| W1.8 W3.1 | QRY | api_error_rate | calls, errors, error_rate, errors_by_status | raw.type=llm_call, raw.payload.error_type, raw.payload.status_code, raw.payload.model |
| W1.8 W3.1 | QRY | llm_ttft_ms | p50, p90 | raw.payload.ttft_ms (llm_call 우선, llm_request 스팬 폴백) |
| W1.9 W3.3 | QRY | refusals | refusals | raw.type=llm_response, raw.payload.stop_reason=refusal, raw.payload.refusal_category |
| W1.9 W3.2 | QRY | hook_blocking, hook_executions | blocking, executions, sessions_with_hooks | raw.type=hook, raw.payload.attrs.{hook_event,num_blocking,num_hooks,duration_ms} |
| W2.0 | QRY | active_time, automation_ratio | user_sec, cli_sec, automation_ratio | raw.point.name=claude_code.active_time.total, raw.point.attrs.type |
| W2.2 W2.8 | QRY | prompts_per_session, command_prompt_ratio | p50, p90, buckets, ratio | raw.type=user_prompt, raw.envelope.session_id, raw.payload.command_name |
| W2.3 | QRY | usage_heatmap, rate_limit_events | hour, weekday, prompts / rate_limited | ee.ts, raw.type=user_prompt / raw.payload.status_code=429 |
| W2.5 | QRY | cost(source=metrics), subagent_cost_ratio | cost_usd by group | raw.point.name=claude_code.cost.usage, raw.point.attrs.{model,query_source,agent.name,skill.name,plugin.name,mcp_server.name,speed,effort} |
| W2.6 | QRY | cache_read_ratio | ratio, numerator, denominator | raw.payload.tokens.* |
| W2.7 | QRY | gate_wait_ms | p50, p90, gates | raw.type=tool_gate, raw.payload.blocked_on_user_ms, raw.payload.decision, raw.payload.decided_by |
| W2.7 W3.3 | QRY | tool_rejections | rejections, decisions | raw.type=tool_decision, raw.payload.{decision,decided_by,tool_name} |
| W2.8 W3.2 | QRY | mcp_connections, mcp_failure_ratio | connections, failures, failure_ratio, sessions | raw.type=lifecycle, raw.payload.kind=mcp_connection, raw.payload.attrs.{server_name,status,transport_type,server_scope,is_plugin} |
| W2.8 W2.10 W3.1 | QRY | tool_calls, tool_failure_rate | calls, failures, failure_rate, top_error_types | raw.type=tool_call, raw.payload.{tool_name,tool_kind,action,success,error_type,mcp_server,agent_id} |
| W2.9 | QRY | compactions, compaction_reduction | compactions, reduction | raw.type=lifecycle, raw.payload.kind=compaction, raw.payload.{tokens_before,tokens_after}, raw.payload.attrs.trigger |
| W3.1 | QRY | llm_duration_ms, api_retry_attempts | p50, p95, p99 / retried_calls | raw.payload.duration_ms, raw.payload.attempt |
| W3.2 | INSTALL-LIST | — | installation_id, platform, client_version, last_seen_at, last_event_at | rdb.installations.{id,member_id,hostname,platform,client_version,status,created_at,last_seen_at}, ee.ts, raw.envelope.client.version |
| W3.2 P5 | META-MANIFESTS | — | version, signals, applied_installations | rdb.manifests.{version,manifest,is_active,activated_at}, rdb.installation_manifest_assignments.applied_at |
| W3.3 | QRY | vendor_account_mismatch | installation_id, vendor_email(도메인), registered_email(도메인) | raw.envelope.identity.vendor_email, rdb.members.email |
| W3.3 | QRY | rubber_stamp_ratio | instant_accepts, accepts, ratio | raw.type=tool_gate, raw.payload.{blocked_on_user_ms,decision,decided_by} |
| W3.4 | SESSION-EVENTS | — | SessionEvent[] | ee.event_id, ee.ts, ee.signal, raw.* (payload/point 그대로) |
| L1 | SCN-LIST | — | ScenarioSummary[] | 정적 카탈로그(코드) |
| L2 | SCN-GET | — | params_schema, findings_rules | 정적 카탈로그 |
| L3 L4 | SCN-RUN, RUN-GET | (시나리오별 metric_ids) | ScenarioRun.result | rdb.dashboard.scenario_runs (부록 A-6) + 위 지표 컬럼 |
| L5 | RUN-SAVE, SAVED-LIST, SAVED-DELETE, RUN-LIST, RUN-DELETE | — | SavedReport | rdb.dashboard.saved_reports, dashboard.scenario_runs |
| 필터 바 | META-FILTERS, META-TEAMS, META-MODELS | — | TeamOption[], ModelOption[] | rdb.teams.{id,name,status}, rdb.team_memberships, ee.product, raw.payload.model ∪ raw.point.attrs.model |
| P5 | META-CONTRACTS | — | contracts[], token_discounts[], term_commitment | rdb.contracts.*, rdb.contract_token_discounts.*, rdb.contract_term_commitments.*, rdb.contract_memberships |
| P5 P3 | META-MEMBERS | — | member_id, email, role, status, team_ids, installation_count | rdb.members.*, rdb.team_memberships, rdb.installations |
| 셸 | ME | — | role, tenant, accessible_team_ids | rdb.members.{id,email,display_name,role}, rdb.tenants.{id,name,timezone}, rdb.team_memberships |

## 8. 부록

### 부록 A — 필요한 스키마·어댑터 변경 (엔드포인트를 만들지 않은 항목)

| # | 항목 | 막힌 지표·시나리오 | 필요한 변경 |
|---|---|---|---|
| A-1 | **ClickHouse 성능 컬럼** | 전 지표(raw_json 전문 파싱), Q28 세션 조회 | `V2__enriched_events_materialized.sql`: `ALTER TABLE enriched_events ADD COLUMN IF NOT EXISTS session_id String MATERIALIZED JSONExtractString(raw_json,'envelope','session_id')`, 같은 방식으로 `event_type`, `metric_name`, `model`, `cost_usd Nullable(Float64)`. ADR 0015(멱등 V파일)·ADR 0017 규칙 6 개정 필요(컬럼 화이트리스트). 인덱스: `INDEX idx_session session_id TYPE bloom_filter`. 정렬 키는 `event_id`라 시간 범위 스캔이 전체 스캔 → `PARTITION BY toYYYYMM(ts)` 추가 검토 |
| A-2 | **어댑터가 드롭하는 이벤트 보존** (ClaudeCodeLogs `else -> null`) | D-3 `permission_mode_changed`(S5-6, S7-3) · E-2 `skill_activated`(S3-5, S4-5, S8-7) · E-3 `plugin_installed/loaded`(S5-4) · E-5 `subagent_completed`(S7-1) · E-6 `at_mention`(S1-5, S7-2) · F-1 `api_retries_exhausted`(S2-2, S6-5) · F-4 `feedback_survey`(S6-1, S8-3) · G-1 `hook_registered/hook_execution_*`(S7-4) · G-3 `retention_sweep`(S7-4) · G-4 `auth`(S1-7, S3-3) · `internal_error`(P3 안정성) | `LogKind`에 값 추가 또는 `LIFECYCLE` kind로 승격(`Lifecycle(kind, attrs)` 화이트리스트). ADR 0017 규칙 1(원문 미취급) 유지하며 속성만 승격. golden fixture 재생성 |
| A-3 | `api_refusal.server_fallback_hop` 보존 | G-2 거부 건수 정확도 | `LlmResponse`에 `serverFallbackHop: Boolean?` 추가 |
| A-4 | **installation → member 조인** | 모든 사람 단위 distinct(A-1 등) | (권장) ClickHouse `DICTIONARY pm_installation_member (installation_id String, member_id String, email String) SOURCE(POSTGRESQL(...enrollment.installations JOIN members)) LIFETIME(300)` → SQL에서 `dictGet`. 현재는 API가 Map 파라미터로 전달(가정 A3) |
| A-5 | **토큰 종류별 계약 단가** | `price_basis=contract`의 정확도 | `cost_usd`는 총액뿐이라 `token_type≠all` 할인을 적용하려면 모델별 단가표(`Pricing.kt`의 codex 표를 RDB `pricing_rates(model_pattern, input, output, cache_read, cache_create, effective_from)`로 승격)가 필요. 그전까지 `token_type='all'` 행만 적용 |
| A-6 | **시나리오 실행·저장 테이블** | P4 전부 | `CREATE SCHEMA dashboard; scenario_runs(id uuid, tenant_id, scenario_id varchar, status, params jsonb, resolved_from, resolved_to, result jsonb, error jsonb, created_by_member_id, created_at, finished_at); saved_reports(id, run_id, tenant_id, name, note, time_mode, created_by_member_id, created_at); audit_log(id, tenant_id, member_id, action, target, reason, created_at)` — Flyway `V5__dashboard_schema.sql` |
| A-7 | **예산·캘린더 저장** | S1-1 예산, S2-3 스프린트, S4-4/S6-3 기준일 재사용 | `dashboard.team_budgets(team_id, period, amount_usd, tokens)`, `dashboard.calendar_events(tenant_id, kind, on_date, label)`. 그전까지 시나리오 파라미터 입력(가정 S1) |
| A-8 | 직군 필드 | S3-1 직군별 채택 | `members.job_function` 또는 팀 속성. 현재 팀으로 대체 |
| A-9 | 원문 기반 지표 | S5-1, S5-3, S5-6 분류, S1-4 유사도, S4-6 주제 | **정책상 미도입**(ADR 0017 규칙 1, 지표보고서 §5-4). 필요 시 별도 컴플라이언스 절차 — 이 API 범위 밖 |
| A-10 | 외부 데이터 조인 | S4-7 DORA, S6-2 환각 신고, S8-1 재무 | Git/CI·신고 시스템·재무 커넥터. `github`/`jira` provider가 no-op 스텁으로 자리만 있음(ADR 0017 규칙 8) |
| A-11 | codex 메트릭 이름 매핑 | product=codex 의 메트릭 기반 지표(A-3, A-4, B-*) | codex 메트릭 이름 집합이 레포에 없음(CodexCommon "실 캡처가 한 건도 없다"). 현재 codex는 `llm_call` 기반 지표(비용·토큰·에러·지연)만 산출. 가정 A8 |

### 부록 B — 가정 목록

| # | 가정 | 근거 / 확인 방법 |
|---|---|---|
| A1 | 메트릭은 delta temporality로 도착 → `sum(value)`. `aggregation_temporality=2` 포인트는 제외하고 `data_quality`로 보고 | Claude Code 문서 기본값 `delta`; 골든 fixture의 2는 합성값 |
| A2 | 활성 사용자 폴백 = 기간 내 이벤트 1건 이상 (`meta.active_user_definition`에 명시) | manifest가 metrics 신호를 끌 수 있음 |
| A3 | installation → member 해석은 API 계층(RDB 조회, Map 파라미터) | ClickHouse에 member 컬럼 없음(ADR 0017 규칙 7). 권장 대안 부록 A-4 |
| A4 | 관리자 인증은 Bearer 세션 토큰(ADR 0007 Spring Security) | AdminAuthenticator KDoc "대시보드가 생길 때 다시 정한다" |
| A5 | 계약 금액 = 공시 금액 × `discount_rate`(`token_type='all'`, `model_pattern` 부분 문자열, 유효기간 내), 매칭 없으면 ×1.0 | contract_token_discounts COMMENT. 토큰 종류별은 부록 A-5 |
| A6 | 실행 이력·저장·감사 로그는 신규 `dashboard` 스키마 | 현 스키마에 없음 |
| A7 | `mcp_connection.attrs.status`의 성공값은 `connected` | Claude Code 문서(connected/failed/disconnected). 골든 fixture로 확인 |
| A8 | codex는 `llm_call` 기반 지표만, 메트릭 기반 지표는 claude_code 한정 | CodexCommon KDoc |
| A9 | `tool_gate.decided_by='user'`가 사람 결정. `code_edit_tool.decision.source` ∈ user_temporary/user_permanent/user_reject/user_abort | ClaudeCodeCommon.DECISION_SOURCES, Claude Code 문서 |
| A10 | 시나리오 `findings_rules` 임계 기본값(스파이크 +200%, 러버스탬프 2초, 프로빙 10분 5회, 도구 실패 5%)은 시나리오 문서의 예시값 | 시나리오 문서 §1-3, §5-5, §5-7, §7-1 |
| S1 | 예산·스프린트 일정·교육 기준일은 시나리오 파라미터 입력 | 저장 테이블 없음(부록 A-7) |
