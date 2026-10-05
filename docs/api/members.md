# 구성원 조회·편집

[전체 API](README.md) · [공통 HTTP 규칙](common.md) · [공통 스키마](common-schemas.md)

## API 목록

| 번호 | API | 기능 | 서버 |
| --- | --- | --- | --- |
| 06 | [GET `/api/v1/organizations/{organizationId}/members/dashboard`](endpoints/06-get-members-dashboard.md) | 구성원 첫 화면 | dashboard-api |
| 07 | [GET `/api/v1/organizations/{organizationId}/members`](endpoints/07-list-members.md) | 구성원 목록 | dashboard-api |
| 08 | [GET `/api/v1/organizations/{organizationId}/members/unassigned`](endpoints/08-list-unassigned-members.md) | 미배정 구성원 | dashboard-api |
| 25 | [POST `/api/v1/organizations/{organizationId}/member-team-assignments`](endpoints/25-assign-member-teams.md) | 팀 일괄 배정 | enrollment-api |
| 26 | [PATCH `/api/v1/organizations/{organizationId}/members/{memberId}`](endpoints/26-update-member.md) | 구성원 편집 | enrollment-api |

<a id="feature-reference"></a>

## 기능 규칙·공유 스키마

[구성원 조회·편집 규칙과 전체 스키마](reference/members.md)
