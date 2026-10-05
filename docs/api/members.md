# 구성원 조회·편집

[API 길잡이](README.md) · [공통 규칙](common.md) · [공통 스키마](common-schemas.md)

## 엔드포인트

<a id="endpoint-dashboard-api-6"></a>

### 구성원 첫 화면

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/members/dashboard -->

```http
GET /api/v1/organizations/{organizationId}/members/dashboard
```

서버: **dashboard-api** · 성공: **200** · [구현](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/MembersController.kt)

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
  startDate: string; // 필수 YYYY-MM-DD
  endDate: string; // 필수, 종료일 포함, 1~366일
  timeZone?: "Asia/Seoul"; // 기본 Asia/Seoul
}
```

**Body**

본문 없음.

**Response**

```ts
// MembersResponse
{
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

[MembersResponse 전체 스키마·중첩 타입](members.md#schema-MembersResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-dashboard-api-7"></a>

### 구성원 목록

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/members -->

```http
GET /api/v1/organizations/{organizationId}/members
```

서버: **dashboard-api** · 성공: **200** · [구현](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/MembersController.kt)

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
  startDate: string; // 필수 YYYY-MM-DD
  endDate: string; // 필수, 종료일 포함, 1~366일
  timeZone?: "Asia/Seoul"; // 기본 Asia/Seoul
  q?: string; // 최대 200자
  limit?: number; // 기본 20, 정수 1~100
  cursor?: string; // 응답 nextCursor
  snapshotId?: string; // 다음 페이지에 이전 응답 값 유지
}
```

**Body**

본문 없음.

**Response**

```ts
// MemberListResponse
{
  meta: AnalyticsMeta;
  members: Page<Member>;
}
```

[MemberListResponse 전체 스키마·중첩 타입](members.md#schema-MemberListResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-dashboard-api-8"></a>

### 미배정 구성원

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/members/unassigned -->

```http
GET /api/v1/organizations/{organizationId}/members/unassigned
```

서버: **dashboard-api** · 성공: **200** · [구현](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/MembersController.kt)

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
  startDate: string; // 필수 YYYY-MM-DD
  endDate: string; // 필수, 종료일 포함, 1~366일
  timeZone?: "Asia/Seoul"; // 기본 Asia/Seoul
  limit?: number; // 기본 20, 정수 1~100
  cursor?: string; // 응답 nextCursor
  snapshotId?: string; // 다음 페이지에 이전 응답 값 유지
}
```

**Body**

본문 없음.

**Response**

```ts
// MemberListResponse
{
  meta: AnalyticsMeta;
  members: Page<Member>;
}
```

[MemberListResponse 전체 스키마·중첩 타입](members.md#schema-MemberListResponse)

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-25"></a>

### 팀 일괄 배정

<!-- endpoint: enrollment-api POST /api/v1/organizations/{organizationId}/member-team-assignments -->

```http
POST /api/v1/organizations/{organizationId}/member-team-assignments
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

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
  assignments: Array<{ memberId: string; teamId: string | null; expectedVersion: number }>; // 1~100명
}
```

**Response**

```ts
// TeamAssignmentSaved
{
  effectiveAt: string;
  members: Array<{ memberId: string; teamId: string | null; version: number }>;
}
```

[TeamAssignmentSaved 전체 스키마·중첩 타입](members.md#schema-TeamAssignmentSaved)

전체 검증 후 원자적으로 적용한다.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.

<a id="endpoint-enrollment-api-26"></a>

### 구성원 편집

<!-- endpoint: enrollment-api PATCH /api/v1/organizations/{organizationId}/members/{memberId} -->

```http
PATCH /api/v1/organizations/{organizationId}/members/{memberId}
```

서버: **enrollment-api** · 성공: **200** · [구현](../../apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/management/ManagementController.kt)

**Path**

```ts
{
  organizationId: string;
  memberId: string;
}
```

**Headers**

```http
Authorization: Bearer <Pulsemetry access_token>
Content-Type: application/json
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](common.md)를 따른다.

**Query**

없음.

**Body**

```ts
{
  expectedVersion: number;
  teamId?: string | null; // 생략 유지, null 해제
  role?: "admin" | "member";
  plannedVendorIds?: string[]; // 생략 유지, [] 전체 해제
}
```

**Response**

```ts
// MemberSaved
{
  memberId: string;
  plannedVendorIds: string[];
  team: { teamId: string; teamName: string } | null;
  role: "owner" | "admin" | "member";
  status: "invited" | "active";
  version: number;
}
```

[MemberSaved 전체 스키마·중첩 타입](members.md#schema-MemberSaved)

선택 필드 중 하나 이상 필요. 자기 역할·owner 역할 변경 제한은 아래 동작 규칙 참고.

오류·검증·부수 효과: [공통 규칙](common.md)과 아래 기능 규칙을 함께 적용한다.


팀 배정은 최대 100명, 전체 검증 후 한 트랜잭션으로 적용한다. `teamId:null`은 배정 해제다.
효력 시각은 서버 시각이며 과거 ClickHouse 팩트는 바꾸지 않는다.
수정 version은 직전 조회 응답 값을 그대로 보낸다. 불일치는 409 `version_conflict`다.
version을 1부터 시작하는 순번이나 날짜로 해석하지 않는다. PUT/PATCH는 expectedVersion, DELETE는 If-Match로 전달한다.

사용 예정 제품은 `plannedVendorIds`(등록 제품 ID 배열, 0~100개, 중복 불가)로 지정한다.
ID는 영숫자·하이픈·밑줄 1~100자다. 배열이 아니거나 중복·개수·형식 오류이면 400 `invalid_request`다.
같은 조직의 보관되지 않은 등록 제품만 새로 선택할 수 있다. 일괄 초대는 해당 행을 `rejected / vendor_not_found`, 구성원 PATCH는 422 `vendor_not_found`로 거절한다.
초대 시 생략하면 빈 배열이다. 이미 초대된 사람·기존 회원에게 중복 초대를 보내도 선택을 덮어쓰지 않는다. 취소 후 다시 초대하면 새 요청의 선택을 적용한다.
PATCH의 생략은 유지, 빈 배열은 전체 해제다. 기존 보관 제품은 유지·제거할 수 있다. 순서만 바뀌면 version은 오르지 않는다.
초대 목록·MemberSaved의 `plannedVendorIds: string[]`는 구성원에 저장된 현재 값을 반환한다. dashboard 구성원 조회는 snapshot 시점의 값을 반환한다.
제품 선택은 실제 좌석 배정·벤더 계정 생성·SSO 또는 수집 권한 변경이 아니다. 좌석 수·회수 후보·청구 지표에 합산하지 않는다(허브 ADR 0015).

구성원 편집(`PATCH /members/{memberId}`)은 한 사람의 팀과 역할을 한 트랜잭션에서 저장한다(ADR 0036).
`expectedVersion`은 필수다. `teamId`·`role`·`plannedVendorIds`는 보낸 것만 바꾸며 셋 다 없으면 400 `invalid_request`다.
`teamId:null`은 미배정이고, 값이 있으면 같은 조직의 활성 팀이어야 한다(아니면 404 `not_found`).
역할은 `admin`과 `member` 사이에서만 바꾼다. 그 밖의 값은 422 `role_not_assignable`,
`owner`의 역할 변경은 422 `owner_role_immutable`, 자기 역할 변경은 422 `self_role_change`다.
요청의 역할이 현재 역할과 같으면 역할 변경이 아니므로 이 규칙들을 적용하지 않는다. `owner`와 자기 자신도 팀은 바꿀 수 있다.
초대 대기(`invited`) 구성원도 편집한다. 정지(`suspended`) 구성원은 409 `member_suspended`다.
팀이 실제로 바뀔 때만 소속 구간을 닫고 새 구간을 더한다. 팀과 역할을 함께 바꿔도 version은 한 번만 오르고,
아무것도 바뀌지 않으면 version도 그대로다. 응답의 `version`은 dashboard-api 구성원 목록의 `version`과 같은 값이다.
역할이 바뀐 구성원의 기존 AT는 다음 요청에서 401이 된다. RT로 갱신하면 새 역할의 AT를 받는다.
`role`은 저장된 값(`owner`·`admin`·`member`)이다. dashboard-api의 구성원 목록은 `owner`도 `admin`으로 표시하므로,
화면은 바꾸지 않은 필드를 보내지 않는다.


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

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

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

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

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

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

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

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

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

[DTO 근거](../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)

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

- [AnalyticsMeta](common-schemas.md#schema-AnalyticsMeta)
- [IdlePolicy](seats.md#schema-IdlePolicy)
- [Ingest](common-schemas.md#schema-Ingest)
- [Page](common-schemas.md#schema-Page)
- [ReclaimCandidate](seats.md#schema-ReclaimCandidate)
- [SeatSummary](seats.md#schema-SeatSummary)
- [Section](common-schemas.md#schema-Section)
- [TeamRef](common-schemas.md#schema-TeamRef)
- [Usage](common-schemas.md#schema-Usage)

## 전체 JSON 응답 예시

[MembersResponse 예시](examples/members-response.example.json)는 가상 데이터이며 현재 DTO의 전체 키를 포함한다. 실서버 응답 캡처나 운영 검증 결과가 아니다.

## 직렬화·검증 근거

- [ManagementStore.kt](../../libs/enrollment-persistence/src/main/kotlin/com/team376/pulsemetry/persistence/enrollment/management/ManagementStore.kt)
