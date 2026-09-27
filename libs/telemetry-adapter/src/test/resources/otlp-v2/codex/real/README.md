# codex/real — Codex 실수신 캡처 익명화 추출본

**실캡처 익명화 — 합성 아님.** 실제 Codex producer 가 로컬 OTLP 수집기로 보낸 export 를 골라 익명화했다.
실제 wire 모양(속성 키·wire 타입·분기·push 묶음)이 목적이고, 값의 내용은 믿을 대상이 아니다.

## 출처

| 항목 | 값 |
|---|---|
| producer | OpenAI Codex (Rust `codex-otel`, `telemetry.sdk.version` 0.31.0) |
| service.name | `codex-app-server` · `Codex Desktop` (캡처에 `codex_cli_rs` 는 없었다) |
| service.version | `0.153.4` · `0.154.0-alpha.6.2` · `0.155.0-alpha.2.6` · `0.155.0-alpha.9.2` |
| 수집 경로 | producer → OTLP 수집기(file exporter, processor 없음) → 한 줄 = export 하나 |
| 캡처 기간 | 2026-09-16 – 2026-09-24 (9일) |
| 원본 파일 | 회전된 로그 파일 1개, 회전된 트레이스 파일 2개, 쓰는 중인 메트릭 파일의 앞 4,000줄 사본 |

캡처에 있는 버전 × 서비스 조합만 파일이 있다. 로그는 다섯 조합(app-server 0.153.4 는 이름 없는 로그 하나뿐),
스팬은 app-server 0.154.0-alpha.6.2·0.155.0-alpha.9.2 와 Desktop 0.153.4, 메트릭은 0.153.4·0.154.0-alpha.6.2 만 있다.

## 파일

`<signal>-<service>-<version>-<kind>.otlp.jsonl`. 한 줄 = 원본 export 하나에서 고른 레코드만 남긴 문서(원래
resource·scope 아래 그대로). 기대값(`*.expected.jsonl`)은 아직 없다 — Codex 프로파일 작업이 명세에서 쓴다.

| kind | 담은 것 |
|---|---|
| `sse` | `codex.sse_event` — `response.completed` 토큰 있음(모두 `cache_write_token_count` 동반 — 없는 분기는 캡처에 없었다), 토큰 없이 오류를 동반한 완료, `error.message` 동반, `event.kind` 마다 하나(0.155.0-alpha.2.6 에만 delta 계열이 있다). `kind` 별칭 키를 가진 SSE 는 캡처에 없었다 |
| `requests` | `codex.api_request` 두 형식 — 모델 목록(최상위 `eventName` 이 `models_endpoint.rs`, `endpoint=/models`, 오류 있음·없음)과 responses(`session_telemetry.rs`, `endpoint=/responses`, 0.155.0-alpha.2.6 에만 있다), `codex.websocket_connect`·`codex.websocket_request` |
| `tools` | `codex.tool_result` — 같은 `call_id` 에 `tool_result_seq` 가 다른 묶음, MCP(stdio·원격 origin) 결과, 네임스페이스별 하나; `codex.tool_decision`(approved·denied), `codex.sandbox_outcome` |
| `session` | `codex.conversation_starts`·`codex.user_prompt`·`codex.turn_ttft`·`codex.startup_phase`(단계별)·`codex.auth_recovery`(0.154.0-alpha.6.2 에만)·`codex.agent_communication`(kind 별), `event.name` 없는 로그 하나 |
| `spans` | 분석 후보 `session_task.turn`·`session_task.compact`·`try_run_sampling_request`·`mcp.tools.call`·도구 실행 계열(`handle_tool_call`·`dispatch_tool_call_with_terminal_outcome`·`handle_tool_call_with_source`·`code_mode.broker.invoke_tool`·`codex.hooks.mcp_tool`)과 내부 스팬 몇 개(`session_task.run`·`handle_responses`·`run_turn`·`codex.exec`) |
| `metrics` | family 후보마다 metric 하나(point 1–8개): `turn.token_usage`·`turn.tool.call`·`tool.call`·`tool.call.duration_ms`·`turn.e2e_duration_ms`·`turn.ttft.duration_ms`·`turn.ttfm.duration_ms`·`thread.started`·`conversation.turn.count`·`mcp.call*`·`hooks.run*`·`skill.injected`·`multi_agent.spawn`·`task.compact`·`guardian.review.token_usage`·`guardian_v2.classification.token_usage`, 미등록 예로 `sqlite.init.count`·`startup.phase.duration_ms` |

레코드 240개(metric point 87개), 문서 130개, 파일 27개.

## 선택 기준

선택 계획은 `scripts/otlp-fixtures/plans/codex.json` 이다. (서비스, 버전) 조합마다 선택자별 상한(대개 1–3개)을 두었고,
분기를 덮도록 속성 유무(`has`·`missing`)·값별 하나(`distinct_by`)·묶음(`group_by`)으로 골랐다. 한 push 에서 고른
레코드는 같은 문서에 남아 원래 묶음을 보존한다.

## 치환 규칙

도구 `scripts/otlp-fixtures/` (커밋 `7858314`)의 `sanitize.py` 를 한 번의 실행으로 모든 파일에 적용했다. 규칙 표는
그 디렉터리의 README 가 적는다. 요지:

- 남김: 구조·속성 키, 숫자·불리언과 그 wire 타입(`input_token_count` 처럼 `stringValue` 로 온 숫자는 그대로),
  enum 꼴 값(버전·모델·종류·상태), 최상위 `eventName` 의 소스 위치, 스팬·메트릭·scope 이름, 메트릭 설명·단위.
- ID(`conversation.id`·`call_id`·`turn.id`·`user.account_id`·trace/span ID 등) → 같은 모양의 가짜. 한 실행 안에서
  대응이 일정하므로 파일을 가로지르는 관계(로그의 traceId ↔ 스팬, 같은 `call_id` 의 여러 결과)가 보존된다.
- `user.email` → `userN@example.test`, `host.name` → `host-redacted`, 경로 → `/redacted/pathN`,
  MCP 서버·커넥터·스킬 → `serverN`·`connectorN`·`skillN`, MCP 네임스페이스·도구 → `mcp__serverN`·`mcp_toolN`,
  원격 MCP origin → `https://originN.example.test`, 하위 에이전트 → `/root/agentN`.
- `prompt`·`arguments`·`output`·`content`·`mcp_servers` 와 로그 `body` → 빈 문자열(키는 남는다).
- 오류 메시지 등 자유 텍스트 → `<redacted len=N>`.
- 시각 → 모든 레코드에 같은 오프셋(밀리초 배수)을 뺐다. 가장 이른 시각이 2026-01-01T00:00:00Z 부근이고
  `event.timestamp` 도 같은 오프셋으로 옮겼다. 오프셋과 salt 는 저장하지 않았다.

관문 `gate.py` 를 원본 파일 넷 전부를 `--source` 로 주고 통과시켰다(원본 ID 교차 0건). 다시 구우면 가짜 값과
시각이 모두 바뀐다 — 기대값이 붙은 뒤에는 이 파일들을 다시 굽지 말고 새 파일을 더한다.
