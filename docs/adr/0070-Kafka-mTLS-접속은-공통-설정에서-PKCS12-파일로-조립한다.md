# 0070. Kafka mTLS 접속은 공통 설정에서 PKCS12 파일로 조립한다.

## Status

Accepted — 사용자가 승인한 dev Strimzi 전환의 backend 구현 결정이다. ADR 0066 Decision 9의 MSK 전용 접속 구현에 mTLS를 추가한다. IAM 호환 경로와 다른 수집 계약은 유지한다. 클러스터 배치·저장소·ACL·배포 순서는 [허브 ADR 0026](../../../docs/adr/0026-dev-telemetry-kafka-strimzi.md)이 소유하며, 이 문서는 배포 완료 증거가 아니다.

## Context

샌드박스에서 MSK 사용이 불가하여 dev는 Strimzi Kafka로 전환한다. 현재 `TelemetryKafkaConnection`은 로컬 `plaintext`와 MSK `iam`만 지원하며 두 앱은 Kafka client를 직접 생성하므로 `spring.kafka.*` 설정을 추가해도 적용되지 않는다. 새 자격 공급은 기존 HTTP·JWT·envelope·ACK/commit 의미를 변경해서는 안 된다.

## Decision

1. 공통 `TelemetryKafkaConnection`에 `mtls` 모드를 추가한다. Kafka `security.protocol=SSL`, truststore·keystore 타입 `PKCS12`, hostname 검증 `https`를 설정한다. SASL·STS·AWS callback은 이 모드에 설정하지 않는다. 기존 `plaintext`·`iam`과 로컬 기본값을 유지하며 자동 fallback은 없다.
2. `pulsemetry.telemetry.kafka.tls`에는 `truststore-location`, `truststore-password-file`, `keystore-location`, `keystore-password-file` 네 파일 경로만 둔다. 두 앱이 같은 공통 타입을 바인딩한다. 비밀번호는 환경변수·YAML·로그로 공급하지 않고 client 구성 때 읽는다. 상세 환경변수 계약은 Kafka 운영 문서 §1.1이 소유한다.
3. mTLS client 구성 전에 네 파일의 읽기 가능 여부·비어 있지 않음과 PKCS12 비밀번호, 신뢰 인증서·개인키 인증서의 유효성을 확인한다. 실패는 자격 내용이나 원본 예외를 포함하지 않는 설정 오류로 기동을 막는다. 비밀번호 문자열을 연결 객체의 필드로 보관하거나 `toString`에 넣지 않는다. Kafka client의 비밀번호 설정은 Kafka가 비밀 타입으로 파싱하도록 표준 키를 사용하며 설정 map 자체는 로그로 출력하지 않는다.
4. Strimzi의 cluster CA와 workload별 KafkaUser Secret에서 필요한 파일만 읽기 전용으로 mount한다. CA 개인키를 앱에 공급하지 않는다. 앱은 Kubernetes API로 Secret을 조회하지 않으며 파일 경로는 infra가 공급한다.
5. 자격은 Kafka client를 생성할 때 읽고, 자동 파일 감시·연결 재생성은 구현하지 않는다. 인증서 갱신 후 infra의 TLS revision 변경과 명시적 앱 재배포로 반영한다. 기존 단일 replica·Recreate에 따른 dev 중단을 배포 절차에 기록한다.
6. HTTP 10 MiB, producer 12 MiB, broker·consumer 13 MiB, `acks=all`·idempotence·at-least-once, 재시도·commit, JWT와 protobuf envelope는 그대로 둔다. mTLS는 broker 접속 방식만 변경한다.

## Alternatives Considered

### A. dev를 PLAINTEXT로 연결한다
- 설정 변경은 작지만 인증·암호화 및 Gateway 전용 topic 쓰기 경계를 잃으므로 채택하지 않는다.

### B. SASL/SCRAM 비밀번호를 JAAS 문자열에 넣는다
- Kafka가 지원하지만 Strimzi가 발급하는 사용자 인증서를 활용할 수 있고 추가 JAAS 비밀번호 인코딩이 필요 없는 mTLS를 택한다.

### C. JVM 안에서 인증서를 자동 재로딩한다
- 재시작을 줄일 수 있지만 producer·consumer의 종료·재연결과 commit을 다루는 새 수명주기가 필요하다. dev 전환은 기존 명시적 재배포 절차를 사용한다.

## Consequences/Tradeoffs

### Positive
- Gateway와 ingest가 같은 검증과 TLS 설정을 사용한다.
- AWS 인증 없이 암호화와 workload별 인증을 제공하며 기존 Kafka 처리 의미를 보존한다.

### Negative
- 인증서 발급·갱신과 앱 재배포의 운영 책임이 생긴다. 파일 변경만으로 실행 중 client가 새 인증서를 사용하는 것은 아니다.
- 비밀번호는 Kafka client 설정에 전달될 때 메모리에 존재한다. 설정 map·환경 전체를 진단 로그로 출력하면 안 된다.

## Follow-up

- infra의 KafkaUser ACL·Secret mount·TLS revision 재배포 및 실제 dev ACK→consume→commit→ClickHouse 검증을 연결한다.
- [허브 Kafka 계약](../../../docs/contracts/telemetry-kafka.md)과 이 구현의 환경변수·파일 계약을 함께 리뷰한다.

## Acceptance Criteria

- mTLS 설정의 누락·손상·잘못된 비밀번호·만료 인증서를 안전하게 거절한다.
- 실제 TLS Kafka에서 producer ACK와 consumer 수신·commit을 확인하고 잘못된 CA·미인증 client를 거절한다.
- 기존 IAM·PLAINTEXT, HTTP/JWT·수집 처리 회귀를 확인한다.

## References

- [ADR 0066](0066-OTLP-수신은-Gateway가-JWT만-검증해-Kafka에-접수하고-ingest가-RDB로-다시-검증한다.md)
- [Kafka 운영](../telemetry-kafka-operations.md)
- [Strimzi client security](https://strimzi.io/docs/operators/1.3.0/deploying.html)
