# 0069. 플랫폼 처리 경로를 OpenTelemetry trace로 추적하고 Kafka record에 traceparent만 싣는다

## Status

Proposed — PROJ-256. 채택되면 [ADR 0068](0068-수집-운영-계측은-전송-계약과-분리하고-앱에서-조립한다.md)의
"분산 추적·새 요청 문맥을 추가하지 않는다"를 대체한다. 0068의 계측 조립·label 금지·평문 로그 형식 보존은 그대로 유효하다.
[ADR 0066](0066-OTLP-수신은-Gateway가-JWT만-검증해-Kafka에-접수하고-ingest가-RDB로-다시-검증한다.md) Decision 4
(Bearer·신원을 envelope·key·header·로그에 싣지 않는다)도 그대로다. header에는 플랫폼 trace 문맥 하나만 더한다.

## Context

Gateway 200은 Kafka 접수이고, ingest의 처리 결과는 별도 프로세스에서 나온다(ADR 0066). 지표는 집계값이고(ADR 0068)
장애 로그는 실패 중심이다. 그래서 요청 하나가 Gateway → Kafka → ingest → ClickHouse를 어떻게 지났는지 이어서 볼 수 없다.

infra는 PROJ-256에서 dev EKS에 Loki·Tempo·플랫폼 trace 전용 Collector를 둔다(infra ADR 0039).
backend가 trace를 만들어 보내야 Grafana에서 로그와 trace를 오갈 수 있다.

endpoint·서비스 이름·sampling·속성 허용목록은 backend와 infra가 함께 지키는 계약이다. 허브 계약 반영은
이번 작업에서 제외했다(2026-10-24 결정). 그때까지 이 ADR과 infra ADR 0039에 같은 값을 적어 둔다.

현재 코드에서 확인한 사실:

- Gateway producer는 `KafkaTemplate`이 아닌 bare `KafkaProducer`다(`GatewayConfig`). header를 붙이지 않는다.
- ingest consumer는 직접 조립한 record listener 컨테이너다(`KafkaConsumerConfig`). Kafka observation을 켜지 않았다.
- ClickHouse 호출은 JDK `HttpClient`이고 SQL·파라미터가 URL query에 들어간다(`ClickHouseHttpClient`).
- 두 앱에 tracing SDK·MDC 사용이 없다. 로그는 Boot 기본 평문 패턴이다.

## Decision

### 범위

- Gateway와 ingest만 계측한다. enrollment·dashboard는 제외한다.
- span 네 종류를 만든다.
  - 업무 포트의 HTTP 서버 요청. health와 actuator 요청은 span을 만들지 않는다. 그 요청의 `http.server.requests` 지표는 그대로 남긴다.
  - Gateway의 Kafka 발행(producer).
  - ingest의 record 처리 시도(consumer). 재시도마다 새 span이다.
  - ClickHouse 요청(client).
- 고객이 보낸 OTLP payload와 그 안의 trace ID는 읽지도 복제하지도 않는다. 플랫폼 자체 span만 만든다.

### SDK와 조립

- Micrometer Tracing + OpenTelemetry bridge를 Boot BOM 버전으로 앱에만 둔다.
  - 의존성: `spring-boot-micrometer-tracing-opentelemetry`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`.
  - `spring-boot-starter-opentelemetry`는 쓰지 않는다. OTLP metrics registry까지 끌어와 기존 Prometheus 지표 경로와 겹친다.
  - Java agent는 붙이지 않는다.
- 수동 span(Kafka 발행·처리 시도·ClickHouse 요청)은 Micrometer Tracing의 `Tracer`·`Propagator`로 만든다. 라이브러리는 이 API만 의존하고
  기본은 `Tracer.NOOP`이다(ADR 0011). Observation API는 같은 이름의 Timer를 함께 만들어 기존 `pulsemetry_*` 지표와 겹치고
  `PULSEMETRY_OBSERVABILITY_ENABLED`로 끄지 못하므로 수동 span에 쓰지 않는다. HTTP 서버 span은 Boot의 서버 observation을 그대로 쓴다.

### 설정 계약

| 환경변수 | Spring 설정 | 기본 |
|---|---|---|
| `PULSEMETRY_TRACING_ENABLED` | `management.tracing.export.enabled` | `false` |
| `PULSEMETRY_TRACING_OTLP_TRACES_ENDPOINT` | `management.opentelemetry.tracing.export.otlp.endpoint` (OTLP/HTTP, `/v1/traces`까지 쓴 전체 URL) | 없음 |
| `PULSEMETRY_TRACING_SAMPLE_PROBABILITY` | `management.tracing.sampling.probability` (parent-based) | `0.1` |
| `PULSEMETRY_TRACING_CLUSTER` | resource `k8s.cluster.name` | 없음 |
| `POD_NAMESPACE`, `POD_NAME` | resource `k8s.namespace.name`, `k8s.pod.name` (Downward API) | 없음 |

- 꺼져 있으면 exporter·전송 작업이 없다. 켜져 있는데 endpoint가 비어 있으면 기동하지 않는다.
  export만 끄면 Boot가 기본 sampler로 span과 로그 MDC를 계속 만든다. 그래서 꺼지면 앱이 `Tracer`·`Propagator`를 NOOP으로 먼저 둔다.
  꺼진 상태의 로그 형식·Kafka header는 지금과 같다.
- `service.name`은 `telemetry-gateway`·`telemetry-ingest`로 고정한다. `spring.application.name`은 바꾸지 않는다.
- `deployment.environment.name`은 `PULSEMETRY_OBSERVABILITY_ENVIRONMENT`를 쓴다.
- `OTEL_*` 환경변수 매핑은 끈다. 설정 출처를 위 표 하나로 둔다.
- 전송은 SDK의 비동기 batch다. queue 2048, export timeout 10초로 둔다. 넘치거나 Collector가 죽으면 span을 버린다.
  업무 응답·Kafka ack·commit·ClickHouse 쓰기는 export 결과를 기다리지 않는다.
- OTLP log·metric export와 Prometheus exemplar는 켜지 않는다.

### 전파

- W3C `traceparent`만 쓴다. `tracestate`·baggage·B3는 받지도 보내지도 않는다.
- 공개 ingress의 trace header를 신뢰하지 않는다. 서버 observation 앞에서 `traceparent`·`tracestate`·`baggage`·B3 header를 지운다.
  따라서 Gateway 요청은 항상 새 플랫폼 root다.
- Gateway는 producer span 문맥의 `traceparent` 하나를 Kafka record header로 싣는다.
  envelope·key·payload·응답 의미·ack 기한은 바꾸지 않는다.
- ingest는 header가 하나뿐이고 형식이 맞을 때만 부모로 쓴다. 없음·중복·형식 오류·과대 길이는 새 root로 처리한다.
  header 유무와 상관없이 처리 결과는 같다.
- 재시도 소진(`telemetry_retry_exhausted`)과 commit 실패 로그는 listener scope 밖에서 나온다.
  record header로 문맥을 잠깐 다시 열어 로그를 남기고 닫는다. 다음 record에 MDC·문맥이 남지 않는다.

### span 속성

- 이름과 속성은 고정 목록이다.
  - 처리: `pulsemetry.signal`, `pulsemetry.stage`, `pulsemetry.outcome`(`completed`·`permanent_rejected`·`transient_failed`·`retry_exhausted`·`commit_failed`), `pulsemetry.message_id`.
  - Kafka: `messaging.system`, `messaging.destination.name`, partition, offset, 시도 횟수.
  - ClickHouse: `db.system.name`, `pulsemetry.operation`, `pulsemetry.table`, `pulsemetry.outcome`.
- 다음은 싣지 않는다: Bearer·자격 증명, payload, tenant·installation·member ID, URL query, SQL과 파라미터, 원본 예외 메시지.
- 실패한 span은 status ERROR로 표시하고 설명은 비운다. Tempo의 `status = error` 검색이 실패를 찾는 근거다.
  - 분류는 `error.type` 속성 하나다. 값은 예외 클래스 이름·reason·outcome 코드뿐이다.
  - 원본 예외를 span에 넘기지 않는다. bridge의 `span.error(예외)`는 메시지를 status 설명에, 메시지·stack을 exception 이벤트에 싣는다.
    메시지·stack이 없는 표식(`PlatformTracing.SPAN_FAILURE`)만 넘긴다.
- 내보내기 직전에 모든 span의 exception 이벤트에서 `exception.message`·`exception.stacktrace`를, status에서 설명을 지운다.
  Boot 서버 observation은 미처리 예외를 원본 그대로 `span.error`에 넘기고, Collector의 attributes 처리기는 이벤트 속성을 보지 않는다.
  두 앱의 `TracingConfig`가 Boot의 `SpanExporters`를 대신해 exporter를 감싼다(`ScrubbedSpanExporter`).

### 로그

- 평문 형식과 `event=<code>` 코드를 유지한다.
- 활성 span 안의 로그에만 `trace_id=<32 hex> span_id=<16 hex>`를 붙인다(Boot log correlation 패턴, Micrometer MDC `traceId`·`spanId`).
  span이 없으면 필드를 찍지 않는다.
- sampled 요청은 Gateway와 ingest에서 완료 로그를 한 줄씩 남긴다:
  `event=platform_trace_completed stage=gateway|ingest outcome=<값>`.
  payload·신원은 넣지 않는다. CloudWatch 실패 event 다섯 종의 term과 겹치지 않는다.
- ingest가 손상된 `traceparent`(중복·길이·형식) 때문에 새 root로 시작하면 그 처리 span 안에서 한 줄을 남긴다:
  `event=platform_trace_context_replaced topic partition offset attempt reason headerCount receivedBytes newTraceparent`.
  - sampling과 무관하게 시도마다 남긴다. header가 없으면 남기지 않는다(trace를 끈 Gateway·header 확장 전 record에서 정상이다).
  - 수신 값 원문은 남기지 않는다. 임의 바이트가 올 수 있고 문자 정제만으로 민감 내용이 사라지지 않는다.
  - `newTraceparent`는 실제 span을 W3C로 직렬화한 값이다. record header는 바꾸지 않는다.

## Alternatives Considered

- **OpenTelemetry Java agent**: 자동 계측 범위와 속성을 통제하기 어렵다. URL·SQL이 속성으로 남을 수 있어 기각한다.
- **spring-kafka observation**: Gateway에는 spring-kafka가 없다. ingest 컨테이너 observation은 수동 outcome·재시도 span과
  겹쳐 span이 두 벌 생긴다. 기각한다.
- **공개 요청의 traceparent를 부모로 쓰기**: 외부 입력이 플랫폼 trace를 고르게 된다. 고객 trace와 섞일 수 있어 기각한다.
- **Kafka envelope에 trace 문맥을 넣기**: envelope v1 계약을 바꿔야 한다. header로 충분하므로 기각한다.
- **모든 앱 계측**: 이번 목표인 수집 경로를 넘는다. 보류한다.

## Consequences/Tradeoffs

### Positive

- 한 요청의 Gateway 접수·Kafka 발행·ingest 시도·ClickHouse 쓰기를 같은 trace로 본다.
- Loki 로그의 `trace_id`로 Tempo trace로 이동하고, 그 반대로도 돌아온다.
- 꺼 두면(기본) 동작과 비용이 지금과 같다.

### Negative

- 앱 의존성과 span 생성·전송의 CPU·메모리 비용이 는다.
- Kafka record에 header 하나가 붙는다. header를 단언하던 시험과 문서를 함께 고친다.
- sampling 10%라 대부분 요청에는 trace가 없다. Collector 장애·queue 초과 때 span이 사라진다. trace 부재를 처리 실패로 해석하지 않는다.
- `platform_trace_completed` 로그는 sampled 요청 수만큼 는다. `platform_trace_context_replaced`는 손상된 header가 있는 처리 시도마다 한 줄이다.
- 허브 계약 반영 전까지 계약이 두 ADR에 나뉘어 있다.

## Follow-up

- 허브 정리 뒤 endpoint·서비스 이름·속성 계약을 허브 계약으로 옮긴다.
- 실제 dev trace로 Tempo의 span 관계와 sampling 비율을 확인한다. 장애 격리 시험 결과로 queue·timeout을 다시 정한다.
- enrollment·dashboard 계측과 tail sampling은 별도 결정으로 다룬다.

## References

- [Spring Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [Micrometer Tracing](https://docs.micrometer.io/tracing/reference/)
