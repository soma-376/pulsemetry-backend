# frontend-api — 화면 요청서의 예시 응답 사본

`pulsemetry-frontend` 의 `docs/api/` 에 있는 예시 응답 JSON 을 **고치지 않고** 복사한 것이다. 대시보드 API 응답의 **키 구조**가
요청서와 같은지 대조하는 테스트가 읽는다 — 값은 대조하지 않는다(예시는 가상의 ID·금액이다).

| 파일 | 원본 |
|---|---|
| `overview-response.example.json` | `pulsemetry-frontend/docs/api/overview-response.example.json` |
| `teams-response.example.json` | `pulsemetry-frontend/docs/api/teams-response.example.json` |
| `team-users-response.example.json` | `pulsemetry-frontend/docs/api/team-users-response.example.json` |
| `members-response.example.json` | `pulsemetry-frontend/docs/api/members-response.example.json` |
| `settings-response.example.json` | `pulsemetry-frontend/docs/api/settings-response.example.json` |

요청서의 예시가 바뀌면 원본을 그대로 다시 복사한다. 테스트를 통과시키려고 이 사본을 고치지 않는다.
