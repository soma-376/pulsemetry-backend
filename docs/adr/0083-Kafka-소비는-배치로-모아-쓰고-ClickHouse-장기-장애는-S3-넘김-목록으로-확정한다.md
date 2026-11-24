# 0083. Kafka 소비는 배치로 모아 쓰고 ClickHouse 장기 장애는 S3 넘김 목록으로 확정한다.

## Status

Proposed — 배치 모드에서 [ADR 0066](0066-OTLP-수신은-Gateway가-JWT만-검증해-Kafka에-접수하고-ingest가-RDB로-다시-검증한다.md) Decision 6(단건 listener·`AckMode.RECORD`·재시도 소진 시 확정)을
개정한다. 설정 `pulsemetry.telemetry.kafka.consumer.batch.enabled` 의 기본값은 `false` 라 이 ADR 만으로 운영 경로가 바뀌지 않는다.
팀 결정(목표 처리량·허용 지연·부분 적재 거부·한 건 반복 실패·재적재 책임·넘긴 receipt 의 ledger·요약·차단기 기준)과 허브 계약 §8 개정이 남아 있다(Follow-up).

## Context

- dev 부하 시험(PROJ-192, 2026-11-20)에서 초당 85건을 넣으면 81~83건만 처리되고 lag 이 늘었다. 지속 한계는 약 80 RPS 다.
- 원인은 단건 구조다. Kafka 레코드 한 건마다 ClickHouse INSERT 2개(분석·ledger)와 S3 PUT 1개가 나가고, 작은 part 가 초당 160~200개 생긴다.
  ClickHouse CPU 와 merge 가 먼저 막힌다. partition·consumer 를 늘리면 part 도 같이 늘어난다.
- `async_insert` 는 쓸 수 없다. [ADR 0024](0024-조직별-보존-삭제-경계는-RDS-가-진실원이고-분석-INSERT-는-ClickHouse-fence-를-서버에서-다시-검사한다.md) §2 가 금지하고, 24.8 에서 `INSERT … SELECT FROM input()` 은 비동기 경로를 타지 않는다.
- 단건 경로는 재시도 4회를 다 쓰면 레코드를 확정하고 버린다(`telemetry_retry_exhausted`). ClickHouse 가 몇 분 멈추면 그 사이 레코드가 모두 미적재로 확정된다.
  원본은 S3 에 있지만 재적재에 필요한 receipt 값(`receivedAt`·신원·버전)은 S3 객체에 없다([ADR 0012](0012-원본-아카이브를-S3에-쓰고-파일-구현은-로컬에만-남긴다.md) Follow-up).
- ClickHouse 24.8.14.39 는 입력 결함 일부를 HTTP 500 으로 돌려준다(코드 69·130·131). HTTP 4xx 만으로 레코드 결함을 판정할 수 없다.
- 설계 검토는 11차 개정안까지 진행했다(PROJ-341 첨부). 이 ADR 은 그 결정을 요약한다.

## Decision

1. **Kafka 에서 모은다.** 배치 모드는 `max.poll.records=100`, `fetch.min.bytes=64KiB`, `fetch.max.wait.ms=1000` 을 시작값으로 쓴다. 앱 메모리·Buffer 엔진·
   `async_insert` 로 모으지 않는다 — 모든 쓰기 뒤에만 offset 을 확정하므로 Pod 가 죽어도 재전달(중복)일 뿐 유실이 아니다.
2. **원본 레코드 단위로 준비하고 tenant·테이블마다 쓴다.** 레코드마다 해독·RDB 자격 확인·마스킹·S3 아카이브(동시 PUT 제한)·정규화·보강을 하고, 분석 행을
   (tenant, 분석 테이블)마다 INSERT 로 묶는다. ledger 는 배치마다 INSERT 하나, 요약은 tenant 마다 한 번이다. INSERT 수는 "배치의 tenant 수 + 1" 이 된다.
   본문은 UTF-8 10,000,000바이트 **미만**으로 나누고, 한 행만으로 그 이상이면 독립 INSERT 로 보낸다. 삭제 경계·서버 fence·`async_insert = 0` 은 그대로다.
3. **입력 결함은 원본 레코드 수로 반씩 나눠 격리한다.** 재현 시험으로 "요청 전체가 쓰기 전에 실패하고 두 달 모두 0행"을 확인한 ClickHouse 오류 코드(26·27·69·72·117·130·131,
   `ClickHouseErrorCodes`)만 이분할한다. 결함 레코드는 기존 단건 경로처럼 영구 거부로 기록한다. 목록 밖 오류는 레코드 결함으로 단정하지 않는다.
4. **ClickHouse 장애는 단기 재시도 뒤 S3 넘김으로 확정한다.** 단기 예산(최초 포함 4회, 대기 1·5·30초)은 배치 전체가 공유한다. 예산을 다 쓰면 소비자의 차단기를 열고,
   S3 에 보관된 레코드를 **넘김 목록**(배치마다 S3 객체 하나, key = group·토픽·파티션별 offset 범위)에 올린 뒤 확정한다. 목록은 레코드마다 receipt·S3 위치·Kafka 위치·
   사유·끝낸 쓰기를 담는다. 이 파이프라인의 책임은 ClickHouse 적재와 넘김 기록까지이고, 재적재는 목록을 읽는 별도 모듈이 맡는다.
5. **넘길 근거가 없으면 기다린다.** 인증 DB·보강·삭제 경계·요약(RDS), S3 아카이브, 넘김 목록 쓰기가 실패하면 단기 예산 뒤 긴 대기(5분)로 자동 재시도한다.
   예상 밖 예외도 레코드를 건너뛰지 않고 같은 일정으로 기다린다. 어떤 대기 일정도 `STOP` 을 내지 않는다.
6. **차단기는 소비자마다 둔다.** 열린 동안 ClickHouse 쓰기를 시도하지 않고 넘김으로 확정한다. 탐침 차례(5분 ±10%)에 `SELECT 1` 과 그 배치의 첫 쓰기가 성공하면 닫는다.
   다른 쓰기는 저장되는데 한 레코드만 반복 실패하면 그 한 건만 넘긴다(팀 결정 4번 기본값). 메모리 한도(241)로 큰 쓰기가 예산을 다 쓰면 같은 호출 안에서 작은 쓰기로 진단한다.
7. **offset 은 배치 끝에서만 확정한다.** `AckMode.BATCH`·`syncCommits=true`·원본 `ConsumerRecords` 리스너(`wantsPollResult`)를 쓴다. 미완료면 실패 인덱스 없이 예외를 던져
   Spring 배치 fallback 이 같은 메모리 배치로 다시 부르게 한다 — 끝낸 PUT·쓰기는 작업 캐시에서 이어간다. 호출에는 단조 시계 마감(420초)이 있고, 진척 있는 마감은 장애로 세지 않는다.
8. **저장 완료 뒤 커밋 실패는 다시 처리하지 않는다.** 리스너는 정상 반환 직전에 이번 전달을 완료 표시하고, 오류 처리기는 표시된 전달의 실패를 재호출 없이
   `event=telemetry_commit_failed` WARN 예외로 끝낸다. 재시도 중 같은 `Consumer` 의 회수·상실이 있으면 옛 배치의 ack 를 INFO(`kafka_rebalance_ack_skipped`)로 건너뛴다 — 커밋 후퇴 방지.
9. **운영 로그 코드를 더한다.** 경보 대상 `telemetry_archive_handoff`·`telemetry_batch_waiting`·`telemetry_batch_unexpected_failure`·`telemetry_batch_stalled`,
   비경보 `ingest_batch_recovered`·`ingest_breaker_opened`·`ingest_breaker_closed`·`kafka_rebalance_ack_skipped`. 파티션 범위는 `p<번호>=<첫>-<끝>` 필드다.

## Alternatives Considered

### A. partition·consumer 를 먼저 늘린다
- 장점: 코드 변경이 없다.
- 단점: part 생성과 ClickHouse CPU 가 같이 늘어 ClickHouse 가 먼저 막힌다. `topic-init.sh` 가 partition 6 을 강제한다.
- 탈락 이유: 병목이 INSERT 횟수다. 배치 뒤에 필요하면 늘린다.

### B. 앱 메모리·Buffer 엔진·`async_insert`(wait=0)로 모은다
- 장점: Kafka 대기 창이 필요 없다.
- 단점: 쓰기 전에 확정하므로 Pod 종료·ClickHouse 장애에서 유실된다. `async_insert` 는 ADR 0024 §2 가 금지한다.
- 탈락 이유: 누락 0 원칙과 맞지 않는다.

### C. 장애가 길어지면 기존처럼 소진 확정하거나, 넘기지 않고 무기한 기다린다
- 장점: 넘김 목록·재적재 모듈이 필요 없다.
- 단점: 소진 확정은 미적재 레코드를 버린다. 무기한 대기는 Kafka 72시간 보존을 넘기면 유실되고, 차단기 없이 배치마다 단기 예산을 쓰면 처리량이 초당 17건 안팎으로 떨어진다.
- 탈락 이유: S3 원본 보존 기간(dev 7일·운영 30일) 안에서 복구할 수 있게 넘기는 편이 안전하다.

### D. 실패 레코드를 DLT(Kafka 토픽)로 보낸다
- 장점: Kafka 도구로 재처리한다.
- 단점: 원문이 다시 Kafka 에 72시간 남고, receipt 값을 새로 정의해야 한다. ADR 0066 Alternative D 가 같은 이유로 탈락시켰다.
- 탈락 이유: 원본은 이미 S3 에 있고 목록만 있으면 된다.

## Consequences/Tradeoffs

### Positive
- 배치의 INSERT 수가 레코드 수 × 2 에서 tenant 수 + 1 로 준다(13건·고객 1명 26→2회, 밀린 100건·고객 10명 200→11회).
- 독립 입력 결함 한 건이 정상 99건을 막지 않는다.
- ClickHouse 장기 장애에도 lag 이 늘지 않고, 미적재 레코드는 모두 넘김 목록이나 거부 기록에 남는다.

### Negative
- **넘긴 레코드는 재적재 전까지 분석 테이블에 없다.** 재적재 모듈이 없으면 S3 보존 기간이 지나 사라진다 — 팀 결정 5번.
- 넘긴 receipt 의 ledger 행과 요약은 이 경로가 쓰지 않는다 — 팀 결정 6번.
- 넘길 수 없는 장애로 기다리면 그 소비자가 맡은 **모든 파티션**이 멈춘다(Spring 배치 fallback 이 할당 전체를 멈춘다).
- 대기 창(최대 1초)만큼 적재 지연이 늘 수 있다. 차단기는 짧은 장애 뒤 최대 5분 동안 넘김을 계속한다.
- 재호출·재전달로 S3 원본 객체와 넘김 목록이 늘 수 있다(같은 메모리 배치의 넘김 목록은 덮어쓴다).
- 비허용 4xx 를 공통 장애로 다루므로 스키마·권한 결함은 거부 대신 넘김으로 간다 — 데이터는 남지만 결함을 고치기 전에는 재적재도 실패한다.

## Follow-up

- 팀 결정: 목표 처리량, 허용 적재 지연(p95), 부분 적재 레코드 거부(코드 기본 `REJECT` — 단건 경로와 같다), 한 건 반복 실패(기본 `HANDOFF`),
  재적재 책임·기한, 넘긴 receipt 의 ledger·요약 작성 주체, 차단기 기준. 정해지면 이 ADR 을 개정하고 Accepted 로 올린다.
- 허브 `contracts/telemetry-ingest.md` §8(오류 분류)을 개정한다. ADR 0024(tenant별 경계 조회)·0012(재처리 원천·넘김 목록)·0021(넘긴 receipt 의 ledger·요약)도 함께 개정한다.
- infra: 지표 필터·경보에 `telemetry_archive_handoff`(필요하면 `telemetry_batch_waiting`·`telemetry_batch_unexpected_failure`·`telemetry_batch_stalled`)를 더하고,
  배치 형식의 `telemetry_commit_failed` 표본으로 TestMetricFilter 를 확인한다. dev 에 `PULSEMETRY_TELEMETRY_KAFKA_CONSUMER_BATCH_ENABLED` 를 켜는 것은 부하 비교 때다.
- 배포 전 시험 중 실제 브로커가 필요한 것(재시도 중 파티션 이동의 offset 후퇴, `max.poll.interval` 초과 재가입, 커밋 타임아웃 뒤 파티션별 확정 위치, ClickHouse 10분 정지·권한 복구)은
  dev 또는 별도 시험 환경에서 한다.
- 한 캠페인에 여러 tenant 를 만드는 부하 시드로 단건·배치를 같은 입력에서 비교한다(초당 새 part, lag 기울기, 적재 p95).

## Acceptance Criteria

- `./gradlew :apps:telemetry-ingest:test` — `BatchIngestProcessorTest`·`TelemetryBatchErrorHandlerTest`·`InsertPackerTest`·`BatchKafkaConsumerTest`(실제 Kafka·ClickHouse·PostgreSQL)가 통과한다.
- `./gradlew :libs:telemetry-persistence:test --tests '*InputDefectCodesEvidenceTest*'` — 허용 목록 코드마다 두 달 0행이다.
- 배치 모드를 끈 기본 설정에서 기존 단건 시험이 그대로 통과한다.

## References

- PROJ-341 · 첨부 `Kafka-배치-INSERT-이분할-적용계획-11차-개정안.md`
- [ADR 0066](0066-OTLP-수신은-Gateway가-JWT만-검증해-Kafka에-접수하고-ingest가-RDB로-다시-검증한다.md) · [ADR 0024](0024-조직별-보존-삭제-경계는-RDS-가-진실원이고-분석-INSERT-는-ClickHouse-fence-를-서버에서-다시-검사한다.md) ·
  [ADR 0012](0012-원본-아카이브를-S3에-쓰고-파일-구현은-로컬에만-남긴다.md) · [ADR 0021](0021-수집-운영-기록은-ledger-와-telemetry-ops-스키마에-두고-enrollment-api-가-적용한다.md)
- [telemetry Kafka 운영](../telemetry-kafka-operations.md) — 배치 모드 설정표·넘김 목록·로그 이벤트
- Spring for Apache Kafka 4.1 — [오류 처리](https://docs.spring.io/spring-kafka/reference/4.1/kafka/annotation-error-handling.html)
