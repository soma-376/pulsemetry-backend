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

**범위 밖**: 웹 대시보드 조회 API, heartbeat,
`uninstall`/`repair`, 초대 이메일 발송, 데이터 파이프라인.

---

## 2. 엔드포인트

| 메서드 | 경로 | 인증 | 성공 |
|---|---|---|---|
| POST | `/v1/enroll` | 없음 (초대 코드 자체가 자격) | 201 |
| POST | `/v1/installations/telemetry-token` | `Authorization: Bearer <installation_token>` | 200 |
| GET | `/v1/manifest` | `Authorization: Bearer <사용자 RT>` | 200 |
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
docker compose up -d --build              # PostgreSQL · ClickHouse · 일회성 시드
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
허브 `contracts/user-auth.md`와 `telemetryctl/contracts/manifest-resync.schema.json`이 계약이다.
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
  }[];
};
type ContractWrite = {
  planId: string; effectiveFrom: string; effectiveTo: string | null;
  termNote: string | null;
  tiers: { label: string; seats: number; monthlyFeePerSeatUsd: Money }[];
};
```

초대는 최대 100명, role은 `admin`·`member`, `teamId`는 UUID 또는 null이다.
**메일 발송을 접수하지 않으므로 `queued`를 반환하지 않는다.** `issued`의 코드를 사용자에게 전달한다.
신규 초대의 기본 만료는 72시간이다. 기존 초대가 있으면 `already_invited`이며 기존 원본 코드를 재조회하지 않는다.

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

### 조회·관리 오류

```json
{"error":{"code":"version_conflict","message":"관리 요청을 처리할 수 없습니다.","fieldErrors":[]},"requestId":"..."}
```

| 상태 | 주요 코드·처리 |
| --- | --- |
| 400 | invalid_request, 필드 오류 표시 |
| 401 | unauthenticated, 로그인/토큰 갱신 |
| 403 | forbidden, 해당 동작 비활성화 |
| 404 | not_found, 타 조직/없는 자원 |
| 409 | version_conflict, idempotency_conflict, team_name_conflict, vendor_already_registered, member_suspended, snapshot_expired |
| 422 | invalid_vendor, invalid_plan, invalid_contract_period, detected_vendor, role_not_assignable, owner_role_immutable, self_role_change |
| 503 | unavailable, Retry-After 후 재시도 |

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
| `PUT /collection-policy` | `{expectedVersion,collectRawContent}` | 200 PolicySaved |
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
  version: number; collectRawContent: boolean; confirmedAt: string;
  application: "future_enrollments";
  existingInstallationsUpdated: false;
};
```

서버는 현재 활성 manifest의 version을 비교한다. 충돌은 409 `version_conflict`다.
활성 manifest가 없으면 `expectedVersion=0`으로 최초 생성한다. 새 판번호는 기존 판번호의 최댓값 다음이다.
최초 생성에는 §9.1의 서버 수집 주소가 필요하다. 누락·잘못된 주소는 409 `manifest_not_configured`이고
manifest와 정책 확인 기록을 저장하지 않는다. 로그인은 이 설정과 무관하게 가능하다.
초기 프로토콜은 `http/protobuf`, 세 signal은 true, privacy는 전부 false에서 사용자의 원문 선택을 반영한다.
새 manifest 판에서 `privacy.collect_user_prompts`와 `privacy.collect_assistant_responses`만 함께 변경한다.
config_revision도 새 판에 맞춘다. endpoint·signals·나머지 privacy 설정 및 이전 판의 JSON은 보존한다.
동일 조직의 변경·완료 명령은 조직 행 잠금과 한 트랜잭션으로 처리한다.
정책 저장은 **이후 enroll의 기본값**이며 이미 설치된 클라이언트에 갱신 알림을 보내거나
installation_manifest_assignments를 적용 완료로 변경하지 않는다. 적용 여부는 대시보드 설치 조회로 확인한다.

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
  }[];
  nextCursor: string | null;
};
type ReissuedInvitation = {
  invitationId: string; replacesInvitationId: string;
  code: string; expiresAt: string;
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
이 API도 메일을 발송하지 않는다.

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

V9는 기존 버전 이력의 보관 여부를 반영한 뒤 중복 활성 제품을 검사한다.
중복이 있으면 적용을 중단하며 자동 병합·삭제하지 않는다. 해당 조직의 중복 등록을 검토한 뒤 다시 적용한다.
telemetry_ops의 V3는 별도 이력으로 관리하며 신규 조직 생성 시 빈 수집 요약을 원자적으로 초기화한다(ADR 0034).
적용된 migration 파일을 수정하지 않고 다음 번호를 추가한다.
organization_onboarding에는 정책 확인자·시각과 완료자만 남는다. 기존 완료 시각은 V8에서 보존한다.
개발 시드 초기화는 이 기록도 해당 시드 조직에 한해 삭제한다.

주요 검증은 `:apps:enrollment-api:test`의 UserAuthApiTest(인증)와 ManagementApiTest(관리·온보딩)다.
팀/초대/계약 쓰기, 조직 격리, 온보딩 완료 조건, 정책 판 보존, 재발급 소비 상태 계승을 실제 PostgreSQL에서 확인한다.
서버 API 구현, 프론트 배선, 실제 시드 E2E 통과는 별도로 확인한다.
프론트 온보딩에서 계약 입력은 선택이며 설정의 계약 관리도 API에 연결돼 있다. 초대 메일 발송은 구현하지 않는다.
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
