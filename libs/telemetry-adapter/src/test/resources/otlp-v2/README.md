# otlp-v2 fixture — 정규화 계약 2판의 입력과 기대값

정규화 계약 2판([ADR 0020](../../../../../../docs/adr/0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md))의
수용 사례다. 입력 OTLP 문서와, **명세에서 쓴** 기대값의 쌍이다.

## 구 `otlp/` 와의 관계

같은 디렉터리의 `../otlp/**/*.normalized.jsonl` 은 구 Python normalizer 가 구운 **이식 동일성 오라클**이다.
그 fixture 는 구 모델이 사는 동안 그대로 두고, 구 모델을 제거할 때 함께 지운다(ADR 0020 §9). 이 디렉터리는 그것과
무관하다 — 구 모델과 같은 값을 내는지가 아니라 **계약대로 정규화하는지**를 본다.

## 디렉터리

| 경로 | 무엇 |
|---|---|
| `common/synthetic/` | 벤더와 무관한 명세 사례(합성). 제품 프로파일 없이 돈다 |
| `<product>/synthetic/` | 제품 프로파일의 명세 사례(합성) |
| `<product>/real/` | 실캡처를 익명화한 추출본. 그 디렉터리의 README 가 producer·버전·캡처 기간·선택 기준·치환 규칙을 적는다 |

합성 입력은 명세의 한 규칙을 드러내려고 손으로 쓴 것이고 실제 제품의 wire 형식을 주장하지 않는다.

`<product>/real/` 은 기대값 없이 입력만 둘 수 있다. 그때는 모든 문서가 읽히고 레코드가 빠짐없이 옮겨지며 아래
공통 불변식을 지키는지만 본다(`FixtureSuite.inputs`·`checkInvariants`). 추출·익명화 도구는 `scripts/otlp-fixtures/` 다.

## 파일 쌍

- `<case>.otlp.jsonl` — 한 줄이 OTLP/JSON export 문서(push) 하나. 최상위 키(`resourceLogs`·`resourceSpans`·
  `resourceMetrics`)로 신호를 정한다. 64비트 정수는 문자열, trace/span ID 는 hex, 비유한 double 은 `"NaN"` 등.
  **모르는 필드가 있으면 실패한다**(오타 방지).
- `<case>.expected.jsonl` — 입력과 같은 줄 수. 줄 i 가 문서 i 의 기대값이다.

```json
{"spec": "ADR 0020 §2", "note": "...", "context": {"archive": false},
 "events": [{"event_type": "diagnostic", "...": "..."}], "metric_points": [], "stats": {"received": 1}}
```

- `spec` **필수** — 이 기대값의 근거 절. 구현을 돌린 출력을 붙여 넣지 않는다. 기대값은 명세에서 독립적으로 쓴다.
- `events`·`metric_points` — 적으면 **개수와 순서까지** 단언한다. 적지 않으면 비어 있어야 한다. 각 관측은
  **단언할 필드만** 적는 부분 일치다. 필드 이름은 분석 테이블의 컬럼 이름이고, `metadata` 는 `metadata_json` 을
  트리로 읽은 가상 필드다(이 둘 아래의 맵·배열은 정확히 같아야 한다).
- 값 표기: 시각(`DateTime64`)은 epoch 나노초 **문자열**, 정수·UInt64·Decimal 은 JSON 숫자, 분류는 wire 문자열.
- `stats` — `received`·`rejected_count`·`excluded_spans`·`source_time_rejections`·`excluded_span_names`·
  `source_time_min/max` 중 단언할 것.
- `context` — 기본은 검증 신원 `tenant-fixture`/`installation-fixture`, 영수증 있음. `{"archive": false}` 면 영수증 없음.

### matcher

한 키짜리 객체로 쓴다.

| matcher | 뜻 |
|---|---|
| `{"$hex64": true}` | 64자리 소문자 hex |
| `{"$notNull": true}` | null 이 아님 |
| `{"$same": "A"}` | 사례 안에서 같은 라벨의 값이 모두 같다(문서를 가로지른다) |
| `{"$distinct": "B"}` | 같은 라벨의 값이 서로 모두 다르다 |
| `{"$contains": [...]}` · `{"$excludes": [...]}` | 문자열이면 부분 문자열, 배열이면 원소 |
| `{"$all": [...]}` | 나열한 기대를 모두 만족 |

## 모든 관측에 자동으로 검사하는 것

- `observation_id`·`analysis_hash`(봉인됨, 재계산 값과 같음)·`series_id` 가 64자리 hex.
- 결정성 — 같은 입력의 두 번째 실행과 protobuf 바이트 왕복 입력의 결과가 같다(JSON/protobuf 동일성).
- 받은 레코드 수 = 행 수 + 거부·제외 수.
- 신원·`received_time`·`identity_version` 이 문맥 값이다.
- `metadata_json` 에 본문류 금지 키가 없다.
- 입력에서 `SENSITIVE:` 로 시작하는 문자열로 표시한 원문이 관측의 어떤 필드에도 남지 않는다.

## 알려진 한계

- `source_time` 거부 사유 중 `unparseable` 은 OTLP/JSON 문자열에서만 생기는데, 정규화의 입력은 수집 단계가 이미
  protobuf 로 읽은 요청이라 여기 도달하지 않는다(그런 요청은 수집 단계가 400 으로 거절한다). 그 사유는
  `SourceTimeTest` 가 문자열 판독 함수로 직접 본다.
