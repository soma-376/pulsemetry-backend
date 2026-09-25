# Claude Code 프로파일 근거 — 이벤트 이름·필드·비용·작업 목적

Claude Code producer 가 내보내는 값의 의미를 확인한 기록이다. 외부 원천의 인용은 이 문서에만 둔다.

- producer 소스는 공개돼 있지 않다. 근거는 둘이다: (1) **실캡처 fixture**(`real/` — wire 모양의 정본), (2) **공식 모니터링
  문서**(`code.claude.com/docs/en/monitoring-usage`). 문서는 웹 조회 도구가 요약·가공한 형태로만 읽을 수 있었고, 그렇게 얻은
  일부 절(compaction·hook·auth 이벤트 속성)은 실캡처와 어긋났다. 그래서 **실캡처와 일치하는 문서 진술만** 근거로 쓰고, 어긋나는
  곳은 실캡처를 따른다.
- 적용 범위: 실캡처가 있는 버전 `2.1.269`·`2.1.270`·`2.1.272`·`2.1.273`·`2.1.278`·`2.1.280`·`2.1.281`·`2.1.282`. 그 밖은 generic.
- 표면(`surface`): 서비스 이름 `claude-code` 하나뿐이고 CLI·IDE 등을 가르는 근거 속성이 없다 — `unknown`.

## 1. 의미 이름의 위치

실캡처 사본의 Claude Code 로그 7,405 건 전부가 이름을 두 곳에 싣는다: 본문 문자열 `claude_code.<이름>` 과 속성 `event.name` =
`<이름>`. 문서의 표준 속성 표도 `event.name` 을 "이벤트 종류 이름(예: `user_prompt`)"으로 적는다. 프로파일은 `event.name` 을 의미
이름으로 읽고, 본문이 있으면 `claude_code.` + 그 이름과 같아야 한다 — 다르면 매핑하지 않는다(`ambiguous`). 최상위 `eventName` 은
쓰지 않는다.

## 2. `api_request`

| 속성 | wire(실캡처) | 매핑 |
|---|---|---|
| `input_tokens`·`output_tokens`·`cache_read_tokens`·`cache_creation_tokens` | intValue | 토큰 네 성분(reasoning·tool·총계 필드는 없다) |
| `cost_usd` | doubleValue | 보고 비용(아래 3) |
| `cost_usd_micros` | intValue | 보고 비용의 정확한 표기(아래 3) |
| `duration_ms`·`ttft_ms` | intValue | duration·TTFT(`ttft_scope = request`) |
| `query_source` | stringValue | 작업 목적(아래 4) |
| `model`·`request_id`·`client_request_id`·`effort`·`speed`·`agent.name`·`skill.name`·`plugin.name`·`marketplace.name`·`mcp_server.name`·`mcp_tool.name` | stringValue | 모델·요청 ID 는 컬럼, 나머지는 metadata |

문서는 `request_id` 를 API 응답 헤더의 요청 ID, `client_request_id` 를 클라이언트가 만든 UUID 로 적는다 — 다른 ID 라 namespace 를
나눈다. 사용자 정의 에이전트·스킬·플러그인·MCP 이름은 producer 가 기본으로 `custom`/`third-party` 로 가린다고 문서가 적고, 실캡처의
값도 내장 이름뿐이다.

## 3. 비용

- `cost_usd` 는 벤더가 보고한 **추정** 금액이다 — 공식 청구 기록은 별도 billing 데이터다(문서). `reported_cost_basis = estimate`.
- 실캡처의 `api_request` 2,006 건 전부가 `cost_usd`(double)와 `cost_usd_micros`(정수 백만분의 일 달러)를 함께 싣고, 두 값의 차이는
  전부 **1 마이크로달러 이하**다(0.5 마이크로달러를 넘는 5 건 — 반올림 경계). 그래서 프로파일은 micros 를 정확한 금액으로 쓰고,
  두 값이 1 마이크로달러보다 크게 어긋나면 보고 금액을 null + `cost_conflict` 로 둔다. micros 가 없으면 double 을 컬럼 정밀도
  (소수 12자리)로 반올림한다.
- 비용 한쪽만 있는 행·비용 없는 행은 실캡처에 없다 — 그 분기는 명세 사례(`synthetic/api`)로만 고정한다.

## 4. `query_source` → `workload_kind`

문서가 적은 값: `main`·`subagent`·`auxiliary`(카운터 메트릭의 범주), 이벤트에서는 `repl_main_thread`·`compact`·하위 에이전트
이름. 실캡처 값: `repl_main_thread`·`compact`·`agent:builtin:<내장 에이전트>`·`agent_summary`·`away_summary`·`prompt_suggestion`·
`generate_session_title`·`rename_generate_name`·`auxiliary`·`sdk`·`web_fetch_apply`·`main`.

| 값 | workload |
|---|---|
| `repl_main_thread`·`main` | `main` |
| `compact` | `compaction` — 작업 목적일 뿐 사용량에서 빼지 않는다(primary) |
| `subagent`·`agent:<종류>:<이름>` | `subagent` |
| 그 밖(보조 작업·모르는 값) | `unknown` |

## 5. 도구와 결정

- 호출 ID 는 모델의 `tool_use` 블록 ID(`tool_use_id`) — 문서가 `tool_result`·`tool_decision`·`claude_code.tool` 스팬에서 같은 값이라
  적고, 실캡처의 세 곳이 같은 모양이다.
- `tool_result`: `success` 는 문자열 `"true"`/`"false"`, `duration_ms` 는 10진 문자열(실캡처). `error_type` 은 오류 분류 식별자
  (`ShellError`·`McpToolCallError`·`chrome_computer_action_failed` — 실캡처)라 식별자 꼴이면 `error_type` 컬럼으로, 아니면 unknown.
  `tool_input`·`tool_parameters`·`error` 는 싣지 않는다. 거절된 호출에는 이 이벤트가 없다(문서).
- MCP 도구: 로그의 `tool_name` 은 producer 가 가린 `mcp_tool` 이고, `tool_result` 는 `mcp_server_scope`, `tool_decision` 은
  `tool_source = mcp` 를 싣는다(실캡처). 이것이 출처(`tool_origin = mcp`)의 근거다 — 동작의 근거는 아니다.
- 내장 도구: 공식 도구 목록(`code.claude.com/docs/en/tools-reference`)의 이름 46개를 `builtin` 으로 본다. 실캡처의 도구 이름
  (Bash·Read·Write·Edit·Agent·Skill·ToolSearch·AskUserQuestion·ExitPlanMode·Artifact)은 모두 그 목록에 있다. 동작은 목록의 설명이
  분명한 것만: Read → read, Write → write, Edit·NotebookEdit → edit, Glob·Grep·WebSearch → search, WebFetch → fetch,
  Bash·PowerShell → exec. 나머지는 unknown.
- `tool_decision`: `decision` 은 `accept`·`reject`, `source` 는 `config`·`hook`·`user_permanent`·`user_temporary`·`user_abort`·
  `user_reject`(문서; 실캡처는 `config`·`user_temporary`·`user_reject`). `config` → config, `user_*` 넷 → user, `hook` 은
  `decision_source` 어휘에 없어 unknown(원래 값은 metadata).

## 6. 생애주기 이벤트

| 이벤트 | event_type | 실캡처 wire | 매핑 |
|---|---|---|---|
| `user_prompt` | `prompt.submitted` | `prompt_length` 10진 문자열, `command_name`·`command_source` | 길이, 내장(`command_source = builtin`) 명령 이름만 컬럼. 사용자 정의 명령 이름(실캡처에 그대로 실린다)은 싣지 않는다 |
| `compaction` | `context.compacted` | `pre_tokens`·`post_tokens`·`duration_ms` 10진 문자열, `success` 문자열 | 전후 토큰은 컨텍스트 크기 — metadata 에만 |
| `subagent_completed` | `agent.completed` | `total_tokens`·`duration_ms` int | `total_tokens` 는 마지막 요청의 컨텍스트 크기 — metadata 에만 |
| `mcp_server_connection` | `mcp.connection` | `duration_ms` 10진 문자열, `server_name`·`status`·`transport_type`·`server_scope` | `server_name` 은 사용자 설정 서버면 producer 가 `custom` 으로 가린다(문서) — `mcp_server` 컬럼 |
| `auth` | `auth.event` | `success` 문자열, `action`·`auth_method` | |
| `skill_activated`·`permission_mode_changed`·`hook_registered`·`hook_execution_start`·`hook_execution_complete` | `skill.activated`·`permission.changed`·`hook.registered`·`hook.started`·`hook.completed` | `total_duration_ms` 10진 문자열 | 허용 metadata. 훅 시작과 완료를 시간으로 잇지 않는다 |

generic 목록(`internal_error`·`plugin_installed`·`plugin_loaded`·`at_mention`·`hook_plugin_metrics`·`feedback_survey`·`retention_sweep`)과
그 밖의 이름(`managed_settings_resolved` 등)은 `vendor.unknown` 이다.

## 7. 실캡처가 없는 이벤트

`api_error`·`api_retries_exhausted`·`api_refusal`·`api_request_body`·`api_response_body` 는 캡처 사본에 없다. 이벤트 이름과 역할은
부록 A.2 대로 매핑하되, 정수 필드(`status_code`·`attempt`·`duration_ms`·`total_attempts`·`total_retry_duration_ms`)는 wire 를 확인하지
못해 intValue·10진 문자열을 모두 받는다. 필드 이름은 문서 요약과 참조 분석이 일부 어긋나(`attempts` 대 `total_attempts` 등) 실캡처로
확인하기 전까지 명세 사례(`synthetic/errors`)만 고정한다.

## 8. 스팬과 메트릭

- 스팬 여섯(`claude_code.interaction`·`llm_request`·`tool`·`tool.execution`·`tool.blocked_on_user`·`hook`)만 행이 된다. 실캡처에
  `claude_code.hook` 은 없다(문서는 상세 추적 설정에서만 난다고 적는다). 모든 스팬이 `session.id` 를 싣는다.
- `llm_request` 의 토큰 네 필드(int)는 같은 요청의 `api_request` 로그와 **같은 값**이다 — 실캡처에서 `client_request_id` 로 짝이 맞는
  666 요청이 전부 일치했다. 그래서 스팬 토큰은 진단용이고 사용량에 더하지 않는다. `stop_reason` 은 API 응답의 값(`end_turn`·
  `tool_use`·`stop_sequence` — 실캡처; `max_tokens`·`pause_turn`·`refusal` — 문서)만 옮긴다. `gen_ai.response.id` 는 실캡처에서
  `request_id` 와 같은 값이라 응답 ID 로 쓰지 않는다.
- 도구 스팬: `tool_use_id` = `gen_ai.tool.call.id`(실캡처에서 같은 값 — 다르면 null). 스팬의 `tool_name` 은 MCP 도구를
  `mcp__<서버>__<도구>` 원문으로 싣고 `tool_name_safe` 는 `mcp_other` 로 가린다 — 컬럼은 `tool_name_safe`. `full_command`·
  `file_path`·`user_prompt` 는 싣지 않는다.
- 메트릭 여덟은 실캡처에서 전부 `sum`(cumulative, monotonic)이다. 프로파일은 이름 + sum 일 때만 family 를 붙인다. 단위는
  `USD`·`tokens`·`s` 등 원형 그대로(`raw_unit`), 단위 registry 는 두지 않았다. `token.usage` 의 `type` 라벨 `input`·`output`·
  `cacheRead`·`cacheCreation` → `token_component`. 카운터의 `query_source` 는 범주 셋(`main`·`subagent`·`auxiliary` — 문서)이다.
