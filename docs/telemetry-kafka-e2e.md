# telemetry Gateway·Kafka 로컬 E2E

**수정하지 않은 telemetryctl 데몬**이 `ptt_` → telemetry JWT 전환과 JWT 만료를 스스로 넘기고, 그 데이터가 Gateway → Kafka → ingest Consumer → ClickHouse 까지
가는지 로컬에서 끝까지 본다(PROJ-245, [ADR 0065](adr/0065-telemetry-token은-전용-RS256-JWT로-발급하고-telemetry_tokens를-자격-기록으로-유지한다.md)·
[ADR 0066](adr/0066-OTLP-수신은-Gateway가-JWT만-검증해-Kafka에-접수하고-ingest가-RDB로-다시-검증한다.md)).
§1~5는 절차와 **한 번의 실행 기록**이다. MSK·IAM·TLS·ALB·EKS 는 그 검증 범위가 아니다(§5). 이후 추가한 Strimzi mTLS 접속 검증은 §6에 별도로 기록한다.

## 1. 구성

```
[daemon 컨테이너: Debian + D-Bus + gnome-keyring]                         [호스트 JVM]
  telemetryctl daemon (127.0.0.1:4318) ─→ localhost:38090 ─proxy─┬─→ host:34316  ingest  (http·dual·kafka)
  telemetryctl enroll / 재발급         ─→ localhost:38080 ─proxy─┼─→ host:38080  enrollment
                                                                  └─→ host:38082  Gateway ─→ Kafka(KRaft, 39092) ─→ ingest Consumer
                                                                                               PostgreSQL 35432 · ClickHouse 38123 (Compose)
```

- 데몬은 telemetryctl `develop` 을 그대로 linux 로 빌드해 mount 한다. 저장소에 변경이 있으면 스크립트가 멈춘다. 비밀은 컨테이너 안의 Secret Service 키링에 둔다 —
  호스트(macOS) Keychain 을 건드리지 않는다.
- telemetryctl 의 OTLP endpoint 는 `https` 또는 `http://localhost` 만 받는다. 그래서 컨테이너 안 `localhost` 를 호스트 앱으로 비추는 TCP 프록시
  (`scripts/telemetry-kafka-e2e/proxy.py`)를 둔다. OTLP 쪽 대상은 파일로 바꿀 수 있고, 바뀌면 열린 연결을 닫는다 — **ALB 의 대상 그룹 전환을 흉내 낸다.**
- Kafka 는 운영 MSK 처럼 자동 topic 생성을 끄고 broker·topic message 상한을 13 MiB 로 둔다. topic 은 6 partition, RF 1, 보존 72시간이다.
- enrollment 는 `--server http://localhost:38080/api` 로 등록한다. telemetryctl 이 서버 URL 뒤에 `/v1/enroll`·`/v1/installations/telemetry-token` 을 붙이고,
  서버 경로는 `/api/v1/…` 이기 때문이다.

## 2. 실행

```sh
scripts/telemetry-kafka-e2e/run.sh <작업 디렉터리>     # E2E_KEEP=1 이면 끝난 뒤 컨테이너를 남긴다
```

필요한 것: Docker, JDK 25, Go, openssl, python3, 형제 체크아웃 `../telemetryctl`(`TELEMETRYCTL_DIR` 로 바꿀 수 있다). 작업 디렉터리에 `run.log`·앱 로그·키·
증거(`evidence/`)가 남고 저장소에는 아무것도 쓰지 않는다. 스크립트는 단계마다 기대한 증거가 없으면 `실패:` 를 찍고 끝난다.

## 3. 단계

| # | 하는 일 | 확인 |
|---|---|---|
| 0 | bootJar 셋, telemetryctl linux 빌드, RSA 2048 키쌍(kid `tk-e2e`) | 빌드 성공, telemetryctl 작업 트리 깨끗함 |
| 1 | Compose 기동, topic 생성 | topic 설정(6 partition·`max.message.bytes=13631488`·`retention.ms=259200000`) |
| 2 | enrollment(발급 꺼짐), 조직·회원·초대·manifest 시드 | health 200 |
| 3 | ingest `dual`(HMAC 키 + 공개키), Gateway(공개키) 기동. OTLP 대상 = ingest | 두 health 200 |
| 4 | 컨테이너 안에서 `telemetryctl enroll`, `telemetryctl daemon --listen 4318` | enroll 완료 |
| 5 | 데몬 수신기에 Claude Code 로그(user_prompt·api_request)를 보낸다 — `ptt_` 로 HTTP ingest | ClickHouse `model.response.usage` 행 |
| 6 | enrollment 재기동(발급 켬, 개인키·활성 kid), OTLP 대상 = Gateway, 다시 보낸다 | Gateway 401 `jwt_malformed` → 데몬 "토큰 갱신 후 재시도" → JWT 200 → Kafka → Consumer → ClickHouse 행 |
| 7 | ingest 를 `kafka` 로 재기동 — HMAC 키·공개키 없이 | health 200, `POST /v1/logs` 404 |
| 8 | enrollment 를 수명 60초로 재기동, 데몬 재기동(짧은 JWT 수령), 보낸다 → 95초 뒤 다시 보낸다 | Gateway 401 `jwt_expired` → 재발급 → 200 → ClickHouse 행 |
| 9 | 증거 수집 | 아래 목록 |
| 10 | Gateway·ingest 에 SIGTERM | graceful 종료 로그 |

9단계의 자동 확인: Gateway 의 `jwt_malformed`·`jwt_expired` 거부 로그, 데몬의 401 뒤 재시도 로그, `telemetry_tokens` 에 회전된 `ptt_`·3시간 JWT·60초 JWT 행,
Consumer group lag 0, 실패 이벤트(`permanent_failure`·`retry_exhausted`·`commit_failed`·`publish_failed`) 없음, 모든 로그에 `eyJ…`·`ptt_…`·`pit_…` 원문 없음.

## 4. 실행 기록 — 2026-10-21

- backend: 브랜치 `feature/PROJ-245-telemetry-jwt-gateway-consumer`(`b4601cb` + 이 스크립트), JDK 25(temurin), macOS arm64, Docker 29.8.2(arm64).
- telemetryctl: `develop` `99932c8`(2026-10-11), linux/arm64 빌드, 수정 없음.
- 이미지: `postgres:16-alpine`, `clickhouse/clickhouse-server:24.8-alpine`, `apache/kafka:3.9.2`, 데몬 `debian:bookworm-slim` 기반.
- 결과: **0~10단계 모두 통과** — 두 번 연속(`E2E_KEEP=1` 한 번, 기본 정리 모드 한 번), 각각 스크립트 종료 코드 0, 실행 약 2분 40초.
  그 앞의 실패는 모두 스크립트 결함이었고 고친 뒤 다시 돌렸다(§4.2). 아래 증거는 마지막(정리 모드) 실행의 것이다.

### 4.1 증거(발췌)

Gateway 거부 로그 — `ptt_` 와 만료 JWT 가 각각 한 번씩 401 이었다.

```
event=telemetry_gateway_rejected stage=authenticate reason=jwt_malformed status=401   (6단계)
event=telemetry_gateway_rejected stage=authenticate reason=jwt_expired status=401     (8단계)
```

데몬(수정 없음) 로그 — 두 번 모두 재발급 뒤 재전송했다.

```
forward: logs 상위 인증 거부 (HTTP 401) — 토큰 갱신 후 재시도
forward: logs 상위 인증 거부 (HTTP 401) — 토큰 갱신 후 재시도
```

`enrollment.telemetry_tokens`(발급 순, `token_format|signing_kid|revoke_reason|revoked|수명초`):

```
opaque||rotated|t|          enroll 때 발급한 ptt_ — 데몬 첫 전송의 재발급으로 회전
opaque||rotated|t|          그 재발급의 ptt_ — Gateway 401 뒤 재발급으로 회전
jwt|tk-e2e|rotated|t|10800  3시간 JWT — 데몬 재기동 뒤 첫 전송의 재발급으로 회전
jwt|tk-e2e|rotated|t|60     60초 JWT — 만료 401 뒤 재발급으로 회전
jwt|tk-e2e||f|60            마지막 발급, 활성
```

ClickHouse `telemetry_events`(push 마다 api_request 행):

| session | 경로 | `event_type` | `received_time` |
|---|---|---|---|
| `e2e-before-switch` | 데몬 → HTTP ingest(`dual`, `ptt_`) | `model.response.usage` | HTTP 수신 시각 |
| `e2e-after-switch` | 데몬 → Gateway → Kafka → Consumer(`dual`) | `model.response.usage` | Gateway 접수 시각 |
| `e2e-short-first` | 데몬 → Gateway → Kafka → Consumer(`kafka`) | `model.response.usage` | Gateway 접수 시각 |
| `e2e-short-expired` | 만료 401 → 재발급 → Gateway → Kafka → Consumer(`kafka`) | `model.response.usage` | Gateway 접수 시각 |

Kafka: Gateway 가 발행한 record 3건이 모두 한 partition 에 있다(record key `tenant:installation` 이 같다 — 이번 실행은 partition 2, 설치 ID 가 바뀌면 달라진다).
Consumer group 의 그 partition committed offset 3 = log-end 3, lag 0.
실패 이벤트(`telemetry_permanent_failure`·`telemetry_retry_exhausted`·`telemetry_commit_failed`·`telemetry_publish_failed`) 없음.
`kafka` 모드 ingest 는 HMAC 키·공개키 없이 떠서 health 200, `POST /v1/logs` 404. 모든 앱·데몬 로그에 `eyJ…`·`ptt_…`·`pit_…` 원문 없음.
SIGTERM 에 Gateway·ingest 모두 `Graceful shutdown complete`(각 약 2초).

### 4.2 실행 중 고친 것(스크립트 결함)

- macOS 기본 bash 3.2 에 연관 배열이 없다 → 이름별 변수로 바꿨다.
- 처음 보낸 로그에 `event.name` 이 없어 진단 이벤트로 적재돼 session 으로 찾지 못했다 → 실캡처와 같은 속성으로 바꿨다.
- 데몬 정지에 `pkill -f 'telemetryctl daemon'` 을 써서 그 명령을 실행하던 셸까지 죽였다 → 프로세스 이름(`-x`)으로 찾는다.
- 컨테이너 PID 1 이 프록시라 끝난 데몬이 좀비로 남아 `pgrep` 이 계속 찾았다 → 데몬 서비스에 `init: true`.

### 4.3 관찰한 것(이 변경 범위 밖)

- **manifest 가 프롬프트 수집을 끄면(`collect_user_prompts=false`) 데몬이 `user_prompt` 로그의 body 를 비운다**(telemetryctl `otlpdecode` 의 privacy 규칙).
  그 레코드는 backend Claude Code 프로필에서 `mapping_status=generic`·`event_type=vendor.unknown`·`session_id=NULL` 로 적재된다.
  HTTP 경로(5단계)와 Kafka 경로가 같다 — Gateway·Kafka 와 무관한 기존 크로스레포 동작이다. 그래서 적재 확인은 `api_request` 로 한다. 후속 확인 대상이다.
- telemetryctl 의 `--server` 는 `/api` 까지 넣어야 한다(`http://localhost:38080/api`) — 클라이언트가 `<server>/v1/enroll` 을 부르고 서버 경로는 `/api/v1/enroll` 이다.
  `docs/enrollment-server-spec.md` 10.1 의 예시(`--server http://localhost:8080`)와, `pulsemetry.public-base-url` 을 그대로 `--server` 로 넘기는
  부트스트랩 스크립트(`bootstrap/install.sh`·`install.ps1`)는 이 빌드 기준으로 경로가 맞지 않는다. 이 변경과 무관한 기존 어긋남이며 확인이 필요하다.
- 데몬은 telemetry token 을 메모리에만 둔다. 데몬을 띄운 뒤 첫 전송이 언제나 재발급이라, 재기동마다 토큰 하나가 회전된다(위 표의 첫·셋째 행).
- 데몬은 만료 전 갱신을 하지 않는다. 만료 뒤 첫 전송이 401 → 재발급 → 재전송 한 번이다(ADR 0065 Negative 와 같다).

## 5. 이 검증이 보지 않은 것

- MSK·IAM(SASL_SSL, 전용 역할 assume)·TLS·실제 ALB 대상 전환·EKS Pod·broker 3대(RF 3·minISR 2). 접속 설정은 단위 테스트(JAAS·클래스 로딩)만 거쳤다.
- broker 장애 중 접수(503, 기한 7.5초)·Consumer 재시도·영구 실패·보안 폐기 거부·10 MiB 경계·commit 실패는 통합 테스트(`:apps:telemetry-gateway`·
  `:apps:telemetry-ingest` 의 Testcontainers Kafka)가 맡는다. 이 E2E 에서는 일으키지 않았다.
- 처리 중 요청·record 가 있는 상태의 SIGTERM, 부하, 메모리 상한.
- 로컬 통과는 dev 배포 완료가 아니다.

## 6. Strimzi mTLS 접속 검증 — 2026-10-29 (PROJ-284)

`KafkaMtlsIntegrationTest`는 `quay.io/strimzi/kafka:1.3.0-kafka-4.2.2`
(digest `sha256:b9b9e37491ce17c1b2722b3668661de91430d9437acf18c5f5bafaef38138652`)로 단일 KRaft broker를 실행한다.
JDK 25 keytool로 서버·사용자·신뢰하지 않는 CA의 PKCS12 자격을 매번 임시 디렉터리에 만들며 운영 Secret을 사용하지 않는다.
Kafka client는 기존 4.2.1을 유지한다.

```sh
./gradlew :apps:telemetry-gateway:test --tests '*KafkaMtlsIntegrationTest'
```

| 검증 | 결과 |
|---|---|
| 실제 Gateway publisher + 공통 mTLS 설정으로 ACK 후 key·envelope 바이트를 수신하고 offset을 명시적으로 commit | 통과 |
| 다른 CA를 신뢰하는 client | `SslAuthenticationException`으로 거절 |
| 사용자 인증서 없는 client | `SslAuthenticationException`으로 거절 |
| 정상 client 구성·발행·수신 로그의 PKCS12 비밀번호·개인키 원문 | 없음 |

이 테스트는 외부 client의 TLS 연결을 검증한다. 3 broker·RF 3·minISR 2, KafkaUser ACL, Kubernetes Secret mount·회전,
ALB 전환과 ClickHouse 최종 적재는 dev 인수 검증(PROJ-285)에 남는다. 단일 테스트 broker의 내부 controller 통신은 격리된 컨테이너 안 plaintext다.
