# 07 GET `/api/v1/organizations/{organizationId}/members`

구성원 목록

[전체 API](../README.md) · [구성원 조회·편집](../members.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/members -->

서버: **dashboard-api** · 성공: **200** · [구현](../../../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/api/MembersController.kt)

## Request

### Path

```ts
{
  organizationId: string;
}
```

### Headers

```http
Authorization: Bearer <Pulsemetry access_token>
```

권한: owner/admin, 조직 범위 검사. [서버별 인증·오류 차이](../common.md)를 따른다.

### Query

```ts
{
  startDate: string; // 필수 YYYY-MM-DD
  endDate: string; // 필수, 종료일 포함, 1~366일
  timeZone?: "Asia/Seoul"; // 기본 Asia/Seoul
  q?: string; // 최대 200자, 계정·이름·현재 팀 이름 포함 검색(대소문자 무시)
  memberId?: string; // 선택 시 UUID 정확히 일치하는 구성원만
  sort?: "account" | "team" | "cost" | "activity"; // 기본 account, activity는 lastUsedAt
  order?: "asc" | "desc"; // 기본 asc
  status?: "all" | "active" | "candidate" | "idle" | "unobserved"; // 기본 all, 계정 상태가 아닌 사용 관측 상태
  limit?: number; // 기본 20, 정수 1~100
  offset?: number; // 기본 0, 0 이상 정수, 다음 응답 nextOffset으로 이어 읽기
  snapshotId?: string; // offset > 0이면 필수, 이전 응답 값 유지
}
```

## Response

```ts
// MemberListResponse
{
  meta: MembersMeta;
  members: OffsetPage<Member>;
}
```

[MemberListResponse 전체 스키마·중첩 타입](../reference/members.md#schema-MemberListResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/members.md)를 함께 적용한다.
