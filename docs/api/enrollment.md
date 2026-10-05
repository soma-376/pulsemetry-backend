# CLI 설치 등록·배포

[전체 API](README.md) · [공통 HTTP 규칙](common.md) · [공통 스키마](common-schemas.md)

## API 목록

| 번호 | API | 기능 | 서버 |
| --- | --- | --- | --- |
| 67 | [POST `/v1/enroll`](endpoints/67-enroll-installation.md) | 설치 등록 | enrollment-api |
| 68 | [POST `/v1/installations/telemetry-token`](endpoints/68-refresh-telemetry-token.md) | 수집 토큰 재발급 | enrollment-api |
| 69 | [GET `/v1/manifest`](endpoints/69-get-manifest.md) | manifest 재조회 | enrollment-api |
| 70 | [POST `/v1/installations/{installationId}/heartbeat`](endpoints/70-send-heartbeat.md) | 설치 보고 | enrollment-api |
| 71 | [GET `/windows`](endpoints/71-get-windows-installer.md) | 설치 스크립트 | enrollment-api |
| 72 | [GET `/unix`](endpoints/72-get-unix-installer.md) | 설치 스크립트 | enrollment-api |
| 73 | [GET `/bin/{filename}`](endpoints/73-download-binary.md) | 바이너리 다운로드 | enrollment-api |
| 74 | [GET `/api/v1/check-updates`](endpoints/74-check-updates.md) | 데몬 업데이트 확인 | enrollment-api |

<a id="feature-reference"></a>

## 기능 규칙·공유 스키마

[CLI 설치 등록·배포 규칙과 전체 스키마](reference/enrollment.md)
