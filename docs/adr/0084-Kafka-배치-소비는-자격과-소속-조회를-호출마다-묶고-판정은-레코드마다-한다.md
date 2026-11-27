# 0084. Kafka 배치 소비는 자격과 소속 조회를 호출마다 묶고 판정은 레코드마다 한다.

## Status

Accepted — 2026-11-27 팀 동의. 구현은 PROJ-345.
Kafka 배치 소비 모드(`pulsemetry.telemetry.kafka.consumer.batch.enabled`)에서만 [ADR 0020](0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md) §5 의
소속 조회 캐시 범위(push 단위)와 [ADR 0083](0083-Kafka-소비는-배치로-모아-쓰고-ClickHouse-장기-장애는-S3-넘김-목록으로-확정한다.md) Decision 2 의
"레코드마다 … RDB 자격 확인 … 보강" 을 부분 개정한다. 두 ADR 의 나머지 결정과 HTTP·단건 Kafka 경로는 그대로다.
ADR 0083 은 Proposed 로 남아 있다. 이 ADR 은 배치 경로의 자격·소속 조회 범위만 정하고 0083 의 팀 결정 항목에는 관여하지 않는다.

## Context

- 배치 모드(ADR 0083)는 ClickHouse INSERT 를 묶었지만 RDB 조회는 레코드마다 세 번이다. 자격 1회(`TelemetryCredentialAuthorizer.authorize` 의
  `findCredentialRowById`), 보강 2회(`ObservationEnricher` 의 `findMemberIdById`·`findAllByMemberId`)다.
- 보강 캐시는 `ObservationEnricher.enrich` 호출 하나(push 하나)를 넘지 않는다(ADR 0020 §5). 배치 경로는 레코드마다 `IngestStages.transform` 을
  부르고 Kafka 레코드 하나가 push 하나이므로, 같은 설치의 레코드가 한 배치에 여럿이어도 매번 다시 읽는다.
- ⑧ 시험(`pipeline-20261120-1530-pipe-batch600`, 2026-11-25, 200→600 RPS 계단)
  - 배치 소비 상한은 약 450건/초다.
  - Hikari 연결 빌림은 280·400·600 RPS 유지 끝에서 모두 레코드당 3.0회다. 600 RPS 유지 끝에서 초당 1,310회(처리 438건/초)였다.
  - 연결 대기(acquire)는 0.004 ms 이하다. 연결 풀이 아니라 차례로 도는 조회 왕복과 그 CPU 가 문제다.
  - 600 RPS 에서 레코드당 authorize 1.68 ms·enrich 3.55 ms 다. 100건 배치면 약 0.52초를 이 두 단계가 차례로 쓴다.
- PROJ-344 JFR(`pipeline-20261120-1530-jfr500`, 2026-11-27, hold 500 RPS)
  - Kafka 소비 스레드 CPU 중 DB(JPA·JDBC·Hikari) 프레임을 포함한 표본이 28%다(CPU 시간 표본. 실행 표본 기준 19%).
  - DB 표본의 출처는 enrich 34%·authorize 27%·스택 잘림 38%다. 보존 경계 조회는 0.1%다.
- 지켜야 하는 규칙
  - [ADR 0066](0066-OTLP-수신은-Gateway가-JWT만-검증해-Kafka에-접수하고-ingest가-RDB로-다시-검증한다.md) Decision 6 — Consumer 는 매 시도 `jti` 로 RDB 를 다시 판정한다.
    판정표·순서는 [ADR 0065](0065-telemetry-token은-전용-RS256-JWT로-발급하고-telemetry_tokens를-자격-기록으로-유지한다.md) Decision 6 이다. DB 장애는 거부가 아니라 일시 실패다.
  - `BatchWork.identity` KDoc — 재호출마다 신원을 다시 확인한다(보안 폐기·차단 반영).
  - ADR 0020 §5 — 소속 조회는 push 단위 캐시를 허용하고, 소속 변경은 다음 push 부터 반영되며, 이미 적재된 행은 재처리로 갱신된다.
- 저장소에는 여러 ID 를 한 번에 읽는 조회가 없다(`TelemetryTokenRepository`·`InstallationRepository`·`TeamMembershipRepository`).
- 정하지 않으면 배치마다 RDS 조회가 레코드 수 × 3 으로 남고, 소비 스레드가 그 왕복을 차례로 기다린다.

## Decision

1. **자격은 호출마다 한 번에 읽고 레코드마다 판정한다.** 배치 소비 호출 한 번에서 미완료 레코드(넘김 대기 제외)의 자격 행을
   고유 `jti` 의 IN 조회 한 번으로 읽는다. 판정은 레코드마다 자기 접수 시각(`acceptedAt`)으로 ADR 0065 Decision 6 의 같은 표·순서를 따르고, 거부 코드도 같다.
   - 재호출마다 다시 읽는다. ADR 0066 Decision 6 의 "매 시도 RDB 판정" 은 배치 소비 호출 단위로 지킨다.
   - 호출을 넘는 캐시는 두지 않는다.
   - 같은 호출 안에서 넘김 전 재준비(`handoffAfterPrepare`)가 준비를 다시 부르면 자격도 한 번 더 읽는다. 차단기를 여는 드문 경로라 보안 반영을 우선한다.
2. **보강의 구성원·소속 조회 결과는 그 호출 안에서만 재사용한다.** 정규화에 성공한 레코드의 고유 설치로 구성원을, 고유 구성원으로 소속을 각각 IN 조회 한 번으로 읽고
   그 호출의 레코드가 함께 쓴다. ADR 0020 §5 의 "push 단위 캐시" 를 배치 모드에서는 "배치 소비 호출 단위" 로 넓힌다. as-of 소속·팀 상태 비필터·품질 플래그 등 보강 규칙은 그대로다.
   - 소속 변경이 반영되지 않는 창은 보통 배치 처리 시간(⑧ 약 1.3초)이고, 최악은 호출 마감 420초(ADR 0083 Decision 7)다.
   - 이미 적재된 행은 재처리(같은 `normalizer_rev`, 큰 `ingest_seq`)로 갱신된다는 ADR 0020 §5 의 문장은 그대로다.
   - 재호출에서 이미 변환을 마친 레코드는 작업 캐시의 보강 결과를 그대로 쓴다(ADR 0083 Decision 7). 이 동작은 이 결정 전과 같다.
3. **ADR 0083 Decision 2 를 고친다.** "레코드마다 해독·RDB 자격 확인·마스킹·S3 아카이브(동시 PUT 제한)·정규화·보강을 하고" 는
   "레코드마다 해독·자격 판정·마스킹·S3 아카이브(동시 PUT 제한)·정규화·보강을 하되, 자격·소속 조회는 호출마다 묶는다" 로 고친다.
   0083 본문에는 `**대체됨(ADR 0084)** —` 표기로 남긴다.
4. **보존 경계([ADR 0024](0024-조직별-보존-삭제-경계는-RDS-가-진실원이고-분석-INSERT-는-ClickHouse-fence-를-서버에서-다시-검사한다.md))는 이 결정과 무관하다.**
   지금처럼 호출마다 tenant 당 한 번 읽는다.
5. **HTTP 와 단건 Kafka 경로는 바꾸지 않는다.** 레코드(push)마다 자격을 읽고, 보강 캐시는 push 하나를 넘지 않는다.
6. **계측.** `pulsemetry_pipeline_stage_duration_seconds` 에 `stage="authorize_batch"`·`stage="enrich_batch"` 를 더한다. 둘 다 배치 소비 호출당 한 번 잰다.
   배치 경로에서는 레코드 단위 `authorize`·`enrich` 를 재지 않고, `normalize` 는 레코드마다 그대로 잰다. 단건·HTTP 경로의 stage 는 그대로다.
   예외는 하나다 — 묶음 보강이 영구 오류로 끝나 레코드별 보강으로 되돌아갈 때는 그 레코드들의 `enrich` 가 레코드 단위로 남는다(정규화는 다시 하지 않는다).
   `authorize_batch` 의 `outcome` 은 묶음 조회의 성패다 — 레코드 거부가 섞여도 조회가 성공하면 `success` 이고, `failure` 는 DB 예외일 때뿐이다.
7. **오류 분류는 바뀌지 않는다.** 자격 조회의 DB 장애와 보강의 일시 장애는 거부하지 않고 기다린다(ADR 0083 Decision 5).
   보강의 영구 오류(자원 계열이 아닌 `NonTransientDataAccessException`)는 레코드별 경로로 되돌아가 해당 레코드만 거부한다 — 어느 레코드의 오류인지 지금처럼 정해진다.

## Alternatives Considered

### A. 호출을 넘는 TTL 캐시
- 장점: 재호출과 다음 배치도 RDS 를 거치지 않아 조회가 더 준다.
- 단점: 보안 폐기·설치 차단이 TTL 만큼 늦게 반영된다.
- 탈락 이유: ADR 0066 Decision 6 의 매 시도 RDB 판정과 어긋난다.

### B. 호출 단위 ID별 메모만 둔다
- 장점: 저장소에 새 조회를 더하지 않는다.
- 단점: 조회 수가 "배치의 고유 설치 수" 가 된다. dev(설치 60개, partition 당 10개)에서는 크게 줄지만 설치가 많은 partition 에서는 거의 줄지 않는다.
- 탈락 이유: 운영 규모에서 효과가 없다. IN 조회는 설치 수와 상관없이 호출당 같은 횟수다.

### C. 그대로 둔다
- 장점: 변경이 없다.
- 단점: RDS 조회가 레코드당 3회로 남고, 소비 스레드가 조회 왕복을 차례로 기다린다.
- 탈락 이유: PROJ-344 에서 소비 스레드 CPU 표본의 28%가 DB 프레임을 포함했다.

## Consequences/Tradeoffs

### Positive
- 자격·보강 RDS 조회가 레코드당 3회에서 호출당 약 3회(자격·구성원·소속 IN 각 1회)로 준다.
  dev(설치 60개, partition 당 10개)의 100건 배치 기준 300회 → 3회, 99% 감소다. 배치가 작으면 줄어드는 비율도 작다.
- 소비 스레드의 DB CPU 와 조회 대기가 준다. PROJ-344 는 레코드당 DB CPU 를 약 0.56 ms 로 추정했다. 효과 크기는 dev hold 500 측정으로 확인한다(Follow-up).
- 판정표·거부 코드·오류 분류가 그대로라 기존 판정 시험을 묶음 경로에 그대로 돌릴 수 있다.

### Negative
- 소속 변경이 반영되지 않는 창이 push 하나에서 배치 소비 호출 하나로 넓어진다(보통 약 1.3초, 최악 420초). 그 사이 잘못 붙은 행은 재처리로 갱신한다.
- 보강이 장애로 실패하면 그 호출의 변환 결과가 남지 않아 재호출에서 정규화를 다시 한다(100건에 약 0.2초). 장애 경로에서만 생긴다.
- 지표 의미가 바뀐다. 배치 모드의 레코드 단위 `authorize`·`enrich` 가 사라져 ⑧ 값과 바로 비교할 수 없다.
  `authorize_batch`·`enrich_batch` 합을 처리 레코드 수로 나눠 비교한다.
- 넘김 전 재준비에서는 같은 호출 안에서 자격을 한 번 더 읽는다.
- CPU 가 함께 줄지 않으면 처리량 이득은 작다. 그때는 RDS 부하·대기 감소로 보고하고 PROJ-346(확장·노드 유형)과 함께 본다.

## Follow-up

- dev 확인: PROJ-344 h500 과 같은 조건(hold 500 RPS, 8분)으로 실행해 Hikari 빌림/레코드, `authorize_batch`+`enrich_batch` 의 레코드당 시간,
  레코드당 JVM CPU, 포화 소비율을 PROJ-344 값과 비교한다. 성능 기록은 노션 시험 행에 남긴다.
- ADR 0083 을 개정해 Accepted 로 올릴 때 Decision 2 의 문장을 Decision 3 대로 바꿔 쓰고 이 ADR 을 참조로 남긴다.
- 호출을 넘는 캐시·정규화 최적화·보존 경계 캐시는 범위 밖이다. 호출을 넘는 자격 캐시는 ADR 0066 Decision 6 개정 없이는 두지 않는다.

## Acceptance Criteria

- `./gradlew :libs:enrollment-persistence:test :libs:telemetry-enricher:test :libs:telemetry-observability:test :apps:telemetry-ingest:test` 가 통과한다.
  - 판정표의 모든 사례에서 단건 판정과 묶음 판정의 결과·거부 코드가 같다.
  - 재호출 때 자격 조회가 다시 일어난다. 호출을 넘는 캐시가 없다.
  - 인증 DB 장애와 보강 일시 장애는 기다리고, 보강 영구 오류는 해당 레코드만 거부한다.
  - `BatchKafkaConsumerTest`(실제 Kafka·ClickHouse·PostgreSQL)에서 배치 모드의 단건 자격 조회(`findCredentialRowById`)가 0회다.
- 배치 모드를 끈 기본 설정의 HTTP·단건 시험과 로컬 E2E(`tools/loadtest/local/run.sh`)가 그대로 통과한다.
- dev hold 500 에서 Hikari 빌림/레코드가 3.0 에서 0.1 이하로 줄고, `authorize_batch`·`enrich_batch` 합을 레코드 수로 나눈 값이 ⑧ 의 authorize + enrich(5.23 ms/건)보다 작다.
  ClickHouse `FINAL` 행 수가 k6 기대치와 같다.

## References

- PROJ-345 · 상위 PROJ-343 · 선행 PROJ-344(JFR)
- ⑧ `pipeline-20261120-1530-pipe-batch600`(2026-11-25) · PROJ-344 `pipeline-20261120-1530-jfr500`(2026-11-27)
- [ADR 0020](0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md) §5 ·
  [ADR 0024](0024-조직별-보존-삭제-경계는-RDS-가-진실원이고-분석-INSERT-는-ClickHouse-fence-를-서버에서-다시-검사한다.md) ·
  [ADR 0065](0065-telemetry-token은-전용-RS256-JWT로-발급하고-telemetry_tokens를-자격-기록으로-유지한다.md) Decision 6 ·
  [ADR 0066](0066-OTLP-수신은-Gateway가-JWT만-검증해-Kafka에-접수하고-ingest가-RDB로-다시-검증한다.md) Decision 6 ·
  [ADR 0083](0083-Kafka-소비는-배치로-모아-쓰고-ClickHouse-장기-장애는-S3-넘김-목록으로-확정한다.md)
- [telemetry Kafka 운영](../telemetry-kafka-operations.md) §1.5 · [수집 운영 계측](../telemetry-observability.md)
