# 좌석 원장·회수·복원

[전체 API](README.md) · [공통 HTTP 규칙](common.md) · [공통 스키마](common-schemas.md)

## API 목록

| 번호 | API | 기능 | 서버 |
| --- | --- | --- | --- |
| 09 | [GET `/api/v1/organizations/{organizationId}/seat-reclaim-candidates`](endpoints/09-list-reclaim-candidates.md) | 회수 후보 | dashboard-api |
| 10 | [GET `/api/v1/organizations/{organizationId}/members/{memberId}/seats`](endpoints/10-get-member-seats.md) | 구성원 좌석 | dashboard-api |
| 11 | [GET `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats`](endpoints/11-list-vendor-seats.md) | 제품 좌석 | dashboard-api |
| 40 | [POST `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats`](endpoints/40-assign-seat.md) | 좌석 배정 | enrollment-api |
| 41 | [PATCH `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/{seatId}`](endpoints/41-update-seat.md) | 좌석 정보 보정 | enrollment-api |
| 42 | [POST `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/{seatId}/release`](endpoints/42-release-seat.md) | 수동 좌석 해제 | enrollment-api |
| 43 | [POST `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/import`](endpoints/43-import-seats.md) | 좌석 CSV 가져오기 | enrollment-api |
| 44 | [POST `/api/v1/organizations/{organizationId}/seat-reclaims/preview`](endpoints/44-preview-seat-reclaims.md) | 회수 미리보기 | enrollment-api |
| 45 | [POST `/api/v1/organizations/{organizationId}/seat-reclaims`](endpoints/45-reclaim-seats.md) | 좌석 회수 접수 | enrollment-api |
| 46 | [POST `/api/v1/organizations/{organizationId}/seat-reclaims/{operationId}/restore`](endpoints/46-restore-seat-reclaims.md) | 회수 복원 접수 | enrollment-api |

<a id="feature-reference"></a>

## 기능 규칙·공유 스키마

[좌석 원장·회수·복원 규칙과 전체 스키마](reference/seats.md)
