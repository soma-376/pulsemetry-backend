# 비동기 작업

[전체 API](README.md) · [공통 HTTP 규칙](common.md) · [공통 스키마](common-schemas.md)

## API 목록

| 번호 | API | 기능 | 서버 |
| --- | --- | --- | --- |
| 18 | [GET `/api/v1/organizations/{organizationId}/operations/{operationId}`](endpoints/18-get-operation.md) | 작업 상태 | dashboard-api |
| 47 | [POST `/api/v1/organizations/{organizationId}/operations/{operationId}/targets/{targetId}/confirm`](endpoints/47-confirm-operation-target.md) | 관리자 조치 확인 | enrollment-api |
| 48 | [POST `/api/v1/organizations/{organizationId}/operations/{operationId}/targets/{targetId}/cancel`](endpoints/48-cancel-operation-target.md) | 관리자 조치 취소 | enrollment-api |

<a id="feature-reference"></a>

## 기능 규칙·공유 스키마

[비동기 작업 규칙과 전체 스키마](reference/operations.md)
