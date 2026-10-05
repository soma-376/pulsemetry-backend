<a id="enrollment-서버-명세"></a>

# Enrollment 서버 운영·실행 안내

HTTP 명세는 [페이지·기능별 API 문서](api/README.md)로 이전했다. 이 문서는 서버 책임·설정·운영·검증 절차와 기존 링크의 진입점을 유지한다.


사람 인증은 표준 OIDC이며 개발·데모 Cognito 설정은 [실행 안내](cognito-dev.md)를 따른다.
사전 등록 회원만 회사 IdP의 검증된 이메일로 최초 sub 연결을 허용한다. 공개 가입이나 자동 회원 생성은 하지 않는다. Cognito 전용 그룹·속성·토큰으로 업무 권한을 판단하지 않으며 아래 API 계약은 제공자에 독립적이다.

데스크탑 CLI 설치·사용자 인증·조직 관리·온보딩을 담당하는 `:apps:enrollment-api`의 동작 명세다.
기본 포트는 8080이다. 대시보드 조회와 공통 벤더 카탈로그는 [페이지·기능별 API 문서](api/README.md)를 따른다.
기존 설치 계약은 §2~§10, 사람 로그인은 §11, 관리 명령은 §12, 온보딩은 §13에 정의한다.
`telemetryctl` 의 `contracts` 와 `internal/contract` 주석이 이 문서를 이름으로 참조한다
(특히 §4.3 봉투 분리, §5 manifest).

관련 결정 기록: [ADR 0003](adr/0003-enrollment-API-계약과-2단-토큰-모델.md) ·
[ADR 0004](adr/0004-Flyway-마이그레이션과-varchar-CHECK-스키마-관리.md) ·
[ADR 0005](adr/0005-설치-부트스트랩-스크립트와-바이너리-서빙.md)

---

## 1. 범위

관리자가 발급한 일회성 초대 코드를 검증·소비해 사용자 PC 의 설치(installation)를 만들고,
그 설치에 귀속되는 자격증명과 회사 단위 OTel 설정(manifest)을 내려준다.
설치 부트스트랩 스크립트와 CLI 바이너리도 서빙한다.

사용자 흐름:

```text
관리자가 POST /v1/invitations 로 초대 코드 발급 → 사용자에게 전달
  → 사용자가 터미널에 한 줄 설치 명령 붙여넣기
  → GET /windows?code=... (또는 /unix?code=...) 가 설치 스크립트 반환
  → 스크립트가 GET /bin/{filename} 로 아키텍처에 맞는 바이너리 다운로드
  → 바이너리가 POST /v1/enroll 호출 → 자격증명 + manifest 수신
  → Codex/Claude 설정 충돌 검사 → 백업 → OTel 키 병합 → daemon 자동 실행 등록 → 완료
```

위 흐름의 마지막 단계(설정 병합·백업·daemon 자동 실행 등록)는 클라이언트의 몫이며 서버는 관여하지 않는다.

**범위 밖**: 웹 대시보드 조회 API,
`uninstall`/`repair`, 데이터 파이프라인.
설치된 데몬의 주기 보고(heartbeat)는 §4.5가 받는다. 그 값으로 수집 상태와 적용 현황을 판정하는 것은 조회 API의 몫이다.
telemetryctl 기본 브랜치의 데몬은 등록(§4.2)·토큰 재발급(§4.3)·OTLP 전달·업데이트 확인(§6.3)을 한다. 설치 보고 송신·manifest 재조회(§11.1)·CLI 로그인(§11)은
데몬 쪽 구현이 없다 — 서버 경로만 있다(ADR 0053).
초대 이메일은 조직 관리 API의 초대(§12·§13.3)가 보낸다. 관리자 키 경로(`POST /v1/invitations`)는 메일을 보내지 않고 설치 명령을 응답으로 돌려준다.

---

### 2.5 생존 확인 — `GET /v1/healthz`

```ts
// 쿼리 파라미터 없음
{}
```

```json
{
  "status": "ok",
  "checks": { "database": "ok" }
}
```

DB 장애 시에는 503과 함께 `status=degraded`, `checks.database=down`을 반환한다.

---


## 8. 설정

| 키 | 기본값 | 설명 |
|---|---|---|
| `pulsemetry.public-base-url` | `http://localhost:8080` | 설치 명령·스크립트에 박히는 서버 주소. 기동 시 형식 검증 |
| `pulsemetry.admin.api-token` | 없음 | 관리자 API 키. **비어 있으면 기동 실패** |
| `pulsemetry.token-hash-secret` | 없음 | telemetry token 의 HMAC-SHA256 키. **비어 있으면 기동 실패.** auth-proxy(ai-telemetry-pipeline)와 같은 값을 써야 OTLP 인증이 성립한다. dev 인프라에서는 `DevEdgeStack` 의 `TokenHashSecretArn` 이 가리키는 Secrets Manager 값. 키 변경 = 발급된 전 토큰 무효 |
| `pulsemetry.invitation.default-ttl-hours` | `72` | `expires_in_hours` 생략 시 만료 시간 |
| `pulsemetry.binaries.dir` | `./binaries` | telemetryctl 릴리스 디렉터리(`v<SemVer>`, §6.3)나 공개 이름의 CLI 바이너리(§6.2)가 놓인 서버 로컬 디렉터리 |
| `pulsemetry.mail.enabled` | `false` | 메일 발송(ADR 0037)을 켠다. 켜면 아래 열한 값이 **모두 필요하다 — 하나라도 비면 기동 실패**. 꺼져 있으면 outbox에 적재하지도 보내지도 않는다 |
| `pulsemetry.mail.smtp.host` · `.port` | 없음 | SMTP 서버 |
| `pulsemetry.mail.smtp.username` · `.password` | 없음 | SMTP 계정. 로그·응답에 싣지 않는다 |
| `pulsemetry.mail.smtp.starttls` | 없음 | STARTTLS를 요구하는가(`true`·`false`). 운영 SMTP는 `true` |
| `pulsemetry.mail.from` | 없음 | 발신 주소 |
| `pulsemetry.mail.encryption-key` | 없음 | 대기 중인 메일 본문의 AES-256-GCM 키(Base64 32바이트). 바꾸면 그때 대기 중이던 메일은 본문을 읽지 못해 실패로 끝난다 |
| `pulsemetry.mail.dispatch-interval` | 없음 | 발송 작업이 outbox를 보는 주기(ISO-8601 기간) |
| `pulsemetry.mail.retry-interval` | 없음 | 일시 실패 뒤 다시 시도하기까지의 간격 |
| `pulsemetry.mail.max-attempts` | 없음 | 한 메일의 최대 시도 횟수(1 이상) |
| `pulsemetry.mail.send-timeout` | 없음 | SMTP 연결·읽기·쓰기 각각의 제한 시간. 선점 임대는 이 값의 네 배다 |
| `pulsemetry.management.invitation-accept-url` | 없음 | 초대 메일의 수락 링크가 가리키는 프론트 주소(fragment 없는 http(s) 주소). 관리 기능과 메일을 **함께 켜면 필수** — 비면 기동 실패 |
| `pulsemetry.inquiries.notification-recipient` | 없음 | 접수된 문의를 알릴 담당자 주소. 문의 접수와 메일을 **함께 켜면 필수** — 비면 기동 실패 |
| `pulsemetry.inquiries.enabled` | `false` | 도입 문의 접수(§2.2)를 켠다. 켜면 아래 네 값이 **모두 필요하다 — 하나라도 비면 기동 실패** |
| `pulsemetry.inquiries.duplicate-window` | 없음 | 같은 회사·이메일의 재전송을 같은 접수로 보는 시간(ISO-8601 기간, 예: `PT10M`) |
| `pulsemetry.inquiries.rate-limit.requests` | 없음 | 출처 하나가 창 안에 보낼 수 있는 요청 수(1 이상) |
| `pulsemetry.inquiries.rate-limit.window` | 없음 | 요청 수를 세는 창(ISO-8601 기간) |
| `pulsemetry.inquiries.allowed-origins` | 없음 | 문의 폼을 띄우는 프론트 출처(쉼표로 구분) |
| `pulsemetry.heartbeat.enabled` | `false` | 설치 보고 수신(§4.5)을 켠다. 켜면 아래 세 값이 **모두 필요하다 — 하나라도 비면 기동 실패** |
| `pulsemetry.heartbeat.report-interval` | 없음 | 응답으로 데몬에 주는 보고 주기(ISO-8601 기간). 계약의 범위인 60초 이상 3600초 이하 |
| `pulsemetry.heartbeat.retry-after` | 없음 | 저장소 장애(503)의 `Retry-After`(1초 이상) |
| `pulsemetry.heartbeat.history-retention` | 없음 | 수집 구간 이력을 남겨 두는 기간(하루 이상) |
| `pulsemetry.vendor-connections.enabled` | `false` | 벤더 연결(§12 "벤더 연결", ADR 0048)을 켠다. 관리 기능도 켜야 하고 아래 두 값이 **모두 필요하다 — 없으면 기동 실패** |
| `pulsemetry.vendor-connections.credential-keys.<키 ID>` | 없음 | 벤더 자격증명의 AES-256-GCM 키(Base64 32바이트). 키 ID는 `[A-Za-z0-9_-]{1,64}`. 옛 키는 그 키로 암호화된 연결(`vendor_connections.credential_key_id`)이 남아 있는 동안 둔다 |
| `pulsemetry.vendor-connections.credential-key-id` | 없음 | 새 암호문을 만드는 키의 ID. 키 목록에 있어야 한다 |
| `pulsemetry.vendor-connections.sync.interval` | 없음 | 연결 하나를 다시 동기화하는 간격(마지막 시도부터). 벤더 권장 주기보다 짧게 두지 않는다(Cursor는 "polled at most once per hour") |
| `pulsemetry.vendor-connections.sync.check-interval` | 없음 | 차례인 연결을 찾는 주기. "지금 동기화" 요청과 회수·복원의 벤더 제어 대상이 기다리는 최대 시간이다(ADR 0049) |
| `pulsemetry.vendor-connections.sync.lease` | 없음 | 한 연결(과 회수·복원 대상 하나)의 선점 기한. 한 번의 동기화(모든 페이지 × 시도 횟수 × 시간 제한)보다 길게 |
| `pulsemetry.vendor-connections.http.request-timeout` | 없음 | 벤더 호출 하나의 시간 제한 |
| `pulsemetry.vendor-connections.http.max-attempts` | 없음 | 호출 하나의 최대 시도 횟수(첫 시도 포함). 일시 장애·한도 초과만 다시 시도한다 |
| `pulsemetry.vendor-connections.http.retry-backoff` | 없음 | 벤더가 대기 시간을 알려 주지 않은 일시 장애 뒤의 대기 |
| `pulsemetry.vendor-connections.http.max-retry-wait` | 없음 | 벤더가 알려 준 대기 시간(`Retry-After` 등)의 상한. 넘으면 기다리지 않고 `rate_limited`로 남긴다 |
| `pulsemetry.vendor-connections.base-urls.<커넥터 ID>` | 벤더 공식 주소 | 모의 서버·스테이징에서만 바꾼다. 로컬 모의 서버는 `tools/mock-vendor/README.md` |

DB 접속은 `PULSEMETRY_DB_URL` · `PULSEMETRY_DB_USERNAME` · `PULSEMETRY_DB_PASSWORD` 로 덮어쓴다.
메일의 키는 `PULSEMETRY_MAIL_ENABLED` · `_FROM` · `_ENCRYPTION_KEY` · `_DISPATCH_INTERVAL` · `_RETRY_INTERVAL` · `_MAX_ATTEMPTS` · `_SEND_TIMEOUT` ·
`_SMTP_HOST` · `_SMTP_PORT` · `_SMTP_USERNAME` · `_SMTP_PASSWORD` · `_SMTP_STARTTLS` 로 준다. local 프로필은 Compose의 메일 수신 컨테이너(`localhost:1025`)로 켠다.
수락 주소와 통지 수신자는 `PULSEMETRY_INVITATION_ACCEPT_URL` · `PULSEMETRY_INQUIRIES_NOTIFICATION_RECIPIENT` 로 준다.
문의 접수의 다섯 키는 `PULSEMETRY_INQUIRIES_ENABLED` · `_DUPLICATE_WINDOW` · `_RATE_LIMIT_REQUESTS` · `_RATE_LIMIT_WINDOW` · `_ALLOWED_ORIGINS` 로 준다.
local 프로필은 개발용 값(10분, 1분에 10회, 출처 3000·3107)으로 켠다. 운영 수치의 배포 기본값은 두지 않는다.
설치 보고의 네 키는 `PULSEMETRY_HEARTBEAT_ENABLED` · `_REPORT_INTERVAL` · `_RETRY_AFTER` · `_HISTORY_RETENTION` 으로 준다. local 프로필은 1분 · 5초 · 400일로 켠다.
벤더 연결은 `PULSEMETRY_VENDOR_CONNECTIONS_ENABLED` · `PULSEMETRY_VENDOR_CONNECTIONS_CREDENTIAL_KEY_ID`와 키마다 `PULSEMETRY_VENDORCONNECTIONS_CREDENTIALKEYS_<키 ID>`로 준다.
동기화·호출 수치는 `PULSEMETRY_VENDOR_CONNECTIONS_SYNC_INTERVAL` · `_SYNC_CHECK_INTERVAL` · `_SYNC_LEASE` · `_HTTP_REQUEST_TIMEOUT` · `_HTTP_MAX_ATTEMPTS` · `_HTTP_RETRY_BACKOFF` · `_HTTP_MAX_RETRY_WAIT`로 준다.
관리 응답 암호화 키·메일 키와 다른 값을 쓴다 — 수명과 노출이 다르다(ADR 0048 §6).

---

## 9. 운영

### 9.1 초기 manifest 준비

조직의 첫 manifest는 `PUT /api/v1/organizations/{organizationId}/collection-policy`에
`expectedVersion=0`으로 정책을 저장할 때 생성한다(§13, ADR 0033). 수집 주소는 서버의
`PULSEMETRY_ONBOARDING_OTLP_ENDPOINT`에서 받는다. 운영 기본값은 없고 local은 `http://localhost:4316`이다.
이후 정책 변경은 기존 manifest를 복사해 새 판으로 저장한다. 필요한 경우 운영자가 직접 준비할 수도 있다.

`enrollment.manifests` 에 해당 tenant 의 `is_active = true` 행이 없으면
그 tenant 의 모든 enroll 이 409 `manifest_not_configured` 로 실패한다.
로그인·초대 발급은 manifest 없이 가능하지만 **설치를 등록하기 전에** 수집 정책을 저장해야 한다.

```sql
INSERT INTO enrollment.manifests
    (id, tenant_id, version, manifest, is_active, created_by_member_id, created_at, activated_at)
VALUES (
    gen_random_uuid(), :tenant_id, 1,
    :manifest_json::jsonb, true, :created_by_member_id, now(), now()
);
```

부분 유니크 인덱스 때문에 tenant 당 활성 행은 하나뿐이다.
설정을 바꿀 때는 기존 행을 고치지 말고 **기존 행을 비활성화한 뒤 새 `version` 행을 활성으로** 넣는다.

Flyway 마이그레이션에는 시드 데이터를 넣지 않는다.
A/B/C 시드는 개발 Compose의 일회성 `dev-seed`가 준비한다(ADR 0031).
서버는 local 프로필에서도 시드를 실행하지 않는다. 기존 enrollment·telemetry_ops Flyway와
ClickHouse 마이그레이터를 재사용하며 스키마 소유권은 바꾸지 않는다.
`docker compose up -d --build` 후 `docker compose logs -f dev-seed`와 종료 코드 0을 확인한다.
Compose 날짜 생략은 서울 기준 실행일이며, 완료된 시드는 재실행·날짜 변경에도 보존한다.
수동 적재·검증도 Docker로 실행한다. 상세는 [시드 가이드](../tools/dev-seed/README.md)를 따른다.

### 9.2 바이너리 배치

`pulsemetry.binaries.dir` 에 telemetryctl 릴리스를 그 태그 이름의 디렉터리로 받아 둔다(§6.3, ADR 0053). 이름을 바꾸거나 메타데이터를 따로 만들지 않는다.

```sh
gh release download v0.2.0 --repo soma-376/telemetryctl \
  --pattern 'pulsemetry_cli_*' --pattern SHA256SUMS --dir "$PULSEMETRY_BINARIES_DIR/v0.2.0"
```

GUI 패키지는 받지 않아도 된다(서빙하지 않는다). 새 릴리스는 새 디렉터리로 받는다 — 판이 가장 높은 디렉터리가 곧 이 서버의 릴리스다.
되돌릴 때는 높은 판의 디렉터리를 치운다. 그 릴리스에 없는 아키텍처는 404 가 되며, 그 아키텍처의 사용자는 설치가 실패한다.
자산의 해시가 `SHA256SUMS`와 다르면 그 대상은 설치(§6.2)도 업데이트 확인(§6.3)도 404다. 설치된 데몬은 업데이트를 "미지원"으로 표시한다.

릴리스 디렉터리 없이 §6.2 의 공개 이름 그대로 파일을 놓아도 설치는 된다. 그 판은 알 수 없어 업데이트 확인은 404다.

### 9.3 헬스체크

`GET /v1/healthz` 는 `SELECT 1` 로 DB 를 확인한다.

```json
{"status": "ok", "checks": {"database": "ok"}}
```

실패 시 503 + `{"status":"degraded","checks":{"database":"down"}}`.
이 엔드포인트는 **로그를 남기지 않는다.** 헬스체커가 초당 호출하므로
로그 한 줄이 하루 수만 줄이 되고 그 소음에 진짜 사고가 묻힌다.

### 9.4 공유 RDS(controlplane) 접속 — 파이프라인과 같은 DB 를 본다

텔레메트리 파이프라인의 auth-proxy(`DATABASE_URL`)와 post-processor(`ENRICHMENT_PG_DSN`)는
infra 가 띄우는 RDS 의 **`controlplane`** 데이터베이스를 가리킨다. 이 서버가 발급한 토큰이
OTLP 경로에서 검증되려면 **이 서버도 같은 DB 에 붙어야 한다** (ADR 0009).

**이 절의 로컬 `bootRun` 레시피는 enrollment 서버가 dev 에 배포되기 전까지의 공식 잠정 절차다.**
공유 RDS 의 `enrollment` 스키마 부트스트랩(마이그레이션 적용)은 이 절차로만 한다 —
파이프라인 DDL 을 `psql` 로 직접 넣는 우회는 쓰지 않는다. 부트스트랩 주체는 backend Flyway 다.

```sh
# 엔드포인트·자격증명은 infra DevEdgeStack 의 CfnOutput 에서 얻는다.
#   RdsEndpoint          → 호스트
#   RdsSecretArn         → username / password (Secrets Manager)
#   TokenHashSecretArn   → PULSEMETRY_TOKEN_HASH_SECRET (Secrets Manager)
export PULSEMETRY_DB_URL="jdbc:postgresql://<RdsEndpoint>:5432/controlplane?sslmode=require"
export PULSEMETRY_DB_USERNAME=postgres
export PULSEMETRY_DB_PASSWORD=...        # RdsSecretArn 시크릿의 password
export PULSEMETRY_TOKEN_HASH_SECRET=...  # TokenHashSecretArn 시크릿 값 (auth-proxy 와 동일해야 한다)
export PULSEMETRY_ADMIN_API_TOKEN=...
./gradlew :apps:enrollment-api:bootRun
```

- JDBC 의 `sslmode=require` 는 libpq 와 같은 의미다 — 암호화하되 CA 검증은 하지 않으므로
  RDS CA 번들이 필요 없다 (infra 의 `CONTROL_DB_SSLMODE` 와 동일한 전제).
- **스키마 부트스트랩은 Flyway 가 맡는다.** 빈 DB 면 V1 부터 생성하고, 파이프라인 DDL 로
  수동 부트스트랩된 스키마가 이미 있으면 `baseline-on-migrate` 가 DROP 없이 baseline
  (V1 스킵) 후 V2 부터 적용한다. V1 이 그 DDL 과 같은 물리 형태(native enum)라서
  성립하는 동작이다 (ADR 0009).
- 개발 시드는 Docker의 고정 개발 DB에만 적재한다. 서버 프로필은 시드를 실행하지 않는다(ADR 0031).
- 수용 기준(B3): enroll 로 발급받은 `ptt_` 토큰으로
  `curl -X POST http://<alb-dns>/v1/traces -H "Authorization: Bearer <ptt>"
  -H "Content-Type: application/json" -d '{"resourceSpans":[]}'` → **2xx**,
  토큰 없이 → 401.

---

## 10. 로컬 실행

```sh
docker compose up -d --build              # PostgreSQL · ClickHouse · 메일 수신 컨테이너 · 일회성 시드
docker compose logs -f dev-seed
docker compose ps -a dev-seed             # Exited (0) 확인
# ingest와 공유하는 HMAC 키. enrollment의 local 기본값과 맞춘다.
export PULSEMETRY_TOKEN_HASH_SECRET=local-development-token-hash-secret
./gradlew :apps:enrollment-api:bootRun --args='--spring.profiles.active=local'
```

시드 컨테이너가 A/C의 시나리오 데이터와 B의 조직·오너 한 명을 준비한다. B에는 manifest와 수집 이력도 없다.
A는 정책 확인과 벤더 선택을 갖춘 온보딩 완료 상태이고 B/C는 미완료다.
별도의 기본 로컬 개발 조직·계정·고정 초대는 생성하지 않는다(ADR 0032).
신규 조직 INSERT에는 빈 `telemetry_ops.tenant_ingest_summary` 생성이 같은 트랜잭션으로 포함된다(ADR 0034).
시드·JPA·직접 SQL 모두 적용하며 수신·관측 시각은 NULL이다. `enrollment` 마이그레이션 이후
`telemetry_ops` V3가 트리거를 설치한다. 기존 조직의 누락된 요약은 자동 백필하지 않는다.
A/B/C 완료 기록이 있으면 기존 변경을 유지하고 건너뛰며 미완료 기록은 자동 삭제 없이 실패로 알린다.
서버에는 자동 시드와 `PULSEMETRY_LOCAL_SEED_ENABLED` 설정이 없다.
시나리오·기준일 선택과 Docker 초기화/검증은 [개발 시드 가이드](../tools/dev-seed/README.md)를 따른다.
local 프로필의 서버는 메일을 Compose의 수신 컨테이너로 보낸다. 받은 메일은 `http://localhost:8025`에서 본다 — 밖으로 나가는 메일은 없다.
메일 본문 암호화 키는 시드 컨테이너가 `build/dev-auth`에 만든다. 이 키가 없던 기존 개발 환경은 `docker compose up -d --build`를 한 번 더 실행한다.

**팀이 배정되지 않은 초대 대상은 설치 후에도 소속이 없다.** 그러면 `telemetry_events.team_id_as_of` 가 null·`team_ids_as_of` 가 빈 배열이 된다(`member_id` 는
채워진다) — 보강 배선이 틀린 것이 아니다.

### 10.1 파이프라인까지 로컬에서 돌리기

```sh
./gradlew :apps:telemetry-ingest:bootRun   # 4316 — OTLP 수신부터 ClickHouse 적재까지
```

`:apps:telemetry-ingest` 는 같은 DB 를 읽지만 **Flyway 를 돌리지 않는다.** enrollment 스키마의
운영 적용 주체는 `:apps:enrollment-api`이며, 로컬에서는 Compose 시드가 같은 마이그레이션을 먼저 실행한다. ClickHouse 스키마는 이 앱이
기동 시 적용하고, ClickHouse 가 죽어 있어도 앱은 뜬다(ADR 0016).

포트 4316 은 **시드 manifest 의 `otlp.endpoint` 와 이미 맞는다** — 데몬 설정을 바꿀 필요가 없다
(포트의 근거는 ADR 0016).

**시드 manifest 는 `signals.logs: true` 다.** 데몬이 logs 를 보내야 claude_code 이벤트가 행이 된다.
시더는 tenant 에 활성 manifest 가 있으면 건너뛰므로, 이 값이 `false` 이던 시절의 로컬 DB 에서는
행이 생기지 않는다 — `docker compose down -v` 로 볼륨을 새로 만든다.

```sh
# A 시드의 대기 초대 또는 관리자 API로 새로 발급한 코드로 등록한다.
pulsemetry enroll --invite <초대코드> --server http://localhost:8080

# 적재 확인 — 분석 테이블(ADR 0020). 구 enriched_events 는 새 행을 받지 않는다.
curl -s http://localhost:8123 --data-urlencode \
  "query=SELECT tenant_id, installation_id, member_id, team_id_as_of, product, event_type, archive_ref FROM telemetry_events FINAL LIMIT 5"
```

수집 운영 기록(수신 ledger·tenant 생애 요약, ADR 0021)은 기본으로 꺼져 있다. 로컬에서 보려면
`PULSEMETRY_TELEMETRY_OPS_ENABLED=true` 로 ingest 를 띄운다 — `telemetry_ops` 스키마는 `:apps:enrollment-api`
기동이 이미 적용한다.

```sh
curl -s http://localhost:8123 --data-urlencode \
  "query=SELECT receipt_id, signal, product, record_count, rejected_count FROM telemetry_ingest_ledger FINAL LIMIT 5"
psql postgresql://pulsemetry:pulsemetry@localhost:5432/pulsemetry \
  -c "SELECT * FROM telemetry_ops.tenant_ingest_summary"
```

토큰 없이 `POST http://localhost:4316/v1/traces` 를 부르면
`{"error":"unauthorized","message":"Invalid or expired credential"}` 가 401 로 돌아온다.

이 절차가 **compose 단독 E2E** 다 — 자동화는 PROJ-106 에서 이미지·CI 와 함께 다룬다(ADR 0016 Follow-up).

### 10.2 데몬 → 서버 → 화면 실경로 검증

telemetryctl 기본 브랜치의 데몬이 하는 일(등록 · OTLP 전달 · 업데이트 확인)이 서버를 거쳐 화면까지 이어지는지 한 번에 본다(ADR 0053).
설치 보고·정책 재조회는 데몬 쪽 구현이 없어 이 검증에 없다 — 그 화면 상태(적용·미적용, 수집 정상·지연)는 서버 통합 테스트와 프론트 목 테스트의 fixture가 맡는다.

화면 쪽은 프론트의 `tests/e2e-daemon/installation-report.spec.ts`(`playwright.daemon.config.ts`)다. 데몬 쪽과 **같은 단계 디렉터리**로 걸음을 맞춘다 —
데몬 쪽이 단계마다 `<단계>.ready`(관찰값 JSON)를 쓰고 화면 쪽이 확인한 뒤 `<단계>.seen`을 쓴다.
데몬 쪽은 telemetryctl 의 실제 코드(`enroll` 명령의 등록·설정 적용, `internal/forward` 전달기, `internal/updatecheck` 클라이언트)를 돌리는 쪽이 맡는다.
telemetryctl 에는 이 단계를 대신 운전하는 통합 테스트가 없다. CLI 는 OS 키링과 홈의 벤더 설정을 쓰므로 개발자 PC 가 아니라 격리한 사용자 환경에서 돌린다.

준비:

- 세 서버를 local 프로필로 띄운다. ingest 는 `PULSEMETRY_TELEMETRY_OPS_ENABLED=true`. 프론트는 서버의 허용 origin 주소로 띄운다.
- **fresh 조직 E** 를 쓴다(개발 시드 시나리오 E — 정책 1판, 설치·수신 없음. `tools/dev-seed/README.md`). 시드 A·B·C 는 바꾸지 않는다.
  E 는 기본 시나리오 목록에 없으니 시드를 `A,B,C,D,E`처럼 명시해 적재한다. 정책은 새로 저장하지 않는다 — 시드의 1판 manifest 를 받는다.
  그 전달 주소는 시드 수신 주소(`PULSEMETRY_LOCAL_SEED_OTLP_ENDPOINT`, 기본 `http://localhost:4316`)다 — 띄운 ingest 주소로 맞춘다.
  telemetryctl 은 `http` 주소를 호스트가 `localhost`일 때만 받는다(`internal/contract/manifest.go`).
  화면이 설치 수를 정확히 보므로 E 에 다른 설치가 없을 때 돈다 — 실서버 E2E(`tests/e2e`)는 E 에 설치를 등록하지 않는다.
- 업데이트 확인용 릴리스를 `PULSEMETRY_BINARIES_DIR` 에 태그 이름 디렉터리로 둔다(§6.3·§9.2).

```sh
# frontend — 실서버 E2E 와 같은 .env.local 값에 단계 디렉터리를 더한다
E2E_DAEMON_STAGE_DIR=<단계 디렉터리> npx playwright test --config=playwright.daemon.config.ts
```

| 단계 | 데몬 쪽이 하는 것과 `<단계>.ready` | 화면이 확인하는 것(대시보드 명세 "공통 헤더 수집 현황"·"정책 적용 현황") |
| --- | --- | --- |
| `enrolled` | 초대 발급 → 실제 `POST /v1/enroll`과 설정 적용. `{installationId}` | 헤더 "수신 대기"(수신 이력 없음), 정책 적용 현황 "확인 불가 1대"·근거 없음 1대(설치 보고도 적용 확인 기록도 없음 — 대시보드 명세 `appliedEvidence` `none`), 설치 행의 근거 "근거 없음"·근거 시각 "-" |
| `collecting` | 전달기로 OTLP 로그 한 묶음 → ingest 2xx. `{installationId}` | 헤더 "수집 상태 확인 불가"·"수집 기기의 보고가 없어 판정할 수 없습니다"와 마지막 수신 — 수신이 있어도 보고 없이 정상이라고 하지 않는다 |
| `updates` | 업데이트 확인 → 릴리스의 판·업데이트 있음, 릴리스를 치우면 미지원. `{latestVersion}` | 없음(데몬 쪽 상태) |

이 검증은 조직 E 에 초대·설치·수집 데이터를 실제로 만든다. 다시 돌리려면 DB 볼륨을 새로 만든다(`docker compose down -v` 뒤 다시 올린다).

테스트는 Testcontainers 로 실제 PostgreSQL 을 띄우므로 Docker 데몬이 필요하다.
H2 등 임베디드 DB 로 대체하지 않는다 — jsonb·부분 유니크 인덱스·스키마 분리를 검증할 수 없다.

과거 native enum 채택(ADR 0009) 때 V1 체크섬이 변경된 이력이 있다. 체크섬 불일치는 해당 DB의 이력과 백업을 먼저 확인한다. 현재 OIDC V28·V29 적용은 기존 데이터 보존을 전제로 하며, 로그인 설정 변경을 위해 DB 볼륨을 초기화하지 않는다.



## 14. 소유 스키마와 검증

Flyway가 enrollment 스키마의 진실원이다. 관련 추가 마이그레이션은 다음과 같다.

| 버전 | 저장 대상·변경 |
| --- | --- |
| V5 | 사용자 세션·인증 코드·로그인 제한, 초대의 가입 소비 상태 |
| V6 | managed_vendors·vendor_contract_versions·멱등 명령 응답 |
| V7 | 정책 확인·온보딩 기록 |
| V8 | 완료 시각을 tenants로 이전, 완료 여부 생성 컬럼 |
| V9 | managed_vendors.archived 동기화, 조직·제품당 활성 등록 하나의 부분 유일 인덱스 |
| V10 | 공통 공급사·제품·플랜 카탈로그와 초기 목록 |
| V11 | openai_biz의 복수 좌석 유형 입력 허용 |
| V28 | 비밀번호 컬럼 제거, OIDC issuer/subject·임시 왕복 세션 추가, 구 사용자 세션/code 폐기 |
| V29 | issuer를 tenants로 이관, 회사별 client ID·비밀 참조·SSO 활성/이메일 검증 설정 추가, 회원 sub 유지 |

PROJ-187의 V13~V27 뒤에 SSO V28·V29를 적용한다. 통합 전 SSO V12·V13을 적용한 개발 DB는 이력 충돌이 있으므로 별도 백업·이관 계획이 필요하다. 자동 repair나 볼륨 초기화는 수행하지 않는다.
V9는 기존 버전 이력의 보관 여부를 반영한 뒤 중복 활성 제품을 검사한다.
중복이 있으면 적용을 중단하며 자동 병합·삭제하지 않는다. 해당 조직의 중복 등록을 검토한 뒤 다시 적용한다.
telemetry_ops의 V3는 별도 이력으로 관리하며 신규 조직 생성 시 빈 수집 요약을 원자적으로 초기화한다(ADR 0034).
적용된 migration 파일을 수정하지 않고 다음 번호를 추가한다.
organization_onboarding에는 정책 확인자·시각과 완료자만 남는다. 기존 완료 시각은 V8에서 보존한다.
개발 시드 초기화는 이 기록도 해당 시드 조직에 한해 삭제한다.

주요 검증은 `:apps:enrollment-api:test`의 OidcLoginApiTest(OIDC·최초 sub 연결·동시 연결), UserAuthApiTest(서비스 인증), ManagementApiTest(관리·온보딩)다.
팀/초대/계약 쓰기, 조직 격리, 온보딩 완료 조건, 정책 판 보존, 재발급 소비 상태 계승을 실제 PostgreSQL에서 확인한다.
서버 API 구현, 프론트 배선, 실제 시드 E2E 통과는 별도로 확인한다.
프론트 온보딩에서 계약 입력은 선택이며 설정의 계약 관리도 API에 연결돼 있다.
초대 메일과 문의 통지는 `InvitationMailApiTest`가 실제 SMTP(메일 수신 컨테이너)로 도착을 확인한다.
활성 구성원 설치 코드는 `InstallationInvitationApiTest`가 새 코드의 실제 enroll·가입 거절·남은 설치 코드 폐기·설치 경로만 담은 메일을 확인한다.
전체 화면의 연동 완료 여부는 [E2E 목표 시나리오](frontend-e2e-scenarios.md)와 실제 실행 결과를 대조한다.



## 이전 API 절 안내

기존 절 번호·앵커로 들어온 경우 아래 링크에서 상세 명세를 확인한다.

<a id="2-엔드포인트"></a>

## 2. 엔드포인트

→ [공통 HTTP 규칙](api/common.md)

<a id="21-post-v1invitations-요청응답"></a>

### 2.1 `POST /v1/invitations` 요청·응답

→ [초대·설치 코드](api/invitations.md)

<a id="22-cli-설치-등록--post-v1enroll"></a>

### 2.2 CLI 설치 등록 — `POST /v1/enroll`

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="23-텔레메트리-토큰-재발급--post-v1installationstelemetry-token"></a>

### 2.3 텔레메트리 토큰 재발급 — `POST /v1/installations/telemetry-token`

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="24-설치-스크립트와-바이너리-다운로드"></a>

### 2.4 설치 스크립트와 바이너리 다운로드

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="22-post-v1inquiries-도입-문의-접수"></a>

### 2.2 `POST /v1/inquiries` 도입 문의 접수

→ [도입 문의](api/inquiries.md)

<a id="3-초대-코드"></a>

## 3. 초대 코드

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="31-정규화"></a>

### 3.1 정규화

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="32-소비"></a>

### 3.2 소비

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="4-인증과-자격증명"></a>

## 4. 인증과 자격증명

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="41-관리자-api"></a>

### 4.1 관리자 API

→ [공통 HTTP 규칙](api/common.md)

<a id="42-enroll"></a>

### 4.2 enroll

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="43-2단-토큰-모델과-봉투-분리"></a>

### 4.3 2단 토큰 모델과 봉투 분리

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="44-규격"></a>

### 4.4 규격

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="45-설치-보고--post-v1installationsinstallation_idheartbeat"></a>

### 4.5 설치 보고 — `POST /v1/installations/{installation_id}/heartbeat`

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="5-manifest"></a>

## 5. Manifest

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="51-저장"></a>

### 5.1 저장

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="511-dbml-과의-의도적-차이-schema-drift"></a>

### 5.1.1 dbml 과의 의도적 차이 (SCHEMA-DRIFT)

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="52-enroll-응답"></a>

### 5.2 enroll 응답

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="6-부트스트랩-스크립트와-바이너리"></a>

## 6. 부트스트랩 스크립트와 바이너리

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="61-get-windows-get-unix"></a>

### 6.1 `GET /windows`, `GET /unix`

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="62-get-binfilename"></a>

### 6.2 `GET /bin/{filename}`

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="63-get-apiv1check-updates--데몬-업데이트-확인"></a>

### 6.3 `GET /api/v1/check-updates` — 데몬 업데이트 확인

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="7-에러-계약"></a>

## 7. 에러 계약

→ [공통 HTTP 규칙](api/common.md)

<a id="11-사용자-인증"></a>

## 11. 사용자 인증

→ [로그인·사용자 인증](api/auth.md)

<a id="110-로그인-페이지--이메일-회사-탐색"></a>

### 11.0 로그인 페이지 — 이메일 회사 탐색

→ [로그인·사용자 인증](api/auth.md)

<a id="111-로그인-페이지--sso-시작"></a>

### 11.1 로그인 페이지 — SSO 시작

→ [로그인·사용자 인증](api/auth.md)

<a id="112-idp-callback--프론트-callback-페이지"></a>

### 11.2 IdP callback → 프론트 callback 페이지

→ [로그인·사용자 인증](api/auth.md)

<a id="113-callback-페이지--서비스-토큰-교환"></a>

### 11.3 callback 페이지 — 서비스 토큰 교환

→ [로그인·사용자 인증](api/auth.md)

<a id="114-공통--로그인-유지로그아웃현재-사용자"></a>

### 11.4 공통 — 로그인 유지·로그아웃·현재 사용자

→ [로그인·사용자 인증](api/auth.md)

<a id="115-폐기된-가입비밀번호-로그인"></a>

### 11.5 폐기된 가입·비밀번호 로그인

→ [로그인·사용자 인증](api/auth.md)

<a id="116-공통-오류와-적용-범위"></a>

### 11.6 공통 오류와 적용 범위

→ [로그인·사용자 인증](api/auth.md)

<a id="117-manifest-재동기화"></a>

### 11.7 manifest 재동기화

→ [CLI 설치 등록·배포](api/enrollment.md)

<a id="12-조직-관리-api"></a>

## 12. 조직 관리 API

→ [공통 HTTP 규칙](api/common.md)

<a id="활성-구성원-설치-코드-adr-0055"></a>

### 활성 구성원 설치 코드 (ADR 0055)

→ [초대·설치 코드](api/invitations.md)

<a id="설치-업데이트-안내-adr-0043"></a>

### 설치 업데이트 안내 (ADR 0043)

→ [설치 현황·업데이트 안내](api/installations.md)

<a id="벤더-연결-adr-0048"></a>

### 벤더 연결 (ADR 0048)

→ [벤더 연결·동기화](api/vendor-connections.md)

<a id="좌석-수동-기록-adr-0048-3의-12행"></a>

### 좌석 수동 기록 (ADR 0048 §3의 1·2행)

→ [좌석 원장·회수·복원](api/seats.md)

<a id="좌석-회수복원-adr-0049"></a>

### 좌석 회수·복원 (ADR 0049)

→ [좌석 원장·회수·복원](api/seats.md)

<a id="알림-규칙-허브-adr-0008-adr-0051의-평가확인"></a>

### 알림 규칙 (허브 ADR 0008, ADR 0051의 평가·확인)

→ [알림·알림 규칙](api/alerts.md)

<a id="조회관리-오류"></a>

### 조회·관리 오류

→ [공통 HTTP 규칙](api/common.md)

<a id="13-온보딩"></a>

## 13. 온보딩

→ [최초 온보딩](api/onboarding.md)

<a id="131-상태와-완료"></a>

### 13.1 상태와 완료

→ [최초 온보딩](api/onboarding.md)

<a id="132-수집-정책-저장"></a>

### 13.2 수집 정책 저장

→ [수집 정책·조직 정책 설정](api/collection-policy.md)

<a id="133-초대-목록재발급"></a>

### 13.3 초대 목록·재발급

→ [초대·설치 코드](api/invitations.md)

<a id="계약-기간-상태-contractstatus"></a>

### 계약 기간 상태 (`contractStatus`)

→ [등록 제품·벤더 계약](api/vendors.md)
