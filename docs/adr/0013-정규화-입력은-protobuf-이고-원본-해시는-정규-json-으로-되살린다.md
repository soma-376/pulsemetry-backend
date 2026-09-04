# 0013. 정규화 입력은 protobuf 이고 원본 해시는 정규 JSON 으로 되살린다.

## Status

Accepted — 부분 대체: [ADR 0014](0014-단계-모듈-사이에-데이터-타입-간선을-둔다.md) 가 "단계 모듈끼리 `project()` 의존을 두지 않는다"를 **seam 인터페이스 구현에 한해** 유효한 것으로 좁힌다. 공개된 데이터 타입은 간선으로 받는다. 나머지 결정은 유효하다.

## Context

변환 단계 `:libs:telemetry-adapter` 가 OTLP 를 읽어 정규화 이벤트를 만든다. 그 입력을 무엇으로
받을지가 이 결정의 대상이다.

**수집 단계가 자리를 이미 파 두었다.** `:libs:telemetry-collector` 의 `SignalConsumer` 가
`consume(signal: Signal, request: Message)` 이고, 그 KDoc 이 *"변환 단계
(`:libs:telemetry-adapter`)가 이 자리에 붙는다"* 고 적었다. 수집 모듈은 OTLP/JSON 과 protobuf 를
모두 받아 **protobuf 메시지로 정규화한 뒤** 마스킹·아카이브를 하고 다음 단계로 넘긴다. 그러니
변환 단계의 자연스러운 입력은 protobuf 다.

### 그런데 봉투의 한 필드가 원본 JSON 텍스트에 매여 있다

`_ingest.source_record_id` 는 **원본 레코드 JSON 의 SHA-1** 이다. 이식 대상 구현이 파싱한 레코드를
`json.dumps(rec, sort_keys=True, ensure_ascii=False)` 로 다시 적어 해시했다. protobuf 에는 그
문자열이 남아 있지 않다.

이 값은 재현이 가능한지부터 확인해야 했다. 두 가지를 측정했다.

1. **해시 재료의 형태.** 정렬된 키, 공백이 있는 기본 구분자(`", "` · `": "`), 비 ASCII 원문 유지,
   Python `repr` 실수 표기. 이 표기로 다시 적으면 fixture 의 `raw-` 값이 그대로 재현된다.
2. **protobuf 왕복의 손실.** protobuf 는 **기본값과 같은 필드를 잃는다.** 클라이언트가
   `"droppedAttributesCount": 0` 이나 `"attributes": []` 처럼 기본값을 명시해 보내면 되살릴 수 없다.
   golden fixture 전 문서(실측 캡처 48 문서 포함)를 왕복시켜 그런 필드가 없음을 확인했고,
   테스트로 고정했다.

### 대안 — JSON 트리를 입력으로 받는다

원본을 1:1 로 옮기면 해시가 자명하게 정확해진다. 대신 수집 단계가 파 둔 seam 을 쓰지 못한다.
조립 앱이 OTLP 를 두 번 파싱하거나, 수집 단계가 원본 바이트를 함께 넘기도록 `SignalConsumer` 를
고쳐야 한다. 이미 선 모듈의 공개 계약을 되돌리는 비용이 크고, 두 번째 파싱은 이 파이프라인이
없애려던 홉을 모듈 안에 다시 만드는 일이다.

### 상위 리더가 받아 주던 두 가지를 이제 받지 못한다

이식 대상 리더는 `resourceSpans` 와 함께 **`resourceTraces`** 키도 받았고, 한 문서에 신호가 섞여
있어도 전부 읽었다. protobuf 요청에는 그런 모양이 없다 — `ExportTraceServiceRequest` 에는
`resource_spans` 뿐이고, 실제 OTLP 는 신호별 엔드포인트로 오므로 push 하나는 언제나 단일 신호다.

## Decision

- **입력은 protobuf 요청이다.** `Normalizer.normalize(request: MessageOrBuilder)` 하나가 진입점이고,
  신호 종류는 요청 타입으로 판별한다. 수집 단계의 `SignalConsumer` seam 을 그대로 쓴다.
- **`source_record_id` 는 protobuf 에서 정규 JSON 을 되살려 계산한다.** `ProtoJson` 이 OTLP/JSON
  모양의 트리를 만들고 `CanonicalJson` 이 이식 대상과 같은 표기로 적는다. 두 벌 다 이 모듈의
  `internal` 이다.
- **기본값을 명시한 문서는 `source_record_id` 가 갈린다는 것을 받아들인다.** optional·oneof 가
  아닌 스칼라 필드의 명시 기본값은 출력값과 `record_id` 까지 바꿀 수 있다 — 아래 Consequences 참고.
- **`resourceTraces` 키와 신호 혼합 문서는 지원하지 않는다.** 구조적으로 도달할 수 없으므로
  방어 코드를 두지 않는다. 공유 golden fixture 도 그 모양을 담지 않는다.
- **단계 모듈끼리 `project()` 의존을 두지 않는다.** 이 모듈은 `SignalConsumer` 를 구현하지 않고
  자체 진입점만 노출한다. 둘을 잇는 배선은 조립 앱이 한다
  ([ADR 0011](0011-라이브러리-모듈은-spring-조립을-앱에-위임한다.md)).
- **`model/` 의 봉투와 payload 타입은 모듈 경계를 넘는 공개 API 다.** 보강·적재 단계가 그대로
  받는다. `protobuf-java` 는 `api()` 로 노출한다 — 진입점 시그니처가 `MessageOrBuilder` 다.
  `opentelemetry-proto` 는 main 시그니처에 나타나지 않으므로 테스트 의존이다
  ([모듈 지도](../module-map.md) 4절).

## Alternatives

**JSON 트리를 입력으로 받는다.** 해시가 자명하게 정확하다. 채택하지 않은 이유는 위 Context 에 있다 —
이미 선 수집 모듈의 공개 계약을 되돌려야 하고, 조립 앱이 OTLP 를 두 번 파싱하게 된다.

**`source_record_id` 의 계산 방식을 바꾼다.** 예를 들어 protobuf 직렬화 바이트를 해시한다.
계산은 간단해지지만 **이식 대상과 값이 전부 달라진다.** 그러면 golden 대조가 그 필드에서 언제나
어긋나고, 동작 동일성을 기계적으로 검증한다는 이식 전체의 전제가 무너진다. 지금 이 방식은
fixture 전 문서에서 값이 일치하고, 어긋나는 경우가 언제인지도 테스트가 드러낸다.

**수집 단계가 원본 바이트를 함께 넘기도록 seam 을 넓힌다.** 손실이 원천적으로 없어진다. 다만
`SignalConsumer` 가 넓어지고, 마스킹 이후의 메시지와 마스킹 이전의 바이트가 함께 흐르게 된다 —
어느 쪽을 봐야 하는지가 호출부마다 갈리는 계약은 위험하다. 되살린 값이 어긋나는 경우가
실측 fixture 에서 0 건이므로 지금 그 복잡도를 사지 않는다.

## Consequences/Tradeoffs

### Positive

- 수집 단계가 파 둔 seam 을 그대로 쓴다. 조립 앱이 쓸 코드가 한 줄이다.
- OTLP 를 한 번만 파싱한다.
- `ProtoJson` 이 트리를 만들어 주므로 리더·어댑터는 이식 대상과 같은 모양의 맵을 다룬다.
  코드가 1:1 로 대응해 리뷰가 나란히 읽힌다.
- 되살린 트리가 원본과 같은지를 fixture 전 문서에 대해 테스트가 확인한다. 규격에서 벗어난 입력이
  fixture 에 들어오면 그 자리에서 드러난다.

### Negative

- **기본값을 명시해 보내는 클라이언트의 `source_record_id` 가 구 파이프라인과 갈라진다.**
  `"droppedAttributesCount": 0` 같은 필드는 protobuf 가 떨어뜨려 되살릴 수 없다.
  **그리고 일부 필드는 출력값과 `record_id` 까지 갈린다.** optional·oneof 가 아닌 스칼라 —
  `HistogramDataPoint.count` · `Sum.is_monotonic` · `aggregation_temporality` · `timeUnixNano` ·
  `startTimeUnixNano` — 는 protobuf 가 명시된 기본값과 부재를 구별하지 못하므로, 원본이 `"count":"0"`
  이라 적어도 이식본은 `null` 로 읽는다(원본 구현은 `0`). `count` 는 메트릭 판별자의 재료이고
  `timeUnixNano` 는 `timestamp` 의 재료라 그 경우 멱등 키가 달라진다. golden fixture 에는 그런 문서가
  없어 대조로는 드러나지 않는다. 현재 트래픽에 그런 클라이언트가 있는지는 아카이브된 원본으로
  확인할 수 있다 — Follow-up 참고.
- **`CanonicalJson` 한 벌이 다른 언어의 표기를 흉내 내는 코드로 남는다.** 실수 표기와 구분자 공백
  같은 것이 눈에 잘 띄지 않는데 어긋나면 전 이벤트의 해시가 바뀐다. 그래서 기대값을 손으로 적지
  않고 실제 Python 출력으로 고정해 두었다.
- **`resourceTraces` 로 보내는 익스포터가 있다면 그 데이터는 조용히 버려진다.** 규격 키가 아니므로
  현행 트래픽에 있을 가능성은 낮지만, 확인은 하지 않았다.

## Follow-up

- 조립 앱(PROJ-105)이 `SignalConsumer` 로 두 단계를 잇는다. 그때 이 결정이 실제로 한 줄로
  끝나는지 확인한다.
- 아카이브된 원본으로 **기본값을 명시해 보내는 클라이언트가 있는지** 확인한다. 있으면
  `source_record_id` 발산 범위와, 위 스칼라 필드로 `record_id` 가 갈리는 문서 수를 함께 잰다.
  후자가 0 이 아니면 `ProtoJson` 이 non-optional 스칼라를 항상 쓰는 대안을 그때 다시 본다 —
  그 대안은 반대로 기본값을 생략한 문서의 `source_record_id` 를 바꾼다.
- **완료** — 보강·적재 단계(PROJ-104)가 `model/` 을 그대로 받는다. **경계를 넘는 타입은 실제로
  `model/` 과 `NormalizedJson` 둘뿐이었다** — 해시·정규 JSON·리더·페어링은 전부 `internal` 로 남았다.
  그 간선을 어떻게 둘지는 [ADR 0014](0014-단계-모듈-사이에-데이터-타입-간선을-둔다.md)가 정한다.

## References

- `libs/telemetry-collector/.../OtlpIngest.kt` — `SignalConsumer` 와 그 KDoc
- `libs/telemetry-adapter/src/test/resources/otlp/README.md` — golden fixture 의 형식과 한계
- [ADR 0010](0010-파이프라인-단계를-모듈-경계로-나눈다.md) · [ADR 0011](0011-라이브러리-모듈은-spring-조립을-앱에-위임한다.md)
- [모듈 지도](../module-map.md) 1절 · 5절
