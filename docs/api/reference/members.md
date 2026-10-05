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
  periodUsage: Usage | null;
  lastUsedAt: string | null;
  observation: "complete" | "partial" | "unobserved";
  seatState: string;
  plannedVendorIds: Array<string>;
};
```

### MemberSummary

[DTO 근거](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

<a id="schema-MemberSummary"></a>

```ts
type MemberSummary = {
  rosterMembers: number;
  activeUsers: number | null;
  unassignedMembers: number;
  periodUnassignedEquivalentCostUsd: string | null;
  periodTotalEquivalentCostUsd: string | null;
  seats: Section<SeatSummary>;
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
  meta: AnalyticsMeta;
  asOf: string;
  ingest: Ingest;
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
  meta: AnalyticsMeta;
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

- [AnalyticsMeta](../common-schemas.md#schema-AnalyticsMeta)
- [IdlePolicy](seats.md#schema-IdlePolicy)
- [Ingest](../common-schemas.md#schema-Ingest)
- [Page](../common-schemas.md#schema-Page)
- [ReclaimCandidate](seats.md#schema-ReclaimCandidate)
- [SeatSummary](seats.md#schema-SeatSummary)
- [Section](../common-schemas.md#schema-Section)
- [TeamRef](../common-schemas.md#schema-TeamRef)
- [Usage](../common-schemas.md#schema-Usage)

## 전체 JSON 응답 예시

[MembersResponse 예시](../examples/members-response.example.json)는 가상 데이터이며 현재 DTO의 전체 키를 포함한다. 실서버 응답 캡처나 운영 검증 결과가 아니다.

## 직렬화·검증 근거

- [ManagementStore.kt](../../../libs/enrollment-persistence/src/main/kotlin/com/team376/pulsemetry/persistence/enrollment/management/ManagementStore.kt)
