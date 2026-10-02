# Enrollment 서버 명세

데스크탑 CLI 설치·사용자 인증·조직 관리·온보딩을 담당하는 `:apps:enrollment-api`의 동작 명세다.
기본 포트는 8080이다. 대시보드 조회와 공통 벤더 카탈로그는 [Dashboard 서버 명세](dashboard-server-spec.md)를 따른다.
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
초대 이메일은 조직 관리 API의 초대(§12·§13.3)가 보낸다. 관리자 키 경로(`POST /v1/invitations`)는 메일을 보내지 않고 설치 명령을 응답으로 돌려준다.

---

## 2. 엔드포인트

| 메서드 | 경로 | 인증 | 성공 |
|---|---|---|---|
| POST | `/v1/enroll` | 없음 (초대 코드 자체가 자격) | 201 |
| POST | `/v1/installations/telemetry-token` | `Authorization: Bearer <installation_token>` | 200 |
| POST | `/v1/installations/{installation_id}/heartbeat` | `Authorization: Bearer <installation_token>` | 200 |
| GET | `/v1/manifest` | `Authorization: Bearer <사용자 RT>` | 200 |
| GET | `/v1/healthz` | 없음 | 200 |
| POST | `/v1/invitations` | `X-Admin-Token` | 201 |
| POST | `/v1/invitations/{id}/revoke` | `X-Admin-Token` | 204 |
| POST | `/v1/inquiries` | 없음 (출처별 요청 수 제한) | 201 |
| GET | `/windows?code=...` | 없음 | 200 `text/plain` |
| GET | `/unix?code=...` | 없음 | 200 `text/plain` |
| GET | `/bin/{filename}` | 없음 | 200 `application/octet-stream` |
| GET | `/api/v1/check-updates` | 없음 | 200 |

스크립트와 바이너리 경로에는 `/v1` 접두사가 없다. 사용자가 터미널에 붙여넣는 URL 이라 짧아야 한다.

### 2.1 `POST /v1/invitations` 요청·응답

요청 본문:

| 필드 | 필수 | 설명 |
|---|---|---|
| `tenant_id` | 필수 | 초대를 발급할 조직 |
| `created_by_member_id` | 필수 | 발급자. 그 tenant 의 **활성** owner·admin 이어야 한다 |
| `email` | 필수 | 초대 대상. 앞뒤 공백은 정리된다. 같은 tenant 에 이미 있으면 그 member 를 대상으로 삼고, 없으면 `role=member`·`status=invited` 로 새로 만든다 |
| `display_name` | 선택 | 새로 만들어지는 member 의 표시 이름 |
| `expires_in_hours` | 선택 | 생략하면 `pulsemetry.invitation.default-ttl-hours`(기본 72). 1~720(30일, 잠정 상한 — 팀 확정 대상) 범위를 벗어나면 400 `invalid_request` |

```json
{
  "tenant_id": "0f9c…", "created_by_member_id": "3a71…",
  "email": "hong@example.com", "display_name": "홍길동", "expires_in_hours": 72
}
```

응답(201):

```json
{
  "invitation_id": "b21e…",
  "code": "ABCD-EFGH-JKMN",
  "expires_at": "2026-08-12T00:00:00Z",
  "install_commands": {
    "windows": "irm 'https://get.../windows?code=ABCD-EFGH-JKMN' | iex",
    "unix": "curl -fsSL 'https://get.../unix?code=ABCD-EFGH-JKMN' | sh"
  }
}
```

`expires_at` 은 ISO-8601 UTC 다.

**원본 `code` 는 이 응답에서 딱 한 번만 나간다.** DB 에는 해시만 있어 다시 볼 방법이 없고,
그래서 재조회 API 를 두지 않는다. 관리자가 이 응답을 잃으면 새로 발급해야 한다.

---

### 2.2 `POST /v1/inquiries` 도입 문의 접수

로그인 전의 문의 폼이 부르는 공개 경로다. 인증이 없고 **조직·계정·초대를 만들지 않는다** — 접수만 저장한다.
담당자가 확인한 뒤 첫 관리자를 초대하는 절차는 이 API 밖이다.
`pulsemetry.inquiries.enabled=true`일 때만 있다. 꺼져 있으면 404 `not_found`다.

```json
{"company": "코드웍스", "email": "lead@example.test"}
```

| 필드 | 규칙 |
|---|---|
| `company` | 필수. 앞뒤 공백을 뗀 1~100자. 제어 문자(줄바꿈 포함)를 받지 않는다 |
| `email` | 필수. 앞뒤 공백을 떼고 소문자로 바꾼 320자 이하의 주소. 형식은 `local@domain.tld` |

계약에 없는 필드는 400이다. 응답은 201이고 `Cache-Control: no-store`다.

```json
{"inquiryId": "3f6c…", "status": "received", "receivedAt": "2026-09-09T12:00:00Z"}
```

- `status`는 `received` 하나다. 접수했다는 뜻이며 담당자 확인이나 초대 발급을 뜻하지 않는다.
- **담당자 통지**: 메일 기능(`pulsemetry.mail.enabled`)을 함께 켠 배포에서는 저장과 같은 트랜잭션에서 담당자(`pulsemetry.inquiries.notification-recipient`)에게 통지 메일을 적재한다(ADR 0038).
  통지에는 접수 번호·접수 시각·회사명·회사 이메일이 담긴다. 재전송은 새로 저장하지 않으므로 통지도 한 번이다.
  문의 응답에는 통지의 발송 상태를 싣지 않는다. 메일이 꺼져 있으면 문의는 저장만 된다.
- **재전송**: 같은 회사명·이메일이 `duplicate-window` 안에 다시 오면 저장하지 않고 앞선 접수의 본문을 그대로 돌려준다(201, 같은 `inquiryId`·`receivedAt`).
  같은지는 정규화한 값으로 본다 — 회사명은 NFKC·소문자·연속 공백 하나, 이메일은 소문자. 판정은 가장 최근 접수가 기준이고,
  그 시간이 지난 뒤의 같은 입력은 새 문의다. 같은 입력의 동시 요청도 한 번만 저장한다.
- **남용 제한**: 출처 주소(서블릿 `remoteAddr`)별로 `rate-limit.window` 안에 `rate-limit.requests`회까지 받는다. 검증에 실패한 요청과 재전송도 센다.
  넘으면 429 `rate_limited`와 `Retry-After`(창이 끝날 때까지의 초)다. preflight(`OPTIONS`)는 세지 않는다.
  제한 상태와 문의 행에는 주소의 SHA-256만 남긴다. forwarded 헤더를 믿지 않으므로 프록시 뒤에서는 프록시 주소 단위의 제한이 된다(사용자 인증의 IP 제한과 같다 — ADR 0018).
- **CORS**: `/v1/inquiries`는 `pulsemetry.inquiries.allowed-origins`의 출처에만 `POST`·`Content-Type`을 허용하고 `Retry-After`를 노출한다. 사용자 인증의 출처 목록과 따로 둔다.

오류 본문은 §7의 두 필드 형태다. 문장은 CLI 가 아니라 문의 폼의 사용자에게 보인다.

| 상황 | HTTP | error |
|---|---|---|
| 필드 누락·형식 오류·계약에 없는 필드·JSON 아님 | 400 | `invalid_request` |
| 출처의 요청 수 초과 | 429 | `rate_limited` + `Retry-After` |
| 저장소 장애 | 503 | `inquiry_unavailable` + `Retry-After: 1` |

## 3. 초대 코드

- 형식: Crockford Base32 12자를 `XXXX-XXXX-XXXX` 로 끊은 것.
- 알파벳: `0123456789ABCDEFGHJKMNPQRSTVWXYZ` — `I` `L` `O` `U` 를 제외한 32자.
  사람이 코드를 옮겨 적거나 불러 주는 상황을 전제하므로 헷갈리는 글자를 애초에 만들지 않는다.
- 정규식: `^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$`
- 생성은 `SecureRandom`. 알파벳 크기가 32(2의 거듭제곱)라 모듈로 편향이 없다.

enroll 요청의 `platform` 은 클라이언트가 `runtime.GOOS` 를 그대로 보낸다.
따라서 macOS 는 `darwin` 으로 도착하며 **서버가 `macos` 로 정규화해** 저장한다.
이미 정규화된 `macos` 도 그대로 받는다. `windows`·`linux` 는 바꾸지 않고,
그 밖의 값은 400 `invalid_request` 다.

### 3.1 정규화

`POST /v1/enroll` 은 입력을 정규화한다: 앞뒤 공백 제거 → 대문자 → 하이픈이 없으면 4자마다 삽입.
그러고도 정규식을 만족하지 못하면 400 `invalid_request` 다.

`GET /windows`·`GET /unix` 는 **정규화하지 않고** 정규식 검증만 한다(§6.1 참조).

### 3.2 소비

코드 소비는 **조건부 UPDATE 한 문장**이다.

```sql
UPDATE enrollment.invitations
SET used_at = :now
WHERE code_hash = :codeHash
  AND used_at IS NULL
  AND revoked_at IS NULL
  AND expires_at > :now
```

`SELECT` 로 상태를 확인한 뒤 `UPDATE` 하지 않는다. 그 사이에 다른 요청이 같은 코드를 쓸 수 있다.
영향 행 수가 1이면 소비 성공이고, 0이면 그때서야 사유를 조회해 §7 의 에러로 옮긴다.
동시 요청 N개가 같은 코드로 들어와도 정확히 하나만 201 을 받고 나머지는 409 `invitation_used` 다.

---

## 4. 인증과 자격증명

### 4.1 관리자 API

`X-Admin-Token` 헤더를 설정값 `pulsemetry.admin.api-token` 과 비교한다.
비교는 `MessageDigest.isEqual` 로 한다 — `==` 는 첫 불일치에서 반환하므로
응답 시간 차이로 키를 한 글자씩 알아낼 수 있다.

헤더가 없든 값이 틀리든 똑같이 401 `unauthorized` 다. 둘을 구분해 주지 않는다.

`X-Admin-Token` 을 통과해도 `POST /v1/invitations` 는 아래 경우 전부 **403 `forbidden`** 이다
(404 가 아니다 — 어느 member 가 존재하는지 알려 주지 않는다).

- `created_by_member_id` 가 존재하지 않는다
- 그 member 가 `tenant_id` 소속이 아니다
- 그 member 가 owner·admin 이 아니다
- 그 member 가 정지(`status = 'suspended'`)됐다
- **초대 대상** member 가 이미 있고 정지됐다

`invited` 대상은 정상이다 — 아직 설치하지 않은 사용자에게 코드를 재발급하는 경로다.

**설정값이 비어 있으면 애플리케이션이 기동하지 않는다.** 빈 문자열을 "인증 없음" 으로 해석하면
설정 실수 하나로 초대 발급이 인터넷에 열린다.

### 4.2 enroll

인증이 없다. 초대 코드 자체가 자격증명이므로, 코드의 형식 검증과 원자적 소비,
그리고 소비 직후의 대상 멤버 정지 검사가 전부다. 대상 멤버가 `suspended` 면
403 `forbidden` 으로 끊는다 — 발급 시점 차단(§4.1)과 같은 정책이다. 트랜잭션
롤백으로 소비도 취소되므로 코드는 살아 있고, 정지 해제 뒤 같은 코드로 다시 설치할 수 있다.

**enroll 성공은 대상 멤버의 `invited → active` 전환 이벤트다.** OTLP 경로의
auth-proxy(ai-telemetry-pipeline)가 `invited`·`suspended` 멤버의 토큰을 거부하므로,
이 전환 없이는 발급된 telemetry token 이 전부 401 이 된다. 재발급(§4.3의
`POST /v1/installations/telemetry-token`)도 같은 전환을 보정한다 — pit_ 인증이 과거
enroll 완료의 증명이기 때문이다. 전환은 `invited` 에서만 일어난다. `suspended` 는
어느 경로도 건드리지 않는다 — 정지 해제는 관리자의 결정이지 설치의 부수효과가 아니다.

### 4.3 2단 토큰 모델과 봉투 분리

설치된 클라이언트는 서로 역할이 다른 두 비밀을 갖는다.

| 토큰 | 접두사 | 저장 위치 | 용도 | 교체 |
|---|---|---|---|---|
| `installation_token` | `pit_` | OS 키링 | 이 설치의 장기 신원 | 하지 않는다 |
| `telemetry_token` | `ptt_` | OS 키링 (데몬이 상위 전송 시 `Authorization` 에 주입) | 텔레메트리 전송 | 언제든 재발급 |

`telemetry_token` 은 벤더 설정 파일로 나가지 않는다. enroll 이 로컬 텔레메트리 파이프라인을
자동 배선하면서 Codex/Claude 설정에는 **로컬 ingest 토큰**이 들어가고, 회사 `ptt_` 는 OS
키링에 저장되어 데몬이 상위 전송 시 `Authorization` 헤더에 주입한다(telemetryctl `forward.go`).
이 서술은 로컬 배선이 성립할 때다 — grpc manifest·키링 불가 등으로 회사 직결로 강등된 설치에서는
벤더 설정의 `Authorization` 에 `ptt_` 가 실린다(허브 `contracts/telemetry-ingest.md` §6).
그래도 `ptt_` 는 전송 경로에 실리는 값이라 유출을 전제로 언제든 교체할 수 있어야 한다.
`installation_token` 은 그 교체를 요청할 근거이며, 재발급 요청과 설치 보고(§4.5) 외에는 키링 밖으로 나가지 않는다.

**봉투 분리**: `installation_id` 와 두 토큰은 "설정" 이 아니라 이 설치의 자격이므로
manifest **밖**, 응답 봉투 상위에 둔다. manifest 안에 넣지 않는 이유는 두 가지다.

1. 설정 재조회 API 가 생겼을 때 매번 secret 을 실어 나르지 않기 위해서다.
2. 클라이언트가 `DisallowUnknownFields` 로 파싱하며 이 설정이 **중첩 manifest 까지 적용된다.**
   manifest 안에 봉투 필드가 하나라도 있으면 설치가 그 자리에서 실패한다.

### 4.4 규격

- `installation_token`: `pit_` + base64url(32 랜덤 바이트, 패딩 없음)
- `telemetry_token`: `ptt_` + base64url(32 랜덤 바이트, 패딩 없음)
- 해시: 초대 코드와 `installation_token` 은 **SHA-256 hex 소문자 64자**.
  `telemetry_token` 만 **HMAC-SHA256(`pulsemetry.token-hash-secret`, 토큰 전문) hex 소문자 64자** —
  auth-proxy 가 같은 키·같은 연산으로 `token_hash` 를 조회하기 때문이다 (§8)

토큰과 초대 코드의 원본은 DB 에 저장하지 않는다. `*_hash` 컬럼에는 해시만 들어간다.
해시가 결정론적이어야 유니크 인덱스 조회가 성립하므로 bcrypt·Argon2 를 쓸 수 없다.
원본이 고엔트로피 난수(토큰 256비트, 초대 코드 60비트)라 사전 공격 대상이 아니라는 전제 위에 서 있으며,
사람이 고른 비밀번호에는 이 방식을 쓸 수 없다.

토큰과 초대 코드 원본을 **로그에 남기지 않는다.** 에러 응답에도 담지 않는다 —
파싱 실패 메시지에는 요청 본문 조각이 섞여 있어 그대로 흘리면 코드가 새어 나간다.

### 4.5 설치 보고 — `POST /v1/installations/{installation_id}/heartbeat`

데몬이 생존, 적용한 manifest 판, 수집 경로의 상태를 주기적으로 보고한다. 데몬의 동작은
허브 `contracts/enrollment-api.md` §7이 정하고, 서버가 받는 요청·응답의 필드는 아래 두 표가 정한다 — 서버의 계약 테스트(`HeartbeatApiTest`)가 이 표를 오라클로 쓴다.
원격 telemetryctl develop에는 이 경로의 JSON Schema도 송신 클라이언트도 아직 없다.
서버의 검증과 저장은 [ADR 0040](adr/0040-설치-보고는-최신-상태-한-행과-수집-구간-이력으로-저장한다.md)을 따른다.
`pulsemetry.heartbeat.enabled`로 켠다. 꺼져 있으면 이 경로는 404다.

**처리 순서**

1. 자격증명을 본다. 판정은 토큰 재발급(§4.3)과 같다 — 없거나 무효면 401 `unauthorized`. `ptt_`와 사용자 토큰은 받지 않는다. **인증되지 않은 요청의 본문은 읽지 않는다.**
2. 본문을 읽는다(16 KiB까지, `Content-Type: application/json`). 아래 요청 표를 어기는 본문을 400 `invalid_request`로 거부한다.
   이 경로는 모르는 키를 무시한다(중첩 객체 안에서도). `receiving_since`·`last_delivered_at`이 `sent_at`보다 뒤면 400이다.
3. 설치 행을 잠근다. 폐기된 설치면 403 `installation_revoked`, 경로의 `installation_id`가 자격증명의 설치와 다르면 403 `forbidden`이다.
4. 한 트랜잭션으로 기록하고 200으로 답한다(`Cache-Control: no-store`).

**요청**

```json
{"sent_at": "2026-09-30T01:05:00Z",
 "daemon": {"version": "0.2.0", "platform": "darwin", "architecture": "arm64", "run_id": "b3f1c2a49d5e4f60a1b2c3d4e5f60718"},
 "applied_config_revision": 3,
 "collection": {"mode": "local", "receiving_since": "2026-09-30T00:00:01Z", "forwarding": true,
                "delivered": 120, "lost": 0, "pending": 2, "last_delivered_at": "2026-09-30T01:04:30Z"}}
```

표의 키는 **모두 필수**다. null은 "또는 null"이라고 적은 두 칸만 받는다. 시각은 UTC RFC 3339(`Z`, 소수 초 허용)다 — 오프셋 표기·공백 구분·숫자는 받지 않는다.

| 필드 | 타입 |
|---|---|
| `sent_at` | 시각 |
| `daemon` | 객체 |
| `daemon.version` | 문자열 1~64자 |
| `daemon.platform` | `darwin` · `linux` · `windows` |
| `daemon.architecture` | 문자열 1~32자 |
| `daemon.run_id` | 문자열 16~64자, `[A-Za-z0-9_-]` |
| `applied_config_revision` | 정수 ≥ 1 (JSON 수 `3.0`은 정수 3이다) |
| `collection` | 객체 |
| `collection.mode` | `local` · `direct` |
| `collection.receiving_since` | 시각 또는 null |
| `collection.forwarding` | 불리언 |
| `collection.delivered` · `lost` · `pending` | 정수 ≥ 0 |
| `collection.last_delivered_at` | 시각 또는 null |

**기록하는 것**

| 곳 | 값 |
|---|---|
| `installations.last_seen_at` | **서버가 받은 시각.** 데몬 시계의 값이 아니다 |
| `installations.client_version` | 데몬 버전(50자까지). `updated_at`은 바꾸지 않는다 |
| `installation_heartbeats` | 설치당 한 행 — 마지막 보고의 값 전부(프로세스 식별자, 버전·아키텍처, 보고한 판과 그 manifest, 수집 경로, 누적 개수, 시각)와 전달 대기가 이어지기 시작한 시각(`pending_since`) |
| `installation_collection_segments` | 수집 중이었다고 보고로 확인된 구간. 손실이 보고된 구간은 따로 남긴다(`lost` > 0) |
| `installation_manifest_assignments.applied_at` | 보고한 판이 그 조직의 manifest일 때, 그 판을 **처음** 보고받은 시각. 배정 행이 없으면 만든다 |

- 본문의 시각은 (받은 시각 − `sent_at`)만큼 옮겨 저장한다. 데몬 시계가 틀려도 구간의 길이는 맞다.
- 수집 구간은 경로가 `local`이고 상위 전달기가 돌고 수신기가 듣고 있을 때만 쓴다. 같은 프로세스가 손실 없이 이어 보고하면 구간을 늘리고,
  프로세스가 바뀌거나 수집이 끊겼다 이어지면 새 구간을 연다. 잃은 개수가 늘면 직전 보고부터 이번 보고까지가 손실 구간이다. 등록 시각보다 앞은 자른다.
- **적용 확인은 보고로만 생긴다.** enroll과 정책 저장은 `applied_at`을 채우지 않는다. 서버가 그 조직의 판으로 갖고 있지 않은 판을 보고하면
  적용 확인을 기록하지 않고 응답의 `acknowledged_config_revision`은 null이다. 그래도 생존은 기록한다.
- 설치가 **지금 집행하는 판**은 `installation_heartbeats.applied_config_revision`이다. `applied_at`은 그 판을 적용한 적이 있다는 이력이다.
- 구간은 끝 시각이 `history-retention`보다 오래되면 그 설치의 다음 보고 때 지운다.
- `pending_since`는 전달 대기(`pending`)가 0이 아닌 보고가 **이어지기 시작한** 보고의 수신 시각이다(ADR 0041). 같은 프로세스가 다음 보고에서도 대기를 말하면 그대로 두고,
  대기가 0이면 비우고, 프로세스가 바뀌면 그 보고부터 다시 센다. 조회 쪽이 "대기가 이어지는 중"과 "보고 순간에 마침 전송 중"을 가르는 데 쓴다.

**응답**

```json
{"received_at": "2026-09-30T01:05:00.412Z", "expected_config_revision": 4, "acknowledged_config_revision": 3, "report_interval_seconds": 300}
```

| 필드 | 타입 |
|---|---|
| `received_at` | 시각(UTC RFC 3339, `Z`) |
| `expected_config_revision` | 정수 ≥ 1 또는 null |
| `acknowledged_config_revision` | 정수 ≥ 1 또는 null |
| `report_interval_seconds` | 정수 60~3600 |

`expected_config_revision`은 그 조직의 활성 manifest 판(없으면 null), `report_interval_seconds`는 `pulsemetry.heartbeat.report-interval`이다.
응답은 manifest를 싣지 않는다 — 재조회는 §11.1이다.

저장소 장애는 **503 `heartbeat_unavailable` + `Retry-After`** 다. 401·403으로 돌리지 않는다 — 데몬이 재등록이 필요하다고 오해한다.

---

## 5. Manifest

회사 단위 OTel 설정이며 계약은 `telemetryctl/contracts/enrollment-manifest.schema.json` 이다.

```json
{
  "schema_version": 1,
  "config_revision": 1,
  "otlp": { "endpoint": "https://...", "protocol": "http/protobuf",
            "compression": "gzip", "timeout_ms": 10000 },
  "signals": { "logs": false, "metrics": true, "traces": true },
  "privacy": { "collect_user_prompts": false, "collect_assistant_responses": false,
               "collect_tool_details": false, "collect_tool_content": false,
               "collect_user_email": false, "collect_raw_api_bodies": false },
  "repository_allowlist": [],
  "resource_attributes": {}
}
```

### 5.1 저장

- 저장 위치는 `enrollment.manifests.manifest` (jsonb). 기존 행을 고치지 않고 설정이 바뀌면 새 `version` 행을 만든다.
- tenant 당 `is_active = true` 행은 **최대 하나**다. 부분 유니크 인덱스
  `UNIQUE (tenant_id) WHERE is_active` 가 이를 보장한다.

### 5.1.1 dbml 과의 의도적 차이 (SCHEMA-DRIFT)

ADR 0004 가 요구한 기록처다 — 스키마가 설계도(`rdb-schema/dbdiagram.dbml`)와 **의도적으로**
다르게 이식된 지점을 여기에 남긴다. 마이그레이션·엔티티·테스트의 `SCHEMA-DRIFT` 주석이 이 절을 가리킨다.

| # | 차이 | 위치 | 이유 |
|---|---|---|---|
| 1 | `enrollment.manifests` 의 부분 유니크 인덱스 `ux_manifests_tenant_active` — dbml 에 없다 | `V2__manifests_single_active_index.sql` | enroll 이 "tenant 의 활성 manifest" 를 단수로 가정하므로 DB 가 보장한다. baseline 된 DB 에도 적용되도록 V1 이 아니라 V2 에 두었다 |
| 2 | `enrollment.telemetry_tokens` 의 부분 유니크 인덱스 `ux_telemetry_tokens_installation_active` — dbml 에 없다 | `V3__telemetry_tokens_single_active_index.sql` | 재발급("전부 폐기 후 발급") 계약의 동시성 최종 방어선 |

### 5.2 enroll 응답

- enroll 응답에는 저장된 manifest 를 싣되 **`config_revision` 만 `manifests.version` 으로 덮어쓴다.**
- 활성 manifest 가 없으면 enroll 은 409 `manifest_not_configured` 로 실패한다.
- 저장된 manifest 가 계약을 어기고 있으면(알 수 없는 필드 등) 서버가 같은 409 로 끊는다.
  어차피 클라이언트가 거부할 응답을 내려보내지 않고, 관리자가 고치도록 안내한다.

클라이언트가 한 번 더 검증하는 항목:

- `otlp.endpoint` 는 **https 필수**. `http` 는 `localhost` 에만 허용된다.
- `otlp.protocol` 은 `http/protobuf` · `http/json` · `grpc` 뿐이다.
  단 **`grpc` 는 계약상 유효해도 클라이언트가 배선하지 못한다** — telemetryctl 이 grpc 상위 전송을
  지원하지 않아 그 테넌트의 설치는 로컬 파이프라인 배선에서 제외되고 회사 직결로 강등된다
  (로컬 대시보드가 빈다 — telemetryctl ADR 0001·0006, 허브 `contracts/telemetry-ingest.md` §6).
  grpc 테넌트를 구성하기 전에 이 제약을 확인한다. 현재 grpc 테넌트는 없다.
- `otlp.timeout_ms` 는 1 이상, `otlp.compression` 은 `none` · `gzip` 뿐이다(계약 스키마).
- `schema_version` 이 클라이언트의 `SupportedSchemaVersion`(현재 1)을 넘으면 거부한다.

서버가 이 규칙을 어긴 값을 주면 설치가 실패한다.

---

## 6. 부트스트랩 스크립트와 바이너리

### 6.1 `GET /windows`, `GET /unix`

- `code` 를 **정규식으로만** 검증한다. 위반이면 400 `invalid_request`.
- **DB 를 조회하지 않는다.** 형식만 맞으면 코드가 실제로 존재하든 아니든 같은 스크립트를 준다.
  인증 없는 공개 엔드포인트가 코드의 유효성을 알려 주면 코드 탐색 오라클이 된다.
- 검증을 통과한 코드만 스크립트에 삽입한다. 정규식이 허용하는 32자에는 셸·PowerShell 메타문자가
  없으므로 **이스케이프하지 않는다** — 화이트리스트가 방어선이다.
- `Content-Type: text/plain;charset=UTF-8`, `Cache-Control: no-store`.
- 스크립트 안의 서버 주소는 설정값 `pulsemetry.public-base-url` 에서만 온다.
  **`Host` 헤더에서 유도하지 않는다.** 이 설정값도 기동 시 형식을 검증한다.

스크립트 본문은 `apps/enrollment-api/src/main/resources/bootstrap/` 의 리소스 파일이며
`__PULSEMETRY_INVITE_CODE__` · `__PULSEMETRY_SERVER__` 두 자리만 치환된다.

### 6.2 `GET /bin/{filename}`

아래 여섯 개와의 **문자열 동등 비교만** 한다.

```text
pulsemetry_windows_amd64.exe   pulsemetry_windows_arm64.exe
pulsemetry_darwin_amd64        pulsemetry_darwin_arm64
pulsemetry_linux_amd64         pulsemetry_linux_arm64
```

목록에 없으면 404. 목록에 있어도 `pulsemetry.binaries.dir` 에 파일이 없으면 404.
`..` 를 문자열 치환으로 지우거나 경로를 정규화해 방어하지 않는다 — 인코딩 변형에 언젠가 뚫린다.

응답 헤더는 `Content-Type: application/octet-stream` 과 함께
`Content-Disposition: attachment; filename="…"` · `Content-Length` 를 싣는다.

바이너리는 서버 로컬 디렉터리에서 서빙한다. S3·GitHub Releases 리다이렉트를 쓰지 않는다.

### 6.3 `GET /api/v1/check-updates` — 데몬 업데이트 확인

데몬이 기동 직후와 24시간마다 "내 버전보다 새 데몬이 있는가"를 묻는다. 계약은 허브 `contracts/daemon-updates.md`(허브 ADR 0011)이고,
실제 호출자는 원격 telemetryctl develop의 `internal/updatecheck/client.go`다 — 서버의 계약 테스트(`UpdateCheckApiTest`)는 그 클라이언트가 보내는 쿼리와
읽는 응답(JSON 문서 하나, 공백이 아닌 `latest_version`, 빠지면 안 되는 불리언 `update_available`, 404는 미지원)을 오라클로 쓴다.
이 경로의 JSON Schema는 원격에 없다. 인증이 없다 — 데몬이 인증 정보를 보내지 않는다.
알려 주기만 한다. 내려받기와 설치는 하지 않는다.

쿼리는 셋 다 필수다: `current_version`(SemVer, `v` 없음) · `platform`(`darwin`·`linux`·`windows`) · `architecture`(`amd64`·`arm64`). 그 밖의 쿼리는 무시한다.

```json
{"latest_version": "0.2.0", "update_available": true}
```

- **최신 버전은 이 서버가 §6.2로 배포하는 바이너리의 판이다.** 외부 릴리스 목록을 보지 않는다.
  판은 `pulsemetry.binaries.dir`의 `pulsemetry_release.json`이 말한다 — `{"version": "0.2.0", "sha256": {"<파일명>": "<소문자 hex 64자>", …}}`.
  `version`은 SemVer(`v` 없음)이고 `sha256`은 비어 있지 않은 객체다. `sha256`의 키는 §6.2의 여섯 이름뿐이다. 모르는 최상위 키는 무시한다.
  이 파일은 `/bin`으로 서빙하지 않는다.
- 요청의 `platform`·`architecture`로 파일명(`pulsemetry_{platform}_{architecture}`, Windows만 `.exe`)을 정한다. 허용 목록과의 동등 비교뿐이다.
  **그 파일이 있고 SHA-256이 메타데이터와 같을 때만** 그 버전을 답한다. 해시는 파일의 크기·수정 시각이 바뀔 때만 다시 계산한다.
- `update_available`은 `current_version` < `latest_version`일 때만 true다. 순서는 SemVer 2.0.0의 우선순위 규칙이다 —
  사전 릴리스는 같은 번호의 정식 판보다 낮고 빌드 메타데이터는 순서에 영향을 주지 않는다. 버전을 주입하지 않은 빌드(`0.1.0`)를 따로 취급하지 않는다.
- 응답은 두 키의 JSON 문서 하나이고 `Cache-Control: no-store`다. 리다이렉트를 내지 않는다(데몬이 따라가지 않는다).

| 상황 | HTTP | error |
|---|---|---|
| 쿼리가 빠졌거나 비었다, `current_version`이 SemVer가 아니다(`v0.2.0`·`dev` 등) | 400 | `invalid_request` |
| 메타데이터가 없거나 형식이 틀렸다 | 404 | `not_found` |
| `platform`·`architecture`가 여섯 파일명으로 이어지지 않는다 | 404 | `not_found` |
| 그 대상의 바이너리가 없거나, 메타데이터에 없거나, 해시가 다르다 | 404 | `not_found` |

404는 "업데이트 없음"이 아니라 "이 서버가 확인해 줄 수 없음"이다. 데몬은 미지원으로 표시한다.
**확인할 수 없을 때 임의의 버전이나 `update_available=false`로 답하지 않는다.**
별도 스위치는 없다 — 메타데이터 파일을 놓으면 켜지고 없으면 404다. 읽는 도중 파일이 교체되는 경합은 §6.2와 같이 없는 파일로 다룬다.

---

## 7. 에러 계약

CLI 는 non-2xx 본문을 그대로 사용자 터미널에 출력한다. 메시지는 한국어로,
사용자가 다음에 무엇을 해야 할지 알 수 있게 쓴다.

```json
{"error": "invitation_expired", "message": "초대 코드가 만료되었습니다. 관리자에게 새 코드를 요청하세요."}
```

| 상황 | HTTP | error |
|---|---|---|
| 형식 오류 / unknown field / 코드 정규식 위반 / 미지원 platform | 400 | `invalid_request` |
| 초대 코드 없음 | 404 | `invitation_not_found` |
| 이미 사용됨 | 409 | `invitation_used` |
| 폐기됨 | 409 | `invitation_revoked` |
| 만료됨 | 410 | `invitation_expired` |
| active manifest 없음 | 409 | `manifest_not_configured` |
| admin 토큰 불일치 / installation 자격증명 무효 | 401 | `unauthorized` |
| 권한 부족 (admin·owner 아님) | 403 | `forbidden` |
| 대상 member 정지됨 (초대 발급·enroll) | 403 | `forbidden` |
| installation 폐기됨 | 403 | `installation_revoked` |
| 설치 보고의 경로 설치가 자격증명의 설치와 다름 (§4.5) | 403 | `forbidden` |
| 설치 보고의 저장소 장애 (§4.5) | 503 | `heartbeat_unavailable` |
| 알 수 없는 경로 | 404 | `not_found` |
| 지원하지 않는 메서드 | 405 | `method_not_allowed` |
| 문의 접수의 요청 수 초과 (§2.2) | 429 | `rate_limited` |
| 문의 접수의 저장소 장애 (§2.2) | 503 | `inquiry_unavailable` |

사용·폐기가 409 이고 만료만 410 인 이유: 사용과 폐기는 **사람의 행위**로 무효화된 상태라
409 Conflict 가 맞고, 만료는 **시간 경과**로 영구 소멸한 자원이라 410 Gone 이 맞다.
사용자 안내문은 세 경우 모두 "관리자에게 새 코드를 요청하세요"로 같고, 이 구분은 진단·로그 용도다.

소비 실패 사유의 우선순위는 **사용 → 폐기 → 만료**다.
동시 요청에서 진 쪽은 `used_at` 을 보게 되므로 409 `invitation_used` 를 받는다.

---

## 8. 설정

| 키 | 기본값 | 설명 |
|---|---|---|
| `pulsemetry.public-base-url` | `http://localhost:8080` | 설치 명령·스크립트에 박히는 서버 주소. 기동 시 형식 검증 |
| `pulsemetry.admin.api-token` | 없음 | 관리자 API 키. **비어 있으면 기동 실패** |
| `pulsemetry.token-hash-secret` | 없음 | telemetry token 의 HMAC-SHA256 키. **비어 있으면 기동 실패.** auth-proxy(ai-telemetry-pipeline)와 같은 값을 써야 OTLP 인증이 성립한다. dev 인프라에서는 `DevEdgeStack` 의 `TokenHashSecretArn` 이 가리키는 Secrets Manager 값. 키 변경 = 발급된 전 토큰 무효 |
| `pulsemetry.invitation.default-ttl-hours` | `72` | `expires_in_hours` 생략 시 만료 시간 |
| `pulsemetry.binaries.dir` | `./binaries` | CLI 바이너리와 릴리스 메타데이터(`pulsemetry_release.json`, §6.3)가 놓인 서버 로컬 디렉터리 |
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
| `pulsemetry.vendor-connections.base-urls.<커넥터 ID>` · `.gemini-token-url` | 벤더 공식 주소 | 모의 서버·스테이징에서만 바꾼다. 로컬 모의 서버는 `tools/mock-vendor/README.md` |

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

`pulsemetry.binaries.dir` 에 §6.2 의 이름 그대로 파일을 놓는다.
없는 아키텍처는 404 가 되며, 그 아키텍처의 사용자는 설치가 실패한다.

같은 디렉터리에 릴리스 메타데이터 `pulsemetry_release.json`(§6.3)을 함께 놓는다. 바이너리를 교체할 때마다 같이 교체한다.
메타데이터가 없거나 바이너리와 해시가 맞지 않으면 업데이트 확인이 404로 답하고, 설치된 데몬은 업데이트를 "미지원"으로 표시한다.

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

설치 보고 묶음(§4.5 설치 보고, 수집 상태 판정, 정책 재조회·적용, 업데이트 확인)이 끝에서 끝까지 이어지는지 한 번에 본다.
데몬 쪽은 telemetryctl 의 통합 테스트 `TestIntegrationEndToEndInstallationReportFromEnrollmentToDashboard`(빌드 태그 `integration`)가,
화면 쪽은 프론트의 `tests/e2e-daemon/installation-report.spec.ts`(`playwright.daemon.config.ts`)가 맡는다. 둘은 **같은 단계 디렉터리**로 걸음을 맞춘다 —
데몬 쪽이 단계마다 `<단계>.ready`(관찰값 JSON)를 쓰고 화면 쪽이 확인한 뒤 `<단계>.seen`을 쓴다.

데몬 테스트는 데몬 바이너리를 띄우지 않고 **실제 데몬 코드**(`daemon.Run`)를 테스트 프로세스 안에서 임시 설치·메모리 키링으로 돌린다 —
개발자 PC의 키체인·자동 시작 등록을 건드리지 않는다. 서버는 실제 HTTP 로 부른다.

준비:

- 세 서버를 local 프로필로 띄운다(설치 보고 주기 1분, 메일 켬). ingest 는 `PULSEMETRY_TELEMETRY_OPS_ENABLED=true`, dashboard-api 의
  `pulsemetry.dashboard.ingest.*`·`completeness.settle-after` 는 local 값 그대로다. 프론트는 서버의 허용 origin 주소로 띄운다.
- **manifest 가 없는 조직**을 쓴다(시드 B). 테스트가 최초 정책을 저장하면 전달 주소가 `PULSEMETRY_ONBOARDING_OTLP_ENDPOINT` 가 된다 — 띄운 ingest 주소로 맞춘다.
  시드 A 의 manifest 는 `http://localhost:4316` 고정이라 다른 ingest 로 보낼 수 있다. 테스트는 등록으로 받은 전달 주소가 `PULSEMETRY_IT_INGEST_URL` 과 다르면 보내지 않고 멈춘다.
- 업데이트 확인용 릴리스 자산(바이너리와 `pulsemetry_release.json` — telemetryctl `task release:assets`)을 `PULSEMETRY_BINARIES_DIR` 에 둔다(§6.3).

```sh
# telemetryctl — 단계 디렉터리는 비어 있는 새 디렉터리
PULSEMETRY_IT_SERVER_URL=http://localhost:8080 PULSEMETRY_IT_DASHBOARD_URL=http://localhost:8081 PULSEMETRY_IT_INGEST_URL=http://localhost:4316 \
PULSEMETRY_IT_TENANT_ID=<시드 B 조직 ID> PULSEMETRY_IT_ADMIN_EMAIL=owner@seed-b.example.test PULSEMETRY_IT_ADMIN_PASSWORD=<개발 시드 비밀번호> \
PULSEMETRY_IT_RELEASE_METADATA=<바이너리 디렉터리>/pulsemetry_release.json PULSEMETRY_IT_RELEASE_PLATFORM=darwin PULSEMETRY_IT_RELEASE_ARCH=arm64 \
PULSEMETRY_IT_STAGE_DIR=<단계 디렉터리> go test -tags integration -count=1 -timeout 20m -run TestIntegrationEndToEnd ./internal/daemon &

# frontend — 실서버 E2E 와 같은 .env.local 값에 단계 디렉터리를 더한다
E2E_DAEMON_STAGE_DIR=<단계 디렉터리> npx playwright test --config=playwright.daemon.config.ts
```

| 단계 | 데몬 쪽이 만드는 것과 확인하는 것 | 화면이 확인하는 것 |
| --- | --- | --- |
| `enrolled` | 최초 정책 → 초대 발급 → 실제 `POST /v1/enroll` → 데몬 기동 직후 보고에 서버가 그 판을 적용 확인. `GET O/installations` 의 적용 판·`lastHeartbeatAt`, 수집 상태 `empty`(수신 이력 없음)·보고 설치 1 | 정책 적용 현황 "적용 1대", 설치 행의 판·마지막 보고, 헤더 "수신 대기 · 보고 중인 설치 1대" |
| `collecting` | 수신기에 OTLP 로그 → 데몬이 ingest 로 전달 → 수집 상태 `healthy`·`lastReceivedAt` | 헤더 "수집 정상 · 마지막 수신" |
| `outdated` | 관리자가 정책 저장(판 +1). 이 PC 의 사용자 세션이 없어 데몬은 `login_required` — 설치는 미적용·알림 가능 | "미적용 1대", 미적용 목록의 이전 판·선택 칸 |
| `applied` | 사용자 로그인 → 다음 보고의 답으로 재조회·적용 → 서버가 새 판 적용 확인 | "적용 1대", 적용 목록의 새 판 |
| `updates` | 릴리스 메타데이터로 `ready`(최신 판·업데이트 있음), 메타데이터를 치우면 `unsupported` — 끝나면 되돌린다 | 없음(데몬 로컬 API) |

이 검증은 조직에 정책·초대·설치·수집 데이터를 실제로 만든다. 끝나면 DB 볼륨을 새로 만든다(`docker compose down -v` 뒤 다시 올린다).
설치 보고가 1분 주기라 한 번 도는 데 2분 남짓 걸린다.

테스트는 Testcontainers 로 실제 PostgreSQL 을 띄우므로 Docker 데몬이 필요하다.
H2 등 임베디드 DB 로 대체하지 않는다 — jsonb·부분 유니크 인덱스·스키마 분리를 검증할 수 없다.

V1 마이그레이션이 native enum 채택(ADR 0009)으로 재작성되어 Flyway 체크섬이 바뀌었다.
이전 버전으로 만들어진 로컬 DB 는 `docker compose down -v` 로 볼륨째 지우고 다시 띄운다.


## 11. 사용자 인증

`pulsemetry.user-auth.enabled=true`와 인증 키 설정이 필요하다.
로컬에서는 Compose가 키를 준비하고, `:apps:enrollment-api:bootRun --args="--spring.profiles.active=local"`이
인증·관리 기능을 활성화한다. 서버는 키를 생성하지 않는다(ADR 0031).
키 설정 상세는 [사용자 인증 운영](user-auth-operations.md)을 따른다.

| 메서드·경로 | 요청 JSON | 성공 |
| --- | --- | --- |
| `POST /v1/auth/signup` | `code`, `email`, `password` | 201, 본문 없음 |
| `POST /v1/auth/login` | `tenant_id` UUID, `email`, `password` | 200 TokenResponse |
| `POST /v1/auth/refresh` | `refresh_token` | 200 TokenResponse |
| `POST /v1/auth/logout` | `refresh_token` | 204 |
| `GET /v1/auth/me` | Bearer 인증 | 200 CurrentUser |
| `POST /v1/auth/cli/authorize` | `tenant_id`, `email`, `password`, `redirect_uri`, `state`, `code_challenge`, `code_challenge_method: "S256"` | 200 `{callback_url}` |
| `POST /v1/auth/cli/token` | `code`, `redirect_uri`, `code_verifier` | 200 TokenResponse |

로그인 요청은 조직 ID를 필요로 한다. 이메일만으로 조직을 자동 탐색하는 API는 없다.
가입 비밀번호는 12글자 이상·UTF-8 72바이트 이하다. 초대 코드는 설치 소비와 가입 소비가 독립적이다.
AT 유효기간은 5분, 세션은 30일이다. refresh는 RT를 회전시키므로 응답의 새 RT를 사용한다.
이미 소비한 RT를 재사용하면 해당 세션이 폐기된다. 동시 refresh를 클라이언트에서 하나로 합친다.

```ts
type TokenResponse = {
  access_token: string; refresh_token: string;
  token_type: "Bearer"; expires_in: number;
};
type CurrentUser = {
  memberId: string; organizationId: string; organizationName: string;
  email: string; displayName: string; role: "admin" | "member";
};
```

`token_type`은 `Bearer`, `expires_in`은 300이다. `refresh_token`은 `urt_` 뒤에 32바이트 난수의 base64url(패딩 없음, 43자)이다.
`access_token`은 RS256 JWT다(헤더 `typ: JWT`, `kid`). 클레임은 아래 열 개뿐이다 — 서버의 계약 테스트(`ManifestResyncApiTest`)가 이 표를 오라클로 쓴다.

| 클레임 | 값 |
| --- | --- |
| `iss` · `aud` | 설정의 `pulsemetry.user-auth.issuer` · `audience` |
| `sub` | 구성원 ID(UUID) |
| `tenant_id` | 조직 ID(UUID) |
| `role` | `owner` · `admin` · `member` |
| `sid` | 세션 ID(UUID) |
| `manifest_revision` | 세션이 기억하는 manifest 판(정수 ≥ 0, 활성 manifest가 없으면 0) |
| `iat` · `exp` | 발급·만료 시각(초). `exp − iat`는 300 |
| `jti` | 토큰마다 다른 ID |

인증 오류는 `{error: string, message: string}`이다.
400 `invalid_request`, 401 `invalid_credentials`, 409 `signup_unavailable`,
429 `rate_limited`, 503 `auth_unavailable`. 429·503의 `Retry-After`를 따른다.
응답은 `Cache-Control: no-store`다. 상세 DTO는 [UserAuthController](../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/UserAuthController.kt)를 참조한다.

계약·벤더·manifest·온보딩 완료 여부는 로그인 조건이 아니다(ADR 0033).
활성 manifest가 없는 세션도 생성하며 `manifest_revision=0`을 쓴다. 일반 RT 갱신은 기존 revision을 유지한다.
이 값으로 온보딩 상태를 판단하지 않고 §13의 온보딩 조회를 사용한다.

### 11.1 manifest 재동기화

`GET /v1/manifest`는 `Authorization: Bearer <사용자 RT>`를 받고 정책과 토큰의 5키 봉투
(`manifest`·`access_token`·`refresh_token`·`token_type`·`expires_in`)를 반환한다.
허브 `contracts/user-auth.md`가 계약이다. 봉투 안의 `manifest`는 원격 telemetryctl의 `contracts/enrollment-manifest.schema.json`을 만족하고,
나머지 네 키는 §11의 TokenResponse와 같다. 원격 telemetryctl develop에는 이 봉투의 JSON Schema도 재조회 클라이언트도 아직 없다.
AT와 설치 `pit_`·`ptt_`는 401 `invalid_credentials`다.
활성 정책과 RT 회전을 한 트랜잭션으로 묶으며 실패 시 전부 롤백한다(ADR 0019).
활성 manifest가 없거나 저장된 정책이 계약 스키마를 어기면 409 `manifest_not_configured`이고 RT는 소비되지 않는다.
응답의 새 토큰은 서버 revision 일치만 보장하고 클라이언트 적용 완료의 증거가 아니다.
GET이 상태를 변경하므로 캐시·프리페치·자동 재시도를 금지한다. 커밋 후 응답 유실은 재로그인으로 복구한다.
OTLP 인증은 `ptt_`를 유지하며 사용자 AT나 revision 검사를 추가하지 않는다(허브 ADR 0008).


## 12. 조직 관리 API

경로 앞에 `/api/v1/organizations/{organizationId}`를 붙인다.
관리 API는 `pulsemetry.management.enabled=true`, 사용자 인증, Base64 32바이트의
`pulsemetry.management.response-encryption-key`를 요구한다. 공개 브라우저 설정에 이 키를 넣지 않는다.
Bearer AT로 검증한 owner/admin만 사용할 수 있다. 타 조직은 404, member는 403이다.
POST 명령에는 `Idempotency-Key`(영숫자·`_`·`-`, 8~128자)를 보낸다.
같은 조직·사용자·경로·키·본문은 24시간 같은 응답을 반환한다. 같은 키와 다른 본문은 409 `idempotency_conflict`다.
초대 코드가 포함된 재시도 응답은 DB에서 암호화된다.

| 메서드·경로 | 요청 | 성공 |
| --- | --- | --- |
| `POST /teams` | `{teamName}` | 201 `{teamId,teamName,version}`, Location |
| `PATCH /teams/{teamId}` | `{teamName,expectedVersion}` | 200 같은 팀 응답 |
| `DELETE /teams/{teamId}` | `If-Match: "team-{version}"` | 204, 팀 보관·현재 배정 해제 |
| `POST /member-team-assignments` | `{assignments:[{memberId,teamId,expectedVersion}]}` | 200 `{effectiveAt,members:[{memberId,teamId,version}]}` |
| `PATCH /members/{memberId}` | `{expectedVersion,teamId?,role?}` | 200 MemberSaved |
| `POST /invitations/batch` | `{invitations:[{email,teamId,role}]}` | 200 InvitationsResponse |
| `POST /invitations/{invitationId}/revoke` | `{}` | 204 |
| `POST /vendors` | `{kind,displayName,contract?}` | 201 VendorResponse, Location, ETag |
| `PATCH /vendors/{vendorId}` | `{expectedVersion,displayName}` | 200 VendorResponse, ETag, 계약 유무와 무관한 이름 정정 |
| `PUT /vendors/{vendorId}/contract` | `{expectedVersion,displayName,contract}` | 200 VendorResponse, ETag |
| `DELETE /vendors/{vendorId}/contract` | `If-Match: "vendor-{version}"` | 204 |
| `DELETE /vendors/{vendorId}` | `If-Match: "vendor-{version}"` | 204, 수동 벤더 보관 |
| `POST /installation-update-notifications` | `{installationIds,expectedPolicyVersion}` | 202 OperationResponse, Location(작업 상태 조회) — 아래 "설치 업데이트 안내" |
| `PUT /vendors/{vendorId}/connection` | `{expectedVersion,settings,credential}` | 200 `{seatSource}`, ETag `"connection-{version}"` — 아래 "벤더 연결" |
| `DELETE /vendors/{vendorId}/connection` | `If-Match: "connection-{version}"` | 204, 자격증명 삭제 |
| `POST /vendors/{vendorId}/connection/verify` | 본문 없음 | 200 `{seatSource}`, 확인 결과를 연결에 남김 |
| `POST /vendors/{vendorId}/connection/sync` | 본문 없음, `Idempotency-Key` | 202 OperationResponse(`seat_sync`), Location(작업 상태 조회) — 아래 "벤더 연결"의 동기화 |
| `POST /vendors/{vendorId}/seats` | `{account,expectedVersion?,memberId?,tierId?,note?}` | 새 좌석 201 SeatSaved + Location·ETag `"seat-{version}"`, 해제된 좌석의 재배정 200 — 아래 "좌석 수동 기록" |
| `PATCH /vendors/{vendorId}/seats/{seatAssignmentId}` | `{expectedVersion,memberId?,memberLink?,tierId?,note?}` | 200 SeatSaved(보정) |
| `POST /vendors/{vendorId}/seats/{seatAssignmentId}/release` | `{expectedVersion}` | 200 SeatSaved(해제) |
| `POST /vendors/{vendorId}/seats/import` | `{mode:"preview"|"apply",csv}` | 200 `{import,provisional}` — 적용에 행 오류가 있으면 422 `seat_import_invalid` |
| `PATCH /settings/alert-rules/{ruleId}` | `{expectedVersion,enabled}` | 200 AlertRule — 아래 "알림 규칙과 목록" |
| `PUT /settings/alert-lists/{listId}` | `{expectedVersion,entries}` | 200 `{list,alertRules}` — 전체 교체 |
| `POST /alerts/{alertId}/acknowledge` | `{expectedVersion}` | 200 `{alertId,version,acknowledgedAt,acknowledgedBy}` — 알림 확인 |

팀 배정은 최대 100명, 전체 검증 후 한 트랜잭션으로 적용한다. `teamId:null`은 배정 해제다.
효력 시각은 서버 시각이며 과거 ClickHouse 팩트는 바꾸지 않는다.
수정 version은 직전 조회 응답 값을 그대로 보낸다. 불일치는 409 `version_conflict`다.
version을 1부터 시작하는 순번이나 날짜로 해석하지 않는다. PUT/PATCH는 expectedVersion, DELETE는 If-Match로 전달한다.

구성원 편집(`PATCH /members/{memberId}`)은 한 사람의 팀과 역할을 한 트랜잭션에서 저장한다(ADR 0036).
`expectedVersion`은 필수다. `teamId`와 `role`은 보낸 것만 바꾸며 둘 다 없으면 400 `invalid_request`다.
`teamId:null`은 미배정이고, 값이 있으면 같은 조직의 활성 팀이어야 한다(아니면 404 `not_found`).
역할은 `admin`과 `member` 사이에서만 바꾼다. 그 밖의 값은 422 `role_not_assignable`,
`owner`의 역할 변경은 422 `owner_role_immutable`, 자기 역할 변경은 422 `self_role_change`다.
요청의 역할이 현재 역할과 같으면 역할 변경이 아니므로 이 규칙들을 적용하지 않는다. `owner`와 자기 자신도 팀은 바꿀 수 있다.
초대 대기(`invited`) 구성원도 편집한다. 정지(`suspended`) 구성원은 409 `member_suspended`다.
팀이 실제로 바뀔 때만 소속 구간을 닫고 새 구간을 더한다. 팀과 역할을 함께 바꿔도 version은 한 번만 오르고,
아무것도 바뀌지 않으면 version도 그대로다. 응답의 `version`은 dashboard-api 구성원 목록의 `version`과 같은 값이다.
역할이 바뀐 구성원의 기존 AT는 다음 요청에서 401이 된다. RT로 갱신하면 새 역할의 AT를 받는다.
`role`은 저장된 값(`owner`·`admin`·`member`)이다. dashboard-api의 구성원 목록은 `owner`도 `admin`으로 표시하므로,
화면은 바꾸지 않은 필드를 보내지 않는다.

```ts
type MemberSaved = {
  memberId: string;
  team: { teamId: string; teamName: string } | null; // 열린 소속이 하나일 때만 값이 있다
  role: "owner" | "admin" | "member";
  status: "invited" | "active";
  version: number;
};
type InvitationsResponse = {
  results: {
    email: string; invitationId: string | null;
    status: "issued" | "already_member" | "already_invited" | "rejected";
    reason: string | null; expiresAt: string | null; code: string | null;
    delivery: Delivery | null; // issued인 항목에만 있다
  }[];
};
// 초대 메일의 발송 상태. 발급(status)과 별개의 사실이다 (ADR 0038).
type Delivery = {
  status: "queued" | "sending" | "sent" | "failed" | "cancelled" | "not_sent";
  reason: "mail_disabled" | "not_queued" | null; // not_sent일 때만 값이 있다
  queuedAt: string | null;      // 적재 시각
  lastAttemptAt: string | null; // 마지막 발송 시도 시각
  sentAt: string | null;        // SMTP 서버가 받은 시각. sent일 때만 값이 있다
  failureCode: string | null;   // 실패 분류 코드. 재시도 대기(queued) 중에는 마지막 시도의 사유
  attempts: number;
};
type ContractWrite = {
  planId: string; effectiveFrom: string; effectiveTo: string | null;
  termNote: string | null;
  tiers: { label: string; seats: number; monthlyFeePerSeatUsd: Money }[];
};
```

초대는 최대 100명, role은 `admin`·`member`, `teamId`는 UUID 또는 null이다.
발급 결과의 `status`는 코드 발급만 말한다. **발급은 발송이 아니다** — 초대 메일의 상태는 `delivery`가 따로 말한다(ADR 0038).
메일 기능(`pulsemetry.mail.enabled`)이 켜져 있으면 발급과 같은 트랜잭션에서 초대 메일을 outbox에 적재하고(`delivery.status=queued`), 발송 작업이 SMTP로 보낸다(ADR 0037).
메일에는 조직 이름·코드·만료 시각, 수락 링크(`pulsemetry.management.invitation-accept-url`에 `#code=…`를 붙인 주소 — 코드를 쿼리에 싣지 않는다), 설치 명령(§2.1의 `install_commands`와 같은 형태)이 담긴다. 제목에는 코드가 없다.
메일 기능이 꺼져 있으면 `delivery`는 `{status:"not_sent", reason:"mail_disabled"}`다. **적재되지 않은 메일을 `queued`로 내지 않는다.** 그때는 `issued`의 코드를 관리자가 직접 전달한다.
`sent`는 SMTP 서버가 메시지를 받았다는 뜻이고 수신함 도착을 뜻하지 않는다. 실패 코드는 `recipient_rejected` · `message_rejected` · `invalid_address`(재시도하지 않음),
`recipient_deferred` · `smtp_deferred` · `smtp_auth_failed` · `smtp_unavailable` · `send_error`(재시도), `outcome_unknown`이다.
같은 멱등 키의 재시도는 저장된 응답을 그대로 돌려주므로 `delivery`도 최초 시점의 값이고 메일은 한 통이다. 최신 발송 상태는 목록(§13.3)에서 본다.
초대 취소(`revoke`)는 그 초대의 아직 보내지 않은 메일을 취소한다(`cancelled`). 이미 나간 메일은 되돌리지 못하고, 그 안의 코드는 폐기돼 쓸 수 없다.
신규 초대의 기본 만료는 72시간이다. 기존 초대가 있으면 `already_invited`이며 기존 원본 코드를 재조회하지 않는다.
만료만 된 초대도 기존 초대다 — 재발급(§13.3)으로 살린다.
초대가 취소(`revoke`)돼 남은 초대가 없는 대기자(`invited`)는 다시 초대할 수 있다. 새 구성원을 만들지 않고 **같은 `memberId`**에
새 초대를 발급하며(`issued`), 요청의 `teamId`·`role`을 그 구성원에 적용하고 version을 올린다. 취소한 코드는 되살아나지 않는다.

벤더 kind/plan은 dashboard-api의 [벤더 카탈로그](dashboard-server-spec.md#3-벤더와-플랜-카탈로그)에서 얻는다.
카탈로그 `id`를 kind로 보내며 조직에 등록된 `vendorId` UUID와 혼동하지 않는다.
공통 공급사·제품·플랜은 `enrollment.vendor_catalog_vendors`·`vendor_catalog_products`·`vendor_catalog_plans`에 저장한다(ADR 0035).
신규 등록과 계약 저장은 DB의 활성 제품·플랜만 허용한다. `active=false`는 새 선택을 막으며 기존 계약 이력을 지우지 않는다.
조직별 단가와 좌석 수는 계속 `vendor_contract_versions.contract`에 저장한다.
contract를 생략하거나 null로 보내면 벤더만 등록하고 `state=needs_review`, `contract=null`을 반환한다.
계약은 이후 `PUT /vendors/{vendorId}/contract`로 추가한다. PUT에는 완전한 contract가 필요하다.
tiers는 allowsSeatTiers=true이면 1~3개, false이면 1개다. seats는 양의 정수,
월 단가는 0 이상 USD decimal(최대 소수 12자리)이다.
서버가 tierId·월 합계·confirmedAt·confirmedBy를 결정한다.
조직별 활성 등록은 제품 kind당 하나이며 중복 등록은 409 `vendor_already_registered`다.
전체 삭제 후 같은 제품을 새 UUID로 등록할 수 있으며 이전 등록 이력은 보존한다.
새 계약의 시작일은 조직의 오늘부터이며 종료일은 시작일 이상 또는 null이다.
기존 계약 PUT은 입력 정보 정정이다. 서버가 저장된 effectiveFrom을 유지하고 요청의 시작일로 바꾸지 않는다.
종료일은 오늘과 기존 시작일 이상이어야 한다. 이미 저장된 과거 종료일은 변경 없이 유지할 수 있다.
과거 시작일 때문에 정정을 거절하지 않는다. 새 버전의 월액을 계산하고 이전 버전·사용량 원본은 보존한다.
PATCH는 표시 이름만 바꾸고 계약을 그대로 보존한다. 신규 계약/실제 조건 갱신/누적 지출 재계산을 의미하지 않는다.
계약 변경·해제는 버전 이력으로 남는다. API 삭제는 실제 벤더 구독 해지·요금 환불을 의미하지 않는다.

- 계약 비우기(`DELETE .../contract`): 등록 UUID를 유지하고 새 버전에 contract=null을 저장한다. 목록에서는 needs_review다.
- 제품 삭제(`DELETE .../{vendorId}`): 등록을 archived로 보관하고 현재 목록에서 제외한다. 이력 행을 삭제하지 않는다.
- 표시 이름 PATCH: 등록 version과 변경 이력은 증가하지만 기존 계약 JSON과 계약의 confirmedAt/confirmedBy는 유지한다.
- 계약 PUT: 새 계약 version·confirmedAt/confirmedBy와 월 금액을 저장한다. 이전 행의 내용과 변경자·시각은 그대로 남는다.
- 비운 계약을 다시 입력하는 것은 기존 계약 정정이 아닌 새 계약 입력이다. 새 시작일 검증을 적용한다.

변경 이력 저장은 구현돼 있지만 이력 목록 조회 API·화면은 현재 범위에 없다.
`enrollment.contracts`의 기존 기간 약정과 새 좌석 계약을 합산하지 않으며, 좌석 계약값으로 기간별 지출을 누적하는 테이블도 추가하지 않는다.

### 설치 업데이트 안내 (ADR 0043)

새 수집 정책을 아직 집행하지 않는 설치의 구성원에게 **확인을 부탁하는 메일**을 보낸다. 원격 업데이트가 아니다 — 서버는 설치에 정책을 밀어 넣지 않고,
설치는 설치 보고(§4.5)의 응답으로 새 판을 알고 사용자 로그인 세션이 있는 데몬이 스스로 받아 적용한다. 메일은 그 확인 절차
(`pulsemetry status`로 상태를 보고, 로그인이 필요하다고 나오면 `pulsemetry login`)를 안내한다. 기기 이름·플랫폼·기대 판·지금 판을 싣고 비밀은 싣지 않는다.

```json
{"installationIds":["…"],"expectedPolicyVersion":2}
```

- 채널은 메일이다. 메일 기능(`pulsemetry.mail.enabled`)이 꺼진 배포는 **422 `notification_channel_unavailable`**이다 — 접수한 척하지 않는다.
- `installationIds`는 1~100개의 서로 다른 UUID(표준 하이픈 표기), `expectedPolicyVersion`은 1 이상의 정수다. 어기면 400 `invalid_request`.
- `expectedPolicyVersion`이 지금 활성 판이 아니면 409 `version_conflict`(`fieldErrors`의 field `expectedPolicyVersion`) — 관리자가 본 화면이 낡았다.
- 이 조직의 설치가 아닌 ID(다른 조직·없는 설치)가 하나라도 있으면 404 `not_found`.
- 폐기된 설치, 구성원이 활성이 아닌 설치, 이미 기대 판을 집행하고 있는 설치가 하나라도 있으면 409 `installation_unavailable`. "집행하는 판"은
  대시보드 명세의 "정책 적용 현황과 업데이트 안내"와 같은 규칙이다(마지막 설치 보고의 판, 보고가 없으면 적용 확인 기록).
- 모두 통과해야 작업(`installation_notification`, ADR 0039)을 만들고 대상마다 메일 한 통을 적재한다. 하나라도 걸리면 아무것도 만들지 않는다.
- 응답은 202이고 본문은 작업 상태 조회(dashboard-api `GET O/operations/{operationId}`)와 같은 모양이다. 접수 직후라 `status=running`, 대상은 모두 `pending`이다.
  `Location`이 그 조회 경로다. 같은 멱등 키의 재시도는 같은 작업을 가리키고 메일을 다시 만들지 않는다. 새 키는 새 안내다.
- **대상의 결과는 메일의 발송 결과다.** 발송 작업이 한 바퀴 돈 뒤 끝난 메일을 대상 결과로 옮긴다 — `sent` → 대상 `succeeded`,
  `failed` → 대상 `failed`(메일의 실패 분류 코드, 위 초대 메일과 같은 목록), `cancelled` → 대상 `failed`(`cancelled`). 재시도 대기 중인 메일의 대상은 `pending`이다.
  `succeeded`는 SMTP 서버가 받았다는 뜻이고 설치가 새 판을 적용했다는 뜻이 아니다. 적용 여부는 대시보드 설치 조회로 다시 확인한다.

### 벤더 연결 (ADR 0048)

등록 제품 하나에 벤더 커넥터 하나를 잇는다. 연결이 있는 제품은 **좌석 원장의 권위가 커넥터 동기화**이고, 수동 입력은 그 전의 임시 기록이다(ADR 0048 §3의 우선순위 표).
`pulsemetry.vendor-connections.enabled=true`(관리 기능과 함께)일 때만 경로가 있다. 꺼져 있으면 404다.

```ts
type ConnectionWrite = {
  expectedVersion: number;          // 새 연결은 0, 교체는 현재 연결의 version
  settings: Record<string, string>; // 비밀이 아닌 설정 — 커넥터 설명의 settingKeys 와 정확히 같은 키
  credential: string;               // 벤더 관리자 자격증명. 응답·로그에 다시 나오지 않는다
};
type SeatSource = {
  authority: "connector" | "manual";
  provisional: boolean;             // 커넥터가 있는 플랜인데 연결이 없어 수동 기록이 임시로 권위다
  connector: {                      // 현재 계약 플랜의 커넥터 설명. null 이면 그 플랜은 수동 원천이다
    connectorId: string; accountKind: "email" | "github_login";
    capabilities: Capability[];     // 이 저장소가 구현한 기능 — 실행 가능 여부는 이것으로 판단한다
    supported: Capability[];        // 벤더 문서가 근거를 준 기능(capabilities ⊆ supported)
    settingKeys: string[];
  } | null;
  connection: {
    connectionId: string; version: number; connectorId: string; settings: Record<string, string>;
    credential: { configured: true; updatedAt: string };  // 비밀은 없다 — 설정됨 여부와 갱신 시각뿐
    check: { status: "unverified" | "verified" | "invalid_credentials" | "insufficient_permission" | "unavailable"; checkedAt: string | null };
    sync: { status: "pending" | "succeeded" | "failing"; lastSucceededAt: string | null; lastFailedAt: string | null; lastError: string | null };
    createdAt: string; updatedAt: string;
    billing: { status: "pending" | "succeeded" | "failing"; lastSucceededAt: string | null; lastFailedAt: string | null; lastError: string | null } | null;  // 가산(ADR 0050). 청구를 구현하지 않은 커넥터는 null
  } | null;
};
type Capability = "seat_list" | "seat_release" | "seat_restore" | "billing";
```

| 커넥터 | 제품 · 플랜 | 계정 | settingKeys | 자격증명 | supported | capabilities(구현) |
| --- | --- | --- | --- | --- | --- | --- |
| `claude_enterprise` | `claude_team` · `enterprise` | 이메일 | 없음 | Admin API 키(`read:members`, 해제는 `write:members`, 청구는 `read:analytics`) | 조회·해제·복원·청구 | 조회·해제·청구 |
| `cursor_enterprise` | `cursor` · `cursor_enterprise` | 이메일 | 없음 | Admin API 키 | 조회·해제·청구 | 조회·해제·청구 |
| `copilot` | `copilot` · `copilot_business`·`copilot_enterprise` | GitHub 로그인 | `organization` | 토큰(`manage_billing:copilot` 또는 `read:org`) | 조회·해제·복원 | 조회·해제·복원 |
| `gemini` | `gemini` · `gemini_standard`·`gemini_enterprise` | 이메일 | `billingAccount`·`order`·`project` | 서비스 계정 키 JSON 전체 | 조회·해제·복원 | 조회·해제·복원 |

`supported`는 `docs/vendor-connector-evidence.md`의 결론을 옮긴 것이다. 구현은 좌석 목록(과 연결 확인), 해제(넷), 복원(Copilot·Gemini), 청구 누계(Claude Enterprise·Cursor Enterprise, ADR 0050)다 —
Claude Enterprise 재초대는 역할을 정해야 해 관리자 조치로 남긴다(ADR 0049 §2). 그 밖의 제품·플랜(Claude Team, OpenAI, Cursor Teams, `other`)은 커넥터가 없고 수동 원천이다.

- 커넥터는 등록 제품의 **현재 계약 플랜**으로 고른다. 계약이 없거나, 그 플랜에 커넥터가 없거나, 이 배포에 그 커넥터의 구현이 없으면 422 `connector_unavailable`이다.
- `settings`는 커넥터의 `settingKeys`와 정확히 같은 키의 문자열(1~200자, 제어 문자 없음)이어야 하고, `credential`은 1~8192자의 비어 있지 않은 문자열이어야 한다. 아니면 400 `invalid_request`(field `settings`·`credential`·`expectedVersion`).
- 활성 연결은 제품마다 하나다. 새 연결은 `expectedVersion: 0`, 교체는 현재 판이다. 어긋나면 409 `version_conflict`.
- **교체**는 자격증명을 새로 암호화하고 확인 상태를 `unverified`로 되돌린다. 커넥터나 `settings`가 바뀌면 동기화 기록(`sync`)도 비운다 — 다른 대상의 기록이다.
- **삭제**는 암호문을 즉시 지우고 연결 행은 이력으로 남긴다. 좌석 원장은 그대로이고 권위가 수동(커넥터 플랜이면 임시)으로 돌아간다. 등록 제품을 보관(`DELETE /vendors/{vendorId}`)하면 그 제품의 연결도 같은 트랜잭션에서 지운다.
- **확인**(`verify`)은 커넥터의 읽기 호출 하나다. 결과를 `check`에 남기고 판은 올리지 않는다. `invalid_credentials`·`insufficient_permission`은 벤더가 거절한 것이고, 한도 초과·일시 장애·응답 해석 불가는 `unavailable`이다.
  호출은 트랜잭션 밖에서 하며, 그 사이 연결이 바뀌었으면 결과를 쓰지 않고 409 `version_conflict`다. 상태만 남기는 확인이라 `Idempotency-Key`를 받지 않는다.
- 자격증명은 AES-256-GCM 암호문(`pulsemetry.vendor-connections.credential-keys`, §8)으로만 저장한다. 요청은 PUT이라 멱등 응답 기록(요청 해시·응답)을 남기지 않는다.
  행의 키 ID가 설정에 없으면(옛 키를 너무 일찍 뺀 경우) 확인은 503 `credential_key_unavailable`이다 — 평문으로 떨어지지 않는다.
- 설정 조회(dashboard-api)의 벤더마다 같은 `seatSource`가 있다. 등록·정정 응답(`VendorResponse`)의 vendor에도 같은 필드가 있다.

**좌석 동기화**(ADR 0048 §3·§7). enrollment-api 안의 주기 작업이 `sync.check-interval`마다 차례인 연결을 찾아 하나씩 동기화한다.
차례는 선점되지 않았고, 동기화 요청이 걸려 있거나 마지막 시도(성공·실패)가 `sync.interval`보다 오래됐거나 시도한 적이 없는 연결이다.
연결 행을 `FOR UPDATE SKIP LOCKED`로 선점하고(기한 `sync.lease`), 연결마다 진행 중인 실행은 하나다. 여러 인스턴스가 떠도 한 연결을 두 번 돌리지 않는다.

- 한 번의 동기화: 자격증명 복호화 → 계약 플랜의 커넥터가 연결의 커넥터와 같은지 확인 → 벤더 좌석 목록(모든 페이지) → 좌석 원장에 **목록 전체로** 반영.
  목록의 계정은 벤더가 보고한 상태가 되고 목록에 없는 보유 좌석은 해제된다(수동 원천 행 포함). 관리자가 정한 구성원 연결·계약 등급·메모는 덮지 않는다.
- 실패는 원장을 바꾸지 않는다. 연결의 `sync`가 `failing`이 되고 마지막 성공 값은 남는다. **한 연결의 실패가 다른 연결을 막지 않는다.**

| 실패 코드 | 뜻 |
| --- | --- |
| `invalid_credentials` · `insufficient_permission` | 벤더가 자격증명·권한을 거절했다(401·403) |
| `directory_managed` · `vendor_rejected` | 벤더 규칙이 거절했다(그 밖의 4xx — 조직 이름·주문이 틀린 경우 포함) |
| `rate_limited` | 한도 초과. 벤더의 대기 시간이 `http.max-retry-wait`보다 길거나 시도 횟수를 다 썼다 |
| `vendor_unavailable` | 5xx·연결 실패·시간 초과가 `http.max-attempts`번 이어졌다 |
| `invalid_response` | 응답이 문서의 모양이 아니다(필수 필드 없음, 끝나지 않는 페이지) |
| `invalid_listing` | 목록의 계정 키가 형식에 맞지 않거나 겹친다 |
| `plan_mismatch` | 계약을 비웠거나 커넥터가 다른 플랜으로 정정했다 — 벤더를 부르지 않는다 |
| `connector_unavailable` · `credential_key_unavailable` · `sync_error` | 이 배포에 구현이 없다 · 행의 암호화 키가 설정에 없다 · 그 밖의 예외 |

- **지금 동기화**(`POST …/connection/sync`): 활성 연결에 `seat_sync` 작업(대기, 대상 = 연결 ID)을 걸어 둔다. 다음 주기 실행이 주기와 무관하게 가져가 실행하고
  결과를 대상 결과로 옮긴다(성공, 또는 위 실패 코드로 실패). 끝나지 않은 요청이 걸려 있으면 새 요청을 만들지 않고 그 작업을 돌려준다.
  선점을 잃은 실행의 요청은 다음 실행이 이어받는다. 연결을 지우면 걸린 요청은 `connection_removed`로 실패한다. 연결이 없으면 404, 벤더 연결이 꺼진 배포도 404다.
- 실행 기록은 `seat_sync_runs`(시작·끝·결과·오류 코드·목록의 좌석 수·바뀐 좌석 수, 계기 `schedule`·`request`)다.
- **청구 누계**(ADR 0050): 커넥터가 청구를 구현했으면 같은 실행이 좌석 목록 뒤에 지금 정산 기간의 누계를 읽어 `vendor_billing_periods`에 남긴다(기간마다 한 행, 다시 읽으면 덮는다).
  Claude Enterprise는 이번 달(서울) 1일 0시부터의 사용 비용(`cost_report`, 한 시간 칸, 센트 → 달러), Cursor Enterprise는 벤더의 이번 청구 주기 on-demand 지출(`/teams/spend`의 `spendCents` 합)이다.
  USD만 받는다. 결과는 연결의 `billing` 칸에 따로 남는다 — 청구 실패(커넥터 실패 종류 또는 `billing_error`)가 좌석 동기화를 실패로 만들지 않고, 좌석 목록이 실패해도 청구는 읽는다.
  연결의 대상(커넥터·설정)을 바꾸면 그 연결이 읽은 누계를 지운다.

### 좌석 수동 기록 (ADR 0048 §3의 1·2행)

커넥터가 없는 플랜(Claude Team, OpenAI, Cursor Teams, `other`)에서는 관리자의 기록이 좌석 원장의 권위다. 커넥터가 있는 플랜이라도 **활성 연결이 없으면** 기록할 수 있고
그 기록은 **임시**다(`provisional: true`) — 연결을 만들면 첫 성공한 동기화가 목록 전체로 대체한다. 활성 연결이 있는 제품에서는 배정·해제·가져오기가
409 `connector_managed`이고 보정(구성원 연결·계약 등급·메모)만 된다. 벤더 연결 기능이 꺼진 배포에서도 된다. 구매 수량(`tiers[].seats`)으로 좌석을 만들지 않는다.

```ts
type SeatSaved = { seat: Seat; warnings: "exceeds_contracted_seats"[]; provisional: boolean };
type Seat = {
  seatAssignmentId: string; vendorId: string;
  account: string; accountKind: "email" | "github_login";   // 계정 키 — 소문자로 정규화한 이메일 또는 GitHub 로그인
  state: "assigned" | "pending_assignment" | "pending_release" | "released";
  source: "connector" | "manual" | "csv" | "vendor_control" | "admin_action"; // 마지막으로 상태를 정한 원천
  memberId: string | null; memberLink: "email_match" | "admin" | null;  // admin + null 은 관리자가 "잇지 않음"으로 정한 것
  tierId: string | null; vendorTier: string | null;
  assignedAt: string; releaseEffectiveOn: string | null; releasedAt: string | null;
  vendorLastActivityAt: string | null;  // 벤더가 준 마지막 활동. null 은 모름이지 미사용이 아니다
  note: string | null; version: number; updatedAt: string;
};
```

- **배정**(`POST …/seats`): 계정은 제품의 계정 종류(Copilot은 GitHub 로그인, 나머지는 이메일)로 정규화한다. 새 계정은 `expectedVersion`을 보내지 않는다(201).
  해제된 좌석을 다시 배정할 때는 그 좌석의 `version`을 보낸다 — 같은 `seatAssignmentId`로 200이다. 보유 중인 좌석은 409 `seat_already_held`(바꾸려면 보정).
- **구성원**: `memberId`를 보내지 않으면 이메일 일치 규칙(조직에 그 이메일의 구성원이 정확히 하나면 `email_match`, 로그인 계정은 잇지 않음), UUID면 관리자 연결(`admin`),
  `null`이면 관리자가 "잇지 않음"으로 정한 것이다. 보정에서 `memberLink: "automatic"`(이때 `memberId`는 보내지 않는다)은 관리자 연결을 거두고 규칙으로 돌린다. 다른 조직의 구성원은 404.
- **등급** `tierId`는 등록 제품의 **현재 계약**에 있는 등급이어야 한다(아니면 422 `invalid_tier`). 보정의 `tierId: null`은 등급을 비운다.
- **보정**(`PATCH`)은 상태·원천을 바꾸지 않는다. 바뀐 것이 없으면 판도 그대로다. **해제**는 배정(`assigned`)된 좌석만 된다 — 해제 예정·배정 대기는 벤더 제어의 몫이라 409 `seat_not_releasable`.
- 경로의 좌석이 그 등록 제품의 것이 아니면 404. 판이 다르면 409 `version_conflict`.
- **경고**: 보유 좌석(해제가 아닌 좌석) 수가 현재 계약의 구매 수량 합을 넘으면 `warnings: ["exceeds_contracted_seats"]`다 — 계약이 낡았을 수 있어 **거절하지 않는다**.

**CSV 가져오기**(`POST …/seats/import`). `mode: "preview"`는 아무것도 쓰지 않고 행마다 계획을 돌려준다. `mode: "apply"`는 모든 행을 검증한 뒤 **오류가 하나도 없을 때만**
한 트랜잭션으로 적용한다. 하나라도 있으면 아무것도 바꾸지 않고 422 `seat_import_invalid`이며 `details`에 미리보기와 같은 결과(행별 오류)를 싣는다.
파일의 행만 바꾼다 — **파일에 없는 좌석은 그대로다**(목록 전체 맞춤은 커넥터 동기화의 몫). 같은 파일을 다시 적용하면 모든 행이 `unchanged`다. 원천은 `csv`로 남는다.

| 열 | 필수 | 값 |
| --- | --- | --- |
| `account` | 예 | 벤더 계정 — 이메일(Copilot은 GitHub 로그인) |
| `status` | 아니오 | `assigned`(기본)·`released` |
| `tier` | 아니오 | 현재 계약의 등급 ID 또는 표시 이름(대소문자 무시). 비우면 새 좌석은 등급 없음, 있는 좌석은 그대로 |
| `member_email` | 아니오 | 이 좌석을 잇는 구성원의 이메일(관리자 연결). 비우면 새 좌석은 이메일 일치 규칙, 있는 좌석은 그대로 |

- 형식: UTF-8(BOM 허용), 첫 줄 머리글, 쉼표 구분, 큰따옴표 감싸기와 `""` 이스케이프, CRLF·LF, 빈 줄은 건너뛴다. 최대 5,000행·1,048,576자.
- **이메일 외의 개인 정보를 받지 않는다** — 위 네 열 밖의 열(이름·전화·메모 등)이 있으면 파일 전체를 거절한다.
- 파일 자체의 문제는 400 `invalid_csv`이고 `details.reason`이 `unknown_column`·`duplicate_column`·`missing_account_column`·`missing_header`·`malformed_quotes`·`too_many_rows`·`too_large` 중 하나다.

| 행 | 지금 좌석 | 동작 |
| --- | --- | --- |
| `assigned` | 없음 | `create` |
| `assigned` | 해제 | `reassign`(같은 좌석 ID) |
| `assigned` | 배정 | 등급·구성원이 다르면 `update`(원천은 그대로), 아니면 `unchanged` |
| `released` | 배정 | `release` |
| `released` | 해제 | `unchanged` |

```ts
type SeatImport = {
  mode: "preview" | "apply"; applied: boolean; digest: string;  // digest = 받은 CSV 내용의 SHA-256
  summary: { create: number; reassign: number; update: number; release: number; unchanged: number; errors: number };
  rows: { line: number; account: string; action: "create" | "reassign" | "update" | "release" | "unchanged" | null;
          seatAssignmentId: string | null; errors: { field: string; code: string }[] }[];
  warnings: "exceeds_contracted_seats"[];
};
```

행 오류 코드: `account` — `required`·`invalid_account`·`duplicate_account`·`not_found`(없는 좌석의 해제), `status` — `invalid_status`·`seat_not_changeable`(해제 예정·배정 대기),
`tier` — `invalid_tier`·`ambiguous_tier`·`not_applicable`(해제 행), `member_email` — `invalid_email`·`member_not_found`·`member_ambiguous`·`not_applicable`, `line` — `column_count`.

### 좌석 회수·복원 (ADR 0049)

회수는 **미리보기 → 실행**이다. 실행은 작업(`seat_reclaim`)으로 접수하고 결과는 작업 상태 조회(`GET O/operations/{operationId}`, dashboard-api)로 본다. 접수(202)는 성공이 아니다.
원장은 벤더가 받아들였거나 관리자가 조치를 확인했을 때만 바뀐다. 벤더 연결 기능이 꺼진 배포에서도 관리자 조치 흐름은 된다.

| 경로 | 본문 | 응답 |
| --- | --- | --- |
| `POST O/seat-reclaims/preview` | `{seats: [{seatAssignmentId, expectedVersion}]}` (1~100, 같은 좌석 두 번 불가) | 200 `ReclaimPreview` |
| `POST O/seat-reclaims` | `{previewId}` | 202 `OperationResponse` + `Location` |
| `POST O/seat-reclaims/{operationId}/restore` | `{}` | 202 `OperationResponse` + `Location` |
| `POST O/operations/{operationId}/targets/{seatAssignmentId}/confirm` | 없음 | 200 `OperationResponse` — 관리자 조치 대기 대상의 확인 |
| `POST O/operations/{operationId}/targets/{seatAssignmentId}/cancel` | 없음 | 200 `OperationResponse` — 관리자 조치 대기 대상의 취소(`cancelled`) |

```ts
type ReclaimPreview = {
  previewId: string; expiresAt: string;                          // 만든 뒤 5분
  eligibleSeatAssignmentIds: string[];
  rejected: { seatAssignmentId: string; reason: string }[];
  estimatedMonthlySavingsUsd: string | null;                     // 대상 좌석 등급의 계약 단가 합. 하나라도 모르면 null
  savingsEffectiveAt: null;                                      // 감액 시점은 모른다
  resultingUnallocatedSeats: number | null;                      // 대상 제품마다 max(계약 − (보유 − 회수), 0)의 합
  savingsBasis: "contract_unit_price" | null;                    // 가산
  targets: { seatAssignmentId: string; vendorId: string; method: "vendor_control" | "admin_action" }[];  // 가산
};
```

- **실행 방식**: 활성 연결이 있고 계약 플랜의 커넥터가 그 연결의 커넥터이며 그 기능(해제·복원)을 구현했으면 `vendor_control`, 아니면 `admin_action`(ADR 0049 §2의 표).
- **거절 사유**: `not_found`(없는 좌석·다른 조직·보관한 제품·UUID 아님), `version_conflict`, `not_assigned`(해제는 배정 좌석만), `control_in_progress`(끝나지 않은 회수·복원이 있음),
  `plan_mismatch`, `vendor_account_unknown`(Claude Enterprise 해제에 구성원 ID가 필요한데 연결 전 기록), `connector_unavailable`(이 배포에 커넥터 호출이 없음).
  복원은 여기에 `seat_reassigned`(이미 다시 보유)·`not_restorable`(해제 예정 좌석을 벤더 제어 없이 되살림)을 더한다.
- **실행**은 미리보기를 요청한 관리자만 한다(다른 관리자에게는 404 `not_found`). 대상마다 판·방식·연결·진행 중인 작업을 다시 검사해 하나라도 다르면 409 `preview_stale`로 전부 거절한다.
  기한이 지나면 409 `preview_expired`, 이미 쓴 미리보기는 409 `preview_used`(같은 `Idempotency-Key`의 재시도는 같은 202), 대상이 없으면 422 `no_eligible_seats`.
  회수 작업의 복원 기한(`restoreUntil`)은 만든 시각 + 30일이다.
- **벤더 제어 대상**은 `pending`으로 남고 주기 작업(좌석 동기화와 같은 작업·`sync.check-interval`, 한 바퀴에서 제어가 먼저)이 선점(`sync.lease`)해 커넥터를 부른다.
  받아들이면 원장(원천 `vendor_control`): 해제 끝남 → `released`, Copilot 취소 → `pending_release`(예정일은 다음 동기화가 채운다), 복원 → `assigned`.
  실패는 대상의 사유다 — 커넥터 실패 종류(위 "좌석 동기화"의 표와 같은 코드), `connection_removed`, `plan_mismatch`, `connector_unavailable`, `credential_key_unavailable`,
  `seat_changed`(호출 전에 좌석이 바뀌어 부르지 않음), `control_error`. 일시 장애도 대상 실패다(다시 하려면 새로 회수한다).
- **관리자 조치 대상**은 `awaiting_admin_action`(조치 코드 `release_in_vendor_console`·`restore_in_vendor_console`)이다. 관리자가 벤더 콘솔에서 조치하고 **확인**하면 원장이 옮겨지고
  (원천 `admin_action` — 해제 `released`, 복원 `assigned`) 대상이 성공한다(확인자 = 요청한 관리자). 확인 사이에 좌석이 이미 그 상태면 원장은 그대로, 표에 없는 전이면 409 `seat_changed`.
  조치 대기에는 기한이 없다. 하지 않기로 하면 **취소**한다(원장 불변). 조치 대기가 아닌 대상의 확인·취소는 409 `not_awaiting_admin_action`.
- **복원**은 되돌릴 수 있는 회수(성공한 대상이 있는 끝난 회수, 기한 안, 살아 있는 복원 없음)에만 된다. 아니면 422 `restore_not_available`. 대상은 회수에서 성공한 좌석이고
  대상마다 방식을 다시 정한다. 되돌릴 수 없는 대상은 그 사유로 실패로 남고, 모든 대상이 불가면 작업을 만들지 않고 422(`details`에 대상별 사유)다.
- 원장 이력의 행위자는 작업이다(`seat_assignment_events.operation_id`). 대상 ID는 `seatAssignmentId`다.

### 알림 규칙과 목록 (ADR 0051)

알림 규칙 네 개의 켜기·끄기와, 규칙이 기대는 두 목록의 전체 교체, 알림 확인이다. 평가와 알림 조회는 dashboard-api(대시보드 명세 "알림")다.
두 명령은 PATCH·PUT 이라 `Idempotency-Key` 를 받지 않는다(판이 재시도를 막는다). 같은 조직 행을 잠가 직렬화한다.

```ts
type AlertRule = {                                               // 설정 조회(dashboard-api)의 alertRules 항목과 같다
  ruleId: "spend_spike" | "quota_exceeded" | "model_not_allowed" | "tool_unapproved";
  version: number;                                               // 켜짐이 바뀔 때만 1 오른다. 저장한 적 없으면 0
  enabled: boolean;
  availability: "available" | "unavailable";                    // 켤 수 있는 근거가 있는가
  reason: string | null;                                         // unavailable 의 사유
  threshold: { value: number; unit: "ratio" | "users" | "events" };
  evaluationWindow: string; comparisonWindow: string | null;     // 기준 데이터 — 바꾸는 명령이 없다
};
type AlertList = { listId: "allowed_models" | "approved_tools"; version: number; entries: string[]; updatedAt: string | null };
```

| 규칙 | 켤 수 있는 조건 | 아니면 `reason` |
| --- | --- | --- |
| `spend_spike` | 조직의 설치가 수집 구간을 보고한 적이 있다(완전한 날의 근거 — ADR 0040·0042) | `completeness_not_available` |
| `quota_exceeded` | 없음 — 한도 초과를 가리키는 검증된 관측이 없다 | `source_not_available` |
| `model_not_allowed` | `allowed_models` 가 비어 있지 않다 | `allowed_models_not_configured` |
| `tool_unapproved` | `approved_tools` 가 비어 있지 않다 | `approved_tools_not_configured` |

- 켤 수 없는 규칙을 켜면 422 `alert_rule_unavailable`(필드 `enabled`, `details.reason`). 끄기는 언제나 된다. 같은 값이면 판 그대로 200 이다.
- 없는 규칙·목록 404 `not_found`, 판 불일치 409 `version_conflict`, `enabled` 가 불리언이 아니면 400 `invalid_request`(필드 `enabled`).
- 목록 항목: 앞뒤 공백 없는 1–200자, 제어 문자 없음, 목록당 200개 이하, 중복 없음. 대소문자를 구분하는 정확 일치이고 끝의 `*` 하나는 접두사 일치다.
  그 밖의 자리의 `*`·`*` 하나뿐인 항목은 거부한다. 어기면 400 `invalid_request`(필드 `entries`). 응답의 `entries` 는 코드 포인트 순서다.
- 내용이 같은 교체는 판을 올리지 않는다. 켜진 규칙이 기대는 목록을 비우면 422 `alert_list_in_use`(필드 `entries`) — 규칙을 먼저 끈다.
- producer 가 가린 도구 이름(`mcp_tool`)은 그 이름 그대로 대조된다. 개별 MCP 도구는 승인할 수 없다.
- **알림 확인**(`POST /alerts/{alertId}/acknowledge`): 알림은 dashboard-api 의 평가 기록(`dashboard_cache.alerts`)이고, 이 명령이 그 표를 읽어 그 조직의 임계값에 이른 알림인지 본다
  (아니면 404 `not_found` — UUID 가 아닌 ID 도 같다). `expectedVersion` 은 알림의 지금 판이다(다르면 409 `version_conflict` — 그 사이 묶음이 늘었다).
  이미 확인한 알림은 처음 확인한 기록을 그대로 돌려준다(멱등). 확인을 되돌리는 명령은 없다. 기록은 `enrollment.alert_acknowledgements` 다.

### 조회·관리 오류

```json
{"error":{"code":"version_conflict","message":"관리 요청을 처리할 수 없습니다.","fieldErrors":[]},"requestId":"..."}
```

| 상태 | 주요 코드·처리 |
| --- | --- |
| 400 | invalid_request, 필드 오류 표시. invalid_csv(`details.reason`) |
| 401 | unauthenticated, 로그인/토큰 갱신 |
| 403 | forbidden, 해당 동작 비활성화 |
| 404 | not_found, 타 조직/없는 자원 |
| 409 | version_conflict, idempotency_conflict, team_name_conflict, vendor_already_registered, member_suspended, installation_unavailable, snapshot_expired, connector_managed, seat_already_held, seat_not_releasable, preview_stale, preview_expired, preview_used, not_awaiting_admin_action, seat_changed |
| 422 | invalid_vendor, invalid_plan, invalid_contract_period, detected_vendor, role_not_assignable, owner_role_immutable, self_role_change, notification_channel_unavailable, connector_unavailable, invalid_tier, seat_import_invalid(`details`에 행별 오류), no_eligible_seats, restore_not_available(`details`에 대상별 사유가 있을 수 있다), alert_rule_unavailable(`details.reason`), alert_list_in_use |
| 503 | unavailable, Retry-After 후 재시도. credential_key_unavailable(벤더 연결 — 운영이 암호화 키 설정을 고칠 때까지 재시도해도 같다) |

쓰기 성공 후 관련 조직의 팀·구성원·설정·개요 Query 캐시를 무효화한다.
버전 충돌 시 자동으로 새 버전을 덮어쓰지 않고 최신 값을 다시 보여 준다.

## 13. 온보딩

§12와 같은 조직 경로·Bearer 인증·관리 기능 설정을 사용한다.
필수 조건은 **수집 여부를 명시적으로 저장 + 활성 벤더 하나 이상 등록**이다.
플랜·좌석·단가는 선택이며 팀·초대도 건너뛸 수 있다.
기본 manifest의 수집=false만으로 관리자가 선택을 완료했다고 간주하지 않는다.
초안·현재 단계는 저장하지 않는다. 저장한 정책·벤더·팀·초대는 남고, 조회 결과로 재개 단계를 결정한다.

| 메서드·경로 | 요청 | 성공 |
| --- | --- | --- |
| `GET /onboarding` | 없음 | 200 OnboardingState |
| `PUT /collection-policy` | `{expectedVersion,collectRawContent?,reclaimIdleDays?,aggregateRetentionMonths?,expectedSettingsVersion?}` | 200 PolicySaved |
| `POST /onboarding/complete` | `{}`, Idempotency-Key | 200 OnboardingState |
| `GET /invitations` | limit=20(1~100), cursor, status?, memberStatus? | 200 InvitationPage |
| `POST /invitations/{invitationId}/reissue` | `{}`, Idempotency-Key | 200 ReissuedInvitation |

### 13.1 상태와 완료

```ts
type OnboardingState = {
  organizationId: string;
  completed: boolean;
  completedAt: string | null;
  policy: {
    confirmed: boolean; confirmedAt: string | null;
    version: number; collectRawContent: boolean | null;
  };
  selectedVendorCount: number;
  canComplete: boolean;
  nextStep: "collection" | "vendors" | "team" | "complete";
};
```

초기 manifest가 없으면 policy.version=0, collectRawContent=null이다.
프롬프트와 응답 플래그가 서로 달라도 collectRawContent=null로 반환해 다시 선택하게 한다.
confirmed는 저장 완료 여부이며 수집 허용 여부가 아니다. false를 저장해도 confirmed=true다.
벤더 수는 직접 등록한 활성 벤더만 센다. 관측으로 발견된 공급자만으로 선택 완료 처리하지 않는다.
완료 전 필수 조건이 부족하면 409 `onboarding_incomplete`다. 완료 후 재호출은 기존 완료 시각을 유지한다.
완료 시각은 `tenants.onboarding_completed_at`에 저장하고 `tenants.onboarding_completed`는 시각의 유무를 계산하는 생성 컬럼이다.
완료는 과거 완료 사실이다. 이후 모든 벤더를 제거해도 완료 시각을 지우지 않으며 canComplete는 현재 조건을 나타낸다.

호출 흐름:

1. 로그인 → `/v1/auth/me`로 조직 확인 → `GET /onboarding`.
2. `PUT /collection-policy`로 선택 저장.
3. dashboard 카탈로그에서 제품 선택 → `POST /vendors`에 kind·displayName만 보내도 등록 가능.
4. 팀·초대는 원하는 경우 저장.
5. `POST /onboarding/complete` 성공 후 개요 이동.

### 13.2 수집 정책 저장

```json
{"expectedVersion":1,"collectRawContent":false}
```

```ts
type PolicySaved = {
  version: number; collectRawContent: boolean | null; confirmedAt: string | null;
  application: "future_enrollments";
  existingInstallationsUpdated: false;
  // 가산 — 조직 정책 설정(ADR 0046)
  reclaimIdleDays: 7 | 14 | 30 | 60 | null;    // 조직이 저장한 값. null이면 조회 서버의 기본 설정을 쓴다
  aggregateRetentionMonths: 12 | 24 | 36 | null; // null = 무기한
  settingsVersion: number;                      // 저장 전 0
  settingsUpdatedAt: string | null;
  cleanupOperationId: string | null;            // 이 저장이 만든 보존 정리 작업(ADR 0047). 없으면 null
};
```

`collectRawContent`는 이제 선택이다. 보내면 아래 규칙대로 새 manifest 판을 만들고, 보내지 않으면 manifest를 바꾸지 않는다.
그때 `version`·`collectRawContent`·`confirmedAt`은 지금의 활성 manifest·정책 확인 기록을 그대로 알려 준다(없으면 0·null·null).
기존 `{expectedVersion, collectRawContent}` 본문의 동작과 응답은 그대로다.

서버는 현재 활성 manifest의 version을 비교한다. 충돌은 409 `version_conflict`다.
활성 manifest가 없으면 `expectedVersion=0`으로 최초 생성한다. 새 판번호는 기존 판번호의 최댓값 다음이다.
최초 생성에는 §9.1의 서버 수집 주소가 필요하다. 누락·잘못된 주소는 409 `manifest_not_configured`이고
manifest와 정책 확인 기록을 저장하지 않는다. 로그인은 이 설정과 무관하게 가능하다.
초기 프로토콜은 `http/protobuf`, 세 signal은 true, privacy는 전부 false에서 사용자의 원문 선택을 반영한다.
새 manifest 판에서 `privacy.collect_user_prompts`와 `privacy.collect_assistant_responses`만 함께 변경한다.
config_revision도 새 판에 맞춘다. endpoint·signals·나머지 privacy 설정 및 이전 판의 JSON은 보존한다.
동일 조직의 변경·완료 명령은 조직 행 잠금과 한 트랜잭션으로 처리한다.
정책 저장은 **이후 enroll의 기본값**이고, 서버가 이미 설치된 클라이언트에 정책을 밀어 넣거나
installation_manifest_assignments를 적용 완료로 변경하지 않는다(`application`·`existingInstallationsUpdated`는 이 저장이 한 일이다).
기존 설치는 설치 보고(§4.5)의 응답으로 새 판을 알고, 사용자 로그인 세션이 있는 데몬이 스스로 받아 적용한 뒤 보고한다 — 적용 확인은 그 보고가 기록한다.
적용 현황은 대시보드 설치 조회로 확인하고, 아직 적용하지 않은 설치의 구성원에게는 §12의 설치 업데이트 안내로 확인을 부탁한다.

**회수 기준·집계 보존**(ADR 0046). 좌석 회수 기준(`reclaimIdleDays` — 7·14·30·60)과 집계 보존(`aggregateRetentionMonths` — 12·24·36, null은 무기한)은
설치에 배포하는 정책이 아니라서 manifest와 따로 `enrollment.organization_policy_settings`에 저장하고 판도 따로 센다. manifest 판을 올리지 않으므로 설치의 적용 상태는 그대로다.

```json
{"expectedVersion":3,"expectedSettingsVersion":0,"reclaimIdleDays":30}
```

- 보낸 필드만 바꾼다. 보내지 않은 값은 저장된 값 그대로다. 바꿀 것(원문 선택·회수 기준·집계 보존)이 하나도 없으면 400 `invalid_request`다.
- 두 값 중 하나라도 보내면 `expectedSettingsVersion`(설정의 판, 저장 전 0)이 필수다. 허용 밖의 값·문자열·판 누락은 400 `invalid_request`이고 `fieldErrors`에 그 필드가 있다.
- `expectedVersion`은 언제나 활성 manifest 판과 맞아야 한다. 설정의 판이 어긋나면 409 `version_conflict`(`fieldErrors`의 `expectedSettingsVersion`)이고 아무것도 저장하지 않는다.
  원문 선택과 설정을 함께 보내면 한 트랜잭션이다.
- 설정의 판은 값이 실제로 바뀔 때만 1 오른다. 같은 값을 다시 보내면 같은 판·같은 저장 시각이다.
- 저장하지 않은 조직의 유효값은 조회 서버가 정한다 — 회수 기준은 dashboard-api의 `pulsemetry.dashboard.members.idle-days`, 집계 보존은 무기한이다(대시보드 명세 "조직 정책 설정").
- 원문 보존 일수는 저장하지 않는다 — 원천(원본 아카이브의 수명)이 이 저장소에 없다.

**집계 보존 단축 → 보존 정리 요청**(ADR 0047). 집계 보존이 바뀐 저장만 다룬다(같은 트랜잭션).

| 저장 | 결과 |
| --- | --- |
| 줄였다(유한 값이 작아짐, 무기한 → 유한) | `retention_cleanup` 작업(대기, 대상 `analysis_source`)과 `enrollment.retention_cleanup_requests` 요청을 만들고 `cleanupOperationId`로 돌려준다 |
| 한 번도 실행하지 않은 요청이 있다 | 그 요청을 `superseded`로 닫고 작업을 같은 사유로 실패시킨다. 새 값이 유한이면(늘린 값이라도) 새 요청을 만든다. 무기한이면 없다 |
| 늘렸고 대체할 요청이 없다 · 무기한으로 바꿨다 | 아무것도 하지 않는다(`cleanupOperationId`=null). 지운 기록은 되돌리지 않는다 |

- 경계는 저장 시각의 KST 날짜에서 N개월 전 자정이다(`as_of`에 고정 — 다시 실행해도 같은 경계).
- 이 앱은 지우지 않는다. 실행은 `:apps:retention-worker --requests`(주기 실행은 배포 환경)가 기존 보존 삭제 순서 그대로 하고, 진행은 dashboard-api의 작업 상태 조회로 본다
  (대시보드 명세 "작업 상태 조회" — `retention`에 가장 최근 삭제 실행의 상태).

### 13.3 초대 목록·재발급

```ts
type InvitationPage = {
  items: {
    invitationId: string; email: string; role: string;
    createdAt: string; expiresAt: string;
    installationUsedAt: string | null; signupUsedAt: string | null;
    revokedAt: string | null;
    status: "pending" | "expired" | "used" | "revoked";
    memberId: string;
    memberStatus: "invited" | "active" | "suspended";
    team: { teamId: string; teamName: string } | null;
    memberVersion: number;
    delivery: Delivery; // §12. 이 초대의 메일 발송 상태
  }[];
  nextCursor: string | null;
};
type ReissuedInvitation = {
  invitationId: string; replacesInvitationId: string;
  code: string; expiresAt: string;
  delivery: Delivery; // 새 초대의 메일
};
```

목록은 invitationId 오름차순이며 다음 요청에는 nextCursor를 그대로 보낸다.
cursor는 UUID다. 실시간 목록으로 snapshot 일관성을 보장하지 않는다. 코드 원문·해시는 목록에 포함하지 않는다.
상태 우선순위는 revoked → 두 소비 완료인 used → expired → pending이다.
pending은 가입 또는 설치 중 하나만 남은 경우도 포함하므로 소비 시각 둘을 함께 확인한다.
`status`를 주면 그 상태의 초대만 돌려준다. 값은 위 네 상태 중 하나이고 그 밖은 400 `invalid_request`다.
다음 페이지에도 같은 `status`를 보낸다. 생략하면 전체다.
`memberId`는 초대 대상 구성원의 불변 ID다. `team`과 `memberVersion`은 그 구성원의 현재 팀과 version이며,
초대 대기자의 팀·역할 편집(§12 `PATCH /members/{memberId}`)에 그대로 쓴다. 이메일로 초대와 구성원을 짝짓지 않는다.
`role`은 구성원에 저장된 현재 역할이다. 편집하면 목록의 값도 바뀐다.
`memberStatus`는 초대 대상 구성원의 상태다. 가입이나 설치 중 하나를 마친 구성원은 `active`이고 초대는 남은 용도 때문에 `pending`일 수 있다.
아직 합류하지 않은 사람만 보려면 `memberStatus=invited`로 거른다. 값은 `invited`·`active`·`suspended`이고 그 밖은 400 `invalid_request`다.

재발급은 만료 여부와 관계없이 아직 폐기되지 않고 소비 권한이 남은 초대에만 허용한다.
기존 코드를 즉시 폐기하고 새 ID·코드·72시간 만료를 만든다. 두 작업은 원자적이다.
소비된 가입/설치 권한은 새 초대에도 소비 시각을 유지한다. 기존 계정·설치·세션은 삭제하지 않는다.
폐기됐거나 두 용도 모두 소비한 초대는 409 `invitation_unavailable`이다.
같은 멱등 키의 재시도는 최초 새 코드를 재전달한다. 재시도 응답 저장에는 §12의 암호화를 사용한다.
재발급은 새 초대의 메일을 같은 트랜잭션에서 적재하고, 폐기한 초대의 아직 보내지 않은 메일을 취소한다. 이미 나간 메일 뒤의 재발급은 새 메일을 한 통 더 보낸다.
같은 멱등 키의 재시도는 메일을 다시 만들지 않는다.

목록의 `delivery`는 그 초대의 현재 발송 상태다. 메일을 적재한 적 없는 초대(메일 기능을 켜기 전의 초대, 관리자 키 경로의 초대)는
`not_sent`이고 `reason`은 메일이 켜져 있으면 `not_queued`, 꺼져 있으면 `mail_disabled`다.

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
| V13 | 도입 문의 접수(`inquiries`)와 출처별 문의 요청 수 제한(`inquiry_attempts`) |
| V14 | 메일 outbox(`mail_outbox`) — 적재·선점·결과와 암호화한 대기 본문 (ADR 0037) |
| V15 | 공통 작업 기록(`operations`)과 대상별 결과(`operation_targets`) — 비동기 작업의 상태·사유·조치 대기·복원 기한 (ADR 0039) |
| V16 | 설치 보고의 최신 상태(`installation_heartbeats`)와 수집 구간 이력(`installation_collection_segments`) (ADR 0040) |
| V17 | 설치 보고의 최신 상태에 전달 대기가 이어지기 시작한 시각(`pending_since`) (ADR 0041) |
| V18 | 관측 제품 → 등록 제품의 명시 매핑(`vendor_catalog_observed_products`) (ADR 0044) |
| V19 | 조직 정책 설정(`organization_policy_settings`) — 회수 기준·집계 보존과 그 판 (ADR 0046) |
| V20 | 보존 정리 요청(`retention_cleanup_requests`) (ADR 0047) |
| V21 | 벤더 연결(`vendor_connections` — 자격증명 암호문·확인·동기화 선점과 결과)·동기화 실행(`seat_sync_runs`)·좌석 원장(`seat_assignments`)과 판별 이력(`seat_assignment_events`) (ADR 0048) |
| V22 | 좌석 동기화 요청 — 작업 종류 `seat_sync`, 연결의 요청 칸(`sync_requested_operation_id`), 실행이 끝내는 요청(`seat_sync_runs.operation_id`) (ADR 0048 §7) |
| V23 | 좌석 이력에 보유 구간의 시작(`seat_assignment_events.assigned_at`) — 조회가 기준 시각의 좌석을 이력으로 다시 세운다 (ADR 0048 §7) |
| V24 | 좌석 회수 미리보기(`seat_reclaim_previews`)와 회수·복원 대상별 실행 방식·벤더 제어 선점(`seat_controls`) (ADR 0049) |
| V25 | 벤더 청구 누계(`vendor_billing_periods` — 정산 기간별 금액·종류·확정 여부·원천)와 연결의 청구 읽기 결과(`last_billing_*`) (ADR 0050) |

V12는 이 표에 없다 — 사용자 로그인 방식 작업이 예약한 번호다. Flyway는 이미 적용한 판보다 낮은 번호를 뒤늦게 받지 않으므로,
V13이 먼저 적용된 DB에는 V12를 넣을 수 없다. 머지 순서가 뒤집히면 그 작업이 번호를 다시 매긴다.
V9는 기존 버전 이력의 보관 여부를 반영한 뒤 중복 활성 제품을 검사한다.
중복이 있으면 적용을 중단하며 자동 병합·삭제하지 않는다. 해당 조직의 중복 등록을 검토한 뒤 다시 적용한다.
telemetry_ops의 V3는 별도 이력으로 관리하며 신규 조직 생성 시 빈 수집 요약을 원자적으로 초기화한다(ADR 0034).
적용된 migration 파일을 수정하지 않고 다음 번호를 추가한다.
organization_onboarding에는 정책 확인자·시각과 완료자만 남는다. 기존 완료 시각은 V8에서 보존한다.
개발 시드 초기화는 이 기록도 해당 시드 조직에 한해 삭제한다.

주요 검증은 `:apps:enrollment-api:test`의 UserAuthApiTest(인증)와 ManagementApiTest(관리·온보딩)다.
팀/초대/계약 쓰기, 조직 격리, 온보딩 완료 조건, 정책 판 보존, 재발급 소비 상태 계승을 실제 PostgreSQL에서 확인한다.
서버 API 구현, 프론트 배선, 실제 시드 E2E 통과는 별도로 확인한다.
프론트 온보딩에서 계약 입력은 선택이며 설정의 계약 관리도 API에 연결돼 있다.
초대 메일과 문의 통지는 `InvitationMailApiTest`가 실제 SMTP(메일 수신 컨테이너)로 도착을 확인한다.
전체 화면의 연동 완료 여부는 [E2E 목표 시나리오](frontend-e2e-scenarios.md)와 실제 실행 결과를 대조한다.

### 계약 기간 상태 (`contractStatus`)

설정·벤더 목록·상세와 등록/정정 응답의 vendor에 `contractStatus: missing | scheduled | active | expired`를 반환한다.
기존 `state`는 유지한다(active만 configured, 나머지는 needs_review). 상태는 DB에 저장하지 않고
서울 시간의 조회 기준일과 계약 기간으로 계산한다. 페이지네이션은 동일 snapshot의 기준 시각을 사용한다.

| 값 | 의미 |
| --- | --- |
| missing | contract=null, 계약 미입력 |
| scheduled | 오늘이 effectiveFrom 이전 |
| active | 시작일 이후이며 종료일 당일까지. 종료일 null은 상한 없음 |
| expired | 오늘이 effectiveTo 이후 |

만료되어도 등록·계약 원문·이력은 보존하며 자동 삭제·해지·갱신하지 않는다. 기존 금액·좌석은
마지막 계약 정보로 표시한다. 합계는 contractStatus=active인 계약만 포함한다. 만료·시작 예정·미입력은 제외하며 UI에 제외 건수를 표시한다. 유효 계약이 없으면 월 계약액과 좌석 수는 0이다. 유효 계약 자체의 필요한 값이 누락되면 해당 합계는 null이다. 이는 유효 계약 기준 합계이며 실제 전체 지출이나 자동 해지·갱신을 의미하지 않는다.
갱신 등록은 지원 범위 밖이며 기존 PUT은 시작일을 보존하는 입력 정정이다.
