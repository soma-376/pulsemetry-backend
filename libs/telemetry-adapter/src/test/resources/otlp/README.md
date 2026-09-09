# golden fixture — 입력과 기대출력 한 쌍

PROJ-100 이 확보한 golden 을 **쌍으로** 가져왔다. 입력 `.otlp.jsonl` 은 수집 모듈
(`libs/telemetry-collector/src/test/resources/otlp/`)의 것과 같은 파일이다.

| 파일 쌍 | 신호 | 덮는 것 |
|---|---|---|
| `claude_code/logs_real.*` | log | E2E 실측 캡처 48문서 → 144 이벤트. `user_prompt` 한 종류뿐 |
| `claude_code/logs_synthetic.*` | log | 로그 9종 + 미지원·namespace 밖 negative |
| `claude_code/traces_synthetic.*` | span | 스팬 6종 + span_id 타이브레이크 |
| `claude_code/metrics_synthetic.*` | metric | 본문 4종(sum·gauge·histogram·exponentialHistogram) |
| `codex/logs_synthetic.*` | log | 로그 6종 + `sse_event` 3분기 + `event.name` 매칭 |
| `codex/traces_synthetic.*` | span | 스팬 4종 |
| `codex/metrics_synthetic.*` | metric | 메트릭 통과 |
| `codex/pairing_synthetic.*` | log | `call_id` 페어링 시나리오 7종 |
| `codex/pairing_spans_synthetic.*` | span | 스팬은 페어링에 참여하지 않는다 |

출처: `soma-376/ai-telemetry-pipeline`
`apps/telemetry-processor/tests/fixtures/` (커밋 `722abb6`, PROJ-100).

## 형식

- 입력 `<name>.otlp.jsonl` — 한 줄이 OTLP 문서 하나
- 기대 `<name>.normalized.jsonl` — 한 줄이 `{"document_index", "event_index", "event"}`
- `event` 는 `NormalizedJson.toTree()` 와 같은 값이다 — ClickHouse `raw_json` 에 실제로 들어가는 그 형태
- 대조는 **JSON 값 동등성**으로 한다. 키 순서에 기대지 마라

**기대값을 손으로 고치지 마라.** 구 레포의 `scripts/regen-golden.py` 가 현행 Python normalizer 를
태워 구운 것이고, 그것이 이 이식의 오라클이다. 기대값이 바뀌어야 한다면 먼저 그쪽에서
다시 굽고 그 변화가 의도된 것인지 따진 다음 여기로 가져온다.

## 문서 하나는 신호 하나다

이 모듈의 입력은 protobuf 요청이라 `resourceTraces` 키와 한 문서에 신호가 섞인 경우를
구조적으로 표현할 수 없다. 이 쌍에는 그 두 모양이 없다. 발산은 ADR 0013 이 기록한다.

## ⚠️ 이 fixture 가 보증하는 것과 못 하는 것

**보증한다** — 이식본이 현행 Python 과 같은 값을 낸다(동작 동일성).

**보증하지 못한다** — 그 값이 벤더 실데이터에 대해 옳다는 것. 구 레포의
`data/codex/*.jsonl` 과 `data/claude_code/{traces,metrics}.jsonl` 은 전부 0바이트다.
codex 와 traces·metrics 는 실 캡처가 한 건도 없어, 합성 입력의 속성 키는 구 레포의
`docs/normalizer.md` 와 어댑터 코드를 근거로 만든 to-spec 이다.

## 현행 결함을 고정한 곳

이식은 동작 동일성이 판정 기준이라 **결함도 그대로 옮기고 테스트로 고정한다.**
고치는 것은 별도 티켓이고, 고치는 순간 기대값과 그 이벤트의 `record_id` 가 함께 바뀐다.

- `claude_code/logs_real` — 캡처의 `payload.prompt.length` 를 어댑터가 읽지 못해 전부 `null`
- `claude_code/traces_synthetic` — 스팬의 파일 경로만 구분자를 통일하지 않는다
- `codex/traces_synthetic` — `approval_policy`·`sandbox_policy` 가 절대 승격되지 않는다
- `codex/pairing_synthetic` — 스팬은 합성 키를 달고도 페어링에 참여하지 않는다

## 왜 사본인가

CI 가 체크아웃하는 형제 레포는 `telemetryctl` 하나뿐이라 `ai-telemetry-pipeline` 경로를
빌드 시점에 기대할 수 없다. 원본이 갱신되면 여기도 같이 갱신한다 — 출처와 커밋을 위에 적어 둔
이유다.
