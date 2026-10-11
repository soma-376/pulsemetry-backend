# 공통 HTTP 규칙

[전체 API](README.md) · [공통 스키마](common-schemas.md)

## API 목록

| 번호 | API | 기능 | 서버 |
| --- | --- | --- | --- |
| 75 | [GET `/api/v1/healthz`](endpoints/75-get-enrollment-health.md) | 생존 확인 | enrollment-api |
| 76 | [GET `/api/v1/healthz`](endpoints/76-get-dashboard-health.md) | 생존 확인 | dashboard-api |

<a id="feature-reference"></a>

### 관리자 API

`X-Admin-Token` 헤더를 설정값 `pulsemetry.admin.api-token` 과 비교한다.
비교는 `MessageDigest.isEqual` 로 한다 — `==` 는 첫 불일치에서 반환하므로
응답 시간 차이로 키를 한 글자씩 알아낼 수 있다.

헤더가 없든 값이 틀리든 똑같이 401 `unauthorized` 다. 둘을 구분해 주지 않는다.

`X-Admin-Token` 을 통과해도 `POST /api/v1/invitations` 는 아래 경우 전부 **403 `forbidden`** 이다
(404 가 아니다 — 어느 member 가 존재하는지 알려 주지 않는다).

- `created_by_member_id` 가 존재하지 않는다
- 그 member 가 `tenant_id` 소속이 아니다
- 그 member 가 owner·admin 이 아니다
- 그 member 가 정지(`status = 'suspended'`)됐다
- **초대 대상** member 가 이미 있고 정지됐다

`invited` 대상은 정상이다 — 아직 설치하지 않은 사용자에게 코드를 재발급하는 경로다.

**설정값이 비어 있으면 애플리케이션이 기동하지 않는다.** 빈 문자열을 "인증 없음" 으로 해석하면
설정 실수 하나로 초대 발급이 인터넷에 열린다.


## 에러 계약

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
| 설치 보고의 경로 설치가 자격증명의 설치와 다름 ([기존 §4.5](../enrollment-server-spec.md#45-설치-보고--post-v1installationsinstallation_idheartbeat)) | 403 | `forbidden` |
| 설치 보고의 저장소 장애 ([기존 §4.5](../enrollment-server-spec.md#45-설치-보고--post-v1installationsinstallation_idheartbeat)) | 503 | `heartbeat_unavailable` |
| 알 수 없는 경로 | 404 | `not_found` |
| 지원하지 않는 메서드 | 405 | `method_not_allowed` |
| 문의 접수의 요청 수 초과 ([기존 §2.2](../enrollment-server-spec.md#22-post-v1inquiries-도입-문의-접수)) | 429 | `rate_limited` |
| 문의 접수의 저장소 장애 ([기존 §2.2](../enrollment-server-spec.md#22-post-v1inquiries-도입-문의-접수)) | 503 | `inquiry_unavailable` |

사용·폐기가 409 이고 만료만 410 인 이유: 사용과 폐기는 **사람의 행위**로 무효화된 상태라
409 Conflict 가 맞고, 만료는 **시간 경과**로 영구 소멸한 자원이라 410 Gone 이 맞다.
사용자 안내문은 세 경우 모두 "관리자에게 새 코드를 요청하세요"로 같고, 이 구분은 진단·로그 용도다.

소비 실패 사유의 우선순위는 **사용 → 폐기 → 만료**다.
동시 요청에서 진 쪽은 `used_at` 을 보게 되므로 409 `invitation_used` 를 받는다.

---


## 조직 관리 API

경로 앞에 `/api/v1/organizations/{organizationId}`를 붙인다.
관리 API는 `pulsemetry.management.enabled=true`, 사용자 인증, Base64 32바이트의
`pulsemetry.management.response-encryption-key`를 요구한다. 공개 브라우저 설정에 이 키를 넣지 않는다.
Bearer AT로 검증한 owner/admin만 사용할 수 있다. 타 조직은 404, member는 403이다.
ManagementController의 POST 명령에는 `Idempotency-Key`(영숫자·`_`·`-`, 8~128자)를 보낸다.
같은 조직·사용자·경로·키·본문은 24시간 같은 응답을 반환한다. 같은 키와 다른 본문은 409 `idempotency_conflict`다.
초대 코드가 포함된 재시도 응답은 DB에서 암호화된다.

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
| 409 | version_conflict, idempotency_conflict, team_name_conflict, vendor_already_registered, member_suspended, member_not_active, installation_unavailable, snapshot_expired, connector_managed, seat_already_held, seat_not_releasable, preview_stale, preview_expired, preview_used, not_awaiting_admin_action, seat_changed |
| 422 | invalid_vendor, invalid_plan, invalid_contract_period, detected_vendor, role_not_assignable, owner_role_immutable, self_role_change, notification_channel_unavailable, connector_unavailable, invalid_tier, seat_import_invalid(`details`에 행별 오류), no_eligible_seats, restore_not_available(`details`에 대상별 사유가 있을 수 있다), alert_rule_unavailable(`details.reason`) |
| 503 | unavailable, Retry-After 후 재시도. credential_key_unavailable(벤더 연결 — 운영이 암호화 키 설정을 고칠 때까지 재시도해도 같다) |

쓰기 성공 후 관련 조직의 팀·구성원·설정·개요 Query 캐시를 무효화한다.
버전 충돌 시 자동으로 새 버전을 덮어쓰지 않고 최신 값을 다시 보여 준다.


## 인증·책임·응답

- 조직 조회와 카탈로그에 `Authorization: Bearer <access_token>`을 사용한다. owner/admin만 접근한다.
- 인증은 enrollment-api가 발급한 JWT의 공개키 검증과 현재 사용자·세션 조회를 결합한다.
  로그아웃·폐기된 세션은 기존 AT로도 다음 요청부터 401이다.
  IdP OIDC는 enrollment-api의 로그인 단계에서만 사용한다. IdP 토큰이나 OIDC 임시 쿠키를
  이 서버의 Bearer 인증 대신 보내지 않는다. 새 로그인 절차는 Enrollment 명세 §11을 따른다.
- 조직 경로는 인증 주체의 조직과 일치해야 한다. 타 조직 경로와 일반 구성원은 403 `forbidden`이다(`OrganizationAccess`). 인증 주체의 조직이 삭제됐으면 404다.
  조직 안의 자원(알림·작업·구성원·제품)은 다른 조직의 것이면 없는 것과 같은 404다. 관리 명령(enrollment-api)은 타 조직 경로를 404로 거부한다(enrollment 명세 §12).
- 인증 기본값은 비활성이며 이때 보호 경로를 모두 거부한다. 공개키·issuer·audience를 설정해 활성화한다.
- PostgreSQL enrollment/telemetry_ops와 ClickHouse 분석 원천은 읽기 전용이다.
  snapshot 및 조회용 캐시만 dashboard_cache에 쓴다. 관리 명령은 enrollment-api가 담당한다.
- 성공은 `application/json`, camelCase DTO이며 별도 공통 data 봉투가 없다. nullable 필드는 유지한다.
- 금액은 USD decimal 문자열이다. null을 0으로 바꾸거나 환산 비용을 실제 청구액으로 표현하지 않는다.
- 허용된 프론트 origin에 GET/OPTIONS CORS를 제공하며 쿠키 인증을 사용하지 않는다.
- `GET /api/v1/healthz`는 인증 없는 생존 확인이다. 쿼리 파라미터는 없다.

```json
{ "status": "ok" }
```


기간은 필수 `startDate`, `endDate` (`YYYY-MM-DD`, 종료일 포함, 1~366일)와 선택 `timeZone`이다.
시간대는 `Asia/Seoul`만 지원하며 생략 시에도 같은 값이다. `compare`는 `prev_week`(기본),
`prev_period`, `none`이고 구성원 화면에는 비교가 없다. `q`는 최대 200자다.
다음 페이지에는 동일 조건과 응답의 `nextCursor`·`snapshotId`를 사용한다.
409 `snapshot_expired`이면 첫 페이지부터 다시 조회한다. 개요는 snapshotId를 노출하지 않는다.

모든 페이지가 공유하는 응답 조각은 다음과 같다.


## 오류와 데이터 일관성

```json
{"error":{"code":"invalid_request","message":"요청 형식이 올바르지 않습니다.","fieldErrors":[]},"requestId":"요청 ID"}
```

| 상태 | 코드·처리 |
| --- | --- |
| 400 | invalid_request — 날짜·검색·커서·limit 확인 |
| 401 | unauthenticated — 로그인/세션 갱신 |
| 403 | forbidden — 관리자 권한 필요·타 조직 경로 |
| 404 | not_found — 없는 조직·없는(다른 조직의) 자원·계약 밖 경로 |
| 405 | method_not_allowed |
| 409 | snapshot_expired — 동일 조건 첫 페이지부터 재조회 |
| 500 | internal_error |
| 503 | unavailable — Retry-After 후 재시도 |

응답의 X-Request-Id를 로그 상관 키로 사용한다. 인증 저장소 장애도 503이며 토큰 오류로 처리하지 않는다.
개요는 요청마다 snapshot 하나에서 섹션을 집계한다. snapshot ID를 개요 응답에 노출하지 않는다.
목록의 snapshot/cursor는 조직·조건에 묶이며 유효기간은 10분이다.
조직·날짜·모델·팀의 합계는 같은 데이터 기준을 사용한다. 누락 비용을 제외한 부분합을 총액으로 표시하지 않는다.


## 이 문서를 읽는 법

API 문서는 백엔드 직접 호출 계약이다. 브라우저는 프론트 BFF를 통해 호출하며 BFF 경로·쿠키 계약은 프론트 인증 문서가 담당한다.
서버별 담당을 확인하고 해당 서버 base URL에 전체 경로를 붙인다. 로컬 기본값은 Enrollment 8080, Dashboard 8081이다.
검증 서버는 별도 빈 포트로 실행하며 기존 개발 서버를 중지하지 않는다.

### 요청·응답 표기

- Path·Query·Body 블록의 `?`는 생략 가능, `| null`은 명시적 null 허용이다. Query에는 JSON null을 보내지 않는다.
- JSON 요청은 `Content-Type: application/json`이다. Body 없음인 관리 POST는 빈 JSON 객체 `{}`도 사용할 수 있다.
- **멱등 키는 ManagementController POST에만 필수**다. `connection/verify`, `alerts/{alertId}/acknowledge`, 인증·CLI·공개 문의는 이 저장형 멱등 키 규칙을 적용하지 않는다.
- Dashboard 목록의 cursor/snapshotId는 불투명 값이다. 초대 목록의 UUID cursor와 카탈로그 cursor를 서로 재사용하지 않는다.
- Dashboard QueryReader는 엔드포인트가 읽지 않는 쿼리 키를 무시한다. 지원하지 않는 파라미터를 보냈다고 기능이 적용되는 것은 아니다.
- `202`의 Location은 작업 조회 경로이며 Dashboard 서버에 요청한다. 실패한 작업도 HTTP 200 + status=failed로 조회된다.
- 카드의 Response 타입은 바로 아래 링크에서 전체 필드·중첩 구조를 볼 수 있다. 문서용 타입이며 SDK 인터페이스를 새로 정의하는 것이 아니다.

<a id="schema-HealthResponse"></a>
<a id="schema-PublicError"></a>
<a id="schema-ManagementError"></a>

```ts
type HealthResponse = { status: "ok" };
type PublicError = { error: string; message: string };
type ManagementError = {
  error: { code: string; message: string; fieldErrors: Array<{ field: string; code: string }> };
  requestId: string;
  details?: unknown; // CSV 행별 오류 등 기능별 구조, 없으면 키 생략
};
```

공개 경로·사용자 인증의 두 필드 오류와 관리/Dashboard의 중첩 오류를 혼용하지 않는다.
Dashboard 오류의 정확한 키·필드 오류 어휘는 [ErrorResponse](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/error/ErrorResponse.kt)를 따른다.


<a id="schema-EnrollmentHealthResponse"></a>

```ts
type EnrollmentHealthResponse = { status: "ok" | "degraded"; checks: { database: "ok" | "down" } };
```
