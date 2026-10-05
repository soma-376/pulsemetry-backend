# 수집 정책·조직 정책 설정 — 기능 규칙·공유 스키마

[API 목록](../collection-policy.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

### 수집 정책 저장

`PUT /collection-policy`

요청 본문:

```json
{"expectedVersion":1,"collectRawContent":false}
```

응답(200):

<a id="schema-PolicySaved"></a>

```ts
type PolicySaved = {
  version: number; collectRawContent: boolean | null; confirmedAt: string | null;
  application: "future_enrollments";
  existingInstallationsUpdated: false;
  // 가산 — 조직 정책 설정(ADR 0046)
  reclaimIdleDays: 7 | 14 | 30 | 60 | null;    // 조직이 저장한 값. null이면 조회 서버의 기본 설정을 쓴다
  aggregateRetentionMonths: 12 | 24 | 36 | null; // null = 무기한
  settingsVersion: number;                      // 저장 전 0
  settingsUpdatedAt: string | null;
  cleanupOperationId: string | null;            // 이 저장이 만든 보존 정리 작업(ADR 0047). 없으면 null
};
```

`collectRawContent`는 이제 선택이다. 보내면 아래 규칙대로 새 manifest 판을 만들고, 보내지 않으면 manifest를 바꾸지 않는다.
그때 `version`·`collectRawContent`·`confirmedAt`은 지금의 활성 manifest·정책 확인 기록을 그대로 알려 준다(없으면 0·null·null).
기존 `{expectedVersion, collectRawContent}` 본문의 동작과 응답은 그대로다.

서버는 현재 활성 manifest의 version을 비교한다. 충돌은 409 `version_conflict`다.
활성 manifest가 없으면 `expectedVersion=0`으로 최초 생성한다. 새 판번호는 기존 판번호의 최댓값 다음이다.
최초 생성에는 [기존 §9.1](../../enrollment-server-spec.md#91-초기-manifest-준비)의 서버 수집 주소가 필요하다. 누락·잘못된 주소는 409 `manifest_not_configured`이고
manifest와 정책 확인 기록을 저장하지 않는다. 로그인은 이 설정과 무관하게 가능하다.
초기 프로토콜은 `http/protobuf`, 세 signal은 true, privacy는 전부 false에서 사용자의 원문 선택을 반영한다.
새 manifest 판에서 `privacy.collect_user_prompts`와 `privacy.collect_assistant_responses`만 함께 변경한다.
config_revision도 새 판에 맞춘다. endpoint·signals·나머지 privacy 설정 및 이전 판의 JSON은 보존한다.
동일 조직의 변경·완료 명령은 조직 행 잠금과 한 트랜잭션으로 처리한다.
정책 저장은 **이후 enroll의 기본값**이고, 서버가 이미 설치된 클라이언트에 정책을 밀어 넣거나
installation_manifest_assignments를 적용 완료로 변경하지 않는다(`application`·`existingInstallationsUpdated`는 이 저장이 한 일이다).
기존 설치는 설치 보고([기존 §4.5](../../enrollment-server-spec.md#45-설치-보고--post-v1installationsinstallation_idheartbeat))의 응답으로 새 판을 알고, 사용자 로그인 세션이 있는 데몬이 스스로 받아 적용한 뒤 보고한다 — 적용 확인은 그 보고가 기록한다.
적용 현황은 대시보드 설치 조회로 확인하고, 아직 적용하지 않은 설치의 구성원에게는 [기존 §12](../../enrollment-server-spec.md#12-조직-관리-api)의 설치 업데이트 안내로 확인을 부탁한다.

**회수 기준·집계 보존**(ADR 0046). 좌석 회수 기준(`reclaimIdleDays` — 7·14·30·60)과 집계 보존(`aggregateRetentionMonths` — 12·24·36, null은 무기한)은
설치에 배포하는 정책이 아니라서 manifest와 따로 `enrollment.organization_policy_settings`에 저장하고 판도 따로 센다. manifest 판을 올리지 않으므로 설치의 적용 상태는 그대로다.

```json
{"expectedVersion":3,"expectedSettingsVersion":0,"reclaimIdleDays":30}
```

- 보낸 필드만 바꾼다. 보내지 않은 값은 저장된 값 그대로다. 바꿀 것(원문 선택·회수 기준·집계 보존)이 하나도 없으면 400 `invalid_request`다.
- 두 값 중 하나라도 보내면 `expectedSettingsVersion`(설정의 판, 저장 전 0)이 필수다. 허용 밖의 값·문자열·판 누락은 400 `invalid_request`이고 `fieldErrors`에 그 필드가 있다.
- `expectedVersion`은 언제나 활성 manifest 판과 맞아야 한다. 설정의 판이 어긋나면 409 `version_conflict`(`fieldErrors`의 `expectedSettingsVersion`)이고 아무것도 저장하지 않는다.
  원문 선택과 설정을 함께 보내면 한 트랜잭션이다.
- 설정의 판은 값이 실제로 바뀔 때만 1 오른다. 같은 값을 다시 보내면 같은 판·같은 저장 시각이다.
- 저장하지 않은 조직의 유효값은 조회 서버가 정한다 — 회수 기준은 dashboard-api의 `pulsemetry.dashboard.members.idle-days`, 집계 보존은 무기한이다(대시보드 명세 "조직 정책 설정").
- 원문 보존 일수는 저장하지 않는다 — 원천(원본 아카이브의 수명)이 이 저장소에 없다.

**집계 보존 단축 → 보존 정리 요청**(ADR 0047). 집계 보존이 바뀐 저장만 다룬다(같은 트랜잭션).

| 저장 | 결과 |
| --- | --- |
| 줄였다(유한 값이 작아짐, 무기한 → 유한) | `retention_cleanup` 작업(대기, 대상 `analysis_source`)과 `enrollment.retention_cleanup_requests` 요청을 만들고 `cleanupOperationId`로 돌려준다 |
| 한 번도 실행하지 않은 요청이 있다 | 그 요청을 `superseded`로 닫고 작업을 같은 사유로 실패시킨다. 새 값이 유한이면(늘린 값이라도) 새 요청을 만든다. 무기한이면 없다 |
| 늘렸고 대체할 요청이 없다 · 무기한으로 바꿨다 | 아무것도 하지 않는다(`cleanupOperationId`=null). 지운 기록은 되돌리지 않는다 |

- 경계는 저장 시각의 KST 날짜에서 N개월 전 자정이다(`as_of`에 고정 — 다시 실행해도 같은 경계).
- 이 앱은 지우지 않는다. 실행은 `:apps:retention-worker --requests`(주기 실행은 배포 환경)가 기존 보존 삭제 순서 그대로 하고, 진행은 dashboard-api의 작업 상태 조회로 본다
  (대시보드 명세 "작업 상태 조회" — `retention`에 가장 최근 삭제 실행의 상태).


### 조직 정책 설정 (ADR 0046)

좌석 회수 기준과 집계 보존은 enrollment-api의 수집 정책 저장(`PUT O/collection-policy`, enrollment 명세 §13.2)이 조직마다 저장한다.
이 앱은 `enrollment.organization_policy_settings`를 읽기 전용 계정으로 요청마다 읽는다. 설정·구성원·회수 후보가 한 곳(`OrganizationPolicies`)에서 같은 값과 같은 판을 쓴다.

| 값 | 규칙 |
| --- | --- |
| 유효 회수 기준 | 조직이 저장한 값. 없으면 이 앱의 `pulsemetry.dashboard.members.idle-days` |
| 집계 보존 | 조직이 저장한 값. 없으면 null(무기한) |
| 설정의 판 | 값이 바뀔 때마다 1 오르는 판. 저장한 적 없으면 0. manifest 판(`collectionPolicy.version`)과 별개다 |


- 설정의 `collectionPolicy.reclaimIdleDays`·`aggregateRetentionMonths`는 유효값이다. `version`·`effectiveAt`·`updatedBy`는 여전히 원문 선택이 실린 manifest의 것이다.
- 구성원 화면의 `policy`(`idleDays`·`version`)와 회수 후보의 `idleDays`·`policy`는 유효 회수 기준과 **설정의 판**이다(고정 0이 아니다).
- `rawContentRetentionDays`는 null이다. 원문 보존 기간의 원천(원본 아카이브의 수명)이 이 저장소에 없다.
- 집계 보존을 줄인 저장은 보존 정리 작업을 만든다(enrollment 명세 §13.2, ADR 0047). 진행은 아래 "작업 상태 조회"로 본다.


## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### CollectionPolicy

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-CollectionPolicy"></a>

```ts
type CollectionPolicy = {
  version: number;
  collectRawContent: boolean;
  reclaimIdleDays: number;
  aggregateRetentionMonths: number | null;
  rawContentRetentionDays: number | null;
  effectiveAt: string;
  updatedBy: string;
  settingsVersion: number;
  settingsUpdatedAt: string | null;
  settingsUpdatedBy: string | null;
  reclaimIdleDaysSource: string;
  options: PolicyOptions;
  cleanupOperationId: string | null;
};
```

### PolicyOptions

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

<a id="schema-PolicyOptions"></a>

```ts
type PolicyOptions = {
  reclaimIdleDays: Array<number>;
  aggregateRetentionMonths: Array<number | null>;
};
```
