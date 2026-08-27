# Enrollment 서버 명세

데스크탑 CLI(`telemetryctl`) 전용 인증 서버의 동작 명세다.
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

**범위 밖**: 웹 대시보드 API, 사용자 로그인, 설정 재조회(`GET /v1/manifest`), heartbeat,
`uninstall`/`repair`, 초대 이메일 발송, 데이터 파이프라인.

---

## 2. 엔드포인트

| 메서드 | 경로 | 인증 | 성공 |
|---|---|---|---|
| POST | `/v1/enroll` | 없음 (초대 코드 자체가 자격) | 201 |
| POST | `/v1/installations/telemetry-token` | `Authorization: Bearer <installation_token>` | 200 |
| GET | `/v1/healthz` | 없음 | 200 |
| POST | `/v1/invitations` | `X-Admin-Token` | 201 |
| POST | `/v1/invitations/{id}/revoke` | `X-Admin-Token` | 204 |
| GET | `/windows?code=...` | 없음 | 200 `text/plain` |
| GET | `/unix?code=...` | 없음 | 200 `text/plain` |
| GET | `/bin/{filename}` | 없음 | 200 `application/octet-stream` |

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
이 전환 없이는 발급된 telemetry token 이 전부 401 이 된다. 재발급(§6.3의
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
그래도 `ptt_` 는 전송 경로에 실리는 값이라 유출을 전제로 언제든 교체할 수 있어야 한다.
`installation_token` 은 그 교체를 요청할 근거이며, 재발급 요청 외에는 키링 밖으로 나가지 않는다.

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

### 5.2 enroll 응답

- enroll 응답에는 저장된 manifest 를 싣되 **`config_revision` 만 `manifests.version` 으로 덮어쓴다.**
- 활성 manifest 가 없으면 enroll 은 409 `manifest_not_configured` 로 실패한다.
- 저장된 manifest 가 계약을 어기고 있으면(알 수 없는 필드 등) 서버가 같은 409 로 끊는다.
  어차피 클라이언트가 거부할 응답을 내려보내지 않고, 관리자가 고치도록 안내한다.

클라이언트가 한 번 더 검증하는 항목:

- `otlp.endpoint` 는 **https 필수**. `http` 는 `localhost` 에만 허용된다.
- `otlp.protocol` 은 `http/protobuf` · `http/json` · `grpc` 뿐이다.
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
| 알 수 없는 경로 | 404 | `not_found` |
| 지원하지 않는 메서드 | 405 | `method_not_allowed` |

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
| `pulsemetry.binaries.dir` | `./binaries` | CLI 바이너리가 놓인 서버 로컬 디렉터리 |

DB 접속은 `PULSEMETRY_DB_URL` · `PULSEMETRY_DB_USERNAME` · `PULSEMETRY_DB_PASSWORD` 로 덮어쓴다.

---

## 9. 운영

### 9.1 manifest 준비 — 현재는 수동 INSERT 다

manifest 를 만드는 관리자 API 는 이 서버의 범위 밖이다(대시보드 서버의 몫).
따라서 **운영 manifest 는 대시보드 서버가 생길 때까지 수동 INSERT 로 넣는다.**

`enrollment.manifests` 에 해당 tenant 의 `is_active = true` 행이 없으면
그 tenant 의 모든 enroll 이 409 `manifest_not_configured` 로 실패한다.
새 tenant 를 온보딩할 때 **초대 코드를 발급하기 전에** manifest 를 먼저 넣어야 한다.

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
로컬 개발용 tenant·admin member·manifest v1 은 `local` 프로파일 시더(`LocalSeeder`)가 넣는다 — §10 참고.

### 9.2 바이너리 배치

`pulsemetry.binaries.dir` 에 §6.2 의 이름 그대로 파일을 놓는다.
없는 아키텍처는 404 가 되며, 그 아키텍처의 사용자는 설치가 실패한다.

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
- **공유 DB 를 향해 `SPRING_PROFILES_ACTIVE=local` 을 켜지 마라.** LocalSeeder 가
  로컬 개발용 가짜 tenant 와 `http://localhost:4318` manifest 를 시드한다 — 로컬 전용이다.
- 수용 기준(B3): enroll 로 발급받은 `ptt_` 토큰으로
  `curl -X POST http://<alb-dns>/v1/traces -H "Authorization: Bearer <ptt>"
  -H "Content-Type: application/json" -d '{"resourceSpans":[]}'` → **2xx**,
  토큰 없이 → 401.

---

## 10. 로컬 실행

```sh
docker compose up -d                       # PostgreSQL 17
export PULSEMETRY_ADMIN_API_TOKEN=...      # 없으면 기동 실패한다
# auth-proxy 와 공유하는 HMAC 키. 로컬은 ai-telemetry-pipeline compose 의 기본값과 맞춘다.
export PULSEMETRY_TOKEN_HASH_SECRET=local-development-secret-change-me
export SPRING_PROFILES_ACTIVE=local        # local 프로파일 시더를 켠다
./gradlew :apps:enrollment-api:bootRun
```

`local` 프로파일은 `LocalSeeder` 를 켠다 — tenant 하나, 활성 owner member 하나,
`is_active = true` 인 manifest v1 을 넣는다. 이게 없으면 §9.1 대로 manifest 를 직접 넣기 전까지
첫 enroll 이 409 `manifest_not_configured` 로 실패한다.
시더는 **멱등**하다. 이미 tenant 가 있으면 아무것도 하지 않는다.
`POST /v1/invitations` 에 넣을 `tenant_id` 와 `created_by_member_id` 는 기동 로그에 찍힌다.

테스트는 Testcontainers 로 실제 PostgreSQL 을 띄우므로 Docker 데몬이 필요하다.
H2 등 임베디드 DB 로 대체하지 않는다 — jsonb·부분 유니크 인덱스·스키마 분리를 검증할 수 없다.

V1 마이그레이션이 native enum 채택(ADR 0009)으로 재작성되어 Flyway 체크섬이 바뀌었다.
이전 버전으로 만들어진 로컬 DB 는 `docker compose down -v` 로 볼륨째 지우고 다시 띄운다.
