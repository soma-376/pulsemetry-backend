# 수집 상태

[API 길잡이](README.md) · [공통 규칙](common.md) · [공통 스키마](common-schemas.md)

## 엔드포인트

<a id="endpoint-dashboard-api-19"></a>

### 현재 수집 상태

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/ingest-status -->

```http
GET /api/v1/organizations/{organizationId}/ingest-status
```

서버: **dashboard-api** · 성공: **200** · [구현](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/IngestStatusController.kt)

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

없음.

**Body**

본문 없음.

**Response**

```ts
// IngestStatusResponse
{
  organizationId: string; status: "empty" | "healthy" | "delayed" | "down" | "unknown";
  reason: string | null; asOf: string; lastReceivedAt: string | null; windowMinutes: number;
  activeInstallations: number | null; observedMembers: number | null; eligibleMembers: number | null;
  coverageRatio: number | null; coverageTargetMembers: number | null; coverageObservedMembers: number | null;
}
```

[IngestStatusResponse 전체 스키마·중첩 타입](ingest-status.md#schema-IngestStatusResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.


## 공통 헤더 수집 현황

`GET /api/v1/organizations/{organizationId}/ingest-status`는 선택 기간과 무관한 조직의 현재 수집 상태다.
기존 설정 조회와 같은 owner/admin 조직 권한 검사를 거치며, 계약·manifest 유무와 무관하게 조회한다.
시각은 UTC ISO 8601이며 `asOf`는 조회 기준 시각이다. DB 쓰기나 새 집계 테이블은 없다.


개요·팀·구성원·설정 응답의 `ingest` 조각은 **같은 계산**의 결과다(ADR 0041). 조각의 키는 그대로이고
`coverageTargetMembers`·`coverageObservedMembers`는 이 응답에만 있다.

### 판정 (ADR 0041)

최근 수신 시각만으로 `healthy`·`delayed`·`down`을 추정하지 않는다. 근거는 설치 보고(enrollment 명세 §4.5)의 전달 결과다.
그 조직의 **활성 설치**만 본다. 위에서 아래로 먼저 맞는 줄이 답이다.

| 조건 | `status` | `reason` |
| --- | --- | --- |
| 수신 이력이 없다 | `empty` | null |
| 판정 대상이 있고 전부 중단 | `down` | `delivery_stalled` |
| 판정 대상이 있고 하나라도 지연·중단 | `delayed` | `delivery_delayed` |
| 판정 대상이 있고 모두 정상 | `healthy` | null |
| 판정 대상이 없고, 수집 중이던 설치의 가장 최근 보고가 `down-after`보다 오래됐다 | `down` | `installations_silent` |
| 판정 대상이 없고, 수집 중이던 설치가 창 밖에서 보고했다 | `unknown` | `installations_silent` |
| 수집 중이라고 보고한 설치가 없다(설치 없음 · 보고한 적 없음 · 수집 비활성이나 직결뿐) | `unknown` | `source_not_available` |
| 설치 보고를 읽지 못했다 | `unknown` | `source_not_available` |

- **판정 대상** = 창 안에 보고했고(`window`, 시작 시각 포함) 수집 중(경로 `local` · 전달기 가동 · 수신기 가동)이라고 보고한 설치.
  회사로 직접 보내는 설치와 전달·수신을 꺼 둔 설치는 판정 대상이 아니다 — "전부 중단"의 전부에 들지 않는다.
- 판정 대상 설치 하나의 상태:
  - 창 안에 끝난 손실 구간이 있고 마지막 전달 성공이 `down-after`보다 오래됐다 → 중단. 전달한 적이 없으면 수신기가 듣기 시작한 때부터 센다.
  - 창 안에 끝난 손실 구간이 있다 → 지연.
  - 전달 대기가 `delayed-after`보다 오래 이어졌다(`installation_heartbeats.pending_since`) → 지연.
  - 그 밖 → 정상. 대기 수가 0이 아니라는 것만으로, 마지막 전달이 오래됐다는 것만으로는 지연이 아니다.
- 수신 이력이 없으면 설치가 보고하고 있어도 `empty`다(ADR 0034의 뜻 그대로).
- 이력 판정 근거 자체가 없으면 기존 개요와 같이 503으로 실패하며 `empty`로 위장하지 않는다.

### 세는 값과 null

| 필드 | 뜻 | null인 때 |
| --- | --- | --- |
| `windowMinutes` | 설정 `pulsemetry.dashboard.ingest.window` | — |
| `activeInstallations` | 창 안에 설치 보고가 있는 활성 설치 수(경로를 가리지 않는다) | 그 조직의 활성 설치가 보고한 적이 없다 · 설치 보고를 읽지 못했다 |
| `observedMembers` | 창 안에 수신(ledger)이 있는 설치의 구성원 수 | 조회 실패 |
| `eligibleMembers` | 활성 구성원 수 | 조회 실패 |
| `coverageRatio` | `coverageObservedMembers ÷ coverageTargetMembers` | 분모가 0 · 수신이나 설치 보고를 읽지 못했다 |
| `coverageTargetMembers` | 활성 구성원 중, 마지막 보고가 수집 중이거나 직결인 활성 설치를 가진 사람 수 | 설치 보고를 읽지 못했다 |
| `coverageObservedMembers` | 그중, 그런 설치에서 창 안에 수신이 확인된 사람 수 | 수신이나 설치 보고를 읽지 못했다 |

- 커버리지의 분모에서 빠지는 것: 보고한 적 없는 설치, 수집 비활성으로 보고한 설치, 활성이 아닌 구성원, 설치가 없는 구성원.
- `coverageRatio`는 `observedMembers ÷ eligibleMembers`가 **아니다.** `eligibleMembers`에는 설치가 없는 사람이 들어 있다.
- 커버리지는 창 안의 수신을 센다. 데몬이 살아 있어도 그 사람이 창 안에 도구를 쓰지 않았으면 분자에 들지 않는다.
- 0은 "세어 보니 0"이다. 근거가 없으면 null이다.

### 임계값 설정

셋 다 기본값이 없다. 비거나 서로 맞지 않으면 기동하지 않는다.

| 설정 | 환경 변수 | 뜻 | 제약 |
| --- | --- | --- | --- |
| `pulsemetry.dashboard.ingest.window` | `PULSEMETRY_DASHBOARD_INGEST_WINDOW` | "지금"으로 보는 창 | 1분 이상의 분 단위. 설치 보고 주기(`pulsemetry.heartbeat.report-interval`)보다 길게 |
| `pulsemetry.dashboard.ingest.delayed-after` | `PULSEMETRY_DASHBOARD_INGEST_DELAYED_AFTER` | 전달 대기가 이보다 오래 이어지면 지연 | 0보다 크다 |
| `pulsemetry.dashboard.ingest.down-after` | `PULSEMETRY_DASHBOARD_INGEST_DOWN_AFTER` | 전달 성공(또는 설치 보고)이 이보다 오래 없으면 중단 | `delayed-after`보다 크다 |

local 프로필은 15분 · 5분 · 24시간이다. 개발 시드에는 살아 있는 데몬이 없어 시드 A는 기준일에서 하루가 지나면 `down`(`installations_silent`)이다.

<a id="schema-IngestStatusResponse"></a>

```ts
type IngestStatusResponse = {
  organizationId: string; status: "empty" | "healthy" | "delayed" | "down" | "unknown";
  reason: string | null; asOf: string; lastReceivedAt: string | null; windowMinutes: number;
  activeInstallations: number | null; observedMembers: number | null; eligibleMembers: number | null;
  coverageRatio: number | null; coverageTargetMembers: number | null; coverageObservedMembers: number | null;
};
```
