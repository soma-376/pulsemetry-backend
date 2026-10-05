# CLI 설치 등록·배포

[API 길잡이](README.md) · [공통 규칙](common.md) · [공통 스키마](common-schemas.md)

## 엔드포인트

<a id="endpoint-enrollment-api-70"></a>

### 설치 등록

<!-- endpoint: enrollment-api POST /v1/enroll -->

```http
POST /v1/enroll
```

서버: **enrollment-api** · 성공: **201** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/EnrollmentController.kt)

**Path**

없음.

**Headers**

```http
Content-Type: application/json
```

**Query**

없음.

**Body**

```ts
{
  code?: string | null; // code 또는 호환 invite에 유효한 코드 필수
  invite?: string | null; // deprecated
  installer_version?: string | null; // deprecated
  operating_environment?: string | null; // deprecated
  device_id?: string | null; // deprecated, 무시
  tools_detected?: string[] | null; // deprecated, 무시
  platform?: string | null;
  architecture?: string | null;
  hostname?: string | null;
  client_version?: string | null;
}
```

**Response**

```ts
// EnrollmentResponse
{
  installation_id: string; installation_token: string; telemetry_token: string; manifest: ManifestPayload;
}
```

[EnrollmentResponse 전체 스키마·중첩 타입](enrollment.md#schema-EnrollmentResponse)

구버전 invite 등 호환 필드와 검증은 아래 설치 등록 규칙 참고. 최상위 응답은 정확히 4키.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-71"></a>

### 수집 토큰 재발급

<!-- endpoint: enrollment-api POST /v1/installations/telemetry-token -->

```http
POST /v1/installations/telemetry-token
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/TelemetryTokenController.kt)

**Path**

없음.

**Headers**

```http
Authorization: Bearer <pit_installation_token>
```

**Query**

없음.

**Body**

본문 없음.

**Response**

```ts
// TelemetryTokenResponse
{ installation_id: string; telemetry_token: string }
```

[TelemetryTokenResponse 전체 스키마·중첩 타입](enrollment.md#schema-TelemetryTokenResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-72"></a>

### manifest 재조회

<!-- endpoint: enrollment-api GET /v1/manifest -->

```http
GET /v1/manifest
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/ManifestResyncController.kt)

**Path**

없음.

**Headers**

```http
Authorization: Bearer <urt_refresh_token>
```

권한: 유효한 사용자 RT. 요청이 RT를 소비·회전하므로 캐시·프리페치·자동 재시도를 금지한다.

**Query**

없음.

**Body**

본문 없음.

**Response**

```ts
// ManifestResyncResponse
{
  manifest: ManifestPayload; access_token: string; refresh_token: string; token_type: "Bearer"; expires_in: number;
}
```

[ManifestResyncResponse 전체 스키마·중첩 타입](enrollment.md#schema-ManifestResyncResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-73"></a>

### 설치 보고

<!-- endpoint: enrollment-api POST /v1/installations/{installationId}/heartbeat -->

```http
POST /v1/installations/{installationId}/heartbeat
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/installation/InstallationHeartbeat.kt)

**Path**

```ts
{
  installationId: string;
}
```

**Headers**

```http
Authorization: Bearer <pit_installation_token>
Content-Type: application/json
```

**Query**

없음.

**Body**

```ts
{
  sent_at: string;
  daemon: { version: string; platform: "darwin" | "linux" | "windows"; architecture: string; run_id: string };
  applied_config_revision: number;
  collection: { mode: "local" | "direct"; receiving_since: string | null; forwarding: boolean; delivered: number; lost: number; pending: number; last_delivered_at: string | null };
}
```

**Response**

```ts
// HeartbeatResponse
{
  received_at: string; expected_config_revision: number | null;
  acknowledged_config_revision: number | null; report_interval_seconds: number;
}
```

[HeartbeatResponse 전체 스키마·중첩 타입](enrollment.md#schema-HeartbeatResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-74"></a>

### 설치 스크립트

<!-- endpoint: enrollment-api GET /windows -->

```http
GET /windows
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/BootstrapController.kt)

**Path**

없음.

**Headers**

필수 인증 헤더 없음.

**Query**

```ts
{
  code: string;
}
```

**Body**

본문 없음.

**Response**

text/plain;charset=UTF-8 — 설치 스크립트

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-75"></a>

### 설치 스크립트

<!-- endpoint: enrollment-api GET /unix -->

```http
GET /unix
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/BootstrapController.kt)

**Path**

없음.

**Headers**

필수 인증 헤더 없음.

**Query**

```ts
{
  code: string;
}
```

**Body**

본문 없음.

**Response**

text/plain;charset=UTF-8 — 설치 스크립트

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-76"></a>

### 바이너리 다운로드

<!-- endpoint: enrollment-api GET /bin/{filename} -->

```http
GET /bin/{filename}
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/api/BinaryController.kt)

**Path**

```ts
{
  filename: string;
}
```

**Headers**

필수 인증 헤더 없음.

**Query**

없음.

**Body**

본문 없음.

**Response**

application/octet-stream — 바이너리

Content-Disposition: attachment; filename="...". 허용 파일·릴리스 해시를 검사한다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-77"></a>

### 데몬 업데이트 확인

<!-- endpoint: enrollment-api GET /api/v1/check-updates -->

```http
GET /api/v1/check-updates
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/update/DaemonUpdateCheck.kt)

**Path**

없음.

**Headers**

필수 인증 헤더 없음.

**Query**

```ts
{
  current_version: string; // v 접두사 없는 SemVer
  platform: string;
  architecture: string;
}
```

**Body**

본문 없음.

**Response**

```ts
// UpdateCheckResponse
{ latest_version: string; update_available: boolean }
```

[UpdateCheckResponse 전체 스키마·중첩 타입](enrollment.md#schema-UpdateCheckResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.


### CLI 설치 등록 — `POST /v1/enroll`

요청 본문. `invite`, `installer_version`, `operating_environment`, `device_id`, `tools_detected`는
구버전 클라이언트 호환 필드다. 새 클라이언트는 앞의 다섯 필드만 보낸다.

```json
{
  "code": "ABCD-EFGH-JKMN",
  "platform": "darwin",
  "architecture": "arm64",
  "hostname": "hong-macbook",
  "client_version": "1.2.3"
}
```

응답(201). 최상위 키는 아래 네 개가 전부이며 필드를 추가하지 않는다.

```json
{
  "installation_id": "b6e9305a-ec5a-4aa4-bc5a-f520ad7ccbe1",
  "installation_token": "pit_<base64url>",
  "telemetry_token": "ptt_<base64url>",
  "manifest": {
    "schema_version": 1,
    "config_revision": 3,
    "otlp": {
      "endpoint": "https://telemetry.example.com",
      "protocol": "http/protobuf",
      "compression": "gzip",
      "timeout_ms": 10000
    },
    "signals": { "logs": true, "metrics": true, "traces": true },
    "privacy": {
      "collect_user_prompts": false,
      "collect_assistant_responses": false,
      "collect_tool_details": false,
      "collect_tool_content": false,
      "collect_user_email": false,
      "collect_raw_api_bodies": false
    },
    "repository_allowlist": [],
    "resource_attributes": {}
  }
}
```

### 텔레메트리 토큰 재발급 — `POST /v1/installations/telemetry-token`

요청 본문과 쿼리 파라미터는 없다. 장기 설치 토큰을 헤더에 보낸다.

```http
Authorization: Bearer pit_<base64url>
```

응답(200). 기존 활성 telemetry token은 폐기된다.

```json
{
  "installation_id": "b6e9305a-ec5a-4aa4-bc5a-f520ad7ccbe1",
  "telemetry_token": "ptt_<new-base64url>"
}
```

### 설치 스크립트와 바이너리 다운로드

`GET /windows`, `GET /unix`

```ts
// 쿼리 파라미터
{ code: string; } // XXXX-XXXX-XXXX
```

```text
# 응답(200, text/plain;charset=UTF-8)
# 플랫폼별 설치 스크립트. 응답에는 요청한 초대 코드가 삽입된다.
```

`GET /bin/{filename}`

```ts
// 쿼리 파라미터 없음
{}
```

```text
# 응답(200, application/octet-stream)
# Content-Disposition: attachment; filename="<filename>"
```


## 초대 코드

- 형식: Crockford Base32 12자를 `XXXX-XXXX-XXXX` 로 끊은 것.
- 알파벳: `0123456789ABCDEFGHJKMNPQRSTVWXYZ` — `I` `L` `O` `U` 를 제외한 32자.
  사람이 코드를 옮겨 적거나 불러 주는 상황을 전제하므로 헷갈리는 글자를 애초에 만들지 않는다.
- 정규식: `^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$`
- 생성은 `SecureRandom`. 알파벳 크기가 32(2의 거듭제곱)라 모듈로 편향이 없다.

enroll 요청의 `platform` 은 클라이언트가 `runtime.GOOS` 를 그대로 보낸다.
따라서 macOS 는 `darwin` 으로 도착하며 **서버가 `macos` 로 정규화해** 저장한다.
이미 정규화된 `macos` 도 그대로 받는다. `windows`·`linux` 는 바꾸지 않고,
그 밖의 값은 400 `invalid_request` 다.

### 정규화

`POST /v1/enroll` 은 입력을 정규화한다: 앞뒤 공백 제거 → 대문자 → 하이픈이 없으면 4자마다 삽입.
그러고도 정규식을 만족하지 못하면 400 `invalid_request` 다.

`GET /windows`·`GET /unix` 는 **정규화하지 않고** 정규식 검증만 한다([기존 §6.1](../enrollment-server-spec.md#61-get-windows-get-unix) 참조).

### 소비

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
영향 행 수가 1이면 소비 성공이고, 0이면 그때서야 사유를 조회해 [기존 §7](../enrollment-server-spec.md#7-에러-계약) 의 에러로 옮긴다.
동시 요청 N개가 같은 코드로 들어와도 정확히 하나만 201 을 받고 나머지는 409 `invitation_used` 다.

---

## 인증과 자격증명


### enroll

인증이 없다. 초대 코드 자체가 자격증명이므로, 코드의 형식 검증과 원자적 소비,
그리고 소비 직후의 대상 멤버 정지 검사가 전부다. 대상 멤버가 `suspended` 면
403 `forbidden` 으로 끊는다 — 발급 시점 차단([기존 §4.1](../enrollment-server-spec.md#41-관리자-api))과 같은 정책이다. 트랜잭션
롤백으로 소비도 취소되므로 코드는 살아 있고, 정지 해제 뒤 같은 코드로 다시 설치할 수 있다.

**enroll 성공은 대상 멤버의 `invited → active` 전환 이벤트다.** OTLP 경로의
auth-proxy(ai-telemetry-pipeline)가 `invited`·`suspended` 멤버의 토큰을 거부하므로,
이 전환 없이는 발급된 telemetry token 이 전부 401 이 된다. 재발급([기존 §4.3](../enrollment-server-spec.md#43-2단-토큰-모델과-봉투-분리)의
`POST /v1/installations/telemetry-token`)도 같은 전환을 보정한다 — pit_ 인증이 과거
enroll 완료의 증명이기 때문이다. 전환은 `invited` 에서만 일어난다. `suspended` 는
어느 경로도 건드리지 않는다 — 정지 해제는 관리자의 결정이지 설치의 부수효과가 아니다.

### 2단 토큰 모델과 봉투 분리

설치된 클라이언트는 서로 역할이 다른 두 비밀을 갖는다.

| 토큰 | 접두사 | 저장 위치 | 용도 | 교체 |
|---|---|---|---|---|
| `installation_token` | `pit_` | OS 키링 | 이 설치의 장기 신원 | 하지 않는다 |
| `telemetry_token` | `ptt_` | OS 키링 (데몬이 상위 전송 시 `Authorization` 에 주입) | 텔레메트리 전송 | 언제든 재발급 |

`telemetry_token` 은 벤더 설정 파일로 나가지 않는다. enroll 이 로컬 텔레메트리 파이프라인을
자동 배선하면서 Codex/Claude 설정에는 **로컬 ingest 토큰**이 들어가고, 회사 `ptt_` 는 OS
키링에 저장되어 데몬이 상위 전송 시 `Authorization` 헤더에 주입한다(telemetryctl `forward.go`).
이 서술은 로컬 배선이 성립할 때다 — grpc manifest·키링 불가 등으로 회사 직결로 강등된 설치에서는
벤더 설정의 `Authorization` 에 `ptt_` 가 실린다(허브 `contracts/telemetry-ingest.md` [기존 §6](../enrollment-server-spec.md#6-부트스트랩-스크립트와-바이너리)).
그래도 `ptt_` 는 전송 경로에 실리는 값이라 유출을 전제로 언제든 교체할 수 있어야 한다.
`installation_token` 은 그 교체를 요청할 근거이며, 재발급 요청과 설치 보고([기존 §4.5](../enrollment-server-spec.md#45-설치-보고--post-v1installationsinstallation_idheartbeat)) 외에는 키링 밖으로 나가지 않는다.

**봉투 분리**: `installation_id` 와 두 토큰은 "설정" 이 아니라 이 설치의 자격이므로
manifest **밖**, 응답 봉투 상위에 둔다. manifest 안에 넣지 않는 이유는 두 가지다.

1. 설정 재조회 API 가 생겼을 때 매번 secret 을 실어 나르지 않기 위해서다.
2. 클라이언트가 `DisallowUnknownFields` 로 파싱하며 이 설정이 **중첩 manifest 까지 적용된다.**
   manifest 안에 봉투 필드가 하나라도 있으면 설치가 그 자리에서 실패한다.

### 규격

- `installation_token`: `pit_` + base64url(32 랜덤 바이트, 패딩 없음)
- `telemetry_token`: `ptt_` + base64url(32 랜덤 바이트, 패딩 없음)
- 해시: 초대 코드와 `installation_token` 은 **SHA-256 hex 소문자 64자**.
  `telemetry_token` 만 **HMAC-SHA256(`pulsemetry.token-hash-secret`, 토큰 전문) hex 소문자 64자** —
  auth-proxy 가 같은 키·같은 연산으로 `token_hash` 를 조회하기 때문이다 ([기존 §8](../enrollment-server-spec.md#8-설정))

토큰과 초대 코드의 원본은 DB 에 저장하지 않는다. `*_hash` 컬럼에는 해시만 들어간다.
해시가 결정론적이어야 유니크 인덱스 조회가 성립하므로 bcrypt·Argon2 를 쓸 수 없다.
원본이 고엔트로피 난수(토큰 256비트, 초대 코드 60비트)라 사전 공격 대상이 아니라는 전제 위에 서 있으며,
사람이 고른 비밀번호에는 이 방식을 쓸 수 없다.

토큰과 초대 코드 원본을 **로그에 남기지 않는다.** 에러 응답에도 담지 않는다 —
파싱 실패 메시지에는 요청 본문 조각이 섞여 있어 그대로 흘리면 코드가 새어 나간다.

### 설치 보고 — `POST /v1/installations/{installation_id}/heartbeat`

데몬이 생존, 적용한 manifest 판, 수집 경로의 상태를 주기적으로 보고한다. 데몬의 동작은
허브 `contracts/enrollment-api.md` [기존 §7](../enrollment-server-spec.md#7-에러-계약)이 정하고, 서버가 받는 요청·응답의 필드는 아래 두 표가 정한다 — 서버의 계약 테스트(`HeartbeatApiTest`)가 이 표를 오라클로 쓴다.
원격 telemetryctl develop에는 이 경로의 JSON Schema도 송신 클라이언트도 아직 없다.
서버의 검증과 저장은 [ADR 0040](../adr/0040-설치-보고는-최신-상태-한-행과-수집-구간-이력으로-저장한다.md)을 따른다.
`pulsemetry.heartbeat.enabled`로 켠다. 꺼져 있으면 이 경로는 404다.

**처리 순서**

1. 자격증명을 본다. 판정은 토큰 재발급([기존 §4.3](../enrollment-server-spec.md#43-2단-토큰-모델과-봉투-분리))과 같다 — 없거나 무효면 401 `unauthorized`. `ptt_`와 사용자 토큰은 받지 않는다. **인증되지 않은 요청의 본문은 읽지 않는다.**
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
응답은 manifest를 싣지 않는다. 재조회는 이 문서의 manifest 재조회 API다.

저장소 장애는 **503 `heartbeat_unavailable` + `Retry-After`** 다. 401·403으로 돌리지 않는다 — 데몬이 재등록이 필요하다고 오해한다.

**현재 상태** — telemetryctl 기본 브랜치에는 이 보고의 송신이 없다. 지금 배포된 데몬의 설치는 보고하지 않으므로 `last_seen_at`·수집 구간·적용 확인이
보고로 생기지 않는다. 조회 API는 그 설치의 적용 판을 확인 불가로 낸다(ADR 0043·0053).

---

## Manifest

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

### 저장

- 저장 위치는 `enrollment.manifests.manifest` (jsonb). 기존 행을 고치지 않고 설정이 바뀌면 새 `version` 행을 만든다.
- tenant 당 `is_active = true` 행은 **최대 하나**다. 부분 유니크 인덱스
  `UNIQUE (tenant_id) WHERE is_active` 가 이를 보장한다.

### dbml 과의 의도적 차이 (SCHEMA-DRIFT)

ADR 0004 가 요구한 기록처다 — 스키마가 설계도(`rdb-schema/dbdiagram.dbml`)와 **의도적으로**
다르게 이식된 지점을 여기에 남긴다. 마이그레이션·엔티티·테스트의 `SCHEMA-DRIFT` 주석이 이 절을 가리킨다.

| # | 차이 | 위치 | 이유 |
|---|---|---|---|
| 1 | `enrollment.manifests` 의 부분 유니크 인덱스 `ux_manifests_tenant_active` — dbml 에 없다 | `V2__manifests_single_active_index.sql` | enroll 이 "tenant 의 활성 manifest" 를 단수로 가정하므로 DB 가 보장한다. baseline 된 DB 에도 적용되도록 V1 이 아니라 V2 에 두었다 |
| 2 | `enrollment.telemetry_tokens` 의 부분 유니크 인덱스 `ux_telemetry_tokens_installation_active` — dbml 에 없다 | `V3__telemetry_tokens_single_active_index.sql` | 재발급("전부 폐기 후 발급") 계약의 동시성 최종 방어선 |

### enroll 응답

- enroll 응답에는 저장된 manifest 를 싣되 **`config_revision` 만 `manifests.version` 으로 덮어쓴다.**
- 활성 manifest 가 없으면 enroll 은 409 `manifest_not_configured` 로 실패한다.
- 저장된 manifest 가 계약을 어기고 있으면(알 수 없는 필드 등) 서버가 같은 409 로 끊는다.
  어차피 클라이언트가 거부할 응답을 내려보내지 않고, 관리자가 고치도록 안내한다.

클라이언트가 한 번 더 검증하는 항목:

- `otlp.endpoint` 는 **https 필수**. `http` 는 `localhost` 에만 허용된다.
- `otlp.protocol` 은 `http/protobuf` · `http/json` · `grpc` 뿐이다.
  단 **`grpc` 는 계약상 유효해도 클라이언트가 배선하지 못한다** — telemetryctl 이 grpc 상위 전송을
  지원하지 않아 그 테넌트의 설치는 로컬 파이프라인 배선에서 제외되고 회사 직결로 강등된다
  (로컬 대시보드가 빈다 — telemetryctl ADR 0001·0006, 허브 `contracts/telemetry-ingest.md` [기존 §6](../enrollment-server-spec.md#6-부트스트랩-스크립트와-바이너리)).
  grpc 테넌트를 구성하기 전에 이 제약을 확인한다. 현재 grpc 테넌트는 없다.
- `otlp.timeout_ms` 는 1 이상, `otlp.compression` 은 `none` · `gzip` 뿐이다(계약 스키마).
- `schema_version` 이 클라이언트의 `SupportedSchemaVersion`(현재 1)을 넘으면 거부한다.

서버가 이 규칙을 어긴 값을 주면 설치가 실패한다.

---

## 부트스트랩 스크립트와 바이너리

### `GET /windows`, `GET /unix`

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

### `GET /bin/{filename}`

아래 여섯 개와의 **문자열 동등 비교만** 한다.

```text
pulsemetry_windows_amd64.exe   pulsemetry_windows_arm64.exe
pulsemetry_darwin_amd64        pulsemetry_darwin_arm64
pulsemetry_linux_amd64         pulsemetry_linux_arm64
```

목록에 없으면 404.

파일의 출처는 ADR 0053이다. `pulsemetry.binaries.dir`에 telemetryctl 릴리스 디렉터리(`v<SemVer>`, [기존 §6.3](../enrollment-server-spec.md#63-get-apiv1check-updates--데몬-업데이트-확인))가 있으면 공개 이름을 그 릴리스의
데몬 자산 `pulsemetry_cli_{os}_{arch}`(Windows만 `.exe`)로 대응하고, `SHA256SUMS`와 해시가 같은 파일만 내려준다. 그 릴리스에 없는 대상은 404다.
릴리스 디렉터리가 하나도 없으면 공개 이름 그대로의 파일을 내려주고, 파일이 없으면 404다.
`..` 를 문자열 치환으로 지우거나 경로를 정규화해 방어하지 않는다 — 인코딩 변형에 언젠가 뚫린다.

응답 헤더는 `Content-Type: application/octet-stream` 과 함께
`Content-Disposition: attachment; filename="…"` · `Content-Length` 를 싣는다.

바이너리는 서버 로컬 디렉터리에서 서빙한다. S3·GitHub Releases 리다이렉트를 쓰지 않는다.

### `GET /api/v1/check-updates` — 데몬 업데이트 확인

데몬이 기동 직후와 24시간마다 "내 버전보다 새 데몬이 있는가"를 묻는다. 계약은 허브 `contracts/daemon-updates.md`(허브 ADR 0011)이고,
실제 호출자는 원격 telemetryctl develop의 `internal/updatecheck/client.go`다 — 서버의 계약 테스트(`UpdateCheckApiTest`)는 그 클라이언트가 보내는 쿼리와
읽는 응답(JSON 문서 하나, 공백이 아닌 `latest_version`, 빠지면 안 되는 불리언 `update_available`, 404는 미지원)을 오라클로 쓴다.
이 경로의 JSON Schema는 원격에 없다. 인증이 없다 — 데몬이 인증 정보를 보내지 않는다.
알려 주기만 한다. 내려받기와 설치는 하지 않는다.

쿼리는 셋 다 필수다: `current_version`(SemVer, `v` 없음) · `platform`(`darwin`·`linux`·`windows`) · `architecture`(`amd64`·`arm64`). 그 밖의 쿼리는 무시한다.

```json
{"latest_version": "0.2.0", "update_available": true}
```

- **최신 버전은 이 서버가 [기존 §6.2](../enrollment-server-spec.md#62-get-binfilename)로 배포하는 바이너리의 판이다.** 외부 릴리스 목록을 보지 않는다.
  판은 telemetryctl 릴리스 산출물 그대로가 말한다(ADR 0053) — `pulsemetry.binaries.dir` 안의 `v<SemVer>` 디렉터리 하나가 릴리스 하나이고,
  그 태그의 데몬 자산 `pulsemetry_cli_{os}_{arch}`(Windows만 `.exe`)와 `SHA256SUMS`를 받은 그대로 담는다. 디렉터리 이름에서 `v`를 뗀 것이 판이다.
  `v` 뒤가 SemVer가 아닌 디렉터리는 릴리스가 아니다. 릴리스가 여럿이면 판이 가장 높은 하나가 이 서버의 릴리스이고, 거기 없는 대상은 낮은 판으로 내려가지 않는다.
- `SHA256SUMS`는 줄마다 소문자 hex 64자, 공백 둘, 자산 이름이다(telemetryctl `scripts/release.mjs`의 `checksums`). 데몬 자산이 아닌 줄(GUI 패키지)은 무시한다.
  형식이 틀린 줄이나 같은 이름이 두 번 나오면 그 릴리스를 읽지 않는다. 마지막 줄바꿈은 없어도 된다.
- 요청의 `platform`·`architecture`로 공개 이름(`pulsemetry_{platform}_{architecture}`, Windows만 `.exe`)을 정하고 허용 목록과 동등 비교한다.
  **그 대상의 자산이 있고 SHA-256이 `SHA256SUMS`와 같을 때만** 그 판을 답한다. 해시는 파일의 크기·수정 시각이 바뀔 때만 다시 계산한다.
  `/bin/{filename}`([기존 §6.2](../enrollment-server-spec.md#62-get-binfilename))이 같은 확인을 거친 같은 파일을 내려준다.
- `update_available`은 `current_version` < `latest_version`일 때만 true다. 순서는 SemVer 2.0.0의 우선순위 규칙이다 —
  사전 릴리스는 같은 번호의 정식 판보다 낮고 빌드 메타데이터는 순서에 영향을 주지 않는다. 버전을 주입하지 않은 빌드(`0.1.0`)를 따로 취급하지 않는다.
- 응답은 두 키의 JSON 문서 하나이고 `Cache-Control: no-store`다. 리다이렉트를 내지 않는다(데몬이 따라가지 않는다).

| 상황 | HTTP | error |
|---|---|---|
| 쿼리가 빠졌거나 비었다, `current_version`이 SemVer가 아니다(`v0.2.0`·`dev` 등) | 400 | `invalid_request` |
| 릴리스 디렉터리가 없다(공개 이름의 평면 파일만 있어도 같다), 그 릴리스의 `SHA256SUMS`가 없거나 형식이 틀렸다 | 404 | `not_found` |
| `platform`·`architecture`가 여섯 파일명으로 이어지지 않는다 | 404 | `not_found` |
| 그 대상의 자산이 없거나, `SHA256SUMS`에 없거나, 해시가 다르다 | 404 | `not_found` |

404는 "업데이트 없음"이 아니라 "이 서버가 확인해 줄 수 없음"이다. 데몬은 미지원으로 표시한다.
**확인할 수 없을 때 임의의 버전이나 `update_available=false`로 답하지 않는다.**
별도 스위치는 없다 — 릴리스 디렉터리를 놓으면 켜지고 없으면 404다. 읽는 도중 파일이 교체되는 경합은 [기존 §6.2](../enrollment-server-spec.md#62-get-binfilename)와 같이 없는 파일로 다룬다.

---


### manifest 재동기화

`GET /v1/manifest`는 `Authorization: Bearer <사용자 RT>`를 받고 정책과 토큰의 5키 봉투
(`manifest`·`access_token`·`refresh_token`·`token_type`·`expires_in`)를 반환한다.
허브 `contracts/user-auth.md`가 계약이다. 봉투 안의 `manifest`는 원격 telemetryctl의 `contracts/enrollment-manifest.schema.json`을 만족하고,
나머지 네 키는 [기존 §11](../enrollment-server-spec.md#11-사용자-인증)의 TokenResponse와 같다. 원격 telemetryctl develop에는 이 봉투의 JSON Schema도 재조회 클라이언트도 아직 없다.
AT와 설치 `pit_`·`ptt_`는 401 `invalid_credentials`다.
활성 정책과 RT 회전을 한 트랜잭션으로 묶으며 실패 시 전부 롤백한다(ADR 0019).
활성 manifest가 없거나 저장된 정책이 계약 스키마를 어기면 409 `manifest_not_configured`이고 RT는 소비되지 않는다.
응답의 새 토큰은 서버 revision 일치만 보장하고 클라이언트 적용 완료의 증거가 아니다.
GET이 상태를 변경하므로 캐시·프리페치·자동 재시도를 금지한다. 커밋 후 응답 유실은 재로그인으로 복구한다.
OTLP 인증은 `ptt_`를 유지하며 사용자 AT나 revision 검사를 추가하지 않는다(허브 ADR 0008).


## 설치 JSON 스키마

<a id="schema-EnrollmentResponse"></a>
<a id="schema-TelemetryTokenResponse"></a>
<a id="schema-ManifestResyncResponse"></a>
<a id="schema-ManifestPayload"></a>
<a id="schema-HeartbeatResponse"></a>
<a id="schema-UpdateCheckResponse"></a>

```ts
type EnrollmentResponse = {
  installation_id: string; installation_token: string; telemetry_token: string; manifest: ManifestPayload;
};
type TelemetryTokenResponse = { installation_id: string; telemetry_token: string };
type ManifestResyncResponse = {
  manifest: ManifestPayload; access_token: string; refresh_token: string; token_type: "Bearer"; expires_in: number;
};
type ManifestPayload = {
  schema_version: number; config_revision: number;
  otlp: {
    endpoint: string; protocol: "http/protobuf" | "http/json" | "grpc";
    compression?: "none" | "gzip"; timeout_ms?: number;
  };
  signals: { logs: boolean; metrics: boolean; traces: boolean };
  privacy: {
    collect_user_prompts: boolean; collect_assistant_responses: boolean; collect_tool_details: boolean;
    collect_tool_content: boolean; collect_user_email: boolean; collect_raw_api_bodies: boolean;
  };
  repository_allowlist?: string[]; resource_attributes?: Record<string, string>;
};
type HeartbeatResponse = {
  received_at: string; expected_config_revision: number | null;
  acknowledged_config_revision: number | null; report_interval_seconds: number;
};
type UpdateCheckResponse = { latest_version: string; update_available: boolean };
```

manifest의 선택 필드는 null이면 응답에서 생략한다(@JsonInclude NON_NULL). 설치 응답 4키와 manifest에 신원·토큰을 추가하지 않는다.
원천 스키마는 [telemetryctl manifest](../../../telemetryctl/contracts/enrollment-manifest.schema.json)다.
code가 없거나 빈 값이면 구버전 invite를 사용하며 둘 다 없으면 400이다. platform이 비면 operating_environment,
client_version이 비면 installer_version을 사용한다. device_id·tools_detected는 수용하되 무시한다.


## 직렬화·검증 근거

- [ManifestPayload.kt](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/contract/ManifestPayload.kt)
- [EnrollRequest.kt](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/contract/EnrollRequest.kt)
