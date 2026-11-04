# 수집 상태 — 기능 규칙·공유 스키마

[API 목록](../ingest-status.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

## 공통 헤더 수집 현황

`GET /api/v1/organizations/{organizationId}/ingest-status`는 선택 기간과 무관한 조직의 현재 수집 상태다.
기존 설정 조회와 같은 owner/admin 조직 권한 검사를 거치며, 계약·manifest 유무와 무관하게 조회한다.
시각은 UTC ISO 8601이며 `asOf`는 조회 기준 시각이다. 이 API는 원천에 쓰지 않는다.
손실 요약은 ADR 0076의 RDS 증가분 원장을 읽으며 현재 수집 상태와 독립적으로 실패를 표시한다.


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
- 현재 상태의 이력 조회가 실패하면 `unknown`/`source_not_available`로 표시하고 `empty`로 위장하지 않는다.
  독립적으로 읽을 수 있는 RDS 손실 요약은 유지한다. 인증·조직 권한 조회 실패는 이 fallback으로 우회하지 않는다.

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
  transmissionLoss: TransmissionLoss;
};
type TransmissionLoss = {
  payloadCount: number | null;
  windowStart: string; windowEnd: string; availableFrom: string | null;
  availability: "complete" | "partial" | "unavailable";
  reasons: string[];
  affectedInstallationCount: number | null;
};
```


## 최근 168시간 보고된 전송 손실 (ADR 0076)

`windowEnd`는 서버 조회 시각이며 창은 정확히 `[windowEnd − 168시간, windowEnd)`다.
원장의 서버 확인 시각을 사용하며 분석 날짜·팀·벤더 필터를 적용하지 않는다. 양수 증가분만 합산하고
폐기·전송 모드 변경 이후에도 이미 기록한 손실은 창에서 제외하지 않는다.

데몬이 보고한 손실 **페이로드** 수다. 이벤트·토큰 수와 다르며 대기·정책상 비수집·단순 보고 공백은 포함하지 않는다.
`mode=local && forwarding=true`인 보고만 관측 근거다. 수신기 가동 시각은 추가 조건이 아니다.
`delivered` 감소와 `pending` 증가는 손실이 아니고 같은 실행의 `lost` 감소는 최고 누적값을 낮추지 않는다.

| availability | 수량 | 의미 |
|---|---|---|
| `complete` | 0 이상 | 기간의 보고된 증가분을 집계할 근거가 있음. 실제 모든 전송의 무손실 보장은 아님 |
| `partial` | 확인된 0 이상 | 추적 시작·중단·카운터 감소 제한과 확인된 수량을 함께 제공 |
| `unavailable` | `payloadCount`, `affectedInstallationCount` 모두 null | 기반·관측 근거 없음 또는 조회 실패 |

| reason | 의미 |
|---|---|
| `tracking_not_started` | 집계 추적 기반 없음 |
| `history_incomplete` | 조회 창 전체를 덮는 추적 이력 없음 |
| `tracking_interrupted` | 추적 중단 이력 또는 현재 중단 상태 |
| `counter_decreased` | 같은 실행의 누적 손실 감소가 관측됨 |
| `no_observation` | 집계 가능한 손실 관측 근거 없음 |
| `query_failed` | 손실 조회 실패 |

`availableFrom`은 실제 집계 가능한 시작 시각이며 기반이 없으면 null이다. 단순 보고 공백은 가용성을 낮추지 않는다.
구버전 API에 `transmissionLoss`가 없으면 클라이언트는 집계 정보 없음으로 처리하며 0을 만들지 않는다.
재조회 실패는 기존 값·기준 시각과 함께 표시한다. 헤더와 상세의 값은 같은 창을 사용한다.

## 영향 기기

[GET 손실 영향 기기](../endpoints/77-list-loss-installations.md)는 요약의 `windowEnd`를 필수로 받는다.
후속 페이지는 같은 창·`snapshotId`·cursor를 유지한다. 설치 ID·기기명·구성원·손실 수·마지막 증가분 확인 시각과
현재 설치 상태를 반환하며 폐기 설치도 포함한다. 정렬·cursor는 해당 창의 목록에 고정한다.
조회 실패를 빈 목록이나 0으로 대신하지 않는다.

```ts
type LossInstallationsResponse = {
  meta: { organizationId: string; snapshotId: string; asOf: string };
  windowStart: string; windowEnd: string;
  installations: Page<LossInstallation>;
};
type LossInstallation = {
  installationId: string; deviceName: string | null;
  memberId: string | null; account: string | null;
  payloadCount: number; lastObservedAt: string; status: "active" | "revoked";
};
```
