# telemetry Gateway·Kafka 수집 운영

OTLP 를 Gateway 가 받아 Kafka 에 접수하고 ingest Consumer 가 처리하는 경로([ADR 0066](adr/0066-OTLP-수신은-Gateway가-JWT만-검증해-Kafka에-접수하고-ingest가-RDB로-다시-검증한다.md))와
telemetry JWT([ADR 0065](adr/0065-telemetry-token은-전용-RS256-JWT로-발급하고-telemetry_tokens를-자격-기록으로-유지한다.md))의 **앱 쪽** 설정과 절차다.
dev Strimzi mTLS 구현은 [ADR 0070](adr/0070-Kafka-mTLS-접속은-공통-설정에서-PKCS12-파일로-조립한다.md), 배치·접근 계약은 [허브 Kafka 계약](../../docs/contracts/telemetry-kafka.md)을 따른다.
AWS 리소스·Helm·ALB 전환·offset 재설정 Job은 infra가 소유한다. 현재 dev는 Strimzi mTLS 경로이며,
배포·검증 범위는 [허브 Kafka 계약](../../docs/contracts/telemetry-kafka.md)과
[infra Strimzi 런북](../../infra/docs/runbooks/dev-strimzi-kafka.md)을 따른다.
아래 최초 JWT·Kafka 전환 절차와 IAM 호환 설정은 현재 dev가 MSK를 사용한다는 뜻이 아니다.
로컬 E2E 결과는 [telemetry-kafka-e2e.md](telemetry-kafka-e2e.md)에 있다.

## GitOps 이미지 인계 상태

[ADR 0075](adr/0075-dev-앱-이미지는-검증한-묶음으로-GitOps에-인계한다.md)는 enrollment·ingest·Gateway·dashboard 네 앱을 같은 backend SHA·run·attempt의
완료 묶음으로 공급하는 Accepted 설계다. manifest·승격 구현과 Argo 소유권 인계 전에는 기존 digest 기반
수동 Helm 운영을 유지한다. 현재 dev의 Strimzi mTLS 배포 상태와 이 GitOps 도입 상태는 별개다.
인계 후 일반 앱 변경은 enrollment의 Flyway 성공 → ingest → Gateway → dashboard 순서를 따르며,
아래 최초 JWT 발급·ALB 전환 절차를 이미지 변경마다 반복하지 않는다.
한 desired commit도 앱 간 구·신 버전 혼합을 없애지 않으므로 JWT·키링·ingress envelope·backlog·
[정규화 계약 ADR 0074](adr/0074-producer-버전-대신-이벤트-계약으로-정규화한다.md)의 호환성을 유지한다.

```
데몬 ─Bearer JWT→ ALB ─→ :apps:telemetry-gateway(8082) ─envelope→ Kafka topic ─→ :apps:telemetry-ingest Consumer ─→ 수집 코어 → S3·ClickHouse
                           JWT 서명·claim 만 검증                                   jti 로 RDB 재판정(매 시도)
```

## 1. 설정

배포는 아래 환경변수와 `SPRING_APPLICATION_JSON` 으로 넣는다. 표에 환경변수가 없는 키는 YAML 자리표시자가 없다 — 기본값을 쓰고,
바꿀 때는 Spring relaxed binding(예: `PULSEMETRY_TELEMETRY_GATEWAY_REQUESTDEADLINE`)으로 넣는다. 검증 위반은 모두 **기동 실패**다.

### 1.1 Kafka 접속 — Gateway·ingest 공통

| 키 | 환경변수 | 기본값 | 검증 |
|---|---|---|---|
| `pulsemetry.telemetry.kafka.bootstrap-servers` | `PULSEMETRY_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `host:port[,host:port…]`. MSK IAM은 9098, dev Strimzi 내부 TLS는 9093 |
| `pulsemetry.telemetry.kafka.security` | `PULSEMETRY_KAFKA_SECURITY` | `plaintext` | `plaintext`·`iam`·`mtls`만 |
| `pulsemetry.telemetry.kafka.topic` | `PULSEMETRY_KAFKA_TOPIC` | `pulsemetry-local-telemetry-ingress` | 비어 있으면 실패. dev 는 `pulsemetry-dev-telemetry-ingress-<stage>` |
| `pulsemetry.telemetry.kafka.consumer-group` (ingest) | `PULSEMETRY_KAFKA_CONSUMER_GROUP` | `pulsemetry-local-telemetry-ingest` | 비어 있으면 실패. dev 는 `pulsemetry-dev-telemetry-ingest-<stage>` |
| `pulsemetry.telemetry.kafka.iam.role-arn` | `PULSEMETRY_KAFKA_IAM_ROLE_ARN` | 빈 값 | `iam` 이면 IAM 역할 ARN 형식 필수 |
| `pulsemetry.telemetry.kafka.iam.session-name` | `PULSEMETRY_KAFKA_IAM_SESSION_NAME` | 빈 값 | `iam` 이면 `[A-Za-z0-9+=,.@_-]{2,64}` 필수. **고정값** — Pod 마다 바꾸지 않는다 |
| `pulsemetry.telemetry.kafka.iam.sts-region` | — | `ap-northeast-2` | 리전 형식 |
| `pulsemetry.telemetry.kafka.tls.truststore-location` | `PULSEMETRY_KAFKA_TLS_TRUSTSTORE_LOCATION` | 빈 값 | `mtls`에서 필수. `/mnt/kafka-tls/cluster/ca.p12` |
| `pulsemetry.telemetry.kafka.tls.truststore-password-file` | `PULSEMETRY_KAFKA_TLS_TRUSTSTORE_PASSWORD_FILE` | 빈 값 | `mtls`에서 필수. `/mnt/kafka-tls/cluster/ca.password` |
| `pulsemetry.telemetry.kafka.tls.keystore-location` | `PULSEMETRY_KAFKA_TLS_KEYSTORE_LOCATION` | 빈 값 | `mtls`에서 필수. `/mnt/kafka-tls/user/user.p12` |
| `pulsemetry.telemetry.kafka.tls.keystore-password-file` | `PULSEMETRY_KAFKA_TLS_KEYSTORE_PASSWORD_FILE` | 빈 값 | `mtls`에서 필수. `/mnt/kafka-tls/user/user.password` |

`iam` 은 `SASL_SSL` + `AWS_MSK_IAM`, JAAS `IAMLoginModule required awsRoleArn=… awsRoleSessionName=… awsStsRegion=…`, 콜백 `IAMClientCallbackHandler` 다.
자격은 기본 공급자 체인(Pod Identity)으로 얻어 전용 MSK 역할을 assume 한다(ADR 0066 Decision 9). 장기 access key 를 넣지 않는다.
Boot 의 Kafka 자동설정은 쓰지 않으므로 `spring.kafka.*` 는 효과가 없다.

`mtls`는 `security.protocol=SSL`, PKCS12 truststore·keystore, `ssl.endpoint.identification.algorithm=https`를 사용한다.
비밀번호 원문은 환경변수·Helm values·로그로 전달하지 않는다. 파일 경로만 설정하며 앱이 client 생성 시 UTF-8 비밀번호를 읽는다.
비밀번호 파일 내용은 개행 제거 없이 그대로 사용하므로 운영 도구로 저장할 때 임의의 끝 개행을 붙이지 않는다.
네 파일은 절대 경로의 읽기 가능한 비어 있지 않은 파일이어야 한다. 비밀번호 파일은 4 KiB, PKCS12 파일은 1 MiB 이하로 제한한다.
PKCS12 비밀번호·유효한 신뢰 인증서·사용자 개인키와 인증서 유효기간을 기동 시 검증한다. 잘못된 파일은 원문/cause를 숨긴 설정 오류로 거절한다.
CA 교체 기간의 이전 인증서는 허용하되 truststore에 현재 유효한 신뢰 인증서가 하나 이상 있어야 한다.

Strimzi의 `<cluster>-cluster-ca-cert` Secret에서 `ca.p12`·`ca.password`, 각 앱의 KafkaUser Secret에서 `user.p12`·`user.password`만
읽기 전용으로 mount한다. 두 앱은 다른 KafkaUser를 사용하며 CA 개인키와 다른 앱의 사용자 키는 받지 않는다. mTLS 모드에서는
IAM 역할·세션·AWS callback을 사용하지 않는다. IAM·plaintext 설정은 호환용으로 남지만 접속 실패 시 자동 전환하지 않는다.

인증서는 자동 재로딩하지 않는다. Strimzi 갱신 후 infra가 CA/user Secret revision을 읽어 TLS revision annotation을 바꾸고
같은 image digest로 재배포한다. GitOps 인계 전에는 수동 Helm, 인계 후에는 허브 계약의 values 검증·desired 승격 경로를 따른다.
Secret 원문이나 비밀번호를 Git으로 옮기지 않는다. 현재 dev의 단일 replica·Recreate에서 재시작 중 중단이 있다.
인증서 만료 전 이 절차를 수행하고 재배포 뒤 실제 publish ACK→consume→commit을 검증한다.
readiness만으로 TLS 연결 성공을 판정하지 않는다.

### 1.2 telemetry JWT 키

| 키 | 앱 | 기본값 | 검증 |
|---|---|---|---|
| `pulsemetry.telemetry-jwt.public-key-files.<kid>` | enrollment·Gateway·ingest | 빈 맵 | kid → X.509 PEM 경로. RSA 2048비트 이상. **Gateway 는 비면 실패**, ingest(`http`·`dual`)는 비면 JWT 형태를 401 로 받는다, ingest(`kafka`)는 읽지 않는다 |
| `pulsemetry.telemetry-jwt.issue-enabled` | enrollment | `false` (`PULSEMETRY_TELEMETRY_JWT_ISSUE_ENABLED`) | 꺼져 있으면 `ptt_` 를 발급하고 키를 읽지 않는다 |
| `pulsemetry.telemetry-jwt.active-kid` | enrollment | 빈 값 (`PULSEMETRY_TELEMETRY_JWT_ACTIVE_KID`) | 켜져 있으면 공개키 맵에 있어야 하고 개인키와 modulus 가 같아야 한다 |
| `pulsemetry.telemetry-jwt.private-key-file` | enrollment | `/mnt/telemetry-jwt/keys/private.pem` (`PULSEMETRY_TELEMETRY_JWT_PRIVATE_KEY_FILE`) | PKCS8 PEM, RSA 2048비트 이상 |
| `pulsemetry.telemetry-jwt.lifetime` | enrollment | `PT3H` | 60초 이상 3시간 이하. **운영은 설정하지 않는다** — 로컬 E2E 의 만료 재발급 확인용이다 |

공개키 맵은 `SPRING_APPLICATION_JSON` 으로 넣는다 — 환경변수 이름은 kid 의 대소문자를 잃는다. kid 에 `.` 이 있으면 대괄호로 감싼다:
`{"pulsemetry":{"telemetry-jwt":{"public-key-files":{"[tk-2026-10]":"/mnt/telemetry-jwt/keys/tk-2026-10.pem"}}}}`.
사용자 JWT(`pulsemetry.user-auth.*`)와 키·파일을 공유하지 않는다.

**설정이 아닌 고정값**(`TelemetryJwtContract`): `typ=pulsemetry-telemetry+jwt`, `iss=pulsemetry-enrollment`, `aud=pulsemetry-telemetry-ingest`,
`scope=telemetry:write`, 수명 상한 3시간, 시계 오차 30초, Bearer 최대 8192자. `pulsemetry.telemetry-jwt.issuer`·`.audience` 를 넣어도 앱은 읽지 않는다.

### 1.3 Gateway

| 키 | 환경변수 | 기본값 | 검증·의미 |
|---|---|---|---|
| `server.port` | `PULSEMETRY_GATEWAY_PORT` | `8082` | |
| `server.tomcat.threads.max` | — | `16` | 동시 처리 상한. 메모리 예산(§7)이 이 값에 비례한다 |
| `spring.lifecycle.timeout-per-shutdown-phase` | — | `30s` | graceful 종료 상한. Pod grace 60초 안 |
| `pulsemetry.telemetry.gateway.max-request-bytes` | — | `10485760` | 압축 전 원본 상한, 넘으면 413 |
| `pulsemetry.telemetry.gateway.request-deadline` | — | `7500ms` | 요청 시작 기준 단일 기한. **10초 미만**이고 `max-block + delivery-timeout` 이상 |
| `pulsemetry.telemetry.gateway.retry-after` | — | `1s` | 503 의 `Retry-After` |
| `pulsemetry.telemetry.kafka.producer.max-block` | — | `2000ms` | `max.block.ms` (metadata·버퍼 대기) |
| `pulsemetry.telemetry.kafka.producer.request-timeout` | — | `4000ms` | `request.timeout.ms` |
| `pulsemetry.telemetry.kafka.producer.delivery-timeout` | — | `5000ms` | `delivery.timeout.ms`. request-timeout 이상 |
| `pulsemetry.telemetry.kafka.producer.buffer-memory` | — | `64MB` | `buffer.memory` |
| `pulsemetry.telemetry.kafka.producer.max-request-size` | — | `12MB` | `max.request.size`. 본문 상한보다 커야 한다 |
| `pulsemetry.telemetry.kafka.producer.close-timeout` | — | `10s` | 종료 때 남은 발행 대기 |

고정: `acks=all`, idempotence, `max.in.flight.requests.per.connection=5`, `linger.ms=5`, 압축 없음, client id `pulsemetry-telemetry-gateway`,
record key `tenant_id:installation_id`. Gateway 에는 DB·HMAC·S3·ClickHouse 설정이 없다.

### 1.4 ingest

| 키 | 환경변수 | 기본값 | 검증·의미 |
|---|---|---|---|
| `pulsemetry.telemetry.ingest.mode` | `PULSEMETRY_INGEST_MODE` | `http` | `http`·`dual`·`kafka` 외에는 실패 |
| `pulsemetry.token-hash-secret` | `PULSEMETRY_TOKEN_HASH_SECRET` | 없음 | `http`·`dual` 에서 필수, `kafka` 는 쓰지 않는다 |
| `pulsemetry.telemetry.kafka.consumer.retry-intervals` | — | `1s,5s,30s` | 양수 목록. 최초 포함 시도 수 = 목록 길이 + 1 |
| `pulsemetry.telemetry.kafka.consumer.max-poll-records` | — | `5` | 1 이상 |
| `pulsemetry.telemetry.kafka.consumer.max-poll-interval` | — | `600s` | 가장 긴 재시도 간격보다 길어야 한다 |
| `pulsemetry.telemetry.kafka.consumer.fetch-max-bytes` | — | `13MB` | `fetch.max.bytes`·`max.partition.fetch.bytes` |
| `pulsemetry.telemetry.kafka.consumer.shutdown-timeout` | — | `45s` | 컨테이너 종료 대기. Pod grace 90초 안 |
| `pulsemetry.telemetry.kafka.consumer.auth-exception-retry-interval` | — | `10s` | Kafka 인증·인가 실패에서 멈추지 않고 다시 시도 |
| `pulsemetry.telemetry.kafka.consumer.concurrency` | — | `1` | listener 스레드 수. 메모리(§7)가 비례한다 |
| `spring.lifecycle.timeout-per-shutdown-phase` | — | `50s` | 컨테이너 종료(45초)보다 길고 Pod grace 안 |

고정: auto commit 끔, `AckMode.RECORD`, 동기 commit, `auto.offset.reset=earliest`, `allow.auto.create.topics=false`(topic 은 infra 가 만든다),
`DefaultErrorHandler` + 기본 sleep backoff + `resetStateOnExceptionChange=false`, `stopImmediate=true`, client id `pulsemetry-telemetry-ingest`.

모드별 동작:

| 모드 | OTLP HTTP(`/v1/*`) | Consumer | 필요한 비밀 |
|---|---|---|---|
| `http` | `ptt_` + JWT(공개키가 있을 때) | 빈만 있고 시작하지 않는다(broker 접속 없음) | HMAC 키, (선택) 공개키 |
| `dual` | 같음 | 시작 | HMAC 키, (선택) 공개키, Kafka 접속 자격 |
| `kafka` | 닫힘(404) | 시작 | Kafka 접속 자격 |

health 는 모든 모드에서 `GET /api/v1/healthz` 다. `dual`·`kafka` 에서 listener 컨테이너가 멈춰 있으면 503 `listener_stopped` 다
(Kafka 연결·인증 장애로는 멈추지 않는다). 복구는 재기동이다 — infra chart 는 `dual`·`kafka` ingest 의 readiness 와 liveness 를 모두
이 경로로 보므로(infra ADR-0036), 멈춘 listener 는 약 30초 뒤 Pod 재시작으로 이어진다. 이 health 가 저장소·Kafka 를 보지 않는 것이 그 전제다.
Gateway 의 health 는 Kafka 를 보지 않는다.

## 2. 로그 이벤트

한 줄 평문 `event=<code> key=value …`(`TelemetryLogEvent`). infra 가 CloudWatch Logs metric filter 의 term match 로 센다 — **코드 문자열을 바꾸면 경보가 멈춘다.**
값은 `[A-Za-z0-9._:/@-]` 밖의 문자를 `_` 로 바꾸고 128자로 자른다. payload·Bearer·예외 메시지 원문은 남기지 않고, 원인은 예외 클래스 이름(`cause`)만 남긴다.

| 코드 | 앱 | 의미 | 필드 | 경보 |
|---|---|---|---|---|
| `telemetry_gateway_rejected` | Gateway | 401·405·404·415·400·413 응답 | `stage`(`authenticate`·`request`) `reason` `status` | 비율 관찰 |
| `telemetry_publish_failed` | Gateway | 기한 안에 broker ack 없음 → 503 | `messageId` `topic` `stage=publish` `reason` `attempt=1` `cause` | 예 |
| `telemetry_permanent_failure` | ingest | 영구 오류로 확정(미적재) | `messageId` `topic` `partition` `offset` `stage`(`envelope`·`authorize`·`decode`·`pipeline`) `reason` `attempt` | 예 |
| `telemetry_transient_failure` | ingest | 한 시도의 일시 실패. 다음 시도가 남음 | `messageId` `topic` `partition` `offset` `stage` `reason=unavailable` `attempt` `cause` | 아니오 |
| `telemetry_retry_exhausted` | ingest | 최초 + 3회 모두 실패 후 확정(미적재) | `messageId` `topic` `partition` `offset` `stage` `reason` `attempt=4` `cause` | 예 |
| `telemetry_commit_failed` | ingest | offset commit 실패. 그 record 는 다시 처리된다 | `topic` `partition` `offset` `stage=commit` `reason=commit_failed` `attempt` `cause` | 예 |

`reason` 값: Gateway 401 은 `TelemetryJwtRejection` 코드(`missing_bearer`·`malformed_bearer`·`jwt_malformed`·`jwt_algorithm`·`jwt_type`·`jwt_header`·`jwt_kid_unknown`·
`jwt_signature`·`jwt_issuer`·`jwt_audience`·`jwt_scope`·`jwt_claim_missing`·`jwt_claim_format`·`jwt_lifetime`·`jwt_not_yet_valid`·`jwt_expired`), 요청 거부는
`method_not_allowed`·`path_unknown`·`unsupported_media_type`·`unsupported_content_encoding`·`payload_too_large`, 발행 실패는 `deadline_exceeded`·`delivery_timeout`·
`metadata_timeout`·`record_too_large`·`not_enough_replicas`·`authorization_failed`·`producer_closed`·`interrupted`·`broker_error`.
Consumer 의 `authorize` 거부는 `credential_unknown`·`credential_format`·`credential_mismatch`·`credential_expired`·`credential_rotated`·`credential_security_revoked`·
`credential_revoked_unknown`과 설치·회원·조직 차단 코드(`installation_revoked` 등), `envelope` 거부는 `envelope_*` 결함 코드다.

## 3. 전환 순서와 복구

**순서가 계약이다.** 아래 제약을 어기면 데몬이 401 → 재발급 → 401 을 되풀이하고, 데몬은 재발급 후 한 번만 재전송하므로 그 사이 데이터는 버려진다.

1. **V33 과 새 이미지.** enrollment(발급 꺼짐)가 V33 을 적용한다. ingest 를 새 이미지 `http` 모드로 올린다 — 지금과 같다.
2. **공개키 선배포.** Gateway·ingest(`http`·`dual`)·enrollment 에 같은 공개키 맵을 넣는다. 공개키 없이 발급을 켜면 그 토큰이 모든 검증자에서 401 이다.
3. **ingest `dual`, Gateway 기동.** Consumer 의 group 가입(`partitions assigned` 로그)과 두 앱의 health 200 을 확인한다.
4. **발급 전환.** enrollment 에 개인키·활성 kid 와 `issue-enabled=true`. 이후 enroll·재발급은 JWT 다. 이미 나간 `ptt_` 는 HTTP ingest 에서 계속 통과한다.
5. **ALB 대상을 Gateway 로.** 데몬의 `ptt_` 는 Gateway 에서 401(`jwt_malformed`)을 받고 재발급한 JWT 로 재전송한다.
   **4 보다 먼저 하지 않는다** — 재발급이 `ptt_` 를 주면 Gateway 에서 다시 401 이다.
6. **ingest `kafka`.** 안정화 뒤 OTLP HTTP 를 닫는다. ALB 대상이 Gateway 인 동안에만 한다(infra 생성기가 순서를 강제한다).

복구(자동 fallback 없음):

- **Gateway·Kafka 장애**: 현재 main 입력으로 ingest `dual`과 TGB를 먼저 복구한다(HMAC 키·공개키 필요).
  직접 Service·NodePort 응답, Pod Ready와 target 등록을 확인한 뒤 ALB 대상을 ingest로 바꾸고 실제 healthy를 확인한다.
  데몬의 JWT는 HTTP ingest가 같은 공개키와 RDB로 검증한다. Consumer는 같은 group으로 backlog를 계속 소비한다.
  현재 Strimzi profile을 유지하며 과거 MSK 자원을 복원하지 않는다. 실제 절차는 infra Strimzi 런북을 따른다.
- **발급 끄기**(`issue-enabled=false`): ALB 대상이 ingest 일 때만 한다. 이미 나간 JWT 는 만료(최대 3시간)까지 HTTP ingest·Gateway 에서 유효하다.
- **ingest 를 PROJ-245 이전 이미지로**: 그 이미지는 Bearer 전문의 HMAC 으로 조회한다. JWT 행도 `token_hash` 를 저장하므로 통과한다 —
  단 그 이미지는 `expires_at` 을 보지 않아 JWT 가 회전·폐기 전까지 유효하다(ADR 0065 Negative). `kafka` 모드에서 되돌릴 때는 먼저 ALB 대상을 ingest 로 옮긴다.
- **enrollment 를 이전 이미지로**: 그 이미지는 `ptt_` 를 발급하고, 회전 UPDATE 가 사유 없이 `revoked_at` 만 쓴다. 그렇게 회전된 JWT 행은 Consumer 가
  `credential_revoked_unknown` 으로 거부하므로 Kafka 에 대기 중인 그 토큰의 record 가 미적재될 수 있다. V33 은 추가형이라 되돌리지 않는다.

GitOps 인계 후의 **일반 이미지 rollback**은 [ADR 0075](adr/0075-dev-앱-이미지는-검증한-묶음으로-GitOps에-인계한다.md)를 따른다.
infra가 승격기·Updater·자동 sync를 중지하고 이전 검증 묶음의 네 digest를 현재 DB 스키마·키링·TLS revision·runtime과
대조해 복구 desired를 만든다. 위 최초 전환 이전 이미지의 예외를 자동 rollback 허용 근거로 쓰지 않는다.
DB·PVC·Kafka offset과 과거 values를 함께 되돌리지 않으며, backlog 호환과 ACK→consume→commit·S3/ClickHouse를
검증한 뒤 desired·실제 상태의 일치를 확인해 자동화를 재개한다.

## 4. 키 회전

**정상 회전**(ADR 0065 Decision 8):

1. 새 키쌍을 등록하고 새 공개키를 **모든 검증자**(Gateway·ingest·enrollment)의 맵에 더한다. 구 키는 그대로 둔다.
2. enrollment 의 `active-kid`·개인키를 새 키로 바꾼다. enrollment 는 기동 때 새 kid 의 공개키·개인키 짝을 확인한다.
3. **마지막 구 키 발급 시각 + 3시간 30초**가 지난 뒤 구 공개키를 맵에서 뺀다. 이미 Kafka 에 접수된 record 는 서명을 다시 보지 않으므로 영향이 없다.

**키 유출**:

1. 새 키를 1·2 와 같이 넣는다.
2. 유출된 kid 를 모든 검증자의 맵에서 **즉시** 뺀다. 그 kid 의 토큰은 Gateway·HTTP ingest 에서 401(`jwt_kid_unknown`)이 되고 데몬이 새 kid 로 재발급받는다.
3. 이미 접수된 record 와 위조 토큰을 Consumer 에서 막기 위해 그 kid 로 발급한 자격을 전부 보안 폐기한다(§5의 kid 단위 SQL).

## 5. 보안 폐기 SQL

관리 API 는 없다. 운영 DB 에서 아래 문장을 실행한다. 모두 재실행에 안전하고(`COALESCE`), 이미 회전된 행에도 적용된다 — 폐기 시각은 그대로 두고 사유만 `security` 로 바꾼다.
`revoked_at` 이 채워지므로 구 ingest 이미지의 해시 조회도 거부한다. 문장은 `TelemetryCredentialRecordTest` 가 같은 형태로 덮는다.

```sql
-- 설치 단위 보안 폐기 — 그 설치의 모든 telemetry token
UPDATE enrollment.telemetry_tokens
   SET revoked_at = COALESCE(revoked_at, now()), revoke_reason = 'security'
 WHERE installation_id = :installation_id;

-- 토큰 하나 (jti = telemetry_tokens.id)
UPDATE enrollment.telemetry_tokens
   SET revoked_at = COALESCE(revoked_at, now()), revoke_reason = 'security'
 WHERE id = :token_id;

-- 서명 키 단위 (키 유출, §4)
UPDATE enrollment.telemetry_tokens
   SET revoked_at = COALESCE(revoked_at, now()), revoke_reason = 'security'
 WHERE signing_kid = :kid;
```

효과와 한계:

- **Gateway 는 RDB 를 보지 않는다.** 폐기한 JWT 도 만료 전이면 Gateway 가 200 으로 접수하고 Consumer 가 `credential_security_revoked` 로 거부한다(미적재).
- **토큰 폐기는 재발급을 막지 않는다.** 데몬은 401 을 받으면 `installation_token` 으로 새 토큰을 받는다. 기기·`installation_token` 이 의심되면 설치를 폐기한다 —
  재발급이 403 `installation_revoked` 가 되고, Consumer 는 처리 시점의 설치 상태로 판정하므로 대기 중인 record 도 거부한다.

```sql
UPDATE enrollment.installations
   SET status = 'revoked', revoked_at = COALESCE(revoked_at, now()), updated_at = now()
 WHERE id = :installation_id;
```

## 6. 수동 재처리와 중복

재처리는 infra 런북의 offset 재설정 절차(같은 group 의 Consumer 를 멈추고 partition·offset 또는 시각으로 되돌린 뒤 다시 띄운다)를 따른다.
**72시간 보존 안의 record 만 가능하다.** DLT·실패 저장소·자동 재처리는 없다(ADR 0066 Alternative D).

재처리된 record 의 성질:

- **신원·시각은 envelope 의 것이다.** receipt ID 는 message ID, receivedAt 은 Gateway 접수 시각이다. 그래서 observation ID·`row_version` 이 처음 처리와 같고
  분석 행(`telemetry_events`·`telemetry_metric_points`)은 `FINAL` 에서 한 행으로 수렴한다.
- **RDB 판정은 다시 한다.** 접수 시각 기준이므로 그 뒤의 정상 회전·만료는 통과하고, 보안 폐기·설치·회원·조직 차단은 처리 시점 상태로 거부한다.
- 수신 ledger(켜져 있을 때)도 receipt·수신 시각이 같아 `FINAL` 에서 수렴한다.
- **늘어나는 것**: 아카이브 객체(S3 key 에 시도마다 새 UUID 가 붙는다 — 재시도·재전달마다 객체가 하나씩 는다)와 `event=` 로그.
  재처리 범위를 넓게 잡을수록 이 중복이 커진다.
- 재시도 소진(`telemetry_retry_exhausted`)·영구 실패(`telemetry_permanent_failure`) 로그의 `topic`·`partition`·`offset` 이 재처리 범위의 근거다.
  영구 실패는 원인이 고쳐지지 않으면 재처리해도 같은 결과다.
- Gateway 기한(7.5초)을 넘겨 503 을 받은 요청은 실제로는 발행됐을 수 있고 데몬이 재전송한다. 이 중복은 message ID 가 달라 새 receipt 다 —
  ledger 행과 아카이브 객체가 늘고, 분석 행만 observation ID 로 수렴한다.

## 7. 메모리 산정 근거

실측이 아니라 상한 추정이다. 이미지 JVM 옵션은 `-XX:MaxRAMPercentage=75.0` 이다. 실제 사용량은 배포 뒤 관측으로 확인한다.

**Gateway** — 요청 하나가 쥐는 것: 본문 배열(최대 10 MiB, `readNBytes` 의 읽는 동안 사본 포함 최대 약 20 MiB), 직렬화한 envelope(본문 + 수백 바이트,
protobuf 는 본문을 `unsafeWrap` 해 다시 복사하지 않는다). producer 는 record 를 `buffer.memory`(64 MiB) 풀에 복사한다.

| 항목 | 상한 |
|---|---|
| 요청 16개 × (본문 10 + 읽기 사본 10 + envelope 10 MiB) | 약 480 MiB |
| producer `buffer.memory` | 64 MiB |
| Spring·Tomcat·kafka-clients 기본 | 약 150~200 MiB |
| 합 | 약 750 MiB → heap 1 GiB 이상(컨테이너 한도 1.5 GiB 이상) |

동시 처리(`server.tomcat.threads.max`)를 늘리면 요청당 약 30 MiB 씩 는다. `buffer.memory` 가 차면 `send()` 가 `max.block.ms`(2초) 동안 기다리다 503 이다.

**ingest Consumer** — fetch 버퍼는 broker 마다 최대 13 MiB(MSK 3대면 약 39 MiB), poll 한 번에 record 최대 5건(최대 5 × 12 MiB).
record 하나의 처리는 기존 HTTP 경로와 같다 — 압축 해제 상한 20 MiB 와 디코딩·마스킹·정규화 객체가 그 몇 배다. `concurrency=1` 이면 동시에 하나만 처리한다.
`dual` 모드는 기존 HTTP 경로의 메모리가 그대로 더해진다. `concurrency` 를 올리면 record 처리 메모리가 그 배수로 는다.

## 8. 로컬

`docker compose up -d` 가 `kafka`(KRaft, `localhost:9092`, 자동 topic 생성 꺼짐, message 13 MiB)와 `kafka-init`(topic 생성)을 띄운다.

```sh
./gradlew :apps:telemetry-gateway:bootRun --args='--spring.application.json={"pulsemetry":{"telemetry-jwt":{"public-key-files":{"local":"/path/public.pem"}}}}'
PULSEMETRY_TOKEN_HASH_SECRET=local-development-token-hash-secret PULSEMETRY_INGEST_MODE=dual ./gradlew :apps:telemetry-ingest:bootRun
```

수정하지 않은 telemetryctl 데몬까지 묶은 전 구간 확인은 `scripts/telemetry-kafka-e2e/run.sh` 와 [telemetry-kafka-e2e.md](telemetry-kafka-e2e.md)다.

## 운영 지표·장애 로그·smoke

PROJ-191의 분리 관리 포트9404, metric 의미, diagnostic log, 기대 ID/FINAL readback smoke는 [수집 운영 계측](telemetry-observability.md)에 있다. broker ack·consumer 완료·논리 적재는 구별한다.
