# 팀 분석·관리

[전체 API](README.md) · [공통 HTTP 규칙](common.md) · [공통 스키마](common-schemas.md)

## API 목록

| 번호 | API | 기능 | 서버 |
| --- | --- | --- | --- |
| 02 | [GET `/api/v1/organizations/{organizationId}/analytics/teams`](endpoints/02-get-team-analytics.md) | 팀 분석 목록 | dashboard-api |
| 03 | [GET `/api/v1/organizations/{organizationId}/analytics/teams/{teamId}`](endpoints/03-get-team-detail.md) | 팀 분석 상세 | dashboard-api |
| 04 | [GET `/api/v1/organizations/{organizationId}/analytics/teams/{teamId}/users`](endpoints/04-get-team-users.md) | 팀 사용자 | dashboard-api |
| 05 | [GET `/api/v1/organizations/{organizationId}/teams`](endpoints/05-list-teams.md) | 현재 팀 선택지 | dashboard-api |
| 22 | [POST `/api/v1/organizations/{organizationId}/teams`](endpoints/22-create-team.md) | 팀 생성 | enrollment-api |
| 23 | [PATCH `/api/v1/organizations/{organizationId}/teams/{teamId}`](endpoints/23-update-team.md) | 팀 이름 변경 | enrollment-api |
| 24 | [DELETE `/api/v1/organizations/{organizationId}/teams/{teamId}`](endpoints/24-archive-team.md) | 팀 보관 | enrollment-api |

<a id="feature-reference"></a>

## 기능 규칙·공유 스키마

[팀 분석·관리 규칙과 전체 스키마](reference/teams.md)
