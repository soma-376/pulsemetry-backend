# claude_code/real — Claude Code 실수신 캡처 익명화 추출본

**실캡처 익명화 — 합성 아님.** 실제 Claude Code 가 로컬 OTLP 수집기로 보낸 export 를 골라 익명화했다.
실제 wire 모양(속성 키·wire 타입·이름의 위치·push 묶음)이 목적이고, 값의 내용은 믿을 대상이 아니다.

## 출처

| 항목 | 값 |
|---|---|
| producer | Anthropic Claude Code (scope `com.anthropic.claude_code.events`) |
| service.name | `claude-code`. 미등록 서비스 사례로 `node_repl` 0.1.0 로그 2건 |
| service.version | 로그 `2.1.269` · `2.1.270` · `2.1.272` · `2.1.273` · `2.1.278` · `2.1.280` · `2.1.281` · `2.1.282`, 스팬 `2.1.269` · `2.1.270` · `2.1.272` · `2.1.278`, 메트릭 `2.1.269` · `2.1.270` · `2.1.272` |
| 수집 경로 | producer → OTLP 수집기(file exporter, processor 없음) → 한 줄 = export 하나 |
| 캡처 기간 | 2026-09-12 – 2026-09-25 (14일) |
| 원본 파일 | 회전된 로그 파일 1개와 쓰는 중인 로그 파일의 줄 사본, 회전된 트레이스 파일 2개, 쓰는 중인 메트릭 파일의 앞 4,000줄 사본 |

## 이벤트 이름의 위치

Claude Code 로그는 의미 이름을 **두 곳**에 싣는다 — 본문 문자열은 접두사가 붙은 `claude_code.<이름>`, 속성 `event.name` 은
접두사 없는 `<이름>`. 캡처 사본의 모든 Claude Code 로그(7,405건)가 그렇다. 최상위 `eventName` 필드는 쓰지 않는다. 익명화는 본문이 정확히
`<접두사>.<같은 레코드의 event.name>` 일 때만 본문을 남겨 이 모양을 보존한다.

## 파일

`<signal>-<service>-<version>-<kind>.otlp.jsonl`. 한 줄 = 원본 export 하나에서 고른 레코드만 남긴 문서(원래 resource·scope 아래
그대로). 기대값(`*.expected.jsonl`)은 아직 없다 — Claude Code 프로파일 작업이 명세에서 쓴다.

| kind | 담은 것 |
|---|---|
| `api` | `api_request` — `cost_usd`·`cost_usd_micros` 둘 다 있음/`cost_usd` 만/비용 없음, MCP·스킬이 걸린 요청, `query_source` 값마다 하나(메인·compact·내장 subagent·요약 등); `assistant_response` |
| `tools` | `tool_result` — 실패·MCP·도구 이름별, `tool_decision` — 거절·출처별 |
| `session` | `user_prompt`(내장·사용자 정의 명령, 명령 없음), `compaction`, `subagent_completed`, `skill_activated`, `permission_mode_changed`, `mcp_server_connection`(상태별), `auth`, `hook_registered`·`hook_execution_start`·`hook_execution_complete` |
| `generic` | `at_mention`·`feedback_survey`·`retention_sweep`·`plugin_loaded`·`managed_settings_resolved`, `node_repl` 의 `codex.browser_use.security_check` |
| `spans` | `claude_code.interaction`·`claude_code.llm_request`(오류 동반·`query_source_safe` 별)·`claude_code.tool`·`claude_code.tool.execution`(오류 동반)·`claude_code.tool.blocked_on_user` |
| `metrics` | `session.count`·`lines_of_code.count`·`pull_request.count`·`commit.count`·`cost.usage`·`token.usage`·`code_edit_tool.decision`·`active_time.total` |

선택 계획에 있으나 캡처에 없었던 것: 로그 `api_error`·`api_retries_exhausted`·`api_refusal`·`api_request_body`·`api_response_body`·
`internal_error`·`plugin_installed`·`hook_plugin_metrics`, 스팬 `claude_code.hook`. 없다는 것은 그 이벤트가 이 버전에 없다는
증거가 아니다.

레코드 275개(metric point 31개), 문서 197개, 파일 38개.

## 선택 기준

선택 계획은 `scripts/otlp-fixtures/plans/claude-code.json` 이다. (서비스, 버전) 조합마다 선택자별 상한(대개 1–3개)을 두었고,
속성 유무·값별 하나로 분기를 덮었다. 한 push 에서 고른 레코드는 같은 문서에 남아 원래 묶음을 보존한다.

## 치환 규칙

도구 `scripts/otlp-fixtures/` (커밋 `6518d61`)의 `sanitize.py` 를 한 번의 실행으로 모든 파일에 적용했다. 규칙 표는 그 디렉터리의
README 가 적는다. 요지:

- 남김: 구조·속성 키, 숫자·불리언과 그 wire 타입(`duration_ms` 가 이벤트마다 `intValue`/`stringValue` 로 다른 것 포함), enum 꼴 값,
  모델 이름, 스팬·메트릭·scope 이름, 메트릭 설명·단위, 제품 내장 에이전트 이름·내장 명령 이름, 위의 이벤트 이름 본문.
- ID(`session.id`·`prompt.id`·`user.id`·`organization.id`·`request_id`·`tool_use_id`·trace/span ID 등) → 같은 모양의 가짜. 한 실행
  안에서 대응이 일정하므로 파일을 가로지르는 관계가 보존된다.
- `user.email` → `userN@example.test`, 경로 → `/redacted/pathN`, MCP 서버·도구 → `serverN`·`mcp_toolN`·`mcp__serverN__mcp_toolN`,
  플러그인·마켓플레이스·스킬 → `pluginN`·`marketplaceN`·`skillN`, 사용자 정의 에이전트·명령 → `agentN`·`commandN`.
- `prompt`·`user_prompt`·`response`·`tool_input`·`tool_parameters`·`full_command` → 빈 문자열(키는 남는다).
- 오류 메시지 등 자유 텍스트 → `<redacted len=N>`.
- 시각 → 모든 레코드에 같은 오프셋(밀리초 배수)을 뺐다. 가장 이른 시각이 2026-01-01T00:00:00Z 부근이다. 오프셋과 salt 는
  저장하지 않았다.

관문 `gate.py` 를 원본 파일 다섯 전부를 `--source` 로 주고 통과시켰다(원본 ID 교차 0건). 다시 구우면 가짜 값과 시각이 모두
바뀐다 — 기대값이 붙은 뒤에는 이 파일들을 다시 굽지 말고 새 파일을 더한다.
