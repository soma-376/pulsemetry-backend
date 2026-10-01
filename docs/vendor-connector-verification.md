# 벤더 커넥터 실계정 검증 절차

커넥터(ADR 0048)는 벤더 문서의 요청·응답을 재현한 모의 서버로 검증했다(`VendorConnectorsTest`, `SeatSyncApiTest`).
**실계정 검증은 하지 않았다.** 이 문서는 자격증명이 생긴 뒤 돌릴 절차다. 근거 문서는 [`vendor-connector-evidence.md`](vendor-connector-evidence.md)다.

2절(커넥터 단독)은 **읽기만** 한다 — 연결 확인과 좌석 목록. 회수·복원(ADR 0049)은 좌석 상태를 바꾸므로 4절의 **테스트 조직 전용** 절차로만 검증한다.

## 1. 준비물 — 벤더별 자격증명과 권한

| 커넥터 ID | 제품·플랜 | 자격증명 | 필요한 권한 | 비밀 아닌 설정 |
| --- | --- | --- | --- | --- |
| `claude_enterprise` | Claude Enterprise | claude.ai 조직 설정의 Admin API 키(primary owner가 만든다) | `read:members` | 없음 |
| `cursor_enterprise` | Cursor Enterprise | 대시보드 API Keys의 Admin API 키(`crsr_…`) | 팀 관리자 | 없음 |
| `copilot` | Copilot Business·Enterprise | 조직 소유자의 토큰(personal access token classic 또는 OAuth 앱 토큰) | `manage_billing:copilot` 또는 `read:org` | `organization`(GitHub 조직 이름) |
| `gemini` | Gemini Code Assist Standard·Enterprise | 서비스 계정 키(JSON 파일 전체) | `roles/billing.admin` 또는 `roles/consumerprocurement.orderAdmin`(`consumerprocurement.licensePools.enumerateLicensedUsers`) | `billingAccount`·`order`·`project`(`X-Goog-User-Project`) |

- 가능하면 **테스트 조직**의 자격증명을 쓴다. 읽기 전용 범위(`read:members`·`read:org`)로 만든 키면 이 절차로 상태가 바뀔 수 없다.
- 자격증명을 파일·셸 이력·로그·이슈에 남기지 않는다. 아래 명령은 환경 변수로만 받는다. 끝나면 키를 폐기하거나 비밀 저장소로 옮긴다.

## 2. 읽기 전용 검증 — 커넥터 단독

빌드·테스트에 들어가지 않는 별도 태스크다. 입력이 없으면 건너뛰지 않고 종료 코드 2로 실패한다.

```bash
# Copilot
PULSEMETRY_VERIFY_CREDENTIAL='<토큰>' PULSEMETRY_VERIFY_SETTING_ORGANIZATION='<조직>' \
  ./gradlew :libs:vendor-connector:verifyVendorAccount -Pvendor=copilot

# Claude Enterprise · Cursor Enterprise (설정 없음)
PULSEMETRY_VERIFY_CREDENTIAL='<Admin API 키>' ./gradlew :libs:vendor-connector:verifyVendorAccount -Pvendor=claude_enterprise
PULSEMETRY_VERIFY_CREDENTIAL='<Admin API 키>' ./gradlew :libs:vendor-connector:verifyVendorAccount -Pvendor=cursor_enterprise

# Gemini — 키는 파일 경로로 준다
PULSEMETRY_VERIFY_CREDENTIAL_FILE=/secure/path/key.json PULSEMETRY_VERIFY_SETTING_BILLINGACCOUNT='<청구 계정>' \
  PULSEMETRY_VERIFY_SETTING_ORDER='<주문>' PULSEMETRY_VERIFY_SETTING_PROJECT='<프로젝트>' \
  ./gradlew :libs:vendor-connector:verifyVendorAccount -Pvendor=gemini
```

출력은 좌석 수와 필드가 채워진 수뿐이다(자격증명·이메일·로그인을 찍지 않는다). 종료 코드 0 통과, 1 벤더 거절·실패(실패 코드가 찍힌다), 2 입력 오류.

### 기대 결과

| 커넥터 | 대조할 값 | 확인할 것 |
| --- | --- | --- |
| `claude_enterprise` | claude.ai 조직 설정의 구성원 수, `GET /v1/organizations/analytics/summaries`(`read:analytics` 키)의 `assigned_seat_count`·`pending_invite_count` | `assigned` = 구성원 수, `pending_assignment` = 대기 중 초대 수. 구성원 수와 배정 좌석 수가 다르면(좌석 없는 구성원) 그 사실을 evidence 문서에 적는다 |
| `cursor_enterprise` | Cursor 대시보드의 구성원 수(제거된 구성원 제외) | `assigned` = 제거되지 않은 구성원 수, 등급·활동은 0 |
| `copilot` | 조직 Copilot 설정의 좌석 수, `GET /orgs/{org}/copilot/billing`의 `seat_breakdown.total`·`pending_cancellation` | 좌석 수 = `total` − 담당자 없는 좌석, `pending_release` = 취소 예정 수, 이메일 0 |
| `gemini` | Cloud 콘솔 라이선스 풀의 배정 사용자 수 | `assigned` = 배정 수, 이메일 = 배정 수 |

값이 다르면 커넥터를 고치기 전에 evidence 문서의 해당 절을 다시 확인하고 확인일을 갱신한다.

## 3. 읽기 전용 검증 — 서비스 경로(연결 → 동기화 → 원장)

스테이징 배포(실제 벤더 주소, `base-urls`를 비움)에서:

1. enrollment-api에 `pulsemetry.vendor-connections.*`(키·동기화·호출 수치)를 주고 켠다(Enrollment 명세 §8).
2. 조직에 그 제품·플랜의 계약을 등록하고 `PUT O/vendors/{vendorId}/connection`으로 연결한다(`expectedVersion: 0`).
3. `POST O/vendors/{vendorId}/connection/verify` → `seatSource.connection.check.status = verified`.
4. `POST O/vendors/{vendorId}/connection/sync` → 202와 작업 ID. 확인 주기가 지난 뒤 `GET O/operations/{operationId}`(dashboard-api)가 `succeeded`.
5. 설정 조회의 그 벤더 `seatSource.connection.sync.status = succeeded`, DB `enrollment.seat_assignments`의 좌석 수가 2절의 출력과 같다.
6. 연결을 지우고(`DELETE …/connection`, `If-Match`) 암호문 열이 비었는지 본다.

## 4. 상태를 바꾸는 검증 — 테스트 조직 전용

**운영 조직에서 돌리지 않는다.** 회수는 실제 좌석을 해지한다(Claude Enterprise는 구성원을 조직에서 제거한다). 벤더의 테스트 조직과, 그 조직에서 잃어도 되는 테스트 계정
하나(관리자 역할이 아닌 계정)로만 돌린다. 쓰기 권한이 필요하다 — Claude `write:members`, Cursor 팀 관리자, Copilot `manage_billing:copilot`, Gemini `consumerprocurement.licensePools.unassign`·`assign`.

| 커넥터 | 회수 | 복원 |
| --- | --- | --- |
| `claude_enterprise` | 벤더 제어(구성원 제거 — 좌석이 풀로 돌아간다) | 관리자 조치 — 콘솔에서 재초대 후 확인(역할을 정해야 해 자동으로 하지 않는다) |
| `cursor_enterprise` | 벤더 제어(`remove-member`) | 관리자 조치 — 대시보드에서 재초대 후 확인 |
| `copilot` | 벤더 제어(취소 — **주기 말 효력**, 즉시는 `pending_release`) | 벤더 제어(재배정 — 새 좌석 구매와 같다) |
| `gemini` | 벤더 제어(`unassign`) | 벤더 제어(`assign`) — 자동 배정 구독이면 벤더가 다시 배정할 수 있어 먼저 배정 방식을 확인한다 |

3절의 연결·동기화가 끝난 스테이징에서:

1. 테스트 계정의 좌석을 찾는다 — `GET O/members/{memberId}/seats`(dashboard-api)의 `seatAssignmentId`·`version`·`canReclaim`·`reclaimMethod`(`vendor_control`이어야 한다).
2. `POST O/seat-reclaims/preview`(좌석 하나) → `eligibleSeatAssignmentIds`에 그 좌석, `targets[0].method = vendor_control`.
3. `POST O/seat-reclaims`(`previewId`, `Idempotency-Key`) → 202. `sync.check-interval`이 지난 뒤 `GET O/operations/{operationId}`가 `succeeded`.
4. 벤더 콘솔에서 확인: Claude·Cursor는 구성원 목록에서 사라졌다, Copilot은 그 로그인이 "pending cancellation", Gemini는 라이선스 사용자에서 빠졌다.
   원장(`enrollment.seat_assignments`)은 `released`(Copilot은 `pending_release`, 다음 동기화 뒤 예정일이 채워진다), 원천 `vendor_control`.
5. 복원 — Copilot·Gemini: `POST O/seat-reclaims/{operationId}/restore` → 다음 주기 뒤 `succeeded`, 콘솔에 좌석이 돌아왔고 원장 `assigned`.
   Claude·Cursor: 복원 작업이 `awaiting_admin_action` → 콘솔에서 재초대 → `POST O/operations/{restoreId}/targets/{seatAssignmentId}/confirm` → 원장 `assigned`(원천 `admin_action`).
   Claude는 초대 수락 전까지 다음 동기화가 `pending_assignment`로 보일 수 있다.
6. 실패 경로: 관리자 역할 계정(Claude)·팀으로 배정된 좌석(Copilot)을 회수하면 대상이 `vendor_rejected`로 실패하고 원장은 그대로인지 본다.

결과(성공·실패 코드, 콘솔과 원장의 일치)와 확인일을 [`vendor-connector-evidence.md`](vendor-connector-evidence.md)의 해당 절에 적는다.
