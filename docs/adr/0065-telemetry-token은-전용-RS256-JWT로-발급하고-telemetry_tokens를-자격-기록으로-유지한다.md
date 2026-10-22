# 0065. telemetry token 은 전용 RS256 JWT 로 발급하고 telemetry_tokens 를 자격 기록으로 유지한다.

## Status

Proposed — 허브 [ADR 0001](../../../docs/adr/0001-otlp-authentication-model.md) Decision 2(불투명 `ptt_`)와
허브 `contracts/enrollment-api.md` §4·`contracts/telemetry-ingest.md`의 Bearer 서술을 부분 대체할 허브 개정이 남아 있다(Follow-up).
발급 전환은 `pulsemetry.telemetry-jwt.issue-enabled`(기본 false)로 켠다. 끄면 지금처럼 `ptt_`를 발급한다.

## Context

- OTLP 의 `telemetry_token` 은 불투명 `ptt_` 다. 검증은 HMAC(`TelemetryTokenHasher`)으로 `telemetry_tokens` 를 조회하는 것뿐이라
  검증자마다 RDB 와 HMAC 키가 있어야 한다. [ADR 0066](0066-OTLP-수신은-Gateway가-JWT만-검증해-Kafka에-접수하고-ingest가-RDB로-다시-검증한다.md)의
  Gateway 는 DB·HMAC 없이 인증해야 하므로 서명 토큰이 필요하다.
- 사용자 로그인 JWT([ADR 0018](0018-사용자-인증은-회전-세션과-명시적-키링으로-운영한다.md))와 같은 키·`typ`·`aud` 를 쓰면 한쪽 토큰이
  다른 경로에서 통과할 수 있다.
- telemetryctl 기본 브랜치의 데몬은 401·403 에서만 재발급하고 한 번 재전송한다. 응답 봉투는 필드 수가 고정이다
  (`DisallowUnknownFields`, AGENTS.md "enroll 응답은 정확히 4키다").
- Kafka 에 대기하는 동안 토큰이 회전·만료·보안 폐기될 수 있다. 지금의 행에는 폐기 시각만 있고 사유·만료·서명 키가 없어
  "접수 뒤 정상 회전"과 "보안 폐기"를 구별할 수 없다.
- 구 ingest 이미지로 되돌리면 그 이미지는 Bearer 전문의 HMAC 으로 행을 찾는다. 해시가 없으면 JWT 가 전부 401 이 된다.

## Decision

1. **최초 enrollment 와 재발급이 같은 발급기를 쓴다.** `:libs:telemetry-auth` 가 서명·검증을 소유하고 nimbus 만 의존한다.
   - RS256. header `typ=pulsemetry-telemetry+jwt`, `kid`.
   - claim `iss=pulsemetry-enrollment`, `aud=pulsemetry-telemetry-ingest`(단일 값), `scope=telemetry:write`,
     `sub=installation_id`, `tenant_id`, `jti=telemetry_tokens.id`, 초 단위 `iat`·`exp`.
   - 수명 3시간, 시계 오차 30초. issuer·audience·typ·scope·수명은 설정이 아니라 고정값이다.
   - 응답 봉투는 바꾸지 않는다. `telemetry_token` 의 값만 JWT 가 된다. `pit_` 와 그 검증은 그대로다.
2. **키는 사용자 JWT 와 분리한다.** enrollment 만 PKCS8 개인키(`pulsemetry.telemetry-jwt.private-key-file`)와 활성 kid 를 받는다.
   검증자는 `pulsemetry.telemetry-jwt.public-key-files`(kid → X.509 PEM 파일)만 받는다.
   토큰의 `jku`·`x5u`·`jwk`·`x5c` 는 거부하고 원격 키를 조회하지 않는다. 기동 때 RSA 2048비트 이상과 활성 kid 의 modulus 일치를 검사한다.
3. **발급 순서**: 행 id 를 먼저 만든다 → `iat` 를 초로 자르고 `exp=iat+3h` → 서명 → JWT 전문의 HMAC 을 `token_hash` 에 저장한다.
   설치 행 잠금·발급 트랜잭션·활성 토큰 하나 제약(V3)은 그대로다.
4. **스키마는 추가형이다(V33).** `token_format`(`opaque`|`jwt`, 기본 `opaque`), `expires_at`, `signing_kid`, `revoke_reason`(`rotated`|`security`).
   CHECK 는 셋이다 — 형식 값, 사유 값과 "사유가 있으면 폐기 시각도 있다", `jwt` 이면 만료·kid 필수이고 만료가 발급보다 뒤다.
   **"폐기 시각이 있으면 사유가 있다"는 DB CHECK 로 걸지 않는다.** 구 enrollment 이미지로 되돌리면 그 이미지의 회전 UPDATE 가
   사유 없이 쓰므로 CHECK 가 재발급을 막는다. 새 코드는 언제나 사유를 쓰고, 사유 없는 폐기는 "사유 불명"으로 거부한다.
   번호는 V33 이다 — 로컬 synthetic 작업이 V32 를 쓰므로 충돌을 피한다.
5. **회전과 보안 폐기를 구분한다.** 재발급은 `revoked_at IS NULL` 행에만 `rotated` 를 쓴다 — 보안 폐기를 덮어쓰지 않는다.
   회전의 `revoked_at` 은 설치 행 잠금을 얻은 뒤의 시각이다. 잠금을 기다리는 동안 이전 토큰으로 접수된 데이터가 6의 판정에서 회전 뒤로 밀리지 않게 한다.
   보안 폐기는 `revoked_at` 을 반드시 채우고(`COALESCE`) `security` 를 쓴다. 이미 회전된 행에도 적용된다.
   `revoked_at` 이 채워져 있으므로 구 이미지도 거부한다. 관리 API 를 두지 않고 운영 절차의 SQL 로 한다.
6. **RDB 판정(Consumer·HTTP 호환 공통)**은 `jti` 로 행·설치·회원·조직을 함께 읽고 접수 시각(`acceptedAt`)으로 판정한다.

   | 행의 상태 | 판정 |
   |---|---|
   | 행 없음 · 형식이 `jwt` 아님 | 거부 |
   | 전달된 tenant·installation·iss·kid·iat·exp 가 행과 다름 | 거부 |
   | `acceptedAt` 가 DB `[issued_at − 30s, expires_at + 30s]` 밖 | 거부. 접수 당시 유효했으면 소비 시점의 만료는 보지 않는다 |
   | `rotated` 이고 `acceptedAt < revoked_at` | 허용 |
   | `rotated` 이고 `acceptedAt ≥ revoked_at` · `security` · 사유 없음 | 거부 |
   | 설치·회원·조직 차단 | 기존 `TelemetryTokenDecision` 의 규칙과 순서로 거부 |

   DB 장애는 거부가 아니라 일시 실패다. 검증 완료 신원은 전달된 claim 이 아니라 행의 tenant·installation 으로 만든다.
7. **HTTP ingest 는 Bearer 형식으로 가른다.** JWT 형태(점 둘)는 검증기와 6의 판정(`acceptedAt`=수신 시각)으로, 그 밖은 기존 해시 조회로 간다.
   **JWT 를 해시로 조회하지 않는다.** 401 본문은 하나다. 공개키 맵이 비어 있으면 JWT 형태는 401 이다 — 공개키를 받지 않은 배포가 그대로 뜬다.
8. **전환 순서**: 모든 verifier(Gateway·HTTP ingest·복구용 ingest)에 공개키 선배포 → enrollment 에 개인키·활성 kid → `issue-enabled=true`.
   정상 키 회전은 새 공개키 선배포 → 서명 키 전환 → 마지막 구 키 발급 후 3시간 30초 경과 → 구 공개키 제거다.

## Alternatives Considered

### A. `ptt_` 를 유지하고 Gateway 가 RDB 를 조회한다
- 장점: 토큰 형식·데몬 동작이 그대로다.
- 단점: Gateway 에 DB 자격·연결·HMAC 키가 들어가 접수 경로가 RDB 가용성에 다시 묶인다.
- 탈락 이유: Gateway 를 DB 없이 두는 것이 ADR 0066 의 전제다.

### B. 사용자 JWT 키·검증기를 함께 쓴다
- 장점: 키 공급 경로가 하나다.
- 단점: 한 키의 유출이 두 경로를 함께 연다. `typ`·`aud` 하나만 틀려도 용도가 섞인다.
- 탈락 이유: 수명(5분 대 3시간)·검증 주체·회전 주기가 다르다.

### C. JWT 의 해시를 저장하지 않는다
- 장점: 만료가 무력해지는 경로가 없다.
- 단점: 구 ingest 로 되돌리면 JWT 가 전부 401 이 되고, 데몬이 재발급받아도 다시 JWT 라 수집이 멈춘다.
- 탈락 이유: 복구 경로를 막는다. 해시 저장의 위험은 Negative 로 받아들인다.

### D. 폐기 시각이 있으면 사유를 요구하는 CHECK
- 장점: 사유 없는 폐기가 DB 에 생기지 않는다.
- 단점: 구 enrollment 의 회전 UPDATE 가 CHECK 에 걸려 재발급이 500 이 된다.
- 탈락 이유: 추가형 migration 의 목적이 롤백 호환이다. 앱이 사유를 쓰고 판정이 사유 없음을 거부하는 것으로 같은 안전을 얻는다.

### E. JWKS 원격 조회
- 탈락 이유: 요청 경로에 외부 의존이 생긴다. 키 목록은 배포가 공급한다(ADR 0018 과 같은 방식).

## Consequences/Tradeoffs

### Positive
- Gateway 가 RDB 없이 서명·용도·시간을 검증한다.
- 회전과 보안 폐기가 행에 남아 Kafka 대기 중의 상태 변화를 접수 시각 기준으로 판정할 수 있다.
- 구 ingest·구 enrollment 로 되돌려도 발급·검증이 이어진다.

### Negative
- **구 ingest 로 되돌린 동안 JWT 의 만료가 무력하다.** 구 이미지는 해시만 보므로 회전 전까지 3시간 뒤에도 통과한다.
  보안 폐기는 `revoked_at` 으로 여전히 거부된다.
- **보안 폐기 토큰은 만료 전까지 Gateway 를 통과한다.** Consumer 가 거부하지만 원문은 Kafka 보존 기간(72시간) 동안 남는다.
- 데몬은 만료 전 갱신을 하지 않는다. 3시간마다 401 → 재발급 → 재전송 왕복이 한 번 생긴다.
- 키 회전·발급 전환은 수동 순서에 의존한다. 순서를 어기면 새 토큰이 401 이다.

## Follow-up

- 허브 개정: ADR 0001 Decision 2(불투명 2단 토큰 중 `ptt_`)와 Constraints(불투명 토큰에는 claim 이 없다),
  `contracts/enrollment-api.md` §4(해시 대상·형식), `contracts/telemetry-ingest.md` 의 Bearer 서술을 이 ADR 기준으로 부분 대체한다.
  이 레포에서는 허브를 고치지 않는다.
- 해시 병행 저장은 구 ingest 로 되돌릴 경로가 없어진 뒤에 걷어 낸다. 그때 V33 의 형식 기본값과 해시 조회 경로도 함께 본다.
- 공개키 공급·VersionId 고정·Secret 원본은 infra 가 소유한다(PROJ-246).
- 데몬의 만료 전 갱신은 telemetryctl 범위다.

## References

- 허브 [ADR 0001](../../../docs/adr/0001-otlp-authentication-model.md) · [ADR 0018](0018-사용자-인증은-회전-세션과-명시적-키링으로-운영한다.md) · [ADR 0066](0066-OTLP-수신은-Gateway가-JWT만-검증해-Kafka에-접수하고-ingest가-RDB로-다시-검증한다.md)
- `libs/enrollment-persistence/.../db/migration/V3__telemetry_tokens_single_active_index.sql` · `V33__telemetry_token_credentials.sql`
- [telemetry Kafka 운영](../telemetry-kafka-operations.md) — 설정표·키 회전·보안 폐기 SQL
