# 로그인·사용자 인증

[전체 API](README.md) · [공통 HTTP 규칙](common.md) · [공통 스키마](common-schemas.md)

## API 목록

| 번호 | API | 기능 | 서버 |
| --- | --- | --- | --- |
| 56 | [POST `/api/v1/auth/organizations`](endpoints/56-discover-organizations.md) | 이메일로 회사 탐색 | enrollment-api |
| 57 | [GET `/api/v1/auth/oidc/authorize`](endpoints/57-authorize-oidc.md) | SSO 시작 | enrollment-api |
| 58 | [GET `/api/v1/auth/oidc/callback/{registrationId}`](endpoints/58-callback-oidc.md) | IdP 콜백 | enrollment-api |
| 59 | [POST `/api/v1/auth/token`](endpoints/59-exchange-token.md) | 서비스 토큰 교환 | enrollment-api |
| 60 | [POST `/api/v1/auth/cli/token`](endpoints/60-exchange-cli-token.md) | 서비스 토큰 교환 | enrollment-api |
| 61 | [POST `/api/v1/auth/refresh`](endpoints/61-refresh-token.md) | 토큰 갱신 | enrollment-api |
| 62 | [POST `/api/v1/auth/logout`](endpoints/62-logout.md) | 서비스 로그아웃 | enrollment-api |
| 63 | [GET `/api/v1/auth/me`](endpoints/63-get-current-user.md) | 현재 사용자 | enrollment-api |

<a id="feature-reference"></a>

## 기능 규칙·공유 스키마

[로그인·사용자 인증 규칙과 전체 스키마](reference/auth.md)
