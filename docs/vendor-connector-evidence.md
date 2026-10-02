# 벤더 좌석·청구 API 근거

확인일: 2026-09-30(OpenAI 절은 2026-10-03 재확인). 카탈로그의 등록 제품에 대해 좌석 조회, 좌석 회수, 좌석 복원, 실제 청구액 조회를
공식 문서로 확인한 결과를 기록한다. 커넥터는 이 문서에 근거가 있는 벤더·기능만 구현한다.
GitHub Copilot(`copilot`)·Gemini Code Assist(`gemini`)는 연동 대상이 아니다(ADR 0054) — 커넥터를 두지 않고 이 문서의 조사 대상에서 뺐다.
두 제품의 카탈로그 플랜은 커넥터가 없는 플랜이라 수동 원천이다.

가격·플랜·API는 바뀔 수 있다. 커넥터를 고치기 전에 해당 절의 문서를 다시 읽고 확인일을 갱신한다.
제3자 글과 비공식 SDK는 근거로 쓰지 않았다. 읽지 못한 문서는 읽은 것으로 적지 않았다.

## 판정 값

| 값 | 뜻 |
|---|---|
| `지원` | 공식 문서에 엔드포인트와 요청·응답이 있다. 아래 절에 예시를 옮겼다 |
| `플랜 제한` | 문서가 특정 플랜에만 제공한다고 적었다 |
| `공개 API 없음` | 문서가 관리 화면 절차만 안내하거나, 해당 조직 유형을 API 대상에서 제외한다 |
| `확인 불가` | 문서를 읽지 못했거나 서술로 판정할 수 없다. 구현 근거로 쓰지 않는다 |
| `조사하지 않음` | 연동 대상이 아니라 문서를 확인하지 않았다(ADR 0054). 구현 근거로 쓰지 않는다 |

인용한 영문은 확인일에 문서에서 읽은 문장이다. 엔드포인트 예시는 문서의 예시를 옮긴 것이며 실계정 응답이 아니다.
실계정 검증은 하지 않았다.

## 제품·플랜 × 기능

| 제품 ID · 플랜 | 좌석 조회 | 좌석 회수 | 좌석 복원 | 실제 청구액 |
|---|---|---|---|---|
| `claude_team` · `team` | 공개 API 없음 | 공개 API 없음 | 공개 API 없음 | 공개 API 없음 |
| `claude_team` · `enterprise` | 지원 | 지원 | 지원(초대 수락 필요) | 플랜 제한(사용량 기반 Enterprise의 사용 비용) |
| `openai_biz` · `business` | 확인 불가 | 확인 불가 | 확인 불가 | 확인 불가 |
| `openai_biz` · `enterprise` | 확인 불가 | 확인 불가 | 확인 불가 | 확인 불가 |
| `cursor` · `cursor_teams` | 플랜 제한 | 플랜 제한 | 공개 API 없음 | 플랜 제한 |
| `cursor` · `cursor_enterprise` | 지원 | 지원 | 공개 API 없음 | 지원(사용 지출. 좌석 구독료 아님) |
| `copilot` · 전 플랜 | 조사하지 않음(ADR 0054) | 조사하지 않음 | 조사하지 않음 | 조사하지 않음 |
| `gemini` · 전 플랜 | 조사하지 않음(ADR 0054) | 조사하지 않음 | 조사하지 않음 | 조사하지 않음 |
| `other` · 전 플랜 | 해당 없음 | 해당 없음 | 해당 없음 | 해당 없음 |

`other`는 특정 공급사가 아니라 조직이 직접 입력하는 계약이다. 공급사 API가 정의상 없다.

## 1. Claude (`claude_team`)

Anthropic의 관리 API는 조직 유형에 따라 쓸 수 있는 범위가 다르다. 문서는 Claude Console과 Claude Enterprise
두 유형만 키 발급 대상으로 든다.

### 1.1 Team 플랜 — 공개 API 없음

- [Team 좌석 관리](https://support.claude.com/en/articles/12004354-purchase-and-manage-seats-on-team-plans)는
  좌석 구매·등급 변경을 관리 화면 절차로만 안내한다. "Navigate to **Organization settings > Organization and access**. Click 'Manage' under 'Total seats.'"
  이 문서에 API 언급은 없다. JIT/SCIM으로 들어온 사용자의 좌석 배정만 적는다:
  "**Users provisioned via JIT or SCIM** are automatically assigned to the highest-available seat type when they're added."
- 좌석 수 축소의 효력: "Reducing your organization's seat count takes effect at your next renewal (on the annual renewal date for annual plans). There is no prorated refund."
- [Admin API 키 만들기](https://platform.claude.com/docs/en/manage-claude/admin-api-keys)의 "Which key do you need?" 표에는
  **Claude Console**과 **Claude Enterprise** 두 행뿐이다. [사용자 관리](https://platform.claude.com/docs/en/manage-claude/user-management)도
  "This page covers managing the people in your **Claude Enterprise** (claude.ai) organization programmatically"라고 대상을 한정한다.
- 판정: Team 플랜의 좌석 조회·회수·복원·청구는 관리 화면에서 한다. 커넥터 대상이 아니다.

### 1.2 Enterprise 플랜

인증과 공통 규칙([Admin API 키 만들기](https://platform.claude.com/docs/en/manage-claude/admin-api-keys),
[사용자 관리](https://platform.claude.com/docs/en/manage-claude/user-management)):

- 키: claude.ai > Organization settings > API에서 만든다. 접두사 `sk-ant-api01-...`. 만들 수 있는 사람은 "The parent organization's **primary owner**".
  `x-api-key` 헤더와 `anthropic-version` 헤더를 매 요청에 보낸다.
- 범위(scope)는 생성 시 고정이다. 구성원·초대 조회 `read:members`, 구성원 제거·초대 생성·회수 `write:members`, 분석·비용 `read:analytics`.
  범위를 넘는 호출은 "`403 Forbidden` with a message listing the scopes the key has and the scopes the endpoint needs."
- 한도: "Admin API endpoints share a per-organization limit of **100 requests per minute**; invite creation has its own limit of **1,200 requests per hour** instead. Requests over a limit return **429 Too Many Requests**."
- 페이지: "Member and invite lists use ID-based pagination: pass `limit` (default 20, max 1000) plus at most one of `before_id` or `after_id`, and page using the `first_id` and `last_id` fields of each response until `has_more` is `false`."

#### 좌석 조회 — 지원

`GET /v1/organizations/users`. `email` 필터를 받는다. 응답에 **좌석 등급과 마지막 활동 시각은 없다.**

```bash
curl "https://api.anthropic.com/v1/organizations/users?limit=20" \
  -H "x-api-key: $ANTHROPIC_ADMIN_KEY" \
  -H "anthropic-version: 2023-06-01"
```

```json
{
  "data": [
    {
      "type": "user",
      "id": "user_01AbCdEfGhIjKlMnOpQrSt",
      "email": "jane@example.com",
      "name": "Jane Smith",
      "role": "user",
      "added_at": "2026-06-12T09:14:03Z"
    }
  ],
  "has_more": false,
  "first_id": "user_01AbCdEfGhIjKlMnOpQrSt",
  "last_id": "user_01AbCdEfGhIjKlMnOpQrSt"
}
```

역할 값은 `user`·`managed`·`owner`·`membership_admin`·`primary_owner`다.

2026-10-01 재확인([사용자 관리](https://platform.claude.com/docs/en/manage-claude/user-management)):

- 목록 순서: "`GET /v1/organizations/users` returns the organization's members, most recently added first."
- 대기 중 초대도 좌석을 잡는다: "If your organization's plan draws members from a finite pool of purchased seats, a pending invite consumes a seat."
  초대 목록은 `GET /v1/organizations/invites`(`read:members`)이고 "returns the organization's invites, most recent first, across the `pending`, `accepted`, and `expired` states; there is no status filter."
  구성원과 같은 ID 기반 페이지다("Member and invite lists use ID-based pagination").
- 구성원 응답에는 좌석을 차지하는지가 따로 없다("returning any purchased seat they occupied" — 좌석 없는 구성원이 있을 수 있다). 커넥터는 구성원을 좌석 보유로 기록한다.
  조직 요약(`analytics/summaries`의 `assigned_seat_count`)과의 대조는 실계정 검증 절차에 둔다.
- 한도 초과는 429다. `Retry-After` 헤더는 이 문서에 없다 — 커넥터는 헤더가 있으면 따르고 없으면 설정한 재시도 간격을 쓴다.

활동 여부는 별도의 [Claude Enterprise Analytics API](https://platform.claude.com/docs/en/manage-claude/analytics-api)로 본다(`read:analytics`).

- `GET /v1/organizations/analytics/users`: 하루 단위 사용자별 활동. 행의 `actor`에 `email`·`user_id`·`deleted`가 있다.
- `GET /v1/organizations/analytics/summaries`: 조직 요약. `assigned_seat_count`·`pending_invite_count`·활성 사용자 수.
- 신선도: "Data for a given day is typically available by about 13:00–13:30 UTC the following day (a 1-day lag)". 2026-01-01 이전 날짜는 없다.
- 한도: "a default of 60 requests per minute across all endpoints in this API."
- 활성 정의: "A user counts as active for a day if any of the following is true: they sent at least one chat message in Claude, they had at least one Claude Code session … or they had at least one Cowork session with tool use or message activity."

사용자별 "마지막 활동 시각" 필드는 확인하지 못했다. 활동 API는 날짜별 집계다.

#### 좌석 회수 — 지원

`DELETE /v1/organizations/users/{user_id}` (`write:members`).
"removes the member from the organization, returning any purchased seat they occupied to the organization's pool."

```bash
curl -X DELETE "https://api.anthropic.com/v1/organizations/users/user_01AbCdEfGhIjKlMnOpQrSt" \
  -H "x-api-key: $ANTHROPIC_ADMIN_KEY" \
  -H "anthropic-version: 2023-06-01"
```

```json
{
  "type": "user_deleted",
  "id": "user_01AbCdEfGhIjKlMnOpQrSt"
}
```

제한:

- "Members holding an administrative role cannot be removed through this endpoint, and if your identity provider manages membership (SCIM), removals return 400."
- 회수는 조직에서 구성원을 **제거**하는 것이다. 좌석만 떼고 구성원으로 남기는 엔드포인트는 문서에 없다.

#### 좌석 복원 — 지원(초대 수락 필요)

제거한 구성원을 되돌리는 전용 엔드포인트는 없다. 다시 초대한다: `POST /v1/organizations/invites` (`write:members`).

```bash
curl -X POST "https://api.anthropic.com/v1/organizations/invites" \
  -H "content-type: application/json" \
  -H "x-api-key: $ANTHROPIC_ADMIN_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -d '{
    "email": "newhire@example.com",
    "role": "managed",
    "rbac_group_ids": ["rbac_group_01UvWxYzAbCdEfGhIjKlMn"]
  }'
```

```json
{
  "type": "invite",
  "id": "invite_01QrStUvWxYzAbCdEfGhIj",
  "email": "newhire@example.com",
  "role": "managed",
  "invited_at": "2026-07-06T16:20:11Z",
  "expires_at": "2026-07-27T16:20:11Z",
  "accepted_at": null,
  "status": "pending",
  "rbac_group_ids": ["rbac_group_01UvWxYzAbCdEfGhIjKlMn"]
}
```

조건:

- 좌석 등급을 고를 수 없다. "the invite automatically takes a seat from the lowest tier that has availability; the API does not take a tier parameter. If no seat is free, the request fails with a 400 error rather than purchasing a seat."
- "Organizations whose identity provider provisions users automatically (JIT or SCIM) cannot create invites through the API."
- 초대는 상대가 수락해야 끝난다. 상태는 `pending` → `accepted` 또는 `expired`다. 요청만으로 복원 완료가 아니다.
- 대기 중인 초대도 좌석을 차지한다. "a `pending` invite holds a seat."

#### 실제 청구액 — 플랜 제한

`GET /v1/organizations/analytics/cost_report` (`read:analytics`). 금액은 "Amount (post-discount, pre-credit) in fractional cents", 통화는 "Currently always `"USD"`".

```bash
curl https://api.anthropic.com/v1/organizations/analytics/cost_report \
    -H 'anthropic-version: 2023-06-01' \
    -H "X-Api-Key: $ANTHROPIC_API_KEY"
```

```json
{
  "data": [
    {
      "ending_at": "2019-12-27T18:11:19.117Z",
      "results": [
        {
          "amount": "amount",
          "cost_type": "code_execution",
          "currency": "USD",
          "list_amount": "list_amount",
          "model": "claude-opus-5",
          "product": "chat",
          "requests": 0
        }
      ],
      "starting_at": "2019-12-27T18:11:19.117Z"
    }
  ],
  "data_refreshed_at": "2019-12-27T18:11:19.117Z",
  "has_more": true,
  "next_page": "next_page",
  "organization_id": "org_013FP9SaFPBg7Kw7fetjn6cF"
}
```

(문서 예시의 `results` 항목에는 `claude_tag_category`·`context_window`·`inference_geo`·`rbac_group_id`·`slack_channel_id`·`speed`·`token_type` 등 분류 필드가 더 있다.)

- 매개변수: `starting_at`(필수, RFC 3339), `ending_at`(최대 31일 범위), `bucket_width`(`1d`·`1h`·`1m`), `group_by[]`, `products[]`, `limit`, `page`.
- 단위: "Currency amounts are returned as decimal strings such as `"41280.000000"` (which represents $412.80)."
- **대상 제한**: "The cost and usage endpoints apply to usage-based Enterprise plans; for seat-based Enterprise plans, they reflect usage credits only."
  좌석 구독료는 이 보고서에 없다.
- 확정 시점: "Values for a given date can be revised for up to 30 days as late events arrive and reconciliation runs. For invoicing-grade totals, query dates at least 30 days in the past."
- 페이지 커서는 질의에 묶인다. 매개변수를 바꾸고 옛 커서를 보내면 400이다.

Claude Console(API 플랫폼) 조직의 비용은 다른 API다: `GET /v1/organizations/cost_report`
([Usage and Cost API](https://platform.claude.com/docs/en/manage-claude/usage-cost-api), Admin API 키 `sk-ant-admin01-...`).
"All costs in USD, reported as decimal strings in lowest units (cents)". 이 조직은 claude.ai 좌석 계약과 별개이므로
`claude_team` 등록 제품의 청구액으로 쓰지 않는다.

## 2. OpenAI (`openai_biz`)

### 2.1 ChatGPT Business·Enterprise 좌석 — 확인 불가

등록 제품의 좌석은 ChatGPT 워크스페이스의 좌석이다. 공개된 관리자 문서는 절차만 적고 API의 엔드포인트·스키마는
인증이 필요한 reference로 넘긴다.

읽은 것:

- [사용자 생애 주기 관리](https://learn.chatgpt.com/docs/enterprise/user-lifecycle.md): 구성원은 수동 초대, 자동 계정 생성, SCIM 디렉터리 동기화로 들어온다.
  SCIM은 "ChatGPT Enterprise, Edu, and Healthcare"에 해당한다. 수동으로 추가한 구성원의 제거는
  "Have a workspace owner or admin remove the member from Workspace settings > Members"다.
  제거가 좌석·청구에 미치는 영향과 복원 절차는 이 문서에 없다.
- [그룹과 프로비저닝](https://learn.chatgpt.com/docs/enterprise/groups-and-provisioning.md): "SCIM provisions workspace membership and group assignments."
  "If you remove the member only from the workspace, a later sync can restore access". SCIM의 주소·속성은 도움말로 넘긴다.
- [Analytics API](https://learn.chatgpt.com/docs/enterprise/analytics-api.md): 워크스페이스 범위 Admin 키와 `enterprise.analytics.read` scope가 필요하다고만 적는다.
  엔드포인트·매개변수·응답은 "Admin API reference"를 보라고 한다. 그 reference는 읽지 못했다.
- [Administration 개요](https://developers.openai.com/api/reference/administration/overview): API 플랫폼 조직의 "users, invites, projects, API keys, and audit logs"를 관리한다.
  "Admin API keys cannot be used for non-administration endpoints." ChatGPT 워크스페이스나 좌석에 대한 서술은 없다.

읽지 못한 것:

- `https://help.openai.com/en/articles/8792536-managing-billing-and-seats-in-chatgpt-business` → HTTP 403.
- `https://platform.openai.com/docs/api-reference/administration` → HTTP 403.
- ChatGPT 워크스페이스의 Admin API reference(인증 필요).

판정: 워크스페이스 구성원을 나열·제거·재초대하는 엔드포인트를 문서로 확인하지 못했다. 좌석 조회·회수·복원은 `확인 불가`다.
커넥터를 만들지 않는다. Business 플랜은 SCIM 대상 목록에도 없다.

#### 재확인 (2026-10-03)

조사 범위는 OpenAI 공식 문서 도메인(`developers.openai.com`·`platform.openai.com`·`help.openai.com`)이다.
API 플랫폼 조직의 Administration API(`/v1/organization/*`)와 ChatGPT 워크스페이스는 다른 대상이다 — 앞의 것은 좌석 근거로 쓰지 않는다.

- [Administration 개요](https://developers.openai.com/api/reference/administration/overview)를 다시 읽었다. 여전히 API 플랫폼 조직만 다루고
  ChatGPT 워크스페이스·`api.chatgpt.com`·좌석·요금제별 가용성에 대한 서술이 없다.
- `help.openai.com` 의 관련 글은 이 환경에서 모두 HTTP 403 이었다 — 본문을 읽지 못했다:
  `…/articles/8542216-managing-members-seat-types-and-roles-in-chatgpt-business`, `…/articles/20001407-managing-admin-keys-in-admin-console`.
  아래 두 항목은 **검색 색인이 보여 준 그 글들의 요약**이다. 본문을 읽은 인용이 아니므로 구현 근거로 쓰지 않는다.
  - 워크스페이스 범위 Admin API 가 있다: 요청 주소는 `https://api.chatgpt.com/v1`, 구성원 목록 예시 경로 `/manage/workspaces/{workspace_id}/users`,
    Users 읽기 권한 `chatgpt.enterprise.user.read`. 초대 API(목록·조회·생성·재전송·삭제)와 사용자 API(수락한 구성원 조회, owner 의 역할·좌석 변경과 멤버십 제거)가 있다.
  - Admin 키는 "eligible managed ChatGPT workspaces, including ChatGPT Enterprise, ChatGPT Edu, and ChatGPT for Healthcare"에 제공된다.
    새 관리 콘솔은 Enterprise·Edu 워크스페이스에 해당하고 "standalone ChatGPT Business customers continue using their existing ChatGPT workspace settings".
    Business 의 구성원 제거는 관리 화면 절차("Remove member")이고, 제거해도 다음 청구 주기 전까지 청구 좌석 수에서 빠지지 않는다.
- 요청·응답 스키마가 있는 Admin API reference 는 이 범위에서 읽지 못했다.

판정(재확인): **Business 플랜** — 좌석 API 를 확인하지 못했고, 확인한 요약도 Admin API 대상에서 Business 를 빼고 관리 화면 절차만 안내한다.
**Enterprise 플랜** — 워크스페이스 Admin API 의 존재와 주소는 요약으로 보이지만 엔드포인트별 요청·응답을 읽지 못했다.
두 플랜 모두 판정 값은 `확인 불가` 그대로다. 근거 없는 엔드포인트를 추측해 구현하지 않으므로 `openai_biz` 는 수동 기록을 유지한다.

### 2.2 실제 청구액 — 확인 불가

ChatGPT 구독(좌석) 청구액을 조회하는 API는 확인하지 못했다.

API 플랫폼 사용 비용을 조회하는 Costs API는 공식 cookbook에서 확인했다
([Usage API와 Cost API 사용법](https://developers.openai.com/cookbook/examples/completions_usage_api)).

- 엔드포인트 `https://api.openai.com/v1/organization/costs`, 헤더 `Authorization: Bearer {OPENAI_ADMIN_KEY}`(Admin API 키).
- 매개변수 `start_time`(Unix 초), `bucket_width`(`1d`), `limit`, `group_by`(`line_item`), 페이지는 응답의 `next_page`.
- 결과 항목 예시:

```json
{
  "amount": {
    "value": 0.13080438340307526,
    "currency": "usd"
  },
  "line_item": null,
  "project_id": null
}
```

이 값은 API 플랫폼 조직의 사용 비용이다. ChatGPT Business·Enterprise 좌석 계약의 청구액이 아니다.
API reference 본문(응답 전체 구조)은 읽지 못했다. `openai_biz` 등록 제품의 실제 청구액으로 쓰지 않는다.

## 3. Cursor (`cursor`)

근거: [Admin API](https://cursor.com/docs/account/teams/admin-api), [API 개요](https://cursor.com/docs/api).

- 제공 범위: API 개요의 가용성 표에서 Admin API는 "Enterprise teams"다. Teams 플랜은 대상이 아니다.
- 인증: "The Admin API uses Basic Authentication with your API key as the username." 키 형식 `crsr_…`.
  "Team administrators can create and manage API keys from the API Keys page in the dashboard."
- 기준 주소 `https://api.cursor.com`.
- 한도: 대부분의 엔드포인트 "20 requests per minute per team", `/teams/remove-member` "50 requests per minute per team",
  `/teams/filtered-usage-events` "60 requests per minute per team". 초과 시 429와 `Retry-After: 60`.
  권장 주기: Admin API 엔드포인트는 "polled at most once per hour".

### 좌석 조회 — 지원(Enterprise)

`GET /teams/members`. "Retrieve all team members and their details."

```bash
curl -X GET https://api.cursor.com/teams/members \
  -u YOUR_API_KEY:
```

```json
{
  "teamMembers": [
    {
      "id": "user_PDSPmvukpYgZEDXsoNirw3CFhy",
      "name": "Alex",
      "email": "developer@company.com",
      "role": "member",
      "isRemoved": false
    },
    {
      "id": "user_kljUvI0ASZORvSEXf9hV0ydcso",
      "name": "Sam",
      "email": "admin@company.com",
      "role": "owner",
      "isRemoved": false
    }
  ]
}
```

응답에 좌석 유형(Standard·Premium), 배정 시각, 마지막 활동 시각은 없다.
일별 활동은 `POST /teams/daily-usage-data`의 `isActive`·`email`·`day`로 본다(`startDate`·`endDate`는 epoch 밀리초, "Date range cannot exceed 30 days").

### 좌석 회수 — 지원(Enterprise)

`POST /teams/remove-member`. "Remove a member from your team programmatically." "Availability: Enterprise only".

```bash
curl -X POST https://api.cursor.com/teams/remove-member \
  -u YOUR_API_KEY: \
  -H "Content-Type: application/json" \
  -d '{"email": "developer@company.com"}'
```

```json
{
  "success": true,
  "userId": "user_PDSPmvukpYgZEDXsoNirw3CFhy",
  "hasBillingCycleUsage": true
}
```

- 요청 본문은 `userId` 또는 `email` 중 하나다. "Provide either `userId` or `email`, but not both".
- "At least one paid member must remain on the team after removal". "At least one admin (owner or free-owner) must remain on the team after removal".
- `hasBillingCycleUsage`: "Whether the member had usage in the current billing cycle". 제거가 청구에 미치는 영향은 이 문서에서 확인하지 못했다.

### 좌석 복원 — 공개 API 없음

Admin API 문서에 구성원 초대·재추가 엔드포인트가 없다. 복원은 Cursor 대시보드에서 다시 초대한다.

### 실제 청구액 — 지원(Enterprise, 사용 지출)

`POST /teams/spend`. "Retrieve spending information for the current billing cycle with search, sorting, and pagination."

요청 본문: `searchTerm`, `sortBy`(`amount`·`date`·`user`), `sortDirection`, `page`(1부터), `pageSize`.

```json
{
  "teamMemberSpend": [
    {
      "userId": "user_PDSPmvukpYgZEDXsoNirw3CFhy",
      "spendCents": 2450.125487,
      "overallSpendCents": 2450.125487,
      "fastPremiumRequests": 1250,
      "name": "Alex",
      "email": "developer@company.com",
      "role": "member",
      "hardLimitOverrideDollars": 100,
      "monthlyLimitDollars": 200,
      "effectivePerUserLimitDollars": 100
    }
  ],
  "subscriptionCycleStart": 1708992000000,
  "totalMembers": 15,
  "totalPages": 1
}
```

- `spendCents`: "On-demand spend in cents for the current billing cycle (excludes included usage)". `overallSpendCents`: "Total spend in cents for the current billing cycle, including both on-demand and included usage".
- `subscriptionCycleStart`는 epoch 밀리초다. 조회 범위는 **현재 청구 주기**뿐이다. 지난 주기를 고르는 매개변수는 문서에 없다.
- 청구서와의 관계: "On June 4th, 2026 we added additional precision to the spendCents and overallSpendCents fields to avoid rounding errors when comparing results to invoice amounts."
- 좌석 구독료(좌석 수 × 단가)를 돌려주는 필드는 문서에 없다. 이 값은 구성원별 사용 지출이다.

## 결론 — 구현 방식

`커넥터`는 공식 문서 근거로 구현하고 모의 서버로 검증한다. `수동`은 관리자의 입력과 조치 확인으로 처리한다.
`미제공`은 원천이 없어 값을 내지 않는다.

| 제품 ID · 플랜 | 좌석 조회 | 좌석 회수 | 좌석 복원 | 실제 청구액 |
|---|---|---|---|---|
| `claude_team` · `team` | 수동 | 수동 | 수동 | 미제공 |
| `claude_team` · `enterprise` | 커넥터 | 커넥터 | 수동(벤더 API 는 재초대가 있으나 구현하지 않음 — 아래) | 커넥터(사용량 기반 계약의 사용 비용만) |
| `openai_biz` · 전 플랜 | 수동 | 수동 | 수동 | 미제공 |
| `cursor` · `cursor_teams` | 수동 | 수동 | 수동 | 미제공 |
| `cursor` · `cursor_enterprise` | 커넥터 | 커넥터 | 수동 | 커넥터(현재 주기의 사용 지출) |
| `copilot` · 전 플랜 | 수동 | 수동 | 수동 | 미제공 |
| `gemini` · 전 플랜 | 수동 | 수동 | 수동 | 미제공 |
| `other` · 전 플랜 | 수동 | 수동 | 수동 | 미제공 |

커넥터가 지켜야 할 제약:

- **요청 성공과 회수 완료는 다르다.** 커넥터는 벤더가 돌려준 상태를 그대로 원장에 남긴다. Claude Enterprise의 복원(재초대)은 초대 수락 뒤에 끝나고,
  초대에 역할을 정해 보내야 하는데 원장은 옛 역할을 모른다 — 그래서 복원은 커넥터로 하지 않고 관리자 조치 확인으로 끝난다(ADR 0049 §2, ADR 0054).
  벤더 API 근거(§1.2 "좌석 복원")는 그대로 남긴다. 복원을 구현하려면 역할을 정하는 규칙을 먼저 정한다.
- **식별자가 다르다.** Claude Enterprise·Cursor는 이메일과 벤더 내부 ID를 준다. 원장의 계정 키는 이메일이고 제어 호출은 내부 ID로 한다.
- **활동 근거가 다르다.** Claude Enterprise와 Cursor는 구성원 응답에 마지막 활동이 없고 일별 활동만 준다. 값이 없다는 것을 미사용으로 확정하지 않는다.
- **좌석 등급을 API로 알 수 없는 벤더가 있다.** Claude Enterprise와 Cursor의 구성원 응답에는 등급이 없다.
- **청구 커넥터의 금액은 좌석 구독료가 아니다.** Claude Enterprise는 사용량 기반 계약의 사용 비용, Cursor는 현재 주기의 사용 지출이다.
  좌석 계약액과 더하거나 대체하지 않는다. 좌석 구독 청구액을 주는 API는 어느 벤더에서도 확인하지 못했다.
- **IdP가 구성원을 관리하면 쓰기가 막힌다.** Claude Enterprise는 SCIM 관리 조직에서 제거가 400, JIT·SCIM 조직에서 초대가 400이다.

## 다시 확인할 것

- OpenAI 도움말(`help.openai.com`)은 조회 시 403이었다(2026-09-30·2026-10-03). ChatGPT Enterprise·Edu 워크스페이스의 Admin API(`https://api.chatgpt.com/v1/manage/workspaces/{workspace_id}/…`)는
  요약으로만 확인했다 — 그 reference(요청·응답·오류·페이지)를 읽으면 `openai_biz` · `enterprise` 의 좌석 조회·회수·복원을 다시 판정한다. Business 플랜은 대상이 아닌 것으로 보인다.
- Cursor Teams 플랜에서 Admin API를 쓸 수 없다는 판정은 API 개요의 가용성 표에 근거한다. Admin API 문서 자체는 전체 제공 범위를 적지 않았다.
- 각 벤더의 레이트 리밋 중 이 문서에 수치가 없는 것.
