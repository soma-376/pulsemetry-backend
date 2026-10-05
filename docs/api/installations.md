# 설치 현황·업데이트 안내

[API 길잡이](README.md) · [공통 규칙](common.md) · [공통 스키마](common-schemas.md)

## 엔드포인트

<a id="endpoint-dashboard-api-15"></a>

### 설치 현황

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/installations -->

```http
GET /api/v1/organizations/{organizationId}/installations
```

서버: **dashboard-api** · 성공: **200** · [구현](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/SettingsController.kt)

**Path**

```ts
{
  organizationId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

```ts
{
  policyStatus?: "applied" | "outdated" | "unknown"; // 생략: 전체
  limit?: number; // 기본 20, 정수 1~100
  cursor?: string; // 응답 nextCursor
  snapshotId?: string; // 다음 페이지에 이전 응답 값 유지
}
```

**Body**

본문 없음.

**Response**

```ts
// InstallationsResponse
{
  meta: CurrentMeta;
  desiredPolicyVersion: number;
  installations: Page<InstallationRow>;
}
```

[InstallationsResponse 전체 스키마·중첩 타입](installations.md#schema-InstallationsResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-49"></a>

### 설치 업데이트 안내 접수

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/installation-update-notifications -->

```http
POST /api/v1/organizations/{organizationId}/installation-update-notifications
```

서버: **enrollment-api** · 성공: **202** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

**Path**

```ts
{
  organizationId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
Content-Type: application/json
Idempotency-Key: <8~128자 영숫자 또는 _ 또는 ->
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

```ts
{
  installationIds: string[]; // UUID 1~100, 중복 불가
  expectedPolicyVersion: number; // 현재 manifest 판, 1 이상
}
```

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

메일 기능 필요. Location 반환. 메일 전송 결과와 정책 적용은 별개다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.


### 설치 업데이트 안내 (ADR 0043)

새 수집 정책을 아직 집행하지 않는 설치의 구성원에게 **확인을 부탁하는 메일**을 보낸다. 원격 업데이트가 아니다 — 서버는 설치에 정책을 밀어 넣지 않는다.
메일은 telemetryctl 기본 브랜치에 있는 명령만 안내한다(ADR 0053): `pulsemetry status`로 데몬이 도는지 보고, 지금의 데몬은 새 정책을 스스로 받아 오지 않으므로
새 정책을 적용하려면 관리자에게 설치 안내를 다시 받아 다시 설치하라고 적는다. 기기 이름·플랫폼·기대 판·지금 판을 싣고 비밀은 싣지 않는다.

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


### 정책 적용 현황과 업데이트 안내 (ADR 0043)

설치마다 **지금 집행하는 판**을 둔다. 설치 보고가 있으면 마지막 보고가 말한 판(`installation_heartbeats.applied_manifest_id`)이고,
그 조직이 모르는 판을 보고했으면 없다. 보고가 없으면 적용 확인 기록(`installation_manifest_assignments.applied_at`) 중 가장 높은 판이다.
적용 확인은 그 판을 적용한 **적이 있다**는 이력이라, 보고하는 설치에서 가장 높은 확인 판을 지금 판으로 쓰지 않는다(뒤로 돌아간 설치를 놓친다).
판정과 함께 그 근거(설치 보고·적용 확인 기록·없음)를 내고, 화면은 근거를 구분해 말한다. telemetryctl 기본 브랜치는 설치 보고를 보내지 않으므로(ADR 0053)
지금 배포된 설치의 판정은 대부분 적용 확인 기록이 근거다 — 그 설치가 지금도 그 판을 집행한다는 최근 확인이 아니다.

| 값 | 규칙 |
| --- | --- |
| 적용 상태 | 지금 판이 활성 판 이상이면 `applied`, 낮으면 `outdated`, 없으면 `unknown`. 보고가 없는 설치를 적용 완료로 추정하지 않는다 |
| `policyRollout` | 활성 설치 전체(`eligibleInstallations`)를 위 셋으로 나눈 수 |
| `/installations?policyStatus=` | `applied`·`outdated`·`unknown` 중 하나로 거른다. 그 밖의 값은 400. 필터마다 cursor 의 범위가 다르다 |
| `appliedPolicyVersion` | 지금 판. 없으면 null |
| `appliedEvidence` | 지금 판의 근거(가산). `heartbeat`는 마지막 설치 보고(보고한 판을 조직이 모르면 판은 null), `applied_confirmation`은 보고가 없어 적용 확인 기록, `none`은 둘 다 없음(판 null·`unknown`) |
| `appliedConfirmedAt` | 근거가 `applied_confirmation`일 때 고른 판(가장 높은 판)의 적용 확인 시각(가산). 더 낮은 판을 나중에 확인한 기록이 있어도 고른 판의 시각이다. 그 밖에는 null |
| `policyRollout.evidence` | 같은 근거별 설치 수 `{heartbeat, appliedConfirmation, none}`(가산). 합은 `eligibleInstallations` |
| `lastHeartbeatAt` | 마지막 설치 보고를 받은 서버 시각. 보고가 없으면 null — 데이터 수신 시각을 넣지 않는다 |
| `capabilities.notifyInstallations` | 안내 채널이 있는 배포(`pulsemetry.management.enabled`와 `pulsemetry.mail.enabled`가 모두 true)면 true |
| `canNotify` | 채널이 있고, 구성원이 활성이고, 적용 상태가 `applied`가 아닌 설치만 true |

안내 명령(`POST O/installation-update-notifications`)은 enrollment-api가 받는다(enrollment 명세 §12 "설치 업데이트 안내"). 202 응답의 `Location`이 이 앱의
작업 상태 조회를 가리키고, 대상 결과는 **메일의 발송 결과**다 — 설치가 새 판을 적용했다는 뜻이 아니다. 적용 여부는 이 표의 적용 상태로 다시 확인한다.


## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### PolicyRollout

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-PolicyRollout"></a>

```ts
type PolicyRollout = {
  desiredVersion: number;
  eligibleInstallations: number;
  appliedInstallations: number;
  outdatedInstallations: number;
  unknownInstallations: number;
  evidence: RolloutEvidence;
};
```

### RolloutEvidence

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-RolloutEvidence"></a>

```ts
type RolloutEvidence = {
  heartbeat: number;
  appliedConfirmation: number;
  none: number;
};
```

### InstallationRow

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-InstallationRow"></a>

```ts
type InstallationRow = {
  installationId: string;
  memberId: string | null;
  account: string | null;
  team: TeamRef;
  agentVersion: string | null;
  appliedPolicyVersion: number | null;
  lastHeartbeatAt: string | null;
  canNotify: boolean;
  appliedEvidence: string;
  appliedConfirmedAt: string | null;
};
```

### InstallationsResponse

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-InstallationsResponse"></a>

```ts
type InstallationsResponse = {
  meta: CurrentMeta;
  desiredPolicyVersion: number;
  installations: Page<InstallationRow>;
};
```


## 연관 스키마

- [CurrentMeta](common-schemas.md#schema-CurrentMeta)
- [Page](common-schemas.md#schema-Page)
- [TeamRef](common-schemas.md#schema-TeamRef)

## JSON 응답 예시 — InstallationsResponse

```json
{
  "meta": {
    "organizationId": "10000000-0000-0000-0000-000000000001",
    "generatedAt": "2026-10-02T00:00:00Z",
    "asOf": "2026-10-02T00:00:00Z",
    "snapshotId": "<opaque-current-snapshot>",
    "currency": "USD",
    "timeZone": "Asia/Seoul"
  },
  "desiredPolicyVersion": 1,
  "installations": {
    "items": [],
    "totalCount": 0,
    "nextCursor": null
  }
}
```
