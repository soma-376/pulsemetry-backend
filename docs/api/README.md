# API 문서 길잡이

페이지·기능별로 Dashboard 조회와 Enrollment 변경을 함께 설명한다. 각 엔드포인트의 담당 서버와 전체 경로를 확인한다.
제품·레포 간 확정 계약은 [문서 허브](../../../docs/contracts/README.md), 설계 결정은 [ADR](../adr/README.md)이 우선한다.
각 API의 제목·파일명·목록에 같은 번호를 붙인다. 예: [01 GET 개요](endpoints/01-get-overview.md).
이 문서는 구현된 HTTP를 기록하며 배포·실계정 검증 완료를 뜻하지 않는다. 프론트 BFF 경로는 프론트 문서가 담당한다.

## 먼저 읽기

- [공통 HTTP 규칙](common.md): 인증·오류·멱등성·버전·응답 형식
- [공통 응답 스키마](common-schemas.md): 기간·금액·null·페이지·가용성·비교
- [Enrollment 운영·실행](../enrollment-server-spec.md), [Dashboard 운영·실행](../dashboard-server-spec.md)

## 페이지·기능 지도

| 화면·기능 | 문서 | 함께 쓰는 기능 |
| --- | --- | --- |
| 로그인 | [인증](auth.md) | 회사 탐색·OIDC·토큰·현재 사용자 |
| 최초 온보딩 | [온보딩](onboarding.md) | [정책](collection-policy.md)·[제품](vendors.md)·[초대](invitations.md) |
| 개요 | [개요](overview.md) | [알림](alerts.md)·[수집 현황](ingest-status.md) |
| 팀 분석·관리 | [팀](teams.md) | [구성원 배정](members.md) |
| 구성원 | [구성원](members.md) | [초대·설치 코드](invitations.md)·[좌석](seats.md) |
| 설정 | [설정](settings.md) | [제품·계약](vendors.md)·[카탈로그](vendor-catalog.md) |
| 벤더 연결 | [연결·동기화](vendor-connections.md) | [좌석](seats.md)·[작업 상태](operations.md) |
| 수집 정책 | [정책](collection-policy.md) | [설치 현황·안내](installations.md) |
| 알림 | [목록·확인·규칙](alerts.md) | [개요](overview.md) |
| 공통 수집 헤더 | [수집 상태](ingest-status.md) | 기간 필터와 무관한 현재 상태 |
| 비동기 명령 | [작업 상태·관리자 조치](operations.md) | 접수와 완료·부분 실패 구분 |
| 도입 문의 | [문의](inquiries.md) | 공개 접수 |
| CLI·데몬 | [설치 등록·manifest·heartbeat·배포](enrollment.md) | [관리자 키 초대](invitations.md) |

## 전체 엔드포인트

등록된 HTTP 매핑과 OIDC 필터 경로를 수록한다. OPTIONS preflight와 프레임워크 내부 오류 디스패치는 별도 업무 API가 아니다.
E = enrollment-api, D = dashboard-api. 같은 경로라도 서버가 다르면 별도 API다.

| 번호 | 서버 | 메서드 | 전체 경로 | 정식 명세 |
| --- | --- | --- | --- | --- |
| 01 | D | GET | `/api/v1/organizations/{organizationId}/analytics/overview` | [개요 조회](endpoints/01-get-overview.md) |
| 02 | D | GET | `/api/v1/organizations/{organizationId}/analytics/teams` | [팀 분석 목록](endpoints/02-get-team-analytics.md) |
| 03 | D | GET | `/api/v1/organizations/{organizationId}/analytics/teams/{teamId}` | [팀 분석 상세](endpoints/03-get-team-detail.md) |
| 04 | D | GET | `/api/v1/organizations/{organizationId}/analytics/teams/{teamId}/users` | [팀 사용자](endpoints/04-get-team-users.md) |
| 05 | D | GET | `/api/v1/organizations/{organizationId}/teams` | [현재 팀 선택지](endpoints/05-list-teams.md) |
| 06 | D | GET | `/api/v1/organizations/{organizationId}/members/dashboard` | [구성원 첫 화면](endpoints/06-get-members-dashboard.md) |
| 07 | D | GET | `/api/v1/organizations/{organizationId}/members` | [구성원 목록](endpoints/07-list-members.md) |
| 08 | D | GET | `/api/v1/organizations/{organizationId}/members/unassigned` | [미배정 구성원](endpoints/08-list-unassigned-members.md) |
| 09 | D | GET | `/api/v1/organizations/{organizationId}/seat-reclaim-candidates` | [회수 후보](endpoints/09-list-reclaim-candidates.md) |
| 10 | D | GET | `/api/v1/organizations/{organizationId}/members/{memberId}/seats` | [구성원 좌석](endpoints/10-get-member-seats.md) |
| 11 | D | GET | `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats` | [제품 좌석](endpoints/11-list-vendor-seats.md) |
| 12 | D | GET | `/api/v1/organizations/{organizationId}/settings` | [설정 첫 화면](endpoints/12-get-settings.md) |
| 13 | D | GET | `/api/v1/organizations/{organizationId}/vendors` | [등록 제품 목록](endpoints/13-list-vendors.md) |
| 14 | D | GET | `/api/v1/organizations/{organizationId}/vendors/{vendorId}` | [등록 제품 상세](endpoints/14-get-vendor.md) |
| 15 | D | GET | `/api/v1/organizations/{organizationId}/installations` | [설치 현황](endpoints/15-list-installations.md) |
| 16 | D | GET | `/api/v1/organizations/{organizationId}/alerts` | [알림 목록](endpoints/16-list-alerts.md) |
| 17 | D | GET | `/api/v1/organizations/{organizationId}/alerts/{alertId}` | [알림 상세](endpoints/17-get-alert.md) |
| 18 | D | GET | `/api/v1/organizations/{organizationId}/operations/{operationId}` | [작업 상태](endpoints/18-get-operation.md) |
| 19 | D | GET | `/api/v1/organizations/{organizationId}/ingest-status` | [현재 수집 상태](endpoints/19-get-ingest-status.md) |
| 20 | D | GET | `/api/v1/vendor-catalog` | [카탈로그 검색](endpoints/20-list-vendor-catalog.md) |
| 21 | D | GET | `/api/v1/vendor-catalog/{vendorId}/plans` | [제품 플랜 조회](endpoints/21-list-vendor-plans.md) |
| 22 | E | POST | `/api/v1/organizations/{organizationId}/teams` | [팀 생성](endpoints/22-create-team.md) |
| 23 | E | PATCH | `/api/v1/organizations/{organizationId}/teams/{teamId}` | [팀 이름 변경](endpoints/23-update-team.md) |
| 24 | E | DELETE | `/api/v1/organizations/{organizationId}/teams/{teamId}` | [팀 보관](endpoints/24-archive-team.md) |
| 25 | E | POST | `/api/v1/organizations/{organizationId}/member-team-assignments` | [팀 일괄 배정](endpoints/25-assign-member-teams.md) |
| 26 | E | PATCH | `/api/v1/organizations/{organizationId}/members/{memberId}` | [구성원 편집](endpoints/26-update-member.md) |
| 27 | E | POST | `/api/v1/organizations/{organizationId}/invitations/batch` | [일괄 초대](endpoints/27-invite-members.md) |
| 28 | E | POST | `/api/v1/organizations/{organizationId}/invitations/{invitationId}/revoke` | [초대 취소](endpoints/28-revoke-invitation.md) |
| 29 | E | POST | `/api/v1/organizations/{organizationId}/invitations/{invitationId}/reissue` | [초대 재발급](endpoints/29-reissue-invitation.md) |
| 30 | E | POST | `/api/v1/organizations/{organizationId}/members/{memberId}/installation-invitations` | [활성 회원 설치 코드](endpoints/30-create-installation-invitation.md) |
| 31 | E | POST | `/api/v1/organizations/{organizationId}/vendors` | [제품 등록](endpoints/31-create-vendor.md) |
| 32 | E | PATCH | `/api/v1/organizations/{organizationId}/vendors/{vendorId}` | [제품 이름 변경](endpoints/32-update-vendor.md) |
| 33 | E | PUT | `/api/v1/organizations/{organizationId}/vendors/{vendorId}/contract` | [계약 저장](endpoints/33-save-vendor-contract.md) |
| 34 | E | DELETE | `/api/v1/organizations/{organizationId}/vendors/{vendorId}/contract` | [계약 제거](endpoints/34-delete-vendor-contract.md) |
| 35 | E | DELETE | `/api/v1/organizations/{organizationId}/vendors/{vendorId}` | [수동 제품 보관](endpoints/35-archive-vendor.md) |
| 36 | E | PUT | `/api/v1/organizations/{organizationId}/vendors/{vendorId}/connection` | [연결 저장](endpoints/36-save-vendor-connection.md) |
| 37 | E | DELETE | `/api/v1/organizations/{organizationId}/vendors/{vendorId}/connection` | [연결 제거](endpoints/37-delete-vendor-connection.md) |
| 38 | E | POST | `/api/v1/organizations/{organizationId}/vendors/{vendorId}/connection/verify` | [연결 검증](endpoints/38-verify-vendor-connection.md) |
| 39 | E | POST | `/api/v1/organizations/{organizationId}/vendors/{vendorId}/connection/sync` | [동기화 접수](endpoints/39-sync-vendor-connection.md) |
| 40 | E | POST | `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats` | [좌석 배정](endpoints/40-assign-seat.md) |
| 41 | E | PATCH | `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/{seatId}` | [좌석 정보 보정](endpoints/41-update-seat.md) |
| 42 | E | POST | `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/{seatId}/release` | [수동 좌석 해제](endpoints/42-release-seat.md) |
| 43 | E | POST | `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats/import` | [좌석 CSV 가져오기](endpoints/43-import-seats.md) |
| 44 | E | POST | `/api/v1/organizations/{organizationId}/seat-reclaims/preview` | [회수 미리보기](endpoints/44-preview-seat-reclaims.md) |
| 45 | E | POST | `/api/v1/organizations/{organizationId}/seat-reclaims` | [좌석 회수 접수](endpoints/45-reclaim-seats.md) |
| 46 | E | POST | `/api/v1/organizations/{organizationId}/seat-reclaims/{operationId}/restore` | [회수 복원 접수](endpoints/46-restore-seat-reclaims.md) |
| 47 | E | POST | `/api/v1/organizations/{organizationId}/operations/{operationId}/targets/{targetId}/confirm` | [관리자 조치 확인](endpoints/47-confirm-operation-target.md) |
| 48 | E | POST | `/api/v1/organizations/{organizationId}/operations/{operationId}/targets/{targetId}/cancel` | [관리자 조치 취소](endpoints/48-cancel-operation-target.md) |
| 49 | E | POST | `/api/v1/organizations/{organizationId}/installation-update-notifications` | [설치 업데이트 안내 접수](endpoints/49-notify-installation-update.md) |
| 50 | E | PATCH | `/api/v1/organizations/{organizationId}/settings/alert-rules/{ruleId}` | [알림 규칙 변경](endpoints/50-update-alert-rule.md) |
| 51 | E | POST | `/api/v1/organizations/{organizationId}/alerts/{alertId}/acknowledge` | [알림 확인](endpoints/51-acknowledge-alert.md) |
| 52 | E | POST | `/api/v1/organizations/{organizationId}/onboarding/complete` | [온보딩 완료](endpoints/52-complete-onboarding.md) |
| 53 | E | PUT | `/api/v1/organizations/{organizationId}/collection-policy` | [수집·조직 정책 저장](endpoints/53-save-collection-policy.md) |
| 54 | E | GET | `/api/v1/organizations/{organizationId}/invitations` | [초대 목록](endpoints/54-list-invitations.md) |
| 55 | E | GET | `/api/v1/organizations/{organizationId}/onboarding` | [온보딩 상태](endpoints/55-get-onboarding.md) |
| 56 | E | POST | `/api/v1/auth/organizations` | [이메일로 회사 탐색](endpoints/56-discover-organizations.md) |
| 57 | E | GET | `/api/v1/auth/oidc/authorize` | [SSO 시작](endpoints/57-authorize-oidc.md) |
| 58 | E | GET | `/api/v1/auth/oidc/callback/{registrationId}` | [IdP 콜백](endpoints/58-callback-oidc.md) |
| 59 | E | POST | `/api/v1/auth/token` | [서비스 토큰 교환](endpoints/59-exchange-token.md) |
| 60 | E | POST | `/api/v1/auth/cli/token` | [서비스 토큰 교환](endpoints/60-exchange-cli-token.md) |
| 61 | E | POST | `/api/v1/auth/refresh` | [토큰 갱신](endpoints/61-refresh-token.md) |
| 62 | E | POST | `/api/v1/auth/logout` | [서비스 로그아웃](endpoints/62-logout.md) |
| 63 | E | GET | `/api/v1/auth/me` | [현재 사용자](endpoints/63-get-current-user.md) |
| 64 | E | POST | `/api/v1/invitations` | [관리자 키 초대](endpoints/64-create-admin-invitation.md) |
| 65 | E | POST | `/api/v1/invitations/{id}/revoke` | [관리자 키 초대 취소](endpoints/65-revoke-admin-invitation.md) |
| 66 | E | POST | `/api/v1/inquiries` | [도입 문의 접수](endpoints/66-create-inquiry.md) |
| 67 | E | POST | `/api/v1/enroll` | [설치 등록](endpoints/67-enroll-installation.md) |
| 68 | E | POST | `/api/v1/installations/telemetry-token` | [수집 토큰 재발급](endpoints/68-refresh-telemetry-token.md) |
| 69 | E | GET | `/api/v1/manifest` | [manifest 재조회](endpoints/69-get-manifest.md) |
| 70 | E | POST | `/api/v1/installations/{installationId}/heartbeat` | [설치 보고](endpoints/70-send-heartbeat.md) |
| 71 | E | GET | `/windows` | [설치 스크립트](endpoints/71-get-windows-installer.md) |
| 72 | E | GET | `/unix` | [설치 스크립트](endpoints/72-get-unix-installer.md) |
| 73 | E | GET | `/bin/{filename}` | [바이너리 다운로드](endpoints/73-download-binary.md) |
| 74 | E | GET | `/api/v1/check-updates` | [데몬 업데이트 확인](endpoints/74-check-updates.md) |
| 75 | E | GET | `/api/v1/healthz` | [생존 확인](endpoints/75-get-enrollment-health.md) |
| 76 | D | GET | `/api/v1/healthz` | [생존 확인](endpoints/76-get-dashboard-health.md) |

## 문서 유지·검증

각 API는 `endpoints/번호-기능명.md` 한 파일에서 관리한다. 페이지·기능 문서는 API 목록 표를 제공하고, `reference/`는 기능 규칙·공유 스키마를 담는다.
번호는 전체 API에서 고유하며 새 API는 마지막 번호 다음에 추가한다. 기존 번호를 재사용하거나 재정렬하지 않는다.
상세 제목은 `# 번호 METHOD 경로`, 본문은 `## Request`, 필요한 `### Headers / Path / Query / Body`, `## Response`로 작성한다. 사용하지 않는 요청 항목은 생략한다.
타입에는 전체 필드·중첩 객체·배열을 정의하고 생략 가능·null·기본값·enum·검증 조건을 구분한다.
예시는 가상 ID·계정·금액을 사용한다. 실제 토큰·자격증명·계정 정보를 넣지 않는다.
API 변경 시 Controller/DTO뿐 아니라 JSON 생성 코드·필터·직렬화 설정과 관련 계약 테스트도 대조한다.
기존 서버 명세의 번호·앵커는 이전 안내로 유지하여 다른 레포의 링크를 보존한다.

```bash
python3 tools/check-api-docs.py
# 선언과 전체 JSON 예시를 함께 tsc로 검사할 때
python3 tools/check-api-docs.py --typescript-output /tmp/api-docs.ts
tsc --noEmit --strict --skipLibCheck --target es2022 /tmp/api-docs.ts
```

문서 검사는 경로·메서드 커버리지, 중복, 응답 타입 정의, 링크·앵커, JSON 문법을 확인한다.
HTTP 동작·실환경 연동 성공을 보장하는 테스트는 아니다. API 동작을 바꿀 때는 해당 서버 계약 테스트를 별도로 실행한다.
