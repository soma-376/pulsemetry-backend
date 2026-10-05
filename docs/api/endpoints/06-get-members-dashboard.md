# 06 GET `/api/v1/organizations/{organizationId}/members/dashboard`

구성원 첫 화면

[전체 API](../README.md) · [구성원 조회·편집](../members.md)

<!-- endpoint: dashboard-api GET /api/v1/organizations/{organizationId}/members/dashboard -->

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
}
```

## Response

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

[MembersResponse 전체 스키마·중첩 타입](../reference/members.md#schema-MembersResponse)

오류·검증·부수 효과: [공통 규칙](../common.md)과 [기능 규칙·스키마](../reference/members.md)를 함께 적용한다.

### JSON 예시

[전체 응답 예시](../examples/members-response.example.json) — 가상 데이터이며 실서버 응답 캡처가 아니다.
