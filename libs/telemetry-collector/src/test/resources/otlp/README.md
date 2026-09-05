# OTLP 입력 fixture

PROJ-100 이 확보한 golden 중 **입력 쪽만** 가져왔다.

| 파일 | 내용 |
|---|---|
| `logs_real.otlp.jsonl` | E2E 실측 캡처 48개 문서. 한 줄이 `{"resourceLogs":[...]}` 문서 하나 |
| `logs_synthetic.otlp.jsonl` | 이벤트 종류·경계 조건을 채운 합성 5개 문서 |

출처: `soma-376/ai-telemetry-pipeline`
`apps/telemetry-processor/tests/fixtures/claude_code/` (커밋 `bb67a8d`, PROJ-100).

## 짝인 `.normalized.jsonl` 은 가져오지 않았다

그 파일들은 **normalizer 단계의 기대값**이라 이 모듈의 것이 아니다. 변환 단계를 이식하는
PROJ-103 이 가져간다. 여기서 이 입력으로 보는 것은 수집 단계가 지켜야 할 것 셋뿐이다.

1. OTLP/JSON 파싱 — 실제 클라이언트가 보내는 모양을 읽어 낸다
2. 재직렬화 왕복 — 아카이브에 쓰는 바이트가 입력과 같은 문서다
3. 마스킹이 실제 데이터에서 건드리는 것이 없음을 확인한다 — 캡처에 시크릿 패턴이 없다

## 왜 사본인가

CI 가 체크아웃하는 형제 레포는 `telemetryctl` 하나뿐이라 `ai-telemetry-pipeline` 경로를
빌드 시점에 기대할 수 없다. 원본이 갱신되면 여기도 같이 갱신한다 — 출처와 커밋을 위에 적어 둔
이유다.
