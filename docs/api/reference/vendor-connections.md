# 벤더 연결·동기화 — 기능 규칙·공유 스키마

[API 목록](../vendor-connections.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

### 벤더 연결 (ADR 0048)

등록 제품 하나에 벤더 커넥터 하나를 잇는다. 연결이 있는 제품은 **좌석 원장의 권위가 커넥터 동기화**이고, 수동 입력은 그 전의 임시 기록이다(ADR 0048 [기존 §3](../../enrollment-server-spec.md#3-초대-코드)의 우선순위 표).
`pulsemetry.vendor-connections.enabled=true`(관리 기능과 함께)일 때만 경로가 있다. 꺼져 있으면 404다.

<a id="schema-ConnectionWrite"></a>
<a id="schema-SeatSource"></a>
<a id="schema-Capability"></a>

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
    connectorId: string; accountKind: "email";
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

`supported`는 `docs/vendor-connector-evidence.md`의 결론을 옮긴 것이다. 구현은 좌석 목록(과 연결 확인), 해제(둘), 청구 누계(둘, ADR 0050)다 —
복원을 구현한 커넥터는 없다. Claude Enterprise 재초대는 역할을 정해야 해 관리자 조치로 남긴다(ADR 0049 [기존 §2](../../enrollment-server-spec.md#2-엔드포인트)). 그 밖의 제품·플랜(Claude Team, OpenAI, Cursor Teams, `other`)은 커넥터가 없고 수동 원천이다.
GitHub Copilot(`copilot`)·Gemini Code Assist(`gemini`)도 커넥터가 없다(ADR 0054) — 카탈로그 플랜으로 등록·계약하고 좌석은 관리자가 기록한다. 계정 종류는 모두 이메일이다.

- 커넥터는 등록 제품의 **현재 계약 플랜**으로 고른다. 계약이 없거나, 그 플랜에 커넥터가 없거나, 이 배포에 그 커넥터의 구현이 없으면 422 `connector_unavailable`이다.
- `settings`는 커넥터의 `settingKeys`와 정확히 같은 키의 문자열(1~200자, 제어 문자 없음)이어야 하고, `credential`은 1~8192자의 비어 있지 않은 문자열이어야 한다. 아니면 400 `invalid_request`(field `settings`·`credential`·`expectedVersion`).
- 활성 연결은 제품마다 하나다. 새 연결은 `expectedVersion: 0`, 교체는 현재 판이다. 어긋나면 409 `version_conflict`.
- **교체**는 자격증명을 새로 암호화하고 확인 상태를 `unverified`로 되돌린다. 커넥터나 `settings`가 바뀌면 동기화 기록(`sync`)도 비운다 — 다른 대상의 기록이다.
- **삭제**는 암호문을 즉시 지우고 연결 행은 이력으로 남긴다. 좌석 원장은 그대로이고 권위가 수동(커넥터 플랜이면 임시)으로 돌아간다. 등록 제품을 보관(`DELETE /vendors/{vendorId}`)하면 그 제품의 연결도 같은 트랜잭션에서 지운다.
- **확인**(`verify`)은 커넥터의 읽기 호출 하나다. 결과를 `check`에 남기고 판은 올리지 않는다. `invalid_credentials`·`insufficient_permission`은 벤더가 거절한 것이고, 한도 초과·일시 장애·응답 해석 불가는 `unavailable`이다.
  호출은 트랜잭션 밖에서 하며, 그 사이 연결이 바뀌었으면 결과를 쓰지 않고 409 `version_conflict`다. 상태만 남기는 확인이라 `Idempotency-Key`를 받지 않는다.
- 자격증명은 AES-256-GCM 암호문(`pulsemetry.vendor-connections.credential-keys`, [기존 §8](../../enrollment-server-spec.md#8-설정))으로만 저장한다. 요청은 PUT이라 멱등 응답 기록(요청 해시·응답)을 남기지 않는다.
  행의 키 ID가 설정에 없으면(옛 키를 너무 일찍 뺀 경우) 확인은 503 `credential_key_unavailable`이다 — 평문으로 떨어지지 않는다.
- 설정 조회(dashboard-api)의 벤더마다 같은 `seatSource`가 있다. 등록·정정 응답(`VendorResponse`)의 vendor에도 같은 필드가 있다.

**좌석 동기화**(ADR 0048 [기존 §3](../../enrollment-server-spec.md#3-초대-코드)·[기존 §7](../../enrollment-server-spec.md#7-에러-계약)). enrollment-api 안의 주기 작업이 `sync.check-interval`마다 차례인 연결을 찾아 하나씩 동기화한다.
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


<a id="schema-ConnectionResponse"></a>

```ts
type ConnectionResponse = { seatSource: SeatSource };
```

연결 저장 요청 예시(설정 키가 없는 현재 커넥터):

```json
{"expectedVersion":0,"settings":{},"credential":"<vendor-admin-credential>"}
```

기능 스위치가 꺼지면 연결 경로는 404다. 커넥터 검증 결과가 invalid_credentials여도 HTTP 200일 수 있으므로 seatSource.connection.check.status를 확인한다.
