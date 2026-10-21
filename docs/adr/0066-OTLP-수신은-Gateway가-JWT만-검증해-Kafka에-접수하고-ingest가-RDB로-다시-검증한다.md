# 0066. OTLP 수신은 Gateway 가 JWT 만 검증해 Kafka 에 접수하고 ingest 가 RDB 로 다시 검증한다.

## Status

Proposed — 허브 [ADR 0005](../../../docs/adr/0005-single-app-telemetry-topology.md)(단일 앱·큐 없음)와
[ADR 0006](../../../docs/adr/0006-otlp-ingest-retry-and-status-contract.md)(200 의 의미·영구 실패 400·서버 큐 없음),
허브 `contracts/telemetry-ingest.md` 를 부분 대체할 허브 개정이 남아 있다(Follow-up).
경로 전환은 배포 설정(`PULSEMETRY_INGEST_MODE`, ALB 대상)으로 하며 이 ADR 만으로 운영 경로가 바뀌지 않는다.

## Context

- 지금은 `:apps:telemetry-ingest` 한 앱이 요청 스레드에서 인증·디코딩·마스킹·아카이브·정규화·적재를 끝내고 응답한다(허브 ADR 0005·0006).
  RDB·S3·ClickHouse 중 하나가 느리면 데몬의 요청이 그 시간만큼 붙잡힌다.
- 데몬(telemetryctl 기본 브랜치)은 요청 하나에 10초를 기다리고, 401·403 이면 재발급 후 한 번 재전송하며, 4xx 는 폐기하고 5xx 는 재시도한다.
- dev EKS 에 MSK(Kafka 3.9, broker 3대, RF 3·minISR 2)를 두기로 했다(PROJ-205). 접수와 처리를 나누면 접수 경로에는 RDB 가 필요 없다.
- 접수 서버가 본문을 디코딩·마스킹하면 마스킹 정책과 디코더가 두 앱에 갈라진다.

## Decision

1. **경로**: ALB → `:apps:telemetry-gateway`(`/v1/logs`·`/v1/traces`·`/v1/metrics`) → MSK topic → `:apps:telemetry-ingest` Consumer → 기존 수집 코어
   ([ADR 0067](0067-수집-코어는-전송과-분리하고-검증된-신원과-수신-문맥을-명시적으로-받는다.md)). Gateway 에는 DB·JPA·S3·ClickHouse 의존과 설정이 없다.
2. **Gateway 의 처리 순서와 상태**

   | 순서 | 검사 | 실패 응답 |
   |---|---|---|
   | 1 | Bearer JWT 검증([ADR 0065](0065-telemetry-token은-전용-RS256-JWT로-발급하고-telemetry_tokens를-자격-기록으로-유지한다.md)) | 401, ingest 와 같은 본문 |
   | 2 | 메서드 · Content-Type · Content-Encoding 허용 목록 | 405 · 415 (상위 문자열 그대로) · 400 `unsupported Content-Encoding` |
   | 3 | 원본 본문 10 MiB | 413 |
   | 4 | 접수 시각(`acceptedAt`) 확정, 그 시각으로 토큰 시간 재검증 | 401 |
   | 5 | envelope 발행(`acks=all`, idempotence) | 503 + `Retry-After: 1` |
   | 6 | broker ack | 200 + OTLP 성공 본문 |

   **200 은 Kafka 접수다.** S3·ClickHouse 적재 완료를 뜻하지 않는다. Gateway 는 본문을 디코딩하지 않으므로
   **깨진 gzip·protobuf·JSON 도 200 을 받고 Consumer 에서 영구 실패로 미적재**된다 — 허브 ADR 0006 의 "디코드 실패는 400"이
   Gateway 경로에서는 성립하지 않는다.
3. **시간**: 요청 시작 기준 단일 기한 7.5초 안에 응답한다. producer 는 `max.block.ms=2000`·`request.timeout.ms=4000`·
   `delivery.timeout.ms=5000`·`buffer.memory=64 MiB` 다. 기한이 지나면 실제 발행 여부와 무관하게 503 이다 —
   timeout 뒤 늦게 발행된 record 와 데몬의 재전송이 중복될 수 있다. Tomcat 작업 스레드를 16개로 제한해 메모리를 묶는다.
4. **envelope**(protobuf, schema version 1): message ID(Gateway 생성 UUID), 접수 시각, 인증 문맥(jti·tenant·installation·iss·kid·iat·exp),
   signal·정규화한 Content-Type·Content-Encoding·원본 body. record key 는 `tenant_id:installation_id`.
   인증 문맥은 검증 결과에서만 만든다. **Bearer 원문은 envelope·key·header·로그 어디에도 넣지 않는다.**
   envelope 별도 서명은 두지 않는다 — ingress topic 쓰기 권한은 Gateway 의 MSK 역할에만 있다(infra 소유).
5. **Kafka 에는 마스킹 전 원문이 저장된다.** 보존 72시간, MSK 저장 암호화·TLS·IAM 으로만 보호한다.
6. **Consumer**: bytes 로 받고 listener 안에서 codec 을 부른다. auto commit off, `AckMode.RECORD`, 단건 listener.
   - 매 시도 `jti` 로 RDB 판정 → 수집 코어. receipt ID·receivedAt 은 message ID·접수 시각이다 — 재시도·재전달에도 같다.
   - **영구 오류**(잘못된 envelope·인증 거부·디코딩·마스킹·기존 파이프라인 영구 실패)는 listener 가 안전한 로그를 남기고 정상 반환해 offset 을 확정한다.
     기존 영구 실패의 운영 기록(ADR 0021)은 그대로 남는다.
   - **일시 오류**(RDB·S3·ClickHouse·분류되지 않은 예외)는 원인 메시지를 지운 예외로 던진다. `DefaultErrorHandler` 가 1·5·30초 간격으로
     세 번 재시도하고(최초 포함 4회), 소진하면 recoverer 가 `telemetry_retry_exhausted` 를 남기고 offset 을 확정한다. recoverer 는 던지지 않는다.
   - `resetStateOnExceptionChange=false` — 예외 종류가 바뀌어도 예산을 새로 시작하지 않는다. 4회는 한 처리 흐름의 예산이다.
   - **기본 sleep backoff 를 쓴다(`ContainerPausingBackOffHandler` 를 쓰지 않는다).** 최대 대기 30초가 `max.poll.interval.ms`(600초)보다
     훨씬 짧고 heartbeat 는 백그라운드 스레드가 보내므로 poll 을 이어 갈 이유가 없다. 대기는 컨테이너가 멈추면 즉시 끝난다(`stoppableSleep`).
     pausing 은 TaskScheduler·pause/resume 이 재할당·종료와 겹치는 경우를 하나 더 만든다.
   - `max.poll.records=5`, `max.poll.interval.ms=600000`, `stopImmediate=true`, 컨테이너 종료 45초.
     인증·인가 예외는 컨테이너를 멈추지 않고 10초마다 다시 시도한다. 소비자는 topic 을 만들지 않는다(`allow.auto.create.topics=false`).
   - at-least-once 다. 재전달·재전송·S3 재시도의 중복을 허용하고 중복 제거 저장소·DLT·실패 S3 경로·자동 재처리를 두지 않는다.
     **장시간 장애에서는 재시도 소진으로 미적재가 생긴다.** 무손실을 주장하지 않는다.
   - offset commit 실패는 성공이 아니다 — 컨테이너가 그 record 를 다시 처리하고 `telemetry_commit_failed` 를 남긴다.
7. **ingest 모드**(`PULSEMETRY_INGEST_MODE`): `http`(기본 — 기존 HTTP 경로 + JWT 호환), `dual`(HTTP + Consumer), `kafka`(Consumer 와 health 만,
   OTLP 경로는 기본 닫힘 404). 잘못된 값은 기동 실패다. `kafka` 모드는 해시 키·telemetry 공개키를 요구하지 않는다.
8. **크기**: HTTP body 10 MiB, producer `max.request.size` 12 MiB, broker·topic·replica fetch·consumer fetch 13 MiB.
9. **MSK IAM**: workload 마다 전용 MSK 역할을 `awsRoleArn`·고정 `awsRoleSessionName`·`awsStsRegion=ap-northeast-2` 로 assume 한다.
   Pod Identity 자격이 갱신될 때마다 세션 이름이 바뀌면 MSK 가 재인증을 거부하기 때문이다. 장기 access key 를 쓰지 않는다.
10. **로그 이벤트**는 평문 `event=<code>` 와 key=value 한 줄이다. 코드는 `telemetry_gateway_rejected`·`telemetry_publish_failed`·
    `telemetry_permanent_failure`·`telemetry_retry_exhausted`·`telemetry_commit_failed`(+ 경보 대상이 아닌 `telemetry_transient_failure`).
    messageId·topic·partition·offset·stage·reason·attempt 를 아는 만큼 붙인다. payload·Bearer·예외 메시지 원문은 남기지 않는다.
11. **종료**: Gateway 는 graceful 30초(새 접수 중단 → 진행 중 요청 기한 안 종료 → producer flush). Consumer 는 진행 중 record 만 끝내고 멈추며,
    끝내지 못한 record 는 확정하지 않아 다음 소유자가 다시 처리한다. Pod 종료 유예는 Gateway 60초·ingest 90초다(infra).

## Alternatives Considered

### A. 동기 ingest 를 유지하고 수평 확장한다
- 장점: 200 이 적재 완료를 뜻하는 지금의 계약이 그대로다.
- 단점: 접수 경로가 계속 RDB·S3·ClickHouse 에 묶인다. 데몬 10초 안에 끝내야 하는 압력이 처리 경로 전체에 걸린다.
- 탈락 이유: PROJ-205 의 목표(접수와 처리 분리)를 충족하지 못한다.

### B. Gateway 가 디코딩·마스킹해 마스킹 후 데이터를 Kafka 에 넣는다
- 장점: Kafka 에 비밀이 남지 않고, 디코드 실패를 400 으로 돌려줄 수 있다.
- 단점: 마스킹 정책·디코더·버전이 두 앱에 갈라지고 Gateway 의 CPU·메모리가 커진다. 신원 스탬프·아카이브 순서(ADR 0016)도 Gateway 로 끌려온다.
- 탈락 이유: 마스킹·스탬프·아카이브는 수집 코어 한 곳에 둔다. Kafka 원문 노출은 보존 기간·IAM·암호화로 묶는다.

### C. `ContainerPausingBackOffHandler`
- 장점: 대기 중에도 poll 을 이어 가 긴 backoff 에서 그룹 이탈을 막는다.
- 단점: TaskScheduler 와 pause/resume 상태가 재할당·종료와 겹친다. 이 설정의 최대 대기(30초)에서는 얻는 것이 없다.
- 탈락 이유: Decision 6 참고. backoff 를 `max.poll.interval.ms` 근처로 늘릴 때 재검토한다.

### D. DLT 또는 실패 S3 경로
- 탈락 이유: 자동 재처리가 없는 실패 저장소는 운영 부담만 늘린다. 72시간 보존 안의 수동 offset 재처리(infra 절차)로 대신한다.

### E. Spring Boot Kafka 자동설정
- 장점: `spring.kafka.*` 로 설정한다.
- 단점: KafkaAdmin·기본 listener factory 가 클래스패스만 보고 생기고, IAM 설정이 모드에 따라 달라 YAML 로 표현되지 않는다.
- 탈락 이유: 무엇이 켜져 있는지 앱의 `@Bean` 만 보면 아는 성질(ADR 0011·0016)을 지킨다. Gateway 는 kafka-clients producer 만,
  ingest 는 spring-kafka 컨테이너를 명시 빈으로 만든다.

## Consequences/Tradeoffs

### Positive
- 접수가 RDB·S3·ClickHouse 장애와 분리된다. Gateway 는 MSK 가 받으면 200 이다.
- 처리 실패가 데몬의 재시도 예산을 태우지 않는다.
- Consumer 가 재시도마다 RDB 를 다시 읽어 접수 뒤의 보안 폐기·차단을 반영한다.

### Negative
- **200 이 적재를 뜻하지 않는다.** 깨진 본문·RDB 거부·재시도 소진은 데몬에 보이지 않고 로그·경보로만 드러난다.
- **Kafka 에 마스킹 전 원문이 72시간 남는다.** MSK 접근 권한이 곧 원문 접근 권한이다.
- 처리 경로의 중복이 늘어난다(재전달·timeout 뒤 재전송). observation ID·row version 이 같아 분석 행은 수렴하지만 ledger·아카이브 객체는 늘 수 있다.
- 장시간 장애에서 재시도 소진 record 는 수동 재처리 전까지 미적재다.
- 운영 단위가 하나(Gateway) 늘고 MSK 비용이 생긴다.

## Follow-up

- 허브 개정: ADR 0005(단일 앱·네트워크 홉 없음), ADR 0006(200 의 의미, Gateway 경로의 디코드 실패, "서버 큐 없음" — Alternative C 의 재검토 조건이 충족됐다),
  `contracts/telemetry-ingest.md`(Gateway 경로·envelope·상태 표)를 부분 대체한다. 이 레포에서는 허브를 고치지 않는다.
- `kafka` 모드 전환이 안정되면 ingest 의 HMAC 키·telemetry 공개키·외부 NodePort 를 걷는다(infra).
- 처리량 지표(micrometer)는 아직 없다. 경보는 로그 metric filter 와 MSK consumer lag 으로 건다(infra). Pod 재시작·OOM 경보는 Container Insights 이후다.
- backoff 를 늘리거나 단건 처리 시간이 `max.poll.interval.ms` 에 가까워지면 Decision 6 의 backoff 방식을 재검토한다.

## References

- 허브 [ADR 0005](../../../docs/adr/0005-single-app-telemetry-topology.md) · [ADR 0006](../../../docs/adr/0006-otlp-ingest-retry-and-status-contract.md)
- [ADR 0016](0016-조립-앱은-인증-체인과-단계-호출을-배선하고-스키마-적용-실패를-견딘다.md) · [ADR 0021](0021-수집-운영-기록은-ledger-와-telemetry-ops-스키마에-두고-enrollment-api-가-적용한다.md) · [ADR 0065](0065-telemetry-token은-전용-RS256-JWT로-발급하고-telemetry_tokens를-자격-기록으로-유지한다.md) · [ADR 0067](0067-수집-코어는-전송과-분리하고-검증된-신원과-수신-문맥을-명시적으로-받는다.md)
- [telemetry Kafka 운영](../telemetry-kafka-operations.md) — 설정표·모드 전환·메모리 산정·로그 이벤트
- Spring for Apache Kafka 4.1 — [오류 처리](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html), [컨테이너](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html)
