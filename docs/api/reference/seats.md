# 좌석 원장·회수·복원 — 기능 규칙·공유 스키마

[API 목록](../seats.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

### 좌석 수동 기록 (ADR 0048 [기존 §3](../../enrollment-server-spec.md#3-초대-코드)의 1·2행)

커넥터가 없는 플랜(Claude Team, OpenAI, Cursor Teams, `other`)에서는 관리자의 기록이 좌석 원장의 권위다. 커넥터가 있는 플랜이라도 **활성 연결이 없으면** 기록할 수 있고
그 기록은 **임시**다(`provisional: true`) — 연결을 만들면 첫 성공한 동기화가 목록 전체로 대체한다. 활성 연결이 있는 제품에서는 배정·해제·가져오기가
409 `connector_managed`이고 보정(구성원 연결·계약 등급·메모)만 된다. 벤더 연결 기능이 꺼진 배포에서도 된다. 구매 수량(`tiers[].seats`)으로 좌석을 만들지 않는다.

<a id="schema-SeatSaved"></a>
<a id="schema-Seat"></a>

```ts
type SeatSaved = { seat: Seat; warnings: "exceeds_contracted_seats"[]; provisional: boolean };
type Seat = {
  seatAssignmentId: string; vendorId: string;
  account: string; accountKind: "email";   // 계정 키 — 소문자로 정규화한 이메일
  state: "assigned" | "pending_assignment" | "pending_release" | "released";
  source: "connector" | "manual" | "csv" | "vendor_control" | "admin_action"; // 마지막으로 상태를 정한 원천
  memberId: string | null; memberLink: "email_match" | "admin" | null;  // admin + null 은 관리자가 "잇지 않음"으로 정한 것
  tierId: string | null; vendorTier: string | null;
  assignedAt: string; releaseEffectiveOn: string | null; releasedAt: string | null;
  vendorLastActivityAt: string | null;  // 벤더가 준 마지막 활동. null 은 모름이지 미사용이 아니다
  note: string | null; version: number; updatedAt: string;
};
```

- **배정**(`POST …/seats`): 계정은 이메일로 정규화한다(trim·소문자 — 계정 종류는 이메일 하나다, ADR 0054). 새 계정은 `expectedVersion`을 보내지 않는다(201).
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
| `account` | 예 | 벤더 계정 — 이메일 |
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

<a id="schema-SeatImport"></a>

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

<a id="schema-ReclaimPreview"></a>

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

- **실행 방식**: 활성 연결이 있고 계약 플랜의 커넥터가 그 연결의 커넥터이며 그 기능(해제·복원)을 구현했으면 `vendor_control`, 아니면 `admin_action`(ADR 0049 [기존 §2](../../enrollment-server-spec.md#2-엔드포인트)의 표).
- **거절 사유**: `not_found`(없는 좌석·다른 조직·보관한 제품·UUID 아님), `version_conflict`, `not_assigned`(해제는 배정 좌석만), `control_in_progress`(끝나지 않은 회수·복원이 있음),
  `plan_mismatch`, `vendor_account_unknown`(Claude Enterprise 해제에 구성원 ID가 필요한데 연결 전 기록), `connector_unavailable`(이 배포에 커넥터 호출이 없음).
  복원은 여기에 `seat_reassigned`(이미 다시 보유)·`not_restorable`(해제 예정 좌석을 벤더 제어 없이 되살림)을 더한다.
- **실행**은 미리보기를 요청한 관리자만 한다(다른 관리자에게는 404 `not_found`). 대상마다 판·방식·연결·진행 중인 작업을 다시 검사해 하나라도 다르면 409 `preview_stale`로 전부 거절한다.
  기한이 지나면 409 `preview_expired`, 이미 쓴 미리보기는 409 `preview_used`(같은 `Idempotency-Key`의 재시도는 같은 202), 대상이 없으면 422 `no_eligible_seats`.
  회수 작업의 복원 기한(`restoreUntil`)은 만든 시각 + 30일이다.
- **벤더 제어 대상**은 `pending`으로 남고 주기 작업(좌석 동기화와 같은 작업·`sync.check-interval`, 한 바퀴에서 제어가 먼저)이 선점(`sync.lease`)해 커넥터를 부른다.
  받아들이면 원장(원천 `vendor_control`): 해제 끝남 → `released`, 주기 말 효력의 해제 예정 → `pending_release`(예정일은 다음 동기화가 채운다), 복원 → `assigned`.
  지금 구현한 두 커넥터의 해제는 그 자리에서 끝난다(`released`). 해제 예정과 커넥터 복원은 그런 벤더 제어를 구현한 커넥터가 생기면 쓰는 규칙이다(ADR 0049·0054).
  실패는 대상의 사유다 — 커넥터 실패 종류(위 "좌석 동기화"의 표와 같은 코드), `connection_removed`, `plan_mismatch`, `connector_unavailable`, `credential_key_unavailable`,
  `seat_changed`(호출 전에 좌석이 바뀌어 부르지 않음), `control_error`. 일시 장애도 대상 실패다(다시 하려면 새로 회수한다).
- **관리자 조치 대상**은 `awaiting_admin_action`(조치 코드 `release_in_vendor_console`·`restore_in_vendor_console`)이다. 관리자가 벤더 콘솔에서 조치하고 **확인**하면 원장이 옮겨지고
  (원천 `admin_action` — 해제 `released`, 복원 `assigned`) 대상이 성공한다(확인자 = 요청한 관리자). 확인 사이에 좌석이 이미 그 상태면 원장은 그대로, 표에 없는 전이면 409 `seat_changed`.
  조치 대기에는 기한이 없다. 하지 않기로 하면 **취소**한다(원장 불변). 조치 대기가 아닌 대상의 확인·취소는 409 `not_awaiting_admin_action`.
- **복원**은 되돌릴 수 있는 회수(성공한 대상이 있는 끝난 회수, 기한 안, 살아 있는 복원 없음)에만 된다. 아니면 422 `restore_not_available`. 대상은 회수에서 성공한 좌석이고
  대상마다 방식을 다시 정한다. 되돌릴 수 없는 대상은 그 사유로 실패로 남고, 모든 대상이 불가면 작업을 만들지 않고 422(`details`에 대상별 사유)다.
- 원장 이력의 행위자는 작업이다(`seat_assignment_events.operation_id`). 대상 ID는 `seatAssignmentId`다.


### 좌석 원장 조회 (ADR 0048)

구성원 화면의 좌석 요약·구성원별 `seatState`·회수 후보, 회수 후보 목록, 구성원 좌석, 개요의 좌석과 효율, 설정의 좌석 수는 **하나의 좌석 원장**(enrollment `seat_assignments`와 판별 이력)에서 계산한다.
구매 수량(`tiers[].seats`)이나 사용자 수로 좌석을 만들지 않는다.

- **기준 시각으로 다시 세운다 — snapshot 에 복제하지 않는다.** 원장은 판마다 이력(`seat_assignment_events`, 판의 상태·원천·구성원 연결·등급·배정 시각)을 남기므로
  기준 시각 이전의 마지막 판이 그 시각의 좌석이다. 구성원 화면(`/members/dashboard`·`/members`·`/members/unassigned`)의 기준 시각은 snapshot 의 asOf,
  회수 후보·구성원 좌석은 현재 상태 토큰의 asOf, 개요는 snapshot 의 asOf(기간 값은 그 기간 끝), 설정은 설정 토큰의 asOf 다. 구성원 화면에 실은 회수 후보 첫 페이지는 snapshot asOf 의 토큰으로 내므로 다음 페이지를 `/seat-reclaim-candidates`로 이어 읽는다.
  그래서 캐시 DDL·`QUERY_CONTRACT`는 바뀌지 않았다. 판이 없는 값 — 벤더 활동 시각, 연결의 동기화 상태 — 은 현재 값이다.
- **제품 단위 가용성** — 원장이 그 제품에 대해 비었거나 낡았으면 그 제품만 낮춘다.

| 등록 제품 | 가용성 | 사유 |
| --- | --- | --- |
| 활성 연결, 성공한 동기화 없음 | unavailable | `seat_sync_pending`(시도 전) · `seat_sync_failing`(실패만) |
| 활성 연결, 마지막 시도가 실패 | partial | `seat_sync_failing` |
| 활성 연결, 마지막 성공이 `seats.stale-after`보다 오래됨 | partial | `seat_sync_outdated` |
| 연결 없음, 기록된 좌석 없음 | unavailable | `seat_source_not_recorded` |
| 연결 없음, 커넥터가 있는 플랜(연결 전 임시 기록) | partial | `seat_source_provisional` |
| 그 밖 | available | — |

  여러 제품을 합친 섹션은 모두 unavailable 이면 unavailable(첫 사유), 하나라도 낮으면 partial(첫 사유), 등록 제품이 없으면 unavailable `not_applicable`이다.
- **`seatState`**: 쓸 수 있는 원장(unavailable 이 아닌 제품)에 보유 좌석(배정·해제 예정·배정 대기)이 있으면 `assigned`, 없고 벤더 제어·관리자 조치로 해제된 좌석이 있으면 `reclaimed`,
  모든 등록 제품의 원장을 쓸 수 있으면 `unassigned`, 아니면 `unknown`(좌석이 없다고 말할 근거가 없다). 현재 팀·역할과 별개다.
- **좌석 요약**(`summary.seats`): 쓸 수 있는 원장만 센다. `contracted`·`unallocated`는 유효한 계약(contractStatus=active)이 있는 제품 범위에서만 —
  `unallocated`는 제품마다 max(계약 좌석 − 보유 좌석, 0)의 합이다(계약 좌석에서 사람 수를 빼지 않는다). `assigned`는 보유 좌석 수.
  `activeInPeriod`는 선택 기간에 그 제품(관측 제품 매핑)을 쓴 보유 좌석 수이고, 모든 보유 좌석이 구성원에 이어지고 관측 가능한 제품일 때만 낸다(아니면 null).
  `inactiveAssigned`는 그중 쓰지 않은 좌석 수이고, 기간 전체가 완전하고 그 좌석들의 구성원이 기간 내내 설치를 갖고 있을 때만 낸다. `reclaimCandidates`는 회수 후보 수,
  `estimatedMonthlySavingsUsd`는 계약의 해지·감액 조건 원천이 없어 null 이다.
- **회수 후보**: 배정(`assigned`) 좌석 중 다음을 **모두** 만족할 때만이다. 사용 이벤트가 없다는 것만으로 후보로 만들지 않는다.
  1. 구성원에 이어져 있고 그 구성원이 로스터(활성·정지)에 있다.
  2. 그 제품의 원장이 unavailable 이 아니고, 관측 제품 매핑이 있다(ADR 0044 — 지금은 Claude·OpenAI 제품뿐이다).
  3. 유휴 일수 ≥ 조직의 회수 기준. 유휴 일수 = 시작부터 기준 시각까지의 완전한 24시간 수, 시작 = 마지막 사용(그 제품의 텔레메트리 사용과 벤더 활동 시각 중 늦은 것)과
     배정 시각 중 늦은 것(사용이 없으면 배정 시각).
  4. 관측이 충분하다 — 확정된 마지막 날까지의 [회수 기준]일이 모두 완전하고(ADR 0042), 기준 시각 앞 [회수 기준]일 동안 등록돼 폐기되지 않은 그 구성원의 설치가 있다.

  유휴 일수 내림차순 + 좌석 ID 오름차순이다. 판정하지 못한 배정 좌석(미연결·관측 매핑 없음·원장 없음·관측 부족)이 있으면 목록에서 빼고 `partial` `observation_incomplete`다.
  `canReclaim`·`reason`은 아래 "회수 가능 여부"이고 실행 방식 `reclaimMethod`(가산)를 더했다. `tierId`는 모르면 null(요청서는 문자열), 절감액은 null,
  `vendorAccount`(가산)는 좌석의 벤더 계정이다(`account`는 구성원의 계정).
- **구성원 좌석** `GET O/members/{memberId}/seats`: 로스터에 없는 구성원은 404(다른 조직·없는 ID·UUID 아님 포함). 보관한 등록 제품의 좌석은 싣지 않는다.
- **제품 좌석** `GET O/vendors/{vendorId}/seats`(설정 권한): 등록 제품 하나의 좌석 전부 — 구성원에 잇지 않은 좌석·해제된 좌석까지, 계정 오름차순 + 좌석 ID. 현재 상태 토큰으로 다음 페이지를 잇는다.
  그 조직에 없는 제품(보관 포함)은 404. 좌석 입력·회수 화면이 쓴다. `memberAccount`는 이은 구성원의 계정(로스터에 없으면 null), `ledgerAvailability`·`ledgerReason`은 그 제품의 원장 가용성이다.
- **최근 회수·복원 작업**(`lastControl`, 구성원 좌석·제품 좌석의 가산 필드): 좌석마다 가장 최근의 `seat_reclaim`·`seat_restore` 작업 ID다(현재 값). 화면이 새로고침 뒤에도 그 작업의 상태(작업 조회)와
  관리자 조치 확인·복원을 다시 찾는다.
- **회수 가능 여부**(ADR 0049 [기존 §2](../../dashboard-server-spec.md#2-페이지별-조직-조회-api)·[기존 §6](../../dashboard-server-spec.md#6-검증과-구현-한계)): 좌석마다 `canReclaim`과 불가 사유, 가능하면 실행 방식(`reclaimMethod` — `vendor_control`·`admin_action`)이다. 규칙은 enrollment 의 회수 명령과
  같은 함수다 — 배정 좌석만(`not_assigned`), 활성 연결이 있고 계약 플랜의 커넥터가 그 연결의 것이며 해제를 구현했으면 벤더 제어, 연결이 없거나 커넥터가 없는 플랜은 관리자 조치,
  연결의 커넥터가 플랜과 다르면 `plan_mismatch`, 구성원 ID 가 필요한데 없으면 `vendor_account_unknown`, 끝나지 않은 회수·복원이 있으면 `control_in_progress`,
  관리 기능이 꺼진 배포는 `management_disabled`다. 이 배포에 커넥터가 조립됐는지는 모른다(명령이 확인한다). 연결·진행 중인 작업은 현재 값이다.
  구성원 화면의 `capabilities.reclaimSeats`·`restoreSeats`는 관리 기능이 켜졌으면 true다 — 벤더 제어가 없는 좌석도 관리자 조치로 끝난다. 회수 후보인가와 회수할 수 있는가는 별개다.
- **개요 좌석과 효율**(`/analytics/overview`의 `seats`): 같은 벤더 범위의 환산가치와 좌석료를 비교한다. 범위(`scopeVendorIds`)는 유효한 계약이 있고,
  원장이 unavailable 이 아니고, 관측 제품 매핑이 있는 등록 제품이다 — 셋 중 하나라도 없으면 그 제품을 빼고 `partial`(뺀 첫 제품의 원장 사유, 매핑 없음은 `product_unobservable`).
  유효한 계약이 없으면 unavailable `not_applicable`, 범위가 비면 unavailable(첫 사유)이다. 기간마다(`current`·`previous`) **그 기간 끝**(기준 시각 이전)의 계약·원장으로 따로 센다.
  `contractedSeats`는 계약 좌석, `monthlyFeeUsd`는 월 요금 합(하나라도 미입력이면 그 기간 null), `allocatedFeeUsd` = 월 요금 × 기간 일수 / 30(`allocationBasis = estimated_30_day` — 배분 **추정액**이다),
  `equivalentCostUsd`는 범위 제품의 환산가치 합(완전한 기간의 사용 없음은 0, 단가 없는 사용이 있으면 null), `efficiency` = 환산가치 / 배분액.
  `activeSeats`는 그 기간에 그 제품을 쓴 보유 좌석 수이고 구성원에 이어지지 않은 보유 좌석이 있으면 null 이다. `reclaimEstimate`는 회수로 줄어드는 금액의 원천(계약의 감액 시점)이 없어 null,
  `reclaimCandidates`(가산)는 범위 제품의 회수 후보 수(판정할 수 없으면 null)다.
- **설정 좌석**(`/settings`·`/vendors`·`/vendors/{vendorId}`): vendor 마다 `seats: Section<{assigned, contracted, unallocated}>`(가산)를 낸다 — 가용성·사유는 위 표,
  `assigned`는 보유 좌석, `contracted`·`unallocated`는 계약이 유효할 때만(아니면 null). `summary.assignedSeats`(가산)는 쓸 수 있는 원장의 보유 좌석 합이고
  모든 제품이 unavailable 이면 null 이다. `summary.activeSeats7d`는 기준일(서울) 전날까지 7일 동안 그 제품을 쓴 보유 좌석 수다 — 모든 보유 좌석이 구성원에 이어지고
  관측 가능한 제품일 때만 내고, 창이 모두 완전하면 0 포함 정확한 수, 아니면 센 수가 있을 때만 그 수다([기존 §7.3](../../dashboard-server-spec.md#73-관측-지표-adr-0044) 관측 인원과 같은 규칙). 관측 사용자 수를 좌석으로 쓰지 않는다.


### 좌석 원천 (ADR 0048)

설정·벤더 목록·상세의 vendor마다 `seatSource`를 더했다(가산). 모양은 Enrollment 명세 §12 "벤더 연결"의 `SeatSource`와 같고, 등록·정정 응답의 vendor도 같은 필드를 낸다.

- `authority`·`provisional`: ADR 0048 [기존 §3](../../dashboard-server-spec.md#3-벤더와-플랜-카탈로그)의 우선순위 표다 — 활성 연결이 있으면 `connector`, 현재 계약 플랜에 커넥터가 있는데 연결이 없으면 `manual`·`provisional=true`(연결 전의 임시 기록),
  커넥터가 없는 플랜(계약 없음 포함)은 `manual`·`provisional=false`.
- `connector`: 현재 계약 플랜의 커넥터 설명(계정 종류·필요한 설정 키·구현한 기능 `capabilities`·벤더 문서가 근거를 준 기능 `supported`). 호출 없이 읽는 값이다.
  실행 가능 여부는 `capabilities`로 판단한다. 이 배포에 그 구현이 조립됐는지는 말하지 않는다 — 연결 저장이 422 `connector_unavailable`로 알린다.
- `connection`: 활성 연결의 비밀 아닌 기록. 자격증명은 `{configured, updatedAt}`뿐이고, 이 앱은 `vendor_connections`의 암호문·키 열을 고르지 않는다(`VendorConnections`).
  `sync.status`는 성공한 동기화가 없으면 `pending`, 마지막 시도가 실패면 `failing`(마지막 성공 값은 남는다), 아니면 `succeeded`다.
- 연결 상태는 조회 기준 시각의 고정값이 아니라 **현재 값**이다 — 관측 지표([기존 §7.3](../../dashboard-server-spec.md#73-관측-지표-adr-0044))처럼 snapshot에 고정하지 않는다. 좌석 원장의 값과 그 신선도는 vendor 의 `seats`(가산)가 따로 낸다("좌석 원장 조회").


## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### SeatSummary

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-SeatSummary"></a>

```ts
type SeatSummary = {
  contracted: number;
  assigned: number;
  unallocated: number;
  activeInPeriod: number | null;
  inactiveAssigned: number | null;
  reclaimCandidates: number | null;
  estimatedMonthlySavingsUsd: string | null;
};
```

### ReclaimCandidate

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-ReclaimCandidate"></a>

```ts
type ReclaimCandidate = {
  seatAssignmentId: string;
  memberId: string;
  account: string;
  team: TeamRef;
  vendorId: string;
  tierId: string | null;
  version: number;
  lastUsedAt: string | null;
  idleDays: number;
  estimatedMonthlySavingsUsd: string | null;
  canReclaim: boolean;
  reason: string | null;
  vendorAccount: string;
  reclaimMethod: string | null;
};
```

### MemberSeat

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-MemberSeat"></a>

```ts
type MemberSeat = {
  seatAssignmentId: string;
  version: number;
  vendorId: string;
  vendorName: string;
  kind: string;
  contractVersion: number | null;
  tierId: string | null;
  tierLabel: string | null;
  vendorTier: string | null;
  account: string;
  accountKind: string;
  state: string;
  source: string;
  memberLink: string | null;
  assignedAt: string;
  releaseEffectiveOn: string | null;
  releasedAt: string | null;
  ledgerAvailability: string;
  ledgerReason: string | null;
  lastUsedAt: string | null;
  idleDays: number | null;
  reviewReason: string | null;
  reclaimCandidate: boolean;
  canReclaim: boolean;
  reclaimReason: string | null;
  reclaimMethod: string | null;
  lastControl: SeatControlRef | null;
};
```

### SeatControlRef

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-SeatControlRef"></a>

```ts
type SeatControlRef = {
  operationId: string;
  kind: string;
};
```

### VendorSeatsResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-VendorSeatsResponse"></a>

```ts
type VendorSeatsResponse = {
  meta: CurrentMeta;
  vendorId: string;
  ledgerAvailability: string;
  ledgerReason: string | null;
  seats: Page<VendorSeatItem>;
};
```

### VendorSeatItem

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-VendorSeatItem"></a>

```ts
type VendorSeatItem = {
  seatAssignmentId: string;
  version: number;
  account: string;
  accountKind: string;
  state: string;
  source: string;
  memberId: string | null;
  memberAccount: string | null;
  memberLink: string | null;
  tierId: string | null;
  tierLabel: string | null;
  vendorTier: string | null;
  assignedAt: string;
  releaseEffectiveOn: string | null;
  releasedAt: string | null;
  note: string | null;
  canReclaim: boolean;
  reclaimReason: string | null;
  reclaimMethod: string | null;
  lastControl: SeatControlRef | null;
};
```

### MemberSeatsResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-MemberSeatsResponse"></a>

```ts
type MemberSeatsResponse = {
  meta: CurrentMeta;
  memberId: string;
  policy: IdlePolicy;
  seats: Array<MemberSeat>;
};
```

### IdlePolicy

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-IdlePolicy"></a>

```ts
type IdlePolicy = {
  idleDays: number;
  version: number;
};
```

### ReclaimCandidatesResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-ReclaimCandidatesResponse"></a>

```ts
type ReclaimCandidatesResponse = {
  meta: CurrentMeta;
  idleDays: number;
  candidates: Section<Page<ReclaimCandidate>>;
  policy: IdlePolicy;
};
```


<a id="schema-SeatImportResponse"></a>

```ts
type SeatImportResponse = { import: SeatImport; provisional: boolean };
```

수동 좌석 배정 요청 예시:

```json
{"account":"developer@example.test","memberId":null,"note":"수동 확인"}
```

CSV 미리보기 요청 예시:

```json
{"mode":"preview","csv":"account,status\ndeveloper@example.test,assigned\n"}
```

관리자 조치 확인·취소 HTTP 명세는 [비동기 작업](../operations.md)에 있다. 좌석 회수 결과는 해당 문서의 OperationResponse로 읽는다.


## 연관 스키마

- [CurrentMeta](../common-schemas.md#schema-CurrentMeta)
- [Page](../common-schemas.md#schema-Page)
- [Section](../common-schemas.md#schema-Section)
- [TeamRef](../common-schemas.md#schema-TeamRef)
