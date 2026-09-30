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

## 가산 키 목록

요청서에 없던 응답 필드를 더했으면(가산 — 기존 필드의 이름·의미는 바꾸지 않는다) `support/JsonStructure.kt`의 `EXTENSIONS`에
**예시 파일 → 경로 → 키**로 선언한다. 경로는 `/a/b` 표기이고 배열은 첫 원소 `/0`이다. 키 구조 검사(`JsonStructure.assertMatches`)는
응답의 키가 "예시의 키 + 그 경로에 선언한 가산 키"와 **정확히** 같아야 통과한다 — 선언하지 않은 키가 있어도, 예시의 키나 선언한 키가 빠져도 실패다.

갱신 규칙:

- 필드를 더하는 커밋에서 이 목록과 `docs/dashboard-server-spec.md`의 타입 블록을 함께 고친다. 근거 ADR을 주석으로 단다.
- 요청서가 그 필드를 받아들여 예시에 들어오면(사본을 다시 복사하면) 목록에서 지운다.
- 지금 선언: 개요 `productUsage`·`teamUsage.topTeams[].products`·`teamUsage.unassigned.products`, 팀 목록 `teams.items[].products`·`unassigned.products`(ADR 0045),
  설정 `summary.detectedProducts`·`summary.unmappedObservations`(ADR 0044).
