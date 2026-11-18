# 0082. 부하 시험 도구는 캠페인 전용 tenant를 SQL로 시드하고 tools/loadtest에 둔다

## Status

Proposed — PROJ-192 구현 계약. 환경·배포·관측은 [infra ADR 0064](../../../infra/docs/adr/0064-dev-pipeline-loadtest.md)가 정한다. 팀 동의 전이다.

## Context

PROJ-192는 dev 파이프라인(gateway → Kafka → ingest → ClickHouse)의 한계를 두 층으로 찾는다. ① Gateway 수용, ② 파이프라인 지속 처리다.
대상 환경은 dev 그 자체이고, 그 결정은 infra ADR 0064가 맡는다. 이 ADR은 backend가 만드는 부하 발생 도구의 경계만 정한다.

부하 요청이 실제 경로를 지나려면 다음 제약을 만족해야 한다.

- Gateway는 RS256 telemetry JWT만 받는다([ADR 0065](0065-telemetry-token은-전용-RS256-JWT로-발급하고-telemetry_tokens를-자격-기록으로-유지한다.md)).
  발급은 enrollment-api `POST /api/v1/installations/telemetry-token`(Bearer `pit_`)이다. 발급하면 그 설치의 기존 활성 토큰은 `rotated`가 된다. 수명은 3시간이다.
- ingest는 JWT의 jti를 PostgreSQL에서 다시 확인한다. 설치·자격 행이 실제 DB에 있어야 한다.
- `pit_`은 `enrollment.installation_credentials.credential_hash = sha256_hex(pit_)`로만 저장된다([ADR 0003](0003-enrollment-API-계약과-2단-토큰-모델.md), `InstallationCredentialVerifier`).
- 앱 API로 설치를 만들려면 설치마다 초대 수락과 OIDC 로그인이 필요하다. 시험에는 설치 수십 개가 필요하다.
- observation ID는 tenant·설치·신호·`service.name`·원천 시각·정규화 레코드의 SHA-256이다(`ObservationIds`). 같은 payload를 다시 보내면 저장되지 않는다.
- 기존 `tools/dev-seed`([ADR 0073](0073-perf-과거-합성-데이터는-독립-신원과-검증된-대상으로만-적재한다.md))는 ClickHouse에 직접 적재한다. 파이프라인을 지나지 않아 부하 시험이 아니다.

결정하지 않으면 부하 데이터가 실제 조직 tenant에 섞이거나, 토큰 원문이 파일·저장소·ConfigMap에 남는다.

## Decision

1. **대상.** 부하는 dev 파이프라인에 직접 건다. 복제 환경을 만들지 않는다. backend는 부하 발생 도구만 소유한다.
2. **캠페인 전용 tenant.** 캠페인마다 tenant를 새로 만든다. 캠페인 ID는 `^[a-z0-9-]{3,40}$`, tenant 이름은 `loadtest-<campaign>`이다.
   - SQL 생성기가 tenant → team 1개 → 구성원 N명 → 팀 소속 → 소비된 초대 → 설치(`linux`, active) → 설치 자격을 한 트랜잭션으로 넣는다. `ON CONFLICT (id) DO NOTHING`으로 같은 입력은 재적용해도 안전하다. 기존 캠페인과 설치 집합이 다르거나(INSERT 전 검사) 설치별 자격증명 해시가 시드 비밀과 다르면(커밋 전 검사) 롤백한다.
   - ID는 캠페인·종류·순번으로 만든 결정적 UUID(uuid5)다. 같은 입력은 같은 SQL을 만든다.
   - 설치는 Kafka 기본 partitioner로 key `"<tenantId>:<installationId>"`를 계산해 partition마다 같은 수로 고른다.
   - 사람이 DB 터널로 적용한다. 생성기는 DB에 접속하지 않는다.
3. **`pit_`은 HMAC으로 파생한다.** `pit_ = "pit_" + base64url_nopad(HMAC-SHA256(시드 비밀, "pulsemetry-loadtest/v1/<campaign>/<installationId>"))`.
   - SQL에는 `sha256_hex(pit_)`만 쓴다. 저장 방식은 ADR 0003 그대로다.
   - 시드 비밀은 32바이트 파일이다. 저장소 밖에 두고 캠페인마다 새로 만든다. 토큰 파일은 만들지 않는다.
   - k6 `setup()`이 같은 비밀로 `pit_`을 다시 만들어 클러스터 안 enrollment-api에서 JWT로 바꾼다. 토큰·`pit_`을 로그에 쓰지 않는다.
4. **위치는 `tools/loadtest/`이고 Gradle 밖이다.**
   - 생성기는 Python 표준 라이브러리만 쓴다. 시나리오는 k6 JS이고 외부 jslib를 쓰지 않는다.
   - payload는 `libs/telemetry-adapter` 테스트 자원의 실제 Claude Code·Codex 캡처를 템플릿으로 쓴다. 요청마다 시각과 ID 속성을 바꿔 중복 제거에 걸리지 않게 한다.
   - 로컬 E2E는 `scripts/telemetry-kafka-e2e`를 복사해 쓴다. 원본은 고치지 않는다.
5. **정리.**
   - 배수(consumer lag 0) 후 생성기 `--revoke` SQL로 그 캠페인의 설치·자격을 폐기한다.
   - 데이터 삭제는 retention-worker로 한다. `--retention-months`가 1 이상이라 방금 넣은 데이터를 지우려면 `--as-of`를 미래로 줘야 한다. 그러면 [ADR 0024](0024-조직별-보존-삭제-경계는-RDS-가-진실원이고-분석-INSERT-는-ClickHouse-fence-를-서버에서-다시-검사한다.md)의 fence가 그 tenant의 이후 INSERT를 거부한다. 그 tenant는 재사용할 수 없다.
6. **범위 밖.** 앱 코드(`apps/**/src/main`, `libs/**/src/main`), Flyway 마이그레이션, `scripts/telemetry-kafka-e2e`는 바꾸지 않는다.

## Alternatives Considered

### A. 앱 API로 tenant·설치를 만든다
- 장점: 앱의 생성 규칙을 그대로 탄다.
- 단점: 설치마다 초대 수락·OIDC 로그인이 필요하다. dev OIDC 공급자에 사람 계정이 묶인다.
- 탈락 이유: 설치 수십 개를 캠페인마다 만들기 어렵다.

### B. `pit_` 원문을 파일이나 SQL에 둔다
- 장점: 파생 계산이 없다.
- 단점: 원문이 저장소·ConfigMap·셸 기록에 남는다.
- 탈락 이유: 시드 비밀 하나만 지키면 되는 구조가 더 안전하다.

### C. 기존 조직 tenant에 부하를 건다
- 장점: 시드가 필요 없다.
- 단점: 실제 대시보드에 부하 데이터가 섞이고, 그 데이터만 지울 수 없다.
- 탈락 이유: 캠페인 단위로 분리·정리할 수 없다.

### D. `tools/dev-seed` 직접 적재를 늘린다
- 장점: 기존 생성기를 재사용한다.
- 단점: gateway·Kafka·ingest를 지나지 않는다.
- 탈락 이유: 측정 대상이 파이프라인이다.

### E. Gradle 모듈(Kotlin)로 만든다
- 장점: 앱 코드와 타입을 공유한다.
- 단점: 빌드가 필요하고 k6 ConfigMap에 묶을 수 없다.
- 탈락 이유: 사람이 DB 터널 옆에서 바로 실행하고, k6가 같은 계산을 JS로 재현하면 충분하다.

## Consequences/Tradeoffs

### Positive
- 부하 요청이 실제 인증·중복 제거·저장 경로를 지난다.
- 캠페인 단위로 데이터가 분리되고, 같은 입력은 같은 시드를 만든다.
- 토큰 원문이 어디에도 저장되지 않는다.

### Negative
- SQL 시드는 앱의 생성 규칙을 우회한다. 스키마가 바뀌면 생성기도 고쳐야 한다.
  - 로컬 E2E가 Flyway 최신 스키마에 시드를 적용해 확인한다.
- 시드 비밀을 잃으면 그 캠페인의 `pit_`을 다시 만들 수 없다. 새 캠페인으로 다시 시드한다.
- 데이터를 지우면 그 tenant는 재사용할 수 없다.
- 시험 중 dev 수집이 늦어지거나 멈출 수 있다(infra ADR 0064).

## Follow-up

- 팀 동의(infra ADR 0064와 함께).
- 정리 방식 결정: 캠페인 tenant를 남길지, retention-worker로 지울지.

## Acceptance Criteria

- `python3 -m unittest discover -s tools/loadtest/tests` 통과. 생성 SQL에 `pit_` 원문이 없다.
- `tools/loadtest/local/run.sh`가 시드 → 토큰 교환 → k6 → ClickHouse 저장 건수 대조까지 종료 0.

## References

- [infra ADR 0064](../../../infra/docs/adr/0064-dev-pipeline-loadtest.md)
- ADR 0003, 0024, 0065, 0073
- `InstallationCredentialVerifier`, `TelemetryTokenController`, `ObservationIds`, `TelemetryRecordKey`
