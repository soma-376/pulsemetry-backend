# golden fixture — 기대출력만 가져온 사본

`libs/telemetry-adapter/src/test/resources/otlp/` 의 `*.normalized.jsonl` 사본이다.
그쪽의 출처는 `soma-376/ai-telemetry-pipeline`
`apps/telemetry-processor/tests/fixtures/`(커밋 `722abb6`, PROJ-100)이고,
구 레포의 `scripts/regen-golden.py` 가 현행 Python normalizer 를 태워 구웠다.

**짝인 `*.otlp.jsonl` 입력은 가져오지 않았다.** 보강·적재 단계는 OTLP 를 파싱하지 않는다 —
이 모듈의 입력은 이미 정규화된 이벤트다.

| 파일 | 신호 | 덮는 것 |
|---|---|---|
| `claude_code/logs_real` | log | E2E 실측 캡처 144 이벤트. 실데이터는 이것뿐이다 |
| `claude_code/logs_synthetic` | log | 로그 9종 — payload 여섯 갈래를 전부 덮는다 |
| `claude_code/traces_synthetic` | span | 스팬 6종 |
| `claude_code/metrics_synthetic` | metric | 본문 4종(sum·gauge·histogram·exponentialHistogram) |
| `codex/logs_synthetic` | log | 로그 6종 |
| `codex/traces_synthetic` | span | 스팬 4종 |
| `codex/metrics_synthetic` | metric | 메트릭 통과 |

어댑터가 들고 있는 `codex/pairing_*` 둘은 가져오지 않았다. `call_id` 페어링은 변환 단계의
관심사이고 보강·적재는 그 결과를 값으로만 본다.

## 형식

- 한 줄이 `{"document_index", "event_index", "event"}` 다
- `event` 는 `NormalizedJson.toTree()` 와 같은 값이고, **ClickHouse `raw_json` 에 실제로
  들어가는 그 형태**다
- 대조는 **JSON 값 동등성**으로 한다. 키 순서에 기대지 마라

## 어떻게 쓰나

`NormalizedJsonReader` 가 `event` 트리를 `Normalized` 로 되돌린다. 어댑터에는 역직렬화기가
없어서 새로 쓴 것이고, **그 리더를 믿어도 되는 근거는 왕복 테스트 하나다** —
`NormalizedJson.toJson(read(line))` 이 원래 줄과 값 동등한지를 모든 줄에 대해 확인한다
(`NormalizedJsonReaderRoundTripTest`). 그 테스트가 깨지면 리더를 고친다.

**기대값을 손으로 고치지 마라.** 이 파일들은 이식의 오라클이다. 기대값이 바뀌어야 한다면
먼저 구 레포에서 다시 굽고, 그 변화가 의도된 것인지 따진 다음, 어댑터를 거쳐 여기로 가져온다.

## 왜 또 사본인가

CI 가 체크아웃하는 형제 레포는 `telemetryctl` 하나뿐이라 `ai-telemetry-pipeline` 경로를
빌드 시점에 기대할 수 없다 — 어댑터가 사본을 둔 것과 같은 이유다. 어댑터 쪽은 `src/test`
소스셋이라 다른 모듈에서 보이지 않으므로, 공유가 필요한 이쪽은 `testFixtures` 에 둔다
(ADR 0008 규칙 6). **어댑터의 원본이 갱신되면 여기도 같이 갱신한다.**

## ⚠️ 보증하는 것과 못 하는 것

**보증한다** — 이식본이 현행 Python 과 같은 값을 낸다(동작 동일성).

**보증하지 못한다** — 그 값이 벤더 실데이터에 대해 옳다는 것. codex 와 traces·metrics 는
실 캡처가 한 건도 없어 합성 입력이 to-spec 이다. 어댑터 쪽 README 가 현행 결함 넷의
목록을 담는다 — 이 사본도 그 결함을 그대로 담고 있다.
