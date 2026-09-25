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
