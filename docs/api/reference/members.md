# 구성원 조회·편집 — 기능 규칙·공유 스키마

[API 목록](../members.md) · [공통 규칙](../common.md) · [공통 스키마](../common-schemas.md)

### 구성원의 사용 예정 제품

구성원 항목의 `plannedVendorIds: string[]`는 초대 또는 구성원 편집에서 지정한 등록 제품 ID다(허브 ADR 0015). 빈 배열은 미지정이다.
`enrollment.members`의 값을 snapshot에 함께 복제해 구성원 version과 같은 시점으로 반환한다. 기존 snapshot의 기본값은 빈 배열이며 새 snapshot부터 저장된 선택을 포함한다.
선택만으로 실제 좌석 수·활성 사용자·회수 후보·청구액을 늘리지 않는다.


<a id="schema-MemberSaved"></a>

```ts
type MemberSaved = {
  memberId: string;
  plannedVendorIds: string[];
  team: { teamId: string; teamName: string } | null; // 열린 소속이 하나일 때만 값이 있다
  role: "owner" | "admin" | "member";
  status: "invited" | "active";
  version: number;
};
```

## 응답 스키마

타입 표기는 HTTP JSON의 필드·null 여부를 나타낸다. 아래에 정의되지 않은 공통·연관 타입은 이 문서 끝의 링크로 연결한다.

### Member

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-Member"></a>

```ts
type Member = {
  memberId: string;
  account: string;
  displayName: string;
  team: TeamRef;
  role: "admin" | "member";
  status: "invited" | "active" | "suspended";
  version: number;
  periodUsage: { equivalentCostUsd: Value<string>; sessionCount: Value<number> } | null;
  lastUsedAt: string | null;
  seatState: string;
  plannedVendorIds: Array<string>;
};
```

### MemberSummary

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-MemberSummary"></a>

```ts
type Value<T> = { value: T | null; status: "complete" | "partial" | "unavailable"; reasons: string[] };
type MembersMeta = { organizationId: string; asOf: string; startDate: string; endDate: string; snapshotId: string };
type MemberSummary = {
  rosterMembers: number;
  activeUsers: Value<number>;
  unassignedMembers: number;
  periodUnassignedEquivalentCostUsd: Value<string>;
  periodUnassignedCostShare: Value<number>; // 0~1 비율, 분모가 0이거나 확인 불가이면 null
  reclaimCandidates: Value<number>;
};
```

### MemberCapabilities

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-MemberCapabilities"></a>

```ts
type MemberCapabilities = {
  invite: boolean;
  assignTeam: boolean;
  reclaimSeats: boolean;
  restoreSeats: boolean;
};
```

### MembersResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-MembersResponse"></a>

```ts
type MembersResponse = {
  meta: MembersMeta;
  summary: MemberSummary;
  policy: IdlePolicy;
  capabilities: MemberCapabilities;
  members: Page<Member>;
  unassigned: Page<Member>;
  reclaimCandidates: Section<Page<ReclaimCandidate>>;
};
```

### MemberListResponse

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-MemberListResponse"></a>

```ts
type MemberListResponse = {
  meta: MembersMeta;
  members: Page<Member>;
};
```


<a id="schema-TeamAssignmentSaved"></a>

```ts
type TeamAssignmentSaved = {
  effectiveAt: string;
  members: Array<{ memberId: string; teamId: string | null; version: number }>;
};
```

조회 Member.role은 owner를 admin으로 표시한다. 저장 MemberSaved.role은 owner/admin/member 원래 값이다.
Member.team은 TeamRef 객체이고 미배정은 teamId=null이다. 저장 MemberSaved.team은 열린 소속이 하나가 아니면 객체 자체가 null이다.

편집 요청 예시 — 제품 전체 해제, 역할과 팀은 유지:

```json
{"expectedVersion":1791158400000,"plannedVendorIds":[]}
```

응답 예시:

```json
{"memberId":"20000000-0000-0000-0000-000000000001","team":null,"role":"member","status":"active","version":1791158400001,"plannedVendorIds":[]}
```


## 연관 스키마

- [IdlePolicy](seats.md#schema-IdlePolicy)
- [Page](../common-schemas.md#schema-Page)
- [ReclaimCandidate](seats.md#schema-ReclaimCandidate)
- [Section](../common-schemas.md#schema-Section)
- [TeamRef](../common-schemas.md#schema-TeamRef)

## 전체 JSON 응답 예시

[MembersResponse 예시](../examples/members-response.example.json)는 가상 데이터이며 현재 DTO의 전체 키를 포함한다. 실서버 응답 캡처나 운영 검증 결과가 아니다.

## 직렬화·검증 근거

- [ManagementStore.kt](../../../libs/enrollment-persistence/src/main/kotlin/com/team376/pulsemetry/persistence/enrollment/management/ManagementStore.kt)

## 화면 응답과 상태

명단·팀·좌석은 snapshot의 현재 기준 시각에 고정한다. 사용량은 프론트가 요청하는 최근 7일이며 비교는 사용하지 않는다. 사용량이 없어도 명단과 관리 기능을 숨기지 않는다. 첫 페이지 이후에는 같은 기간·조직·snapshot의 페이지를 끝까지 확인해 검색과 CSV를 만든다.

본문 응답에서 헤더의 수집 현황, 중복 asOf, 비교·가격 버전 메타, 구성원별 토큰 상세·활성 사용자 수, 사용하지 않는 좌석 집계를 제거했다. 비용 비중은 서버가 같은 snapshot의 미배분 비용과 전체 비용으로 계산한다. 현재 팀 미배정 인원과 이벤트 당시 미배분 비용은 다른 범위다.

`Value`는 개요와 같은 의미이며 0과 확인 불가를 구분한다. 미완료 기간·식별 누락·가격 누락 사유를 보존하고, 회수 후보 수는 후보 목록의 판정 가용성과 같은 사유를 갖는다. 관측이 부족한 좌석을 후보 0으로 확정하지 않는다. 본문 조회 실패는 HTTP 오류이고 초대·팀 목록·좌석 상세의 독립 조회와 쓰기 경로는 유지한다.

CSV는 명단에 보이는 비용·세션·마지막 사용에 비용/세션 상태와 사유를 함께 내보낸다. 미확정 값은 빈 칸이다. 기존 응답과 호환되지 않으므로 프론트와 백엔드를 함께 적용한다.
