# 0017. 정규화 불변 규칙과 enrichment_json 승격 금지는 이 저장소가 정한다.

## Status

Accepted

## Context

변환 단계(`:libs:telemetry-adapter`)와 적재 단계(`:libs:telemetry-persistence`)에는 코드가 지키는
규칙이 여럿 있는데, 그 근거가 이 저장소 안에 없었다.

- 변환 단계의 KDoc 은 "프롬프트 원문을 담지 않는다", "값이 없으면 `null` 이지 `0` 이 아니다" 같은
  규칙을 곳곳에 적어 두었지만, 그 규칙을 한자리에 모아 정한 문서가 없다.
  [모듈 지도](../module-map.md) 1절과 [ADR 0013](0013-정규화-입력은-protobuf-이고-원본-해시는-정규-json-으로-되살린다.md)은
  입력 형식과 경계만 다룬다.
- 적재 단계와 보강 단계의 KDoc 아홉 곳이 "provider 산출물은 `enrichment_json` 으로만 적재한다"의
  근거로 **파이프라인 ADR 0006** 을 인용했다. 그 문서는 이식 원본 저장소에만 있고, 이 저장소의
  ADR 0006(파이프라인 병합, Superseded)과 허브 ADR 0006(상태 코드 계약)이 같은 번호를 쓴다.
  독자가 세 문서 중 어느 것인지 밖에 나가 확인해야 했다.

규칙 자체는 이미 코드와 golden fixture 가 고정하고 있다. 없는 것은 **왜 그런지를 이 저장소 안에서
읽을 수 있는 자리**다. 이식 원본이 폐기되면 인용이 끊기고, 그전에도 규칙을 바꾸려는 사람이 어느
문서를 개정해야 하는지 알 수 없다.

## Decision

아래 규칙의 소유자는 이 ADR 이다. 바꾸려면 이 ADR 을 개정한다.

### 정규화 불변 규칙 — `:libs:telemetry-adapter`

1. **프롬프트·응답 원문을 다루지 않는다.** 봉투와 payload 에 원문 필드가 없다. claude_code 의
   `user_prompt` 는 `Prompt(length, commandName)` 으로만 남고, 승격되는 속성은 화이트리스트뿐이다
   (`ClaudeCodeLogs.promote`). 서버가 원문을 받아도 정규화 결과에는 실리지 않는다.
2. **billable 토큰은 `input + output + cache_read + cache_create` 다.** reasoning·tool 토큰은
   합산에 넣지 않는다 — 벤더가 이미 output 에 포함해 보고하므로 더하면 이중 계산이다
   (`Tokens.billable`, `Pricing`). 스팬 이벤트는 토큰과 비용을 비운다.
3. **값이 없으면 `null` 이다. `0` 으로 채우지 않는다.** 없는 속성·범위 밖 정수·NaN 은 전부 `null`
   이고(`OtlpAttributes.optInt`·`optDouble`), billable 이 0 이면 비용도 `null` 이다.
   `0` 은 "측정했더니 0" 이고 `null` 은 "측정하지 않았다" 다. 집계에서 둘을 구분해야 한다.
4. **이벤트 사이의 조인 키는 `call_id` 다.** 벤더가 주지 않으면 `session|tool|seq|ts` 재료로 합성하고
   `call_id_inferred = true` 로 표시한다(`CallId`). 벤더 고유 키(`request_id` 등)는 보존하되 조인에
   쓰지 않는다.
5. **신호 사이의 조인은 여기서 하지 않는다.** 로그·스팬·메트릭은 각자 이벤트가 되고, 스팬 셋을
   합치거나 `request_id` 로 로그와 스팬을 잇는 것은 다운스트림 집계(group-by)의 몫이다.
   변환 단계는 push 하나 안에서만 상태를 갖는다(`Normalizer` 의 페어링 버퍼).

### 적재 화이트리스트 — `:libs:telemetry-persistence` · `:libs:telemetry-enricher`

6. **`enriched_events` 행은 `EnrichedEventRow.COLUMNS` 의 아홉 컬럼뿐이다.** 컬럼의 진실원은
   `V*.sql`([ADR 0015](0015-clickhouse-ddl-은-번호-붙은-멱등-파일이고-기동-시-적용한다.md))이고
   화이트리스트는 그 순서를 따른다.
7. **provider 산출물은 `enrichment_json` 으로만 적재한다.** 공통 컬럼으로 승격되는 예외는
   `org` provider 의 `team_ids_as_of` 하나다. 새 provider 가 컬럼을 원하면 이 ADR 을 개정하고
   `V*.sql` 에 컬럼을 더한다 — provider 파일 하나로 스키마가 바뀌면 안 된다.
8. **발견된 모든 provider 가 항상 항목을 쓴다.** 아무것도 하지 않는 provider(github·jira·ai_analysis)
   도 빈 항목을 남긴다. `enrichment_json` 의 키 집합이 곧 "어떤 provider 가 돌았는가" 이고,
   저장된 값의 바이트가 그 집합에 좌우된다.

## Alternatives

**규칙을 KDoc 에만 둔다(현행).** 코드 옆에 있어 읽기는 쉽지만, 규칙이 여덟 파일에 흩어져 어느 것이
규칙이고 어느 것이 구현 설명인지 갈리지 않는다. 바꿀 때 개정할 문서가 없다.

**ADR 0015 에 흡수한다.** 적재 화이트리스트(6–8)는 스키마 소유 결정이라 자리가 맞지만, 정규화 규칙
(1–5)은 변환 단계의 것이라 스키마 ADR 에 넣으면 독자가 찾지 못한다. 둘을 한 ADR 로 묶는 것은
**한쪽을 바꾸면 다른 쪽이 따라 바뀌기 때문**이다 — 승격 예외(7)를 늘리면 정규화가 그 값을 만들어야
하고, 정규화 규칙(3)을 바꾸면 저장되는 값이 바뀐다.

**이식 원본의 ADR 을 그대로 복사한다.** 그 문서는 Python 구현과 당시 운영 경로를 전제로 쓰였다.
이 저장소에서는 그 전제가 없으므로 근거를 다시 써야 하고, 그러면 복사가 아니라 이 ADR 이다.

## Consequences/Tradeoffs

### Positive

- 규칙의 근거를 대는 KDoc 이 전부 이 저장소 안의 한 문서를 가리킨다. 이식 원본이 폐기돼도 끊기지 않는다.
- "ADR 0006" 이 세 문서를 뜻하던 혼동이 사라진다.
- 규칙을 바꾸는 절차가 생긴다 — 이 ADR 개정 + golden 재생성 + `V*.sql` 추가.

### Negative

- **규칙을 코드와 문서 두 곳에서 지켜야 한다.** KDoc 이 규칙을 다시 적으면 어긋날 수 있다.
  완화책은 KDoc 이 규칙 문장을 반복하지 않고 이 ADR 의 번호만 가리키는 것이다.
- 규칙 3 의 "범위 밖 정수·NaN → `null`" 은 이식 원본보다 넓다. 원본(Python)은 bigint 라 범위가 없고
  NaN 은 예외였다. golden 에 그런 값이 없어 대조로는 드러나지 않는다.
- 규칙 8 때문에 아무것도 하지 않는 클래스 셋이 일부러 남아 있다. 지우면 저장되는 값이 바뀐다.

## Follow-up

- 골든 fixture 를 다시 구울 때(`libs/telemetry-adapter/src/test/resources/otlp/README.md`) 규칙
  1–5 에 걸리는 변화가 있으면 이 ADR 을 먼저 개정한다.
- 대시보드가 `enrichment_json` 안의 값을 컬럼으로 필요로 하는 첫 사례가 규칙 7 의 재검토 시점이다.
