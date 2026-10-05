# 비동기 작업

[API 길잡이](README.md) · [공통 규칙](common.md) · [공통 스키마](common-schemas.md)

## 엔드포인트

<a id="endpoint-dashboard-api-18"></a>

### 작업 상태

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/operations/{operationId} -->

```http
GET /api/v1/organizations/{organizationId}/operations/{operationId}
```

서버: **dashboard-api** · 성공: **200** · [구현](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/OperationController.kt)

**Path**

```ts
{
  organizationId: string;
  operationId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

본문 없음.

**Response**

```ts
// OperationResponse
{
  operationId: string;
  kind: "seat_reclaim" | "seat_restore" | "installation_notification" | "retention_cleanup" | "seat_sync";
  status: "pending" | "running" | "awaiting_admin_action" | "succeeded" | "partially_failed" | "failed";
  createdAt: string;
  completedAt: string | null;
  results: {
    targetId: string;
    status: "pending" | "awaiting_admin_action" | "succeeded" | "failed";
    reason: string | null;
    action: string | null;
  }[];
  canRestore: boolean;
  restoreUntil: string | null;
  retention: {
    status: "running" | "incomplete" | "logically_deleted" | "failed";
    requestedBefore: string; deletedBefore: string | null;
    startedAt: string; finishedAt: string | null;
  } | null;
}
```

[OperationResponse 전체 스키마·중첩 타입](operations.md#schema-OperationResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-47"></a>

### 관리자 조치 확인

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/operations/{operationId}/targets/{targetId}/confirm -->

```http
POST /api/v1/organizations/{organizationId}/operations/{operationId}/targets/{targetId}/confirm
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

**Path**

```ts
{
  organizationId: string;
  operationId: string;
  targetId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

본문 없음.

**Response**

```ts
// OperationResponse
{
  operationId: string;
  kind: "seat_reclaim" | "seat_restore" | "installation_notification" | "retention_cleanup" | "seat_sync";
  status: "pending" | "running" | "awaiting_admin_action" | "succeeded" | "partially_failed" | "failed";
  createdAt: string;
  completedAt: string | null;
  results: {
    targetId: string;
    status: "pending" | "awaiting_admin_action" | "succeeded" | "failed";
    reason: string | null;
    action: string | null;
  }[];
  canRestore: boolean;
  restoreUntil: string | null;
  retention: {
    status: "running" | "incomplete" | "logically_deleted" | "failed";
    requestedBefore: string; deletedBefore: string | null;
    startedAt: string; finishedAt: string | null;
  } | null;
}
```

[OperationResponse 전체 스키마·중첩 타입](operations.md#schema-OperationResponse)

요청한 관리자가 외부 조치를 수행한 뒤 확인한다. targetId는 좌석 ID.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-48"></a>

### 관리자 조치 취소

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/operations/{operationId}/targets/{targetId}/cancel -->

```http
POST /api/v1/organizations/{organizationId}/operations/{operationId}/targets/{targetId}/cancel
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

**Path**

```ts
{
  organizationId: string;
  operationId: string;
  targetId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

본문 없음.

**Response**

```ts
// OperationResponse
{
  operationId: string;
  kind: "seat_reclaim" | "seat_restore" | "installation_notification" | "retention_cleanup" | "seat_sync";
  status: "pending" | "running" | "awaiting_admin_action" | "succeeded" | "partially_failed" | "failed";
  createdAt: string;
  completedAt: string | null;
  results: {
    targetId: string;
    status: "pending" | "awaiting_admin_action" | "succeeded" | "failed";
    reason: string | null;
    action: string | null;
  }[];
  canRestore: boolean;
  restoreUntil: string | null;
  retention: {
    status: "running" | "incomplete" | "logically_deleted" | "failed";
    requestedBefore: string; deletedBefore: string | null;
    startedAt: string; finishedAt: string | null;
  } | null;
}
```

[OperationResponse 전체 스키마·중첩 타입](operations.md#schema-OperationResponse)

원장을 바꾸지 않고 대상을 cancelled로 실패 처리한다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.


### 작업 상태 조회

`GET /api/v1/organizations/{organizationId}/operations/{operationId}` — 명령이 접수한 뒤 요청 밖에서 끝나는 일의 상태다(ADR 0039).
이 앱은 읽기만 한다. 작업을 만들고 결과를 기록하는 쪽은 그 명령을 받은 앱과 실행 주체이며, 기록은 `enrollment.operations`·`operation_targets`에 있다.
snapshot을 쓰지 않는 현재 상태 조회다.


- 작업의 `status`는 대상 결과에서 계산한 값이다. 기다리는 대상이 남아 있으면 `running`, 기다리는 대상이 없고 조치 대기가 남아 있으면 `awaiting_admin_action`,
  모두 끝났으면 전부 성공일 때만 `succeeded`다. 성공이 하나도 없으면 `failed`, 섞였으면 `partially_failed`다. `results`는 작업에 넣은 순서다.
- `awaiting_admin_action`은 시스템이 끝낼 수 없는 대상이다. 관리자가 `action`의 조치를 시스템 밖에서 하고 확인해야 성공이 된다. 자동으로 성공이 되지 않는다.
- `Retry-After`(초)는 `pending`·`running`에만 싣는다. 값은 `pulsemetry.dashboard.retry-after`다. 조치 대기와 끝난 작업에는 없다 — 클라이언트는 헤더가 없으면 반복 조회를 멈춘다.
- `canRestore`는 성공한 대상이 있는 끝난 `seat_reclaim`이고 `restoreUntil` 전이며, 그 회수를 되돌리는 실패하지 않은 복원 작업이 없을 때만 true다.
- `retention`은 `retention_cleanup` 작업이 가리키는 가장 최근 삭제 실행(`telemetry_ops.retention_operations`)이다. 아직 실행된 적이 없으면 null이다.
- 보존 정리 작업(ADR 0047): 요청 모드의 보존 작업이 선점하면 `running`, 삭제 실행이 `logically_deleted`면 `succeeded`(대상 `analysis_source` 성공)다.
  `incomplete`·`failed`인 실행 뒤에는 작업이 `running`인 채로 `retention.status`가 그 값이고 다음 실행이 같은 경계로 이어서 끝낸다. 정한 횟수 안에 끝내지 못하면
  대상 실패(`retention_incomplete`·`retention_failed`)로 `failed`다. 실행 전에 새 저장이 대체하면 `superseded`로 `failed`다.
  `succeeded`는 논리 삭제 완료이고 물리 제거 완료가 아니다.
  `logically_deleted`는 논리 삭제 완료이며 물리 제거 완료가 아니다. 삭제한 행 수와 상세 문구는 싣지 않는다.
- 그 조직에 없는 작업은 404 `not_found`다. 다른 조직의 작업, 없는 ID, UUID가 아닌 ID가 같은 응답이다. **실패한 작업은 404가 아니라 200과 `status=failed`다.**
- 작업을 만드는 명령은 설치 업데이트 안내(`installation_notification` — 대상 ID는 설치 ID, 결과는 메일 발송 결과), 수집 정책 저장의 보존 단축(`retention_cleanup`, ADR 0047),
  좌석 동기화 요청(`seat_sync` — 대상 ID는 벤더 연결 ID, 결과는 동기화 실행의 결과이고 실패 사유는 Enrollment 명세 §12 "벤더 연결"의 실패 코드, ADR 0048),
  좌석 회수·복원(`seat_reclaim`·`seat_restore` — 대상 ID는 좌석 ID, 관리자 조치 대상의 조치 코드는 `release_in_vendor_console`·`restore_in_vendor_console`, 실패 사유는
  Enrollment 명세 §12 "좌석 회수·복원", ADR 0049)이다.


<a id="schema-OperationResponse"></a>

```ts
type OperationResponse = {
  operationId: string;
  kind: "seat_reclaim" | "seat_restore" | "installation_notification" | "retention_cleanup" | "seat_sync";
  status: "pending" | "running" | "awaiting_admin_action" | "succeeded" | "partially_failed" | "failed";
  createdAt: string;
  completedAt: string | null; // succeeded·partially_failed·failed일 때만 값이 있다
  results: {
    targetId: string; // 무엇의 ID인지는 kind가 정한다
    status: "pending" | "awaiting_admin_action" | "succeeded" | "failed";
    reason: string | null; // failed일 때의 실패 분류 코드
    action: string | null; // 관리자가 시스템 밖에서 해야 하는(했던) 조치의 코드
  }[];
  canRestore: boolean;
  restoreUntil: string | null;
  retention: {
    status: "running" | "incomplete" | "logically_deleted" | "failed";
    requestedBefore: string; deletedBefore: string | null;
    startedAt: string; finishedAt: string | null;
  } | null;
};
```

## JSON 응답 예시 — OperationResponse

```json
{
  "operationId": "30000000-0000-0000-0000-000000000001",
  "kind": "seat_sync",
  "status": "failed",
  "createdAt": "2026-10-02T00:00:00Z",
  "completedAt": "2026-10-02T00:00:10Z",
  "results": [
    {
      "targetId": "40000000-0000-0000-0000-000000000001",
      "status": "failed",
      "reason": "vendor_unavailable",
      "action": null
    }
  ],
  "canRestore": false,
  "restoreUntil": null,
  "retention": null
}
```
