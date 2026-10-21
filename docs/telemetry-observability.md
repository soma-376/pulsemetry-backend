# 수집 운영 계측과 독립 smoke

PROJ-191의 Backend 구현이다. 설계 결정은 [ADR 0068](adr/0068-수집-운영-계측은-전송-계약과-분리하고-앱에서-조립한다.md), Kafka의 재시도·확정 정책은 [Kafka 운영](telemetry-kafka-operations.md)이 소유한다. Grafana·Prometheus·MSK·ClickHouse 서버 exporter·CloudWatch 로그 수집은 Infra 범위다. 이 코드와 로컬 테스트 통과가 AWS 배포 완료를 뜻하지 않는다.

## 관리 포트와 기본 binder

Gateway와 ingest는 `MANAGEMENT_SERVER_PORT`(기본 **9404**)의 `GET /actuator/prometheus`를 연다. 업무 포트(8082·4316)의 `/actuator`와 하위 경로는 `404 text/plain`이며 기존 `/api/v1/healthz` 의미는 같다. 관리 포트는 업무 요청·health·env·actuator index를 제공하지 않는다.

관리 포트의 그 밖 요청은 두 앱이 다르게 거부한다. Boot는 관리 컨텍스트에 부모의 Security 체인만 넘기고 서블릿 필터는 넘기지 않는다.

| 요청 | ingest | Gateway |
|---|---|---|
| `/actuator/prometheus` POST·PUT·DELETE | 404 `text/plain` (관리 Security 체인) | 405 (MVC, Boot 오류 JSON) |
| 그 밖 경로 | 404 `text/plain` | 404 (Boot 오류 JSON) |

업무 포트의 `/actuator`는 ingest가 기본 닫힘 체인, Gateway가 계약 밖 경로 매핑으로 같은 404 바이트를 낸다. `MetricsEndpointTest`가 두 앱의 응답을 고정한다. Prometheus 외 actuator를 exposure 목록에 추가하지 않는다. scrape는 내부 NetworkStack Security Group과 ClusterIP로 제한하고 ALB 경로에는 연결하지 않는다. 같은 호스트에서 앱 둘을 실행하면 관리 포트를 각각 다르게 정한다. 업무 포트와 같은 값은 기동을 거부한다.

공통 tag는 `application`, `service=gateway|ingest`, `environment=${PULSEMETRY_OBSERVABILITY_ENVIRONMENT:local}`다. Kubernetes의 cluster/namespace/pod/job/instance는 Infra target label이다. Infra scrape는 `service=telemetry-gateway|telemetry-ingest` target label을 쓰므로 충돌한 앱 service tag는 `exported_service`가 될 수 있다. Grafana/alert는 Infra의 service 값을 사용하고 raw 앱 scrape의 값과 구별한다. 사용자·tenant·설치·run·message·request ID, URL, exception message를 custom metric label에 넣지 않는다. `MicrometerTelemetryObserver`가 유한 목록으로 값을 다시 제한한다. 알려지지 않은 값은 `other`/`unknown`으로 합친다.

- Actuator: `jvm_*`, `process_*`, `system_*`, `http_server_requests_seconds_*`.
- Tomcat: `server.tomcat.mbeanregistry.enabled=true`로 `tomcat_threads_busy_threads`, `tomcat_threads_current_threads`, `tomcat_threads_config_max_threads` 등을 실제 노출한다. 업무 listener와 management listener를 name label로 구분한다.
- ingest RDB: Boot 관리 Hikari DataSource의 자동 binder가 `hikaricp_connections_*`, `jdbc_connections_*`를 노출한다. 같은 pool에 수동 binder를 중복 등록하지 않는다. Gateway에는 DB가 없다.
- Kafka: 수동 producer는 `KafkaClientMetrics`, 수동 consumer factory는 `MicrometerConsumerListener`를 명시 연결한다. client 종료 시 binder도 닫혀 meter가 해제되고, Consumer를 멈췄다 다시 띄우면 같은 `client.id`로 다시 등록된다(`consumerMetersFollowLifecycle`). `kafka_producer_*`, `kafka_consumer_*`를 native 값으로 쓴다. broker 지표와 group의 committed lag는 Infra exporter가 소유한다. consumer client lag는 commit 완료 증거가 아니다.
- JVM GC pause는 기본 sum/count/max를 쓴다. client percentile과 추가 GC histogram은 켜지 않았다. consumer의 `pulsemetry_pipeline_listener_running`은 container의 running/expected state이며 처리 성공이나 lag=0을 보장하지 않는다. http 모드에서는 0이다.

### 계측 on/off

`PULSEMETRY_OBSERVABILITY_ENABLED`(`pulsemetry.observability.enabled`, 기본 true)가 false면 두 앱이 `MicrometerTelemetryObserver` 대신 no-op observer를 조립한다. 꺼지는 것은 `pulsemetry_*` 업무 지표뿐이다. JVM·process·Tomcat·Hikari/JDBC·Kafka client binder와 관리 포트 scrape는 양쪽이 같다. ingest의 `pulsemetry_pipeline_listener_running`은 요청마다 기록하지 않는 상태 gauge라 꺼도 남는다. calibration 전에 저부하 on/off를 비교하고, 비교시험 전체에서 한 값으로 고정한다.

## 지표 의미

이름은 Prometheus에 노출되는 이름이다. Timer는 `_bucket`, `_count`, `_sum`, `_max`를 갖는다. 초 단위 일반 bucket은 0.005·0.01·0.025·0.05·0.1·0.25·0.5·1·2.5·5·10·30, 비동기 age bucket은 0.1·0.25·0.5·1·2.5·5·10·30·60·120·300·600이다. byte histogram은 1KiB·16KiB·64KiB·256KiB·1MiB·4MiB·10MiB다. 최초 사건 전 counter/timer series가 없을 수 있으므로 target 생존은 `up`/JVM 지표로 판단한다.

| 지표 | label | 의미 |
|---|---|---|
| `pulsemetry_gateway_requests_total` | signal,status,outcome | controller 처리 종료. outcome은 accepted(200), rejected(4xx), failed(그 밖) |
| `pulsemetry_gateway_request_duration_seconds` | signal,status | 요청 controller 진입부터 응답 처리 종료까지 |
| `pulsemetry_gateway_request_bytes` | signal | 제한 안에서 본문을 모두 읽은 요청의 전송 byte. decode 전이며 gzip은 압축 byte |
| `pulsemetry_gateway_inflight` | 없음 | controller 처리 중 요청 수. Gateway에만 있다 |
| `pulsemetry_gateway_rejections_total` | stage,reason | 인증·요청형태 거부의 고정 사유. 발행 실패는 publish counter |
| `pulsemetry_gateway_publish_total` | signal,outcome,reason | outcome=acknowledged는 producer future가 broker ack로 끝남. failed는 ack 확인 실패 |
| `pulsemetry_gateway_publish_duration_seconds` | signal,outcome | producer send/ack 대기 시간 |
| `pulsemetry_pipeline_attempts_total` | signal,outcome | Kafka listener 시도 하나. completed·permanent_rejected·transient_failed |
| `pulsemetry_pipeline_failures_total` | stage,reason | 영구/일시 처리 실패·컨테이너 실패 사건. attempts와 중복 의미가 있어 합산하지 않음 |
| `pulsemetry_pipeline_retry_exhausted_total` | stage | 재시도 예산 소진 recoverer 호출 |
| `pulsemetry_pipeline_commit_failures_total` | 없음 | offset commit 실패 callback |
| `pulsemetry_pipeline_stage_duration_seconds` | stage,outcome | authorize·decode(압축 해제 포함)·mask·archive·normalize·enrich·retention·insert·ops, success/failure |
| `pulsemetry_pipeline_accepted_to_attempt_start_seconds` | signal | Gateway acceptedAt부터 각 시도 시작까지의 age. 순수 broker 대기시간이 아님 |
| `pulsemetry_pipeline_accepted_to_completion_seconds` | signal | acceptedAt부터 성공한 시도 완료까지. backlog·재시도·처리시간 포함 |
| `pulsemetry_pipeline_inflight` | 없음 | 실행 중 listener 수. ingest에만 있다 |
| `pulsemetry_pipeline_empty_total` | signal | decode 후 레코드가 없는 push |
| `pulsemetry_pipeline_excluded_observations_total` | reason | source_time·excluded_span·retention 사유 제외 수. 재시도 때 다시 셀 수 있음 |
| `pulsemetry_pipeline_clock_skew_total` | 없음 | 음수 age를 버린 수. 음수를 0초 성공으로 기록하지 않음 |
| `pulsemetry_clickhouse_requests_total` | operation,table,outcome | HTTP 요청의 success·permanent_error·transient_error. operation은 insert/select/delete/schema, 분류하지 않은 호출만 other. table은 telemetry_events·telemetry_metric_points·telemetry_ingest_ledger·telemetry_retention_fence, 그 밖은 other |
| `pulsemetry_clickhouse_request_duration_seconds` | operation,table,outcome | ClickHouse HTTP 왕복 시간 |
| `pulsemetry_clickhouse_rows_submitted_total` | table | INSERT에 제출한 행. 실패·재시도·server retention fence를 포함하므로 실제 적재 수가 아님 |

`completed`에는 빈 push와 모든 관측이 제외된 push도 포함된다. 영구 거부는 정상 반환해 offset을 확정하지만 completed가 아니다. 소진한 일시 실패도 오류 처리기가 확정하므로 offset 전진만으로 처리 성공을 주장하지 않는다. commit 실패로 같은 record를 다시 처리하면 attempts와 rows submitted가 다시 증가할 수 있다. 두 분석 테이블과 ops 기록은 하나의 트랜잭션이 아니다.

Gateway의 **200은 Kafka 접수**, pipeline completed는 **시도 완료**, ClickHouse success는 **INSERT HTTP 성공**이다. 적재 완전성은 기대 `observation_id` 집합과 `FINAL` 논리 행을 별도 대조해야 한다. 현재 meter는 persisted/E2E counter를 만들지 않는다. 비동기 age는 Gateway와 ingest의 벽시계 차이에도 영향을 받는다. 순수 stage duration은 `System.nanoTime()`을 쓴다.

Observer 기본값은 no-op이며 `.observe`가 계측 RuntimeException을 격리한다. 성공 반환·원래 처리 예외·Kafka 재시도/offset 결정은 계측 실패로 바뀌지 않는다. 치명적인 VM Error를 삼키는 전역 예외 처리기는 추가하지 않았다.

## 장애 로그

기존 `TelemetryLogEvent`의 `event=<code>`와 필드 구조는 보존한다. JVM/Spring 기동·graceful shutdown·Kafka 재시도 로그도 보존한다. CH 스키마 시작/재적용 실패, RDB 인증 조회 장애, ops 백필 실패에는 component·reason·exception class·제한된 cause/stack frame을 남긴다. `SafeDiagnostic`은 exception message와 suppressed 원문을 제외하고 cause depth 8, 각 stack 8, 전체 4096자로 제한한다. payload·Bearer·자격증명 canary가 로그·metric tag에 새지 않는 테스트를 둔다.

이 로그의 목적은 서비스 기동·종료·의존성 실패 원인을 좁히는 것이다. trace ID, 새 message context, envelope header/schema 변경은 없다. MSK broker 로그, ClickHouse system 로그, GC/safepoint stderr, OOMKilled·exit code·restart·Kubernetes Event는 Infra의 CloudWatch 수집과 함께 확인한다. heap dump·stack dump를 자동 강제 생성하지 않는다. 앱 로그가 없는 SIGKILL/OOM도 플랫폼 증거로 판단한다.

### GC·safepoint 로그 (Infra 인계)

JVM 옵션이라 Spring 설정으로 켜지 않는다. 성능시험 values에서 Infra가 `JAVA_TOOL_OPTIONS`로 넣는다. 이미지 ENTRYPOINT의 `-XX:MaxRAMPercentage=75.0`은 그대로 두고 덧붙는다.

```text
JAVA_TOOL_OPTIONS=-Xlog:gc,safepoint:stderr:utctime,level,tags
```

stderr에 사건 하나가 한 줄이다. Temurin 25.0.4(런타임 이미지와 같은 메이저)에서 확인한 형식은 다음과 같다.

```text
Picked up JAVA_TOOL_OPTIONS: -Xlog:gc,safepoint:stderr:utctime,level,tags
[2026-10-21T18:27:05.621+0000][info][gc] Using G1
[2026-10-21T18:27:05.811+0000][info][gc] GC(0) Pause Young (Concurrent Start) (G1 Humongous Allocation) 85M->59M(128M) 1.482ms
[2026-10-21T18:27:05.811+0000][info][safepoint] Safepoint "G1TryInitiateConcMark", Time since last: 185400250 ns, Reaching safepoint: 1875 ns, At safepoint: 1554916 ns, Leaving safepoint: 2375 ns, Total: 1559166 ns, Threads: 0 runnable, 12 total
[2026-10-21T18:27:05.811+0000][info][gc       ] GC(1) Concurrent Mark Cycle
```

- tags 칸은 공백으로 폭을 맞출 수 있다(`[gc       ]`). parser는 값을 trim한다.
- 첫 `Using <GC>` 줄로 collector를 확인한다. CPU·메모리가 작은 컨테이너에서는 JVM이 G1 대신 Serial을 고를 수 있다.
- `gc*`(하위 태그 전부)·debug 수준은 켜지 않는다. 앱 로그와 같은 stderr라 Fluent Bit이 그대로 수집한다.
- calibration부터 비교시험 전체가 같은 옵션과 `PULSEMETRY_OBSERVABILITY_ENABLED` 값을 쓴다.

## 독립 smoke 실행

`scripts/observability-smoke/smoke.py`는 Python3 표준 라이브러리만 쓰고 미완성 k6 runner와 독립적이다. 실행자는 **유효한 telemetry JWT**, 그 JWT의 tenant/installation UUID, retention 범위 안 현재 source time, 접근 가능한 Gateway·각 metrics·ClickHouse endpoint를 준비한다. 인증/시드는 기존 [로컬 Kafka E2E](telemetry-kafka-e2e.md)를 따른다. 이 도구는 토큰·tenant를 발급하거나 인프라를 배포하지 않는다. JWT 파일은 제한된 권한으로 만들고 저장소 밖에 둔다.

아래 포트는 내부 endpoint에 별도로 port-forward한 예시다. 출처의 revision/image digest는 `--provenance`를 반복해 기록할 수 있다. 비밀을 넣지 않는다.

```bash
python3 scripts/observability-smoke/smoke.py \
  --gateway http://127.0.0.1:8082 \
  --gateway-metrics http://127.0.0.1:19404 \
  --ingest-metrics http://127.0.0.1:29404 \
  --clickhouse http://127.0.0.1:8123 \
  --tenant "$SMOKE_TENANT_ID" --installation "$SMOKE_INSTALLATION_ID" \
  --run-id "$SMOKE_RUN_ID" --source-time "$SMOKE_SOURCE_TIME" \
  --token-file "$SMOKE_TOKEN_FILE" --output "$SMOKE_NEW_OUTPUT_DIR" \
  --provenance "backend-revision=$SMOKE_BACKEND_REVISION"
```

`SMOKE_RUN_ID`는 새 UUID, `SMOKE_SOURCE_TIME`은 timezone이 있는 ISO8601(예: 해당 실행의 UTC 현재 시각)이다. 같은 run ID·시각은 바이트가 같고 다른 실행에는 새 run ID를 쓴다. 선택적으로 `PULSEMETRY_SMOKE_CLICKHOUSE_USER`/`PULSEMETRY_SMOKE_CLICKHOUSE_PASSWORD` 환경변수로 CH 읽기 자격을 넘긴다. endpoint URL에 userinfo/query/fragment를 넣거나 redirect를 따르지 않는다.

실행 순서는 다음과 같다.

1. 양 앱 Prometheus scrape를 저장하고 JVM/custom 지표가 실제 존재하는지 본다.
2. 동일 run의 기존 `FINAL` 행이 없음을 확인한다. 중복 run이면 전송하지 않는다.
3. Claude Code 2.1.290 `api_request` 한 건을 `/v1/logs`에 한 번 전송한다. input1066/output28이며 프롬프트 원문은 없다.
4. tenant·installation·session·request로 범위를 좁혀 `telemetry_events FINAL`을 polling한다. 정확한 기대 fingerprint `observation_id` **1개**, event type, 토큰 값, archive_ref를 대조한다. 기대 ID는 이 고정 fixture에 한정된 id-v1/stamp-v1 oracle이며 임의 OTLP canonicalizer가 아니다.
5. 사후 scrape와 증거를 저장한다. 출력 디렉터리는 새로 만들며 덮어쓰지 않는다. `payload.json`, 전후 `.prom`, `evidence.json`에 payload SHA256·기대 ID·UTC 시작/HTTP200 관측/논리 행 관측/종료·provenance가 남는다. HTTP accepted 시각은 클라이언트가200을 본 시각이지 내부 Gateway acceptedAt이 아니다.

HTTP200과 논리 행 일치가 모두 있어야 종료코드0이다. 200만 받고 행이 없거나, 예상 ID/값 불일치, 중복 논리 행, transport timeout이면 실패/미확정이다. 모호한 응답은 자동 재전송하지 않고 readback 증거를 남긴다. 실행 실패 시 `phase`, `http_status`, `logical_rows`를 함께 확인한다. 서버 오류 본문과 토큰은 증거에 기록하지 않는다. 한 건의 성공은 처리량/SLO·3개 신호 전체·MSK 장애복구·운영 전체 완전성의 증명이 아니다.

## 로컬 검증과 남은 확인

```bash
./gradlew :apps:telemetry-gateway:test :apps:telemetry-ingest:test \
  :libs:telemetry-observability:test :libs:telemetry-collector:test :libs:telemetry-persistence:test
python3 -m unittest discover -s scripts/observability-smoke -p 'test_*.py'
```

테스트는 실제 분리한 **random management 포트**에서 scrape, 업무 포트 actuator 차단과 기존404 바이트 계약을 확인한다. JVM·Tomcat·Hikari/JDBC·Kafka binder 노출을 확인하고 실제 Kafka/PostgreSQL/ClickHouse에서 completed/empty/소진4회/commit 실패·재전달과 smoke 논리 행을 검증한다. Kafka 경로에서 logs·traces·metrics 세 signal이 기대 `observation_id` 집합과 같은 `FINAL` 논리 행이 되는지(`allSignalsPersistExpectedLogicalRows`), 분석·ledger INSERT 뒤 실패한 시도를 다시 처리하면 `rows_submitted`는 두 번 오르고 논리 행은 하나인지(`retryAfterPartialWriteSubmitsTwiceButStoresOnce`) 본다. Gateway를 거친 3 signal 관통은 실환경 smoke의 몫이다. no-op/실패 observer 반환·재시도 경계, 유한 label, canary, 음수 age도 검증한다. Python fixture ID와 JVM canonical ID를 교차검증한다.

재생성되는 fixture는 `libs/telemetry-observability/build/metrics-contract.prom`(실제 Prometheus registry에서 시나리오를 주입한 계약 fixture), `apps/telemetry-{gateway,ingest}/build/management-scrape.prom`(로컬 실제 앱 HTTP scrape)다. 로컬 client는 Kafka4.2.1, broker Testcontainers3.9.2이며 운영 MSK와 동일하다고 주장하지 않는다. 실제 배포9404·MSK IAM·내부 Security Group/ClusterIP·CloudWatch/GC/OOM 로그 도착·Grafana query와 alert·standalone smoke의 배포 endpoint 실행은 별도 인수 확인이다. 기존 `telemetry-kafka-e2e/run.sh` verifier는 유지했고 관리 포트 충돌만 피하도록39416/39482를 지정했다.
