# 알림·알림 규칙

[전체 API](README.md) · [공통 HTTP 규칙](common.md) · [공통 스키마](common-schemas.md)

## API 목록

| 번호 | API | 기능 | 서버 |
| --- | --- | --- | --- |
| 16 | [GET `/api/v1/organizations/{organizationId}/alerts`](endpoints/16-list-alerts.md) | 알림 목록 | dashboard-api |
| 17 | [GET `/api/v1/organizations/{organizationId}/alerts/{alertId}`](endpoints/17-get-alert.md) | 알림 상세 | dashboard-api |
| 50 | [PATCH `/api/v1/organizations/{organizationId}/settings/alert-rules/{ruleId}`](endpoints/50-update-alert-rule.md) | 알림 규칙 변경 | enrollment-api |
| 51 | [POST `/api/v1/organizations/{organizationId}/alerts/{alertId}/acknowledge`](endpoints/51-acknowledge-alert.md) | 알림 확인 | enrollment-api |

<a id="feature-reference"></a>

## 기능 규칙·공유 스키마

[알림·알림 규칙 규칙과 전체 스키마](reference/alerts.md)
