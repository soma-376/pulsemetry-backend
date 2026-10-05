# 초대·설치 코드

[전체 API](README.md) · [공통 HTTP 규칙](common.md) · [공통 스키마](common-schemas.md)

## API 목록

| 번호 | API | 기능 | 서버 |
| --- | --- | --- | --- |
| 27 | [POST `/api/v1/organizations/{organizationId}/invitations/batch`](endpoints/27-invite-members.md) | 일괄 초대 | enrollment-api |
| 28 | [POST `/api/v1/organizations/{organizationId}/invitations/{invitationId}/revoke`](endpoints/28-revoke-invitation.md) | 초대 취소 | enrollment-api |
| 29 | [POST `/api/v1/organizations/{organizationId}/invitations/{invitationId}/reissue`](endpoints/29-reissue-invitation.md) | 초대 재발급 | enrollment-api |
| 30 | [POST `/api/v1/organizations/{organizationId}/members/{memberId}/installation-invitations`](endpoints/30-create-installation-invitation.md) | 활성 회원 설치 코드 | enrollment-api |
| 54 | [GET `/api/v1/organizations/{organizationId}/invitations`](endpoints/54-list-invitations.md) | 초대 목록 | enrollment-api |
| 64 | [POST `/api/v1/invitations`](endpoints/64-create-admin-invitation.md) | 관리자 키 초대 | enrollment-api |
| 65 | [POST `/api/v1/invitations/{id}/revoke`](endpoints/65-revoke-admin-invitation.md) | 관리자 키 초대 취소 | enrollment-api |

<a id="feature-reference"></a>

## 기능 규칙·공유 스키마

[초대·설치 코드 규칙과 전체 스키마](reference/invitations.md)
