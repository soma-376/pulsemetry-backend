# 0036. 구성원 역할은 admin과 member 사이에서만 바꾸고 owner와 자기 역할은 바꾸지 않는다

## Status

Accepted

## Context

구성원의 역할을 바꾸는 운영 경로가 없다. `enrollment.members.role`은 초대 때 정해지고 그 뒤에는 DB를 직접 고쳐야 바뀐다.
`ManagementStore`의 관리 명령 가운데 구성원을 다루는 것은 팀 배정(`POST /member-team-assignments`)뿐이고 역할 컬럼을 쓰지 않는다.

이미 정해진 제약은 다음과 같다.

- 역할 어휘는 native enum `enrollment.member_role`의 `owner`·`admin`·`member` 셋이다(ADR 0009).
- 대시보드와 관리 명령은 `owner`·`admin`만 쓸 수 있고, 새 사용자 역할은 `member`·`admin` 범위다(ADR 0026).
  초대 명령도 `admin`·`member`가 아닌 역할을 `role_not_assignable`로 거절한다.
- 사용자 AT는 역할을 클레임으로 싣는다. `UserAccessVerifier.verify`는 세션과 구성원을 다시 읽고
  저장된 역할이 클레임과 다르면 인증을 거부한다. RT 갱신(`UserAuthService.rotate`)은 그 시점의 저장된 역할로 새 AT를 만든다(ADR 0018).
- 구성원의 버전은 `members.updated_at`의 밀리초다. dashboard-api의 구성원 목록이 같은 값을 `version`으로 낸다.
  팀 소속은 `team_memberships`의 구간 이력이고 변경은 서버 시점의 새 구간을 더한다(ADR 0026).

정하지 않으면 생기는 일: 역할 변경을 아무 규칙 없이 열면 관리자가 자기 권한을 없애거나 조직의 owner를 강등해
관리 명령을 실행할 사람이 없는 조직이 생길 수 있다. 어휘에 없는 역할을 받으면 인가 판정(`owner`·`admin` 여부)과 어긋난 값이 저장된다.

## Decision

- 역할 변경은 `PATCH /api/v1/organizations/{organizationId}/members/{memberId}`가 한다. 팀 변경과 같은 명령이다.
  보내지 않은 필드는 바꾸지 않는다. 팀과 역할을 함께 보내면 한 트랜잭션에서 저장하고 버전은 한 번만 올린다.
- 허용하는 전이는 `admin → member`와 `member → admin`뿐이다. 그 밖의 값은 422 `role_not_assignable`이다.
- `owner`의 역할은 이 명령으로 바꾸지 않는다. 422 `owner_role_immutable`이다. `owner`로 올리는 전이도 없다.
- 호출자는 자기 역할을 바꾸지 못한다. 422 `self_role_change`다.
- 요청의 역할이 저장된 역할과 같으면 역할 변경이 아니다. 위 세 규칙을 적용하지 않는다.
- `invited` 구성원은 편집할 수 있다. 초대를 수락하면 그때 저장된 팀과 역할로 시작한다.
  `suspended` 구성원은 편집하지 않는다. 409 `member_suspended`다.
- `owner`와 자기 자신도 팀은 바꿀 수 있다. 제한은 역할에만 건다.
- 역할이 바뀐 구성원의 기존 AT는 다음 검증에서 거부된다(401). 클라이언트는 RT로 갱신하고, 갱신한 AT는 새 역할을 싣는다.
  세션을 폐기하지 않는다. 역할 변경을 위해 토큰이나 세션 테이블에 따로 쓰지 않는다.
- 마지막 관리자를 따로 세지 않는다. 호출자는 `owner` 또는 `admin`이어야 하고 자기 역할을 바꿀 수 없으므로,
  명령이 성공한 뒤에도 호출자가 관리자로 남는다.
- 역할 어휘를 늘리지 않는다. `enrollment.member_role`은 그대로다.

## Alternatives Considered

### A. 역할 전용 엔드포인트를 따로 둔다
- 장점: 팀 배정 명령과 책임이 나뉜다.
- 단점: 화면의 편집기는 팀과 역할을 한 번에 저장한다. 두 요청으로 나누면 한쪽만 성공한 상태가 생기고 버전이 두 번 오른다.
- 탈락 이유: 같은 구성원 행의 같은 버전을 다루는 변경을 두 트랜잭션으로 쪼갤 이유가 없다.

### B. 역할이 바뀌면 그 구성원의 세션을 전부 폐기한다
- 장점: 낡은 권한의 토큰이 남지 않는다는 점이 눈에 보인다.
- 단점: 승격된 사람도 다시 로그인해야 한다. 기존 검증이 이미 클레임과 저장 역할의 불일치를 거부한다.
- 탈락 이유: `UserAccessVerifier`가 같은 효과를 요청마다 내고 있다. 폐기는 사용자 경험만 나빠진다.

### C. `owner` 이전과 자기 강등을 허용하고 마지막 관리자만 막는다
- 장점: 소유권 이전을 API로 할 수 있다.
- 단점: 조직에 `owner`가 몇 명인지, 0명이 될 수 있는지에 대한 규칙이 저장소에 없다. 동시 요청에서 마지막 관리자 검사는 조직 단위 잠금에 기대야 한다.
- 탈락 이유: 소유권 이전은 별도의 결정이 필요하다. 지금 필요한 것은 편집기의 `admin`·`member` 전환이다.

### D. 화면의 `lead`·`viewer`를 역할로 더한다
- 장점: 화면의 선택지를 그대로 저장할 수 있다.
- 단점: 조회 범위와 인가 정책 전체가 바뀐다. 허브 PRD는 팀장 권한 분리를 Non-goal로 둔다.
- 탈락 이유: enum 추가로 끝나는 변경이 아니다. 화면이 서버 어휘를 따른다.

## Consequences/Tradeoffs

### Positive
- 편집기의 저장이 한 요청, 한 버전 증가로 끝난다. 오래된 버전은 409로 거절된다.
- 관리 명령을 실행할 수 있는 사람이 없는 조직이 이 명령으로는 생기지 않는다.
- 권한 변경이 다음 요청부터 반영된다. 새 저장 구조가 필요 없다.

### Negative
- 소유권 이전과 `owner` 강등은 여전히 API로 할 수 없다.
- 역할이 바뀐 사람의 화면은 한 번 401을 받고 토큰을 갱신해야 한다. 갱신을 직렬화하지 않는 클라이언트는 RT 재사용으로 세션을 잃을 수 있다(ADR 0018).
- dashboard-api의 구성원 목록은 `owner`와 `admin`을 같은 화면 역할로 낸다. 화면은 바꾸지 않은 필드를 보내지 않아야 `owner`의 팀만 고칠 수 있다.

## Follow-up

- 소유권 이전이 필요해지면 `owner`의 수와 이전 절차를 정하는 ADR을 따로 쓴다.
- 역할 변경의 감사 기록은 이 결정에 없다. 변경 이력 조회가 요구되면 저장 구조와 함께 정한다.

## Acceptance Criteria

`MemberEditApiTest`가 팀만·역할만·둘 다 변경, 같은 값 재전송, 오래된 버전 409, 초대 대기자 편집,
`owner`·자기·정지 구성원·어휘 밖 역할 거부, 다른 조직 404, 일반 구성원 403, 역할 변경 뒤 기존 AT 401과 갱신 뒤 판정을 검증한다.

## References

- `libs/enrollment-persistence` `ManagementStore.editMember`, `libs/security` `UserAccessVerifier`·`UserAuthService.rotate`
- [Enrollment 서버 명세](../enrollment-server-spec.md) §12
- ADR 0009, ADR 0018, ADR 0026
