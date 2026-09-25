# Codex 프로파일 근거 — 토큰 성분·provider·operation

Codex producer 가 내보내는 값의 **의미**를 producer 소스로 확인한 기록이다. ADR 0020 부록 C 의 Codex 행이 이
문서를 근거 경로로 가리킨다. 외부 원천(producer 소스·공식 문서)의 인용은 이 문서에만 둔다.

- producer 소스: openai/codex 저장소의 `codex-rs/`(Apache-2.0). 태그 넷 `rust-v0.153.4`·`rust-v0.154.0-alpha.6.2`·
  `rust-v0.155.0-alpha.2.6`·`rust-v0.155.0-alpha.9.2` 를 각각 읽었다. 경로는 `codex-rs/` 기준, 표기는 `경로@태그:행`.
- 대응 fixture: `real/`(실캡처 익명화, 같은 네 버전). 파일 이름은 `real/README.md`.
- **이 문서는 근거 기록이지 프로파일 확정이 아니다.** 소스로 확인한 사실과, 소스로 확인하지 못해 unknown 으로
  남긴 것을 구분한다. 실캡처의 수치 관계는 끝의 "관측 대조"에 따로 두며 포함관계의 증거로 쓰지 않는다.

## 요약

네 태그의 해당 코드는 **행 번호만 다르고 내용이 같다**(아래 "태그별 위치"). 그래서 답도 네 버전에서 같다.

| # | 질문 | 답 | 근거 수준 |
|---|---|---|---|
| 1 | `input_token_count` 의 원천·cache read 포함 | API `usage.input_tokens` 를 그대로 옮긴다. producer 는 cache read 를 input 의 일부로 정의한다(`non_cached_input = input − cached`) | 소스 |
| 2 | `cache_write_token_count` 의 원천·포함·해당 없음 | API `usage.input_tokens_details.cache_write_tokens`. **필드가 없으면 0 으로 채운다** — wire 의 0 이 측정 0 인지 미보고인지 구별할 수 없다. input 포함 여부·"해당 없음"은 소스로 확정되지 않는다 | unknown |
| 3 | `reasoning_token_count`·`tool_token_count` ⊂ output | reasoning: API `usage.output_tokens_details.reasoning_tokens`(없으면 0), producer 는 output 의 일부로 다룬다. **`tool_token_count` 는 tool 성분이 아니라 API `usage.total_tokens` 다** — output 의 부분집합이 아니다 | 소스 |
| 4 | provider 근거의 위치·설정 경로 | 사용량 SSE 행에는 **없다**. `codex.conversation_starts` 의 `provider_name`(로그와 `session_init` 스팬 이벤트)과 `model_client.websocket_connection` 스팬의 `provider` 에만 있고, 둘 다 설정된 provider 의 **표시 이름**이다 | 소스 |
| 5 | `codex.api_request` 의 `eventName` 위치 → operation | 모델 목록: `model-provider/src/models_endpoint.rs:204`(0.153.4·0.154) · `:230`(0.155 둘) → `models_list`. 세션 형식: `otel/src/events/session_telemetry.rs:665` · `:688` — **여러 경로가 공유하는 위치**라 위치만으로는 operation 을 정할 수 없고 `endpoint` 속성이 경로를 싣는다 | 소스 |
| 6 | 사용량 행의 세션 속성·`event.kind`/`kind` | 세션 속성(`conversation.id` 등)이 **있다**. SSE 로그는 `event.kind` 만 싣고 `kind` 키는 내지 않는다 | 소스 |
| 7 | 토큰·`duration_ms` 가 문자열인 이유 | `tracing` 필드에 `%`(Display)로 기록한 값은 문자열, 정수 그대로 기록한 값은 정수로 나간다 | 소스 |

공식 문서(OpenAI Responses API 의 usage 필드 정의)는 확인하지 못했다 — 참조 페이지가 이 환경에서 HTTP 403 을
돌려줬다. 그래서 API 수준의 의미(예: `input_tokens` 가 `input_tokens_details` 의 성분을 모두 포함하는지)는
**producer 가 그렇게 다룬다는 사실**까지만 적는다.

## 1. `input_token_count`

- 기록: `otel/src/events/session_telemetry.rs` 의 `sse_event_completed` — `input_token_count = %usage.input_tokens`.
- 값의 출처: `codex-api/src/sse/responses.rs` 의 `impl From<ResponseCompletedUsage> for TokenUsage` —
  `input_tokens: val.input_tokens`. `ResponseCompletedUsage.input_tokens` 는 `response.completed` 이벤트의
  `response.usage.input_tokens` 이고 필수 필드다(없으면 파싱 실패 → 아래 "오류 행").
- HTTP SSE 와 websocket 두 전송이 같은 변환을 쓴다 — `codex-api/src/endpoint/responses_websocket.rs` 가
  `crate::sse::process_responses_event` 를 호출한다.
- **cache read 포함(producer 정의).** `protocol/src/protocol.rs` 의 `impl TokenUsage`:

  ```rust
  pub fn non_cached_input(&self) -> i64 {
      (self.input_tokens - self.cached_input()).max(0)
  }
  /// Primary count for display as a single absolute value: non-cached input + output.
  pub fn blended_total(&self) -> i64 {
      (self.non_cached_input() + self.output_tokens.max(0)).max(0)
  }
  ```

  producer 는 cached 를 input 에서 빼서 비캐시 input 을 만든다 — cache read 를 input 의 부분으로 정의한다.
  같은 파일의 파서 단위 테스트 `parses_cache_write_token_usage`(`codex-api/src/sse/responses.rs`)도
  `input_tokens: 100`, `cached_tokens: 40`, `cache_write_tokens: 60`, `total_tokens: 110`(output 10)을 쓴다.
  이것은 producer 가 만든 예시이지 API 명세가 아니다.

## 2. `cache_write_token_count`

- 기록: `cache_write_token_count = usage.cache_write_input_tokens`(정수).
- 값의 출처: `cache_write_input_tokens: input_tokens_details.cache_write_tokens`.

  ```rust
  let input_tokens_details = val.input_tokens_details.unwrap_or_default();
  ...
  struct ResponseCompletedInputTokensDetails {
      cached_tokens: i64,
      #[serde(default)]
      cache_write_tokens: i64,
  }
  ```

  `input_tokens_details` 가 없으면 `Default`(두 성분 모두 0), `cache_write_tokens` 가 없으면 `serde(default)` 로 0 이다.
  **따라서 wire 의 `cache_write_token_count = 0` 은 "API 가 0 을 보고함"과 "API 가 이 필드를 보내지 않음"을 구별하지
  못한다.** `cached_token_count` 도 `input_tokens_details` 전체가 없을 때는 같은 문제를 갖는다.
- input 포함 여부: 필드가 `input_tokens_details`(input 의 세부 항목) 아래에 있고, producer 의 `non_cached_input` 은
  cache write 를 빼지 않는다. 이것만으로는 API 가 cache write 를 `input_tokens` 에 포함하는지 확정할 수 없다.
- 해당 없음: 소스는 필드를 읽으므로 **해당 없음이 아니다** — 어느 backend 가 값을 채우는지는 소스로 알 수 없다.
- 결론: **unknown.** 이 범위에서 cache write 를 "보고됨"으로도 "해당 없음"으로도 확정하지 않는다.

## 3. `reasoning_token_count`·`tool_token_count`

- `reasoning_token_count = usage.reasoning_output_tokens` ← `val.output_tokens_details.map(|d| d.reasoning_tokens).unwrap_or(0)`.
  `output_tokens_details`(output 의 세부 항목)가 없으면 0 이다(2 와 같은 구별 불가). producer 의 `blended_total` 은
  `non_cached_input + output` 이고 reasoning 을 따로 더하지 않는다 — reasoning 을 output 의 부분으로 다룬다.
- **`tool_token_count = %usage.total_tokens`.** 이름과 달리 tool 성분이 아니고 API `usage.total_tokens` 를 그대로
  옮긴 값이다(`total_tokens: val.total_tokens`). output 의 부분집합이 아니며 tool 성분은 이 범위에 **없다**.
  원본이 사용량 총계로 정의한 필드이므로 `tokens_total_reported` 의 후보이고, `tokens_tool` 에 넣으면 틀린다.

## 4. provider 근거

- 사용량 행(`sse_event_completed`)의 속성: `event.kind`·토큰 여섯·`ttft_ms`·`service_tier`·`model_reasoning_effort` +
  공통 세션 속성(6). **provider 는 없다.**
- `codex.conversation_starts`: `provider_name = %provider_name`. 호출부 `core/src/session/session.rs` 가
  `config.model_provider.name` 을 넘긴다. 로그(`log_event!`)와 트레이스(`trace_event!` — 스팬 이벤트) 두 곳에 나간다.
- 스팬 `model_client.websocket_connection`(`core/src/client.rs`): `provider = %…provider.info().name`, `wire_api`.
- 설정 경로: `config.toml` 의 `model_provider`(id)가 `model_providers` 표에서 provider 를 고른다
  (`core/src/config/mod.rs` — `built_in_model_providers(openai_base_url)` 와 설정의 `model_providers` 를 합친다).
  내장 provider(`model-provider-info/src/lib.rs` 의 `built_in_model_providers`): `openai`(표시 이름 `OpenAI`,
  `openai_base_url` 로 base URL 교체 가능), `amazon-bedrock`·`amazon-bedrock-runtime`, `ollama`·`lmstudio`(로컬).
  모두 Responses wire API 를 쓰고, 사용자는 `model_providers` 에 임의 이름·URL 의 provider 를 더할 수 있다.
- 따라서 `provider_name`/`provider` 는 **설정된 표시 이름**이다. `OpenAI` 라는 값도 base URL 이 OpenAI 라는
  증거가 아니다. 사용량 행에는 행 단위 provider 근거가 없고, 다른 신호의 값을 끌어 쓰는 것은 신호 간 조인이다.
- fixture: `real/logs-*-session.otlp.jsonl` 의 `codex.conversation_starts`(`provider_name`). 스팬 `model_client.websocket_connection`
  은 fixture 에 없다.

## 5. `codex.api_request` 의 두 형식과 operation

| 형식 | emitter | 위치(eventName) | 속성 |
|---|---|---|---|
| 모델 목록 | `model-provider/src/models_endpoint.rs` `ModelsRequestTelemetry::on_request` — `tracing::event!(target: "codex_otel.log_only", …)` | 0.153.4·0.154.0-alpha.6.2 `:204`, 0.155.0-alpha.2.6·0.155.0-alpha.9.2 `:230` | `endpoint = MODELS_ENDPOINT`(`"/models"` 상수), bool `success`, 세션 속성 **없음** |
| 세션 형식 | `otel/src/events/session_telemetry.rs` `record_api_request` — `log_and_trace_event!` | 0.153.4·0.154.0-alpha.6.2 `:665`, 0.155.0-alpha.2.6·0.155.0-alpha.9.2 `:688` | `endpoint` = 호출부가 넘긴 경로, `success` **없음**(metric 태그에만), 세션 속성 있음 |

- 같은 파일의 두 번째 `tracing::event!`(`:230` / `:256`)는 `codex_otel.trace_safe` 대상 — 로그가 아니라 스팬 이벤트로 나간다.
- 세션 형식의 `endpoint` 값은 `core/src/client.rs` 의 `RequestRouteTelemetry::for_endpoint(…)` 가 정한다. 0.153.4·0.154:
  `endpoint.path()`(`responses_endpoint(…)` 가 고른 경로 — `codex-api/src/endpoint/responses.rs` 의 `ResponsesEndpoint`:
  `/responses`·`/guardian`·`/guardian-classifier`), `/responses/compact`, `/memories/trace_summarize`.
  0.155.0-alpha.2.6: `endpoint.path()`, `/memories/trace_summarize`. 0.155.0-alpha.9.2: `"/responses"`, `/memories/trace_summarize`.
  `log_request` 경로는 `"unknown"` 을 넘긴다. **즉 세션 형식의 위치는 여러 요청 경로가 공유한다** — 위치를 `responses` 로
  대응시키면 compact·guardian·memories 요청도 `responses` 가 된다. operation 은 위치(세션 형식임을 확인)와 `endpoint` 값을 함께
  봐야 한다 — 프로파일은 세션 형식 위치에서 `endpoint = "/responses"` 인 요청만 `responses` 로, 나머지 경로는 `unknown` 으로 둔다.
- `codex.websocket_request` 는 경로를 싣지 않는다(`core/src/client.rs` 의 `on_ws_request` 가 `record_websocket_request` 에 경로를
  넘기지 않는다) — operation 은 `unknown`. `codex.websocket_connect` 는 연결 관측이라 operation 이 해당 없음이다.
- 이 네 버전에는 두 형식 모두 `endpoint` 속성이 **있다**(실캡처: `/models`, `/responses`).
- fixture: `real/logs-*-requests.otlp.jsonl`. 세션 형식은 0.155.0-alpha.2.6 에만 캡처됐다.

## 6. 사용량 행의 세션 속성과 `event.kind`

- `log_event!`(`otel/src/events/shared.rs`, 네 태그 동일)가 모든 세션 로그에 `event.timestamp`·`conversation.id`·
  `app.version`·`auth_mode`·`originator`·`user.account_id`·`user.email`·`terminal.type`·`model`·`slug` 를 붙인다.
  사용량 행도 이 매크로로 나가므로 **`conversation.id` 가 있다**(값이 `None` 인 선택 필드는 빠진다).
- SSE 로그를 내는 곳은 넷이고 모두 `event.kind` 만 쓴다 — `sse_event`(진행 이벤트), `sse_event_failed`(kind 있음/없음),
  `see_event_completed_failed`, `sse_event_completed`. `kind` 는 같은 함수들이 올리는 **메트릭 태그**의 키다(`codex.sse_event`
  메트릭). 이 범위에서 SSE 로그의 `kind` alias 는 **없다**.
- 오류 행: `core/src/client.rs` 는 응답 스트림의 첫 오류에서 `see_event_completed_failed` 를 부르고, 그 함수는
  `event.kind = "response.completed"` 를 **고정값으로** 적고 `error.message` 를 붙인다. 서버가 보낸 완료 이벤트가 아니다.
  사용량 행은 `ResponseEvent::Completed` 에 usage 가 있을 때만 나간다. 두 함수가 따로 나가므로 한 로그에 토큰과 오류가
  함께 실리는 경우는 이 범위의 producer 가 만들지 않는다.
- 진행 이벤트 행(`sse_event`, `duration_ms` 만)은 HTTP SSE 스트림에서만 나간다 — `codex-api/src/sse/responses.rs` 가
  poll 마다 `on_sse_poll` 을 부르고 `core/src/client.rs` 의 구현이 `log_sse_event` 로 잇는다. websocket 전송의
  `on_ws_event` 는 `record_websocket_event`(메트릭만)로 이어지고 로그를 내지 않는다. 진행 이벤트 행의
  `event.kind = response.completed` 에는 토큰이 없다.

## 7. wire 타입

`tracing` 필드의 `%` 는 `Display` 로 기록하고, OTel 로그 브리지는 그것을 `stringValue` 로 싣는다. `%` 없는 정수(`i64`·`u64`)는
`intValue`, `bool` 은 `boolValue` 다. `Option` 이 `None` 이면 필드가 빠진다.

| 속성 | 기록 | wire |
|---|---|---|
| `input_token_count`·`output_token_count`·`tool_token_count` | `%usage.…`(i64 Display) | stringValue, 부호 있는 10진 |
| `duration_ms` | `%duration.as_millis()`(u128 Display) | stringValue |
| `cached_token_count`·`cache_write_token_count`·`reasoning_token_count` | i64 | intValue |
| `ttft_ms` | `Option<i64>` | intValue 또는 없음 |
| `attempt` | u64 | intValue |
| 모델 목록 형식의 `success` | bool | boolValue |

## 태그별 위치

| 항목 | 0.153.4 | 0.154.0-alpha.6.2 | 0.155.0-alpha.2.6 | 0.155.0-alpha.9.2 |
|---|---|---|---|---|
| `session_telemetry.rs` 사용량 행(`sse_event_completed`) | :1012 | :1012 | :1035 | :1035 |
| 〃 오류 행(`see_event_completed_failed`) | :999 | :999 | :1022 | :1022 |
| 〃 진행 이벤트(`sse_event`) | :948 | :948 | :971 | :971 |
| 〃 `sse_event_failed`(kind 있음 / 없음) | :972 / :979 | :972 / :979 | :995 / :1002 | :995 / :1002 |
| 〃 `conversation_starts` | :570 | :570 | :593 | :593 |
| 〃 `record_api_request` | :665 | :665 | :688 | :688 |
| `models_endpoint.rs` 로그 / 스팬 이벤트 | :204 / :230 | :204 / :230 | :230 / :256 | :230 / :256 |
| `responses.rs` usage 변환(`From<ResponseCompletedUsage>`) | :138 | :138 | :138 | :138 |
| `protocol.rs` `non_cached_input` / `blended_total` | :2402 / :2407 | :2405 / :2410 | :2422 / :2427 | :2421 / :2426 |
| `client.rs` 사용량 행 / 오류 행 호출 | :2232 / :2289 | :2243 / :2300 | :2177 / :2234 | :2290 / :2347 |
| `client.rs` 스팬 `model_client.websocket_connection` 의 `provider` | :1468 | :1467 | :1375 | :1468 |
| `session.rs` `conversation_starts` 호출 | :1145 | :1155 | :1189 | :1289 |

실캡처의 `eventName` 은 위 위치와 정확히 일치한다(`codex-app-server` 네 버전, `Codex Desktop` 0.153.4·0.154.0-alpha.6.2).

## 관측 대조 — 증거 아님

실캡처 로그 사본(4,762 사용량 행, 네 버전)에서 본 관계다. 소스가 정한 의미와 **모순이 없다**는 확인일 뿐 포함관계의 증거가 아니다.

- `tool_token_count = input_token_count + output_token_count` 가 모든 행에서 성립 — 소스(API total)와 일치.
- `cache_write_token_count` 는 모든 행에서 0 — 위 2 의 이유로 측정 0 인지 미보고인지 알 수 없다.
- wire 타입은 7 의 표와 같다. `ttft_ms` 는 일부 행에 없다.

## Codex Desktop

`Codex Desktop` 은 배포 빌드의 소스가 공개돼 있지 않다. 캡처의 `eventName` 이 공개 태그의 위치와 행까지 같아 같은
`codex-rs` 를 포함한 것으로 보이지만, 그 빌드의 소스로 확인한 것이 아니므로 **unknown** 으로 둔다.

## 부록 C 반영

| service | 상태 | 이유 |
|---|---|---|
| `codex-app-server` 네 버전 | `evidence_only` | input 은 cache read 포함(producer 정의), reasoning ⊂ output, `tool_token_count` = total. cache write 는 unknown, 사용량 행의 provider 근거 없음 — 프로파일 확정과 fixture 기대값은 다음 단계 |
| `codex_cli_rs` 같은 태그 | `evidence_only` | 같은 소스. 실캡처가 없어 fixture 없음 |
| `Codex Desktop` 0.153.4·0.154.0-alpha.6.2 | `unknown` | 배포 빌드 소스 비공개 |

## 8. 스팬과 메트릭

소스는 메트릭이 캡처된 0.153.4·0.154.0-alpha.6.2 와, 스팬이 캡처된 0.154.0-alpha.6.2·0.155.0-alpha.9.2 를 읽었다.

- **작업 스팬**(`core/src/tasks/mod.rs`): 작업마다 `info_span!("turn", otel.name = span_name, thread.id, turn.id = sub_id,
  model = slug, codex.turn.reasoning_effort, codex.turn.token_usage.* = Empty)` 를 열고, 턴이 끝나면
  `Span::current().record("codex.turn.token_usage.…", …)` 로 **세션 누적 사용량의 턴 시작 대비 증분**(음수는 0)을 적는다 — 정수
  (`intValue`). 이름(`otel.name`)은 작업 종류의 `span_name()`: `session_task.turn`(regular)·`session_task.compact`(compact)·
  `session_task.review`·`session_task.user_shell`(`core/src/tasks/*.rs`). 이 스팬의 `thread.id` 는 대화 스레드 ID 문자열이고,
  OTel 브리지가 붙이는 OS 스레드 `thread.id`(정수)와 이름이 겹친다 — 세션으로 쓰지 않는다.
- **`try_run_sampling_request`**(`core/src/session/turn.rs`): `#[instrument(fields(turn_id = sub_id, model = slug))]` — 같은 턴 ID 의
  다른 키 표기다.
- **`mcp.tools.call`**(`core/src/mcp_tool_call.rs` 의 `mcp_tool_call_span`): `otel.kind = "client"`, `rpc.system`·`rpc.method`·
  `mcp.server.name`·`mcp.server.origin`·`mcp.transport`·`mcp.connector.id/name`·`tool.name`·`tool.call_id = call_id`·
  `conversation.id = thread_id`·`session.id = thread_id`·`turn.id`, 오류 시 `error.type`·`codex.mcp.error.code`. `call_id` 는
  `handle_mcp_tool_call(…, call_id, …)` 인자 — 모델이 준 도구 호출 ID 로, 도구 결과 로그의 `call_id` 와 같은 값의 공간이다.
- **일반 도구 스팬**(`handle_tool_call`·`dispatch_tool_call_with_terminal_outcome`·`handle_tool_call_with_source`): 캡처에서 속성이 없다
  (코드 위치 속성만). `code_mode.broker.invoke_tool` 은 `tool_name` 과 `runtime_tool_call_id`(code mode 실행기 안의 번호 — 모델의 호출
  ID 가 아니다)를 싣는다. 도구 이름 + native 호출 ID 조건을 채우는 일반 도구 스팬이 없어 `tool` 행을 만들지 않는다.
- **메트릭 이름**(`otel/src/metrics/names.rs`): `codex.tool.call`·`codex.tool.call.duration_ms`·`codex.turn.e2e_duration_ms`·
  `codex.turn.ttft.duration_ms`·`codex.turn.ttfm.duration_ms`·`codex.turn.tool.call`·`codex.turn.token_usage`·
  `codex.guardian.review.token_usage`·`codex.thread.started`. `codex.conversation.turn.count`·`codex.guardian_v2.classification.token_usage` 는
  이 파일 밖에서 정의된다(실캡처에 있다).
- **계측 타입**: `codex.turn.token_usage` 는 `session_telemetry.histogram(…, &[("token_type", …), tmp_mem])` — 턴 증분의 여섯 성분
  (`total`·`input`·`cached_input`·`cache_write_input`·`output`·`reasoning_output`)을 point 로 낸다(`core/src/tasks/mod.rs`).
  `codex.turn.tool.call` 은 histogram(턴당 도구 호출 수), `codex.turn.e2e_duration_ms` 는 timer, `codex.turn.ttfm.duration_ms` 는
  `record_duration`(`core/src/turn_timing.rs`). 실캡처의 wire 타입: sum 은 delta·monotonic, histogram 은 delta, 시간 계열 단위 `ms`.
  guardian token usage 의 `token_type` 에는 `non_cached_input` 도 있다.
- 프로파일은 이름과 **이 타입이 함께 맞을 때만** family 를 붙인다(`CodexMetrics`). 단위 registry 는 두지 않았다.
