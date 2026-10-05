# 등록 제품·벤더 계약

[전체 API](README.md) · [공통 HTTP 규칙](common.md) · [공통 스키마](common-schemas.md)

## API 목록

| 번호 | API | 기능 | 서버 |
| --- | --- | --- | --- |
| 13 | [GET `/api/v1/organizations/{organizationId}/vendors`](endpoints/13-list-vendors.md) | 등록 제품 목록 | dashboard-api |
| 14 | [GET `/api/v1/organizations/{organizationId}/vendors/{vendorId}`](endpoints/14-get-vendor.md) | 등록 제품 상세 | dashboard-api |
| 31 | [POST `/api/v1/organizations/{organizationId}/vendors`](endpoints/31-create-vendor.md) | 제품 등록 | enrollment-api |
| 32 | [PATCH `/api/v1/organizations/{organizationId}/vendors/{vendorId}`](endpoints/32-update-vendor.md) | 제품 이름 변경 | enrollment-api |
| 33 | [PUT `/api/v1/organizations/{organizationId}/vendors/{vendorId}/contract`](endpoints/33-save-vendor-contract.md) | 계약 저장 | enrollment-api |
| 34 | [DELETE `/api/v1/organizations/{organizationId}/vendors/{vendorId}/contract`](endpoints/34-delete-vendor-contract.md) | 계약 제거 | enrollment-api |
| 35 | [DELETE `/api/v1/organizations/{organizationId}/vendors/{vendorId}`](endpoints/35-archive-vendor.md) | 수동 제품 보관 | enrollment-api |

<a id="feature-reference"></a>

## 기능 규칙·공유 스키마

[등록 제품·벤더 계약 규칙과 전체 스키마](reference/vendors.md)
