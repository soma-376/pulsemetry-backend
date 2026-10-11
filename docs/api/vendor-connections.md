# 벤더 연결·동기화

[전체 API](README.md) · [공통 HTTP 규칙](common.md) · [공통 스키마](common-schemas.md)

## API 목록

| 번호 | API | 기능 | 서버 |
| --- | --- | --- | --- |
| 36 | [PUT `/api/v1/organizations/{organizationId}/vendors/{vendorId}/connection`](endpoints/36-save-vendor-connection.md) | 연결 저장 | enrollment-api |
| 37 | [DELETE `/api/v1/organizations/{organizationId}/vendors/{vendorId}/connection`](endpoints/37-delete-vendor-connection.md) | 연결 제거 | enrollment-api |
| 38 | [POST `/api/v1/organizations/{organizationId}/vendors/{vendorId}/connection/verify`](endpoints/38-verify-vendor-connection.md) | 연결 검증 | enrollment-api |
| 39 | [POST `/api/v1/organizations/{organizationId}/vendors/{vendorId}/connection/sync`](endpoints/39-sync-vendor-connection.md) | 동기화 접수 | enrollment-api |

<a id="feature-reference"></a>

## 기능 규칙·공유 스키마

[벤더 연결·동기화 규칙과 전체 스키마](reference/vendor-connections.md)
