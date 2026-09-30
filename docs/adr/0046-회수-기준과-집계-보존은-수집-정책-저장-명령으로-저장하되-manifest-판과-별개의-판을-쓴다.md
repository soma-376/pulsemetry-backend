# 0046. 회수 기준과 집계 보존은 수집 정책 저장 명령으로 저장하되 manifest 판과 별개의 판을 쓴다

## Status

Accepted

## Context

설정 화면에는 "좌석 회수 기준"(유휴 일수)과 "집계 보존"(개월 수)이 있지만 저장할 곳이 없다.

- 회수 기준은 dashboard-api의 배포 설정 `pulsemetry.dashboard.members.idle-days` 하나다(7·14·30·60 중 하나). 조직마다 다를 수 없다.
  구성원 화면의 `policy.version`은 "저장소에서 온 값이 아님"을 뜻하는 고정 0이다(`MembersService.POLICY_VERSION`).
- 집계 보존은 설정 조회에서 늘 null이다. 보존 삭제의 실행은 `:apps:retention-worker`가 하고 경계는 RDS가 진실원이다(ADR 0024).
  그 작업의 트리거와 수치는 이 저장소 밖에 있다.
- 수집 정책 저장은 이미 `PUT O/collection-policy`(`{expectedVersion, collectRawContent}`)다. 원문 선택을 새 manifest 판으로 만들고,
  `expectedVersion`은 활성 manifest의 판이다(enrollment 명세 §13.2, ADR 0033).
- manifest 판은 설치에 배포하는 정책의 판이다. 설치 보고의 응답이 기대 판을 알리고, 정책 적용 현황은 설치가 집행하는 판이 활성 판 이상인지로
  적용·미적용을 나눈다(ADR 0043). 판을 올리면 그 순간 모든 설치가 미적용이 된다.
- 조직 관리 명령은 enrollment-api가 받고 `:libs:enrollment-persistence`가 조직 행을 잠근 한 트랜잭션으로 저장한다.
  dashboard-api는 enrollment 표를 읽기 전용 계정으로 읽는다.

정하지 않으면 생기는 일: 화면의 두 선택지를 켤 수 없다. 설치와 무관한 값을 manifest에 넣으면 값을 바꿀 때마다 전 설치가 미적용으로 보인다.
별도 경로를 새로 만들면 같은 설정 화면의 저장이 두 경로·두 판으로 갈라진다.

## Decision

- **저장 명령은 기존 `PUT O/collection-policy`를 확장한다.** 새 경로를 만들지 않는다. 새 필드는 모두 선택이다.

  | 필드 | 값 | 생략하면 |
  | --- | --- | --- |
  | `expectedVersion` | 활성 manifest 판(필수 — 지금과 같다) | 400 |
  | `collectRawContent` | 불리언. 보내면 지금처럼 새 manifest 판을 만든다 | manifest를 바꾸지 않는다 |
  | `reclaimIdleDays` | 7·14·30·60 | 저장된 값 유지 |
  | `aggregateRetentionMonths` | 12·24·36 또는 null(무기한) | 저장된 값 유지 |
  | `expectedSettingsVersion` | 조직 정책 설정의 판(저장 전 0). 위 두 값 중 하나라도 보내면 필수 | — |

  바꿀 것(원문 선택·회수 기준·집계 보존)이 하나도 없으면 400이다. 허용 밖의 값, 판 없는 설정 변경은 400 `invalid_request`(`fieldErrors`에 그 필드)다.
  기존 `{expectedVersion, collectRawContent}` 본문의 동작과 응답 필드는 그대로다.
- **회수 기준·집계 보존은 manifest와 따로 저장하고 판도 따로 센다.** 설치에 배포하는 정책이 아니므로 manifest 판을 올리지 않는다.
  저장 위치는 `enrollment.organization_policy_settings`(조직당 한 행, enrollment Flyway)이고 쓰기 소유는 `:libs:enrollment-persistence`의 수집 정책 저장이다.
  - 판은 값이 실제로 바뀔 때만 1 오른다. 같은 값을 다시 보내면 같은 판·같은 저장 시각이다. 행이 없으면 판 0이다.
  - 충돌: `expectedVersion`은 언제나 활성 manifest 판과 맞아야 하고, 설정을 보낼 때는 `expectedSettingsVersion`도 저장된 판과 맞아야 한다.
    어느 쪽이 어긋나도 409 `version_conflict`이고 아무것도 저장하지 않는다(설정 판이 어긋나면 `fieldErrors`에 `expectedSettingsVersion`).
    원문 선택과 설정을 함께 보내면 한 트랜잭션이다.
- **저장값이 없을 때의 유효값**: 회수 기준은 조회 서버의 기본 설정(`pulsemetry.dashboard.members.idle-days`), 집계 보존은 무기한(null)이다.
  행이 있어도 회수 기준 칸이 비어 있으면(집계 보존만 저장한 조직) 기본 설정이다. 기본값의 출처는 조회 응답의 `reclaimIdleDaysSource`(`organization`·`default`)로 밝힌다.
- **응답**(`PolicySaved`, 가산): `reclaimIdleDays`(조직이 저장한 값, 없으면 null — 유효값은 조회가 정한다), `aggregateRetentionMonths`,
  `settingsVersion`, `settingsUpdatedAt`, `cleanupOperationId`(보존 기간을 줄였을 때 만들 정리 작업의 ID — 이 결정에서는 늘 null).
  원문 선택을 보내지 않은 저장에서 `version`·`collectRawContent`·`confirmedAt`은 지금의 manifest·정책 확인 기록을 그대로 알려 준다(없으면 0·null·null).
- **조회**(dashboard-api, 요청마다 현재 값): 설정의 `collectionPolicy`는 유효 회수 기준·집계 보존과 가산 필드
  `settingsVersion`·`settingsUpdatedAt`·`settingsUpdatedBy`·`reclaimIdleDaysSource`·`options`(저장할 수 있는 값)를 낸다. `version`·`effectiveAt`·`updatedBy`는
  여전히 manifest의 것이다. 구성원 화면의 `policy`와 회수 후보(`idleDays`, 가산 `policy`)는 같은 조직의 유효 회수 기준과 **설정의 판**을 쓴다(고정 0을 쓰지 않는다).
- 원문 보존 일수(`rawContentRetentionDays`)는 저장하지 않는다. 그 값의 원천(원본 아카이브의 수명)이 이 저장소에 없다 — 조회는 null이다.
- 집계 보존을 저장해도 이 결정만으로는 아무것도 지우지 않는다. 보존 기간 단축을 보존 작업으로 잇는 일은 별도 결정이 정한다(ADR 0024의 순서·주체는 그대로).

## Alternatives Considered

### A. 설정 전용 경로(`PATCH O/settings/collection-policy`)를 새로 둔다
- 장점: manifest와 무관한 값이 manifest 경로를 지나지 않는다.
- 단점: 같은 화면의 "수집 정책" 저장이 두 경로·두 요청으로 나뉜다. 원문 선택과 보존을 함께 바꾸면 한쪽만 성공한 상태가 생긴다.
- 탈락 이유: 기존 경로에 선택 필드를 더하면 호환이 유지되고 한 트랜잭션으로 저장된다.

### B. 두 값을 manifest에 넣고 manifest 판 하나로 버전을 관리한다
- 장점: 판이 하나라 충돌 제어가 단순하다.
- 단점: 회수 기준을 바꿀 때마다 새 판이 생기고 모든 설치가 미적용으로 보인다(ADR 0043). 데몬이 쓰지 않는 값을 설치에 배포한다.
- 탈락 이유: 설치에 배포하는 정책과 조회·삭제에만 쓰는 조직 설정은 다른 것이다.

### C. 설정 변경에는 판 검사를 하지 않는다(마지막 저장이 이긴다)
- 장점: 화면이 판을 하나만 다룬다.
- 단점: 두 관리자가 동시에 보존 기간을 고치면 먼저 저장한 변경이 조용히 사라진다. 보존은 삭제로 이어지는 값이다.
- 탈락 이유: 다른 관리 명령(팀·계약·구성원)과 같이 낙관적 잠금을 쓴다.

### D. 저장 전 조직에 기본값 행을 만들어 둔다(조직 생성 때 삽입)
- 장점: 조회가 늘 행을 읽는다.
- 단점: 기본 회수 기준은 조회 서버의 배포 설정이다. 행에 복사하면 설정을 바꿔도 기존 조직에 반영되지 않는다. 조직 생성 경로를 모두 고쳐야 한다.
- 탈락 이유: "저장하지 않았음"을 판 0과 빈 칸으로 표현하면 기본값의 진실원이 한 곳에 남는다.

## Consequences/Tradeoffs

### Positive
- 설정 화면의 두 선택지가 조직별로 저장되고, 설정·구성원·회수 후보가 같은 값과 같은 판을 쓴다.
- 회수 기준·보존을 바꿔도 설치의 정책 적용 상태가 흔들리지 않는다.
- 기존 호출(`{expectedVersion, collectRawContent}`)과 기존 응답 필드는 그대로다.

### Negative
- 화면은 판 둘(manifest `version`, 설정 `settingsVersion`)을 다룬다. 설정만 바꿀 때도 manifest 판을 함께 보낸다.
- `PolicySaved`의 `collectRawContent`·`confirmedAt`은 원문 선택을 보내지 않은 저장에서 null일 수 있다(저장 전 조직).
- 회수 기준의 유효값은 enrollment-api가 모른다(기본값은 조회 서버의 설정이다). 저장 응답의 `reclaimIdleDays`는 조직이 저장한 값뿐이다.

## Follow-up
- 보존 기간 단축을 보존 작업 요청으로 잇고 `cleanupOperationId`를 채운다(ADR 0024를 개정하는 결정).
- 설정 화면의 두 선택지를 이 명령에 연결한다.
- 원문 보존 일수의 원천(아카이브 수명)이 생기면 조회에 싣는다.
