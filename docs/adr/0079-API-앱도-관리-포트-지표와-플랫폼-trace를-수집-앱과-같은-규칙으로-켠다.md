# 0079. API 앱도 관리 포트 지표와 플랫폼 trace를 수집 앱과 같은 규칙으로 켠다

## Status

Proposed — PROJ-324. 채택되면 다음 두 문장의 범위를 enrollment-api·dashboard-api까지 넓힌다.

- [ADR 0068](0068-수집-운영-계측은-전송-계약과-분리하고-앱에서-조립한다.md): "Gateway와 ingest만 Actuator/Prometheus를 의존한다"
- [ADR 0069](0069-플랫폼-처리-경로를-OpenTelemetry-trace로-추적하고-Kafka-record에-traceparent만-싣는다.md): "Gateway와 ingest만 계측한다. enrollment·dashboard는 제외한다"

두 ADR의 나머지 결정은 두 API 앱에도 그대로 적용한다. 0068은 관리 포트 분리, label 금지, 평문 로그다. 0069는 SDK와 조립, 설정 계약, 전파, span 속성, 예외 원문 삭제다.
infra 쪽 sender 확장은 infra ADR 0039의 개정이 같이 다룬다.

## Context

0068과 0069는 수집 경로(Gateway → Kafka → ingest → ClickHouse)만 다뤘다. 0069 Follow-up은
"enrollment·dashboard 계측은 별도 결정으로 다룬다"고 남겼다.

현재 코드와 dev에서 확인한 사실:

- 두 앱의 로그는 이미 infra Fluent Bit이 CloudWatch와 Loki로 보낸다. 지표와 trace는 없다.
  actuator·Micrometer·tracing 의존성과 관리 포트가 없다.
- 2026-11-06 dev에서 개요 1년 조회는 5~7초 걸렸다. 서버 내부 구간은 ClickHouse `query_log`와 브라우저 시각을 맞대어서만
  나눌 수 있었다. 요청률·지연 분포·커넥션 풀 상태를 볼 지표도 없다.
- 두 앱의 경로에는 조직 ID가 들어간다(`/api/v1/organizations/{organizationId}/...`). Boot 서버 observation은
  요청 경로를 high-cardinality 속성 `http.url`에 싣는다. Gateway·ingest의 경로에는 ID가 없어서 0069에서는 문제가 아니었다.
- dashboard-api는 ingest와 다른 자체 ClickHouse 클라이언트(`ClickHouseConnection`)를 쓴다.
  URL query에 `param_*` 값이 들어가고, 오류 상세에 응답 본문이 들어간다.
- 두 앱에는 Spring Security 체인이 있다. dashboard-api는 기본 닫힘(`defaultDenyFilterChain`)이고 enrollment-api는 `permitAll`이다.
- dashboard-api 로그는 이미 correlation 자리에 `[requestId]`를 찍는다(`RequestIdFilter`).
- infra 차트와 플랫폼 Collector 수신 정책은 sender를 Gateway·ingest로 고정한다(infra ADR 0039). 이 결정만으로는 켜지지 않는다.

## Decision

### 범위

- enrollment-api와 dashboard-api에 0068의 관리 포트 지표와 0069의 플랫폼 trace를 켠다.
  retention-worker는 서버가 아니라서 제외한다.
- span은 다음만 만든다.
  - 업무 포트의 HTTP 서버 요청. health(`/api/v1/healthz`)와 actuator 요청은 span을 만들지 않고 지표만 남긴다(0069와 같다).
  - dashboard-api의 ClickHouse 요청(client).
  - dashboard-api의 `@Scheduled` 작업(알림 평가·snapshot 정리)은 Spring이 만드는 observation root span을 그대로 둔다.
- JDBC·RDS 호출과 snapshot 단계별 수동 span은 만들지 않는다.

### 지표

- 관리 서버(`MANAGEMENT_SERVER_PORT`, 기본 9404)에서 GET `/actuator/prometheus`만 연다. 업무 포트의 actuator는 닫혀 있다.
- 관리 포트 요청만 받는 Security 체인을 `@Order(0)`으로 둔다. GET scrape 밖은 `404 text/plain`(`404 not found`)이다.
  - Boot는 관리 컨텍스트에 Security 체인만 넘긴다. 서블릿 필터로 관리 포트를 막지 않는다(0068과 같다).
  - 업무 포트의 기존 체인과 404 계약은 바꾸지 않는다.
- 업무 observer(`TelemetryObserver`, `pulsemetry_*`)는 수집 전용이라 쓰지 않는다.
  - Boot 기본 binder만 노출한다: JVM, process, Tomcat, Hikari·JDBC, `http.server.requests`, Spring Data repository.
  - `PULSEMETRY_OBSERVABILITY_ENABLED`는 두 앱에 없다.
- `http.server.requests`는 고정 SLO 버킷 8개(50ms·100ms·250ms·500ms·1s·2.5s·5s·10s)로 히스토그램을 낸다.
  route별 p95와 수 초 단위 조회를 함께 덮으면서 series 수를 고정한다.
- common tag는 `application=pulsemetry-<앱>`, `service=enrollment|dashboard`, `environment`다.
  enrollment-api의 `spring.application.name`(`pulsemetry`)은 바꾸지 않는다.
- 호스트에서 여러 앱을 함께 띄우는 local 프로필은 관리 포트를 앱별로 나눈다: enrollment 9405, dashboard 9406.

### trace

- 0069의 설정 계약 표, 전파, span 속성 금지 목록, 예외 원문 삭제(`ScrubbedSpanExporter`)를 그대로 쓴다.
- `service.name`은 `enrollment-api`, `dashboard-api`로 고정한다. Kubernetes workload 이름과 같다.
- 서버 span에서 `http.url`을 뺀다. span 이름과 `uri` 속성은 경로 템플릿이라 남긴다.
  Collector도 `http.url`을 지우지만, 앱 밖으로 나가기 전에 앱이 먼저 막는다.
- 공개 요청의 trace header는 지금처럼 지운다. 프론트 BFF의 trace와는 잇지 않는다.
- dashboard-api ClickHouse span:
  - 이름은 `clickhouse <operation> <table>`이다.
  - 속성은 0069의 ClickHouse 속성 넷뿐이다(`db.system.name`, `pulsemetry.operation`, `pulsemetry.table`, `pulsemetry.outcome`).
  - operation과 table은 호출부가 넘기는 고정 값이다. 앱의 허용 목록 밖은 `other`다.
  - URL, SQL, `param_*` 값, 응답 본문은 싣지 않는다.
  - 실패는 `error.type`(예외 단순 이름)과 `PlatformTracing.SPAN_FAILURE`로 표시한다.

### 로그

- 평문 형식을 유지한다. 활성 span 안의 로그에만 `trace_id=<32 hex> span_id=<16 hex>`를 붙인다.
  dashboard-api는 `[requestId]` 뒤에 붙인다.
- sampled 요청은 서버 span 안에서 완료 로그를 한 줄 남긴다:
  `event=platform_trace_completed stage=enrollment|dashboard method=<메서드> route=<경로 템플릿> status=<코드> outcome=<success|client_error|server_error>`.
  - route는 경로 템플릿이다. 실제 ID·query는 싣지 않는다.
  - 로그 → trace 이동의 기준점이다. 경보 대상이 아니다.

### 조립

- `TracingConfig`는 Gateway·ingest처럼 앱마다 둔다(0068의 앱 조립, ADR 0011).
- 공통 lib `:libs:telemetry-observability`에는 서비스 이름 상수만 더한다.

## Alternatives Considered

### A. RDS(JDBC) span을 함께 만든다
- 장점: 개요 조회의 RDS 구간이 trace에 바로 보인다.
- 단점: 계측 라이브러리가 SQL을 속성으로 싣는다. snapshot 복사는 한 요청에 수백 번 INSERT해서 span이 폭증한다.
- 탈락 이유: 보류한다. snapshot 경로를 재설계하는 PROJ-308과 함께 단계 span으로 다룬다.

### B. `TracingConfig`를 공통 모듈로 추출한다
- 장점: 같은 코드가 네 벌로 늘지 않는다.
- 단점: dev에서 검증을 마친 Gateway·ingest를 함께 바꿔야 한다. 0068의 앱 조립 원칙도 고쳐야 한다.
- 탈락 이유: 보류한다. 네 앱이 모두 안정된 뒤 따로 정한다.

### C. 프론트 BFF가 보낸 traceparent를 부모로 쓴다
- 장점: 브라우저·BFF·API를 한 trace로 본다.
- 단점: 외부 입력이 플랫폼 trace의 부모를 고르게 된다. BFF 쪽 trace 계약도 없다.
- 탈락 이유: 0069의 공개 ingress 규칙을 유지한다.

### D. `http.url`은 Collector에서만 지운다
- 장점: 앱 코드가 줄어든다.
- 단점: Collector 설정이 바뀌거나 우회되면 조직 ID가 그대로 저장된다.
- 탈락 이유: 앱 밖으로 나가는 데이터는 앱이 먼저 막는다(0069 span 속성 규칙).

### E. 업무 지표(`pulsemetry_dashboard_*`)를 함께 만든다
- 장점: snapshot 빌드·알림 평가 같은 업무 단위를 직접 센다.
- 단점: 무엇을 셀지 정할 기준선이 없다.
- 탈락 이유: 보류한다. 기본 binder로 기준선을 본 뒤 정한다.

## Consequences/Tradeoffs

### Positive

- 두 API의 route별 요청률·오류율·p95, Hikari 풀, JVM 상태를 Grafana에서 본다.
- dashboard 요청 trace에서 ClickHouse 구간과 나머지(앱·RDS) 구간을 나눠 본다.
- Loki 로그의 `trace_id`로 Tempo trace로 가고, trace에서 같은 로그로 돌아온다.
- 꺼 두면(기본) tracing 동작과 비용이 지금과 같다. 관리 포트는 열리지만 업무 포트 응답은 바뀌지 않는다.

### Negative

- 앱 의존성과 메모리가 는다. 관리 포트 하나가 더 열린다.
- Prometheus series가 늘어난다(route × method × status × outcome × 버킷 10).
- sampling 10%라 특정 느린 요청에 trace가 없을 수 있다.
- RDS 구간은 span 없이 "ClickHouse가 아닌 빈 구간"으로만 보인다.
- `TracingConfig`가 네 벌이다. 규칙을 바꾸면 네 앱을 함께 고쳐야 한다.
- infra 차트·Collector 정책·검증기를 함께 바꿔야 켤 수 있다.

## Follow-up

- 채택되면 0068·0069의 Status에 부분 개정을 적고, 인덱스를 갱신한다.
- RDS 구간 분해(단계 span 또는 JDBC span)는 PROJ-308과 함께 정한다.
- 5xx·지연 경보 임계값은 dev 기준선을 본 뒤 infra에서 정한다.
- `TracingConfig` 공통화를 다시 검토한다.
- 허브 계약 이관은 0069 Follow-up과 함께 한다.

## References

- [ADR 0011](0011-라이브러리-모듈은-spring-조립을-앱에-위임한다.md)
- [ADR 0068](0068-수집-운영-계측은-전송-계약과-분리하고-앱에서-조립한다.md)
- [ADR 0069](0069-플랫폼-처리-경로를-OpenTelemetry-trace로-추적하고-Kafka-record에-traceparent만-싣는다.md)
- infra ADR 0039 — dev Loki·Tempo와 플랫폼 trace Collector
- `docs/telemetry-observability.md`
