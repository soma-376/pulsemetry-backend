# otlp-fixtures — 실캡처를 익명화해 fixture 로 만드는 도구

실제 producer(Codex·Claude Code)가 보낸 OTLP 의 **모양**(wire 타입·속성 키·분기)을 저장소 fixture 로 고정하기 위한
도구다. 내용은 익명화한다. python3 표준 라이브러리만 쓴다. **이 디렉터리에는 데이터를 두지 않는다** — 캡처 원본과
중간 산출물은 저장소 밖(또는 git 이 무시하는 작업 디렉터리)에 둔다.

| 파일 | 하는 일 |
|---|---|
| `extract.py` | 캡처(한 줄 = OTLP/JSON export 하나)에서 계획 파일의 조건으로 레코드를 고른다. 고른 레코드를 원래 resource·scope 아래 그대로 두어 push 묶음 구조를 보존한다. 출력은 **원문**이다 |
| `sanitize.py` | 고른 문서를 allowlist 방식으로 익명화한다 |
| `gate.py` | 익명화 결과가 커밋 가능한지 검사한다. 원본 캡처에서 ID·호스트·내용 값 집합을 모아 교차 검사하고, 하나라도 걸리면 비0 으로 끝난다 |
| `plans/*.json` | 제품별 선택 계획 — 어떤 이벤트·스팬·메트릭을 몇 개씩 고르는지 |

## 흐름

```bash
# 1. 고르기 — 출력(원문)은 저장소 밖 작업 디렉터리에
python3 scripts/otlp-fixtures/extract.py --plan scripts/otlp-fixtures/plans/codex.json \
    --out-dir <RAW_DIR> <CAPTURE.jsonl> [<CAPTURE.jsonl> ...]

# 2. 익명화 — 한 번의 실행에 모든 파일을 함께 넘긴다(파일을 가로지르는 ID·시각 관계가 같은 대응표로 보존된다)
python3 scripts/otlp-fixtures/sanitize.py --out-dir <FIXTURE_DIR> <RAW_DIR>/*.otlp.jsonl

# 3. 관문 — 원본 캡처를 모두 --source 로 준다. README 등 fixture 디렉터리 전체를 넘긴다
python3 scripts/otlp-fixtures/gate.py --source <CAPTURE.jsonl> [--source ...] --list-kept <FIXTURE_DIR>

# 4. 사람 검토 — --list-kept 가 키별로 남은 값을 보여준다. 그리고 git diff --cached 를 직접 훑는다
```

캡처는 수 백 MB 일 수 있다. 편집기나 파일 뷰어로 열지 말고 이 도구들로 스트리밍한다. 쓰는 중인 파일은
`head -n <N>` 사본으로 떠서 넘긴다.

## 계획 파일

`extract.py` 의 docstring 이 형식을 적는다. 요지:

- `services` — 볼 `service.name`.
- `groups[]` — 출력 파일 하나의 종류(`kind`)와 신호(`signal`: logs·traces·metrics), 선택자 목록(`select`).
- 선택자 — `name`(로그는 `event.name` 속성, 스팬·메트릭은 이름), `match`(속성 값 일치), `has`·`missing`(속성 유무),
  `eventName`(로그 최상위 `eventName` 부분 문자열), `has_events`(스팬 이벤트 유무), `distinct_by`+`per_value`(값마다 몇 개),
  `group_by`+`group_min`+`groups`+`per_group`(같은 값을 공유하는 묶음 — 예: 같은 `call_id` 의 여러 `tool_result_seq`),
  `max_points`(메트릭 point 수), `cap`(서비스·버전 조합마다 최대 수).
- 레코드 하나는 처음으로 맞는 선택자 하나에만 배정된다. 출력 파일은 `<signal>-<service>-<version>-<kind>.otlp.jsonl`.

## 익명화 규칙(`sanitize.py`)

남기는 것: 구조 키, 속성 키, 숫자·불리언 값과 **그 wire 타입**(`stringValue` 로 온 숫자는 `stringValue` 그대로),
enum 꼴 한 토큰 값(`[A-Za-z0-9_.:+-]`, 80자 이하 — 버전·모델·상태·종류), 로그 최상위 `eventName` 의
`event <소스 경로>.rs:<줄>` 꼴, 스팬·메트릭·scope 이름, 메트릭 설명·단위, API 경로 꼴(`endpoint`·`api.path`·`rpc.method`),
공개 벤더 도메인(`server.address`).

| 대상 | 치환 |
|---|---|
| ID 키(`*.id`·`*_id`·`*_uuid`, `call_id`·`conversation.id`·`auth.cf_ray` 등), `traceId`·`spanId`·`parentSpanId` | 같은 모양의 가짜 — uuid → uuid(버전·variant 니블 유지), hex → 같은 길이 hex, 숫자 → 같은 자릿수 숫자, `접두사_`·`접두사-` 는 남기고 나머지를 문자 종류별로. 정수로 온 ID(`intValue`)는 그대로 |
| 남기는 값 안에 박힌 uuid·12자 이상 hex | 같은 대응표로 가짜 |
| 이메일(키 또는 값 모양) | `userN@example.test` |
| `host.name` | `host-redacted` |
| 경로 키(`cwd`·`code.file.path`·`file_path` 등), 구분자가 든 `path`·`directory`·`bash_argv0` | `/redacted/pathN` |
| 저장소·브랜치 키, 사람 이름 키, 비밀꼴(`sk-`·`ghp_`·`Bearer `·JWT 등) | `<redacted len=N>` |
| MCP 서버·커넥터·플러그인·마켓플레이스·스킬 이름 | `serverN`·`connectorN`·`pluginN`·`marketplaceN`·`skillN`(`none`·`unknown`·`unattributed`·`stdio`·`local`·`builtin` 은 그대로) |
| `mcp__<서버>__<도구>` 꼴 값(어느 키든), `mcp_tool.name` | `mcp__serverN__mcp_toolN`, `mcp_toolN` |
| 에이전트 종류(`agent.name`·`agent_type`·`subagent_type`), `query_source` 의 `agent:<builtin\|custom>:<이름>` | 제품 내장 이름(`Explore`·`general-purpose`·`Plan` 등)만 그대로, 그 밖은 `agentN` |
| 명령 이름(`command_name`) | 같은 레코드의 `command_source = builtin` 이면 그대로, 사용자 정의면 `commandN` |
| MCP 네임스페이스 `mcp__<서버>`, MCP 레코드의 도구 이름 | `mcp__serverN`, `mcp_toolN`(같은 서버 이름은 같은 번호). `mcp__<서버><도구>` 로 붙어 온 값은 풀리면 쌍으로, 아니면 가린다 |
| MCP 서버 origin(URL) | `https://originN.example.test` |
| 하위 에이전트 이름 `/root/<이름>` | `/root/agentN`(루트 `/root` 는 그대로) |
| 내용 키(`prompt`·`user_prompt`·`response`·`content`·`arguments`·`output`·`tool_input`·`tool_parameters`·`full_command`·`mcp_servers` 등), 로그 `body` | 빈 문자열 — 키와 wire 타입은 남아 존재 여부가 보존된다. 단 본문이 정확히 `<접두사>.<같은 레코드의 event.name>` 이면(Claude Code 처럼 이벤트 이름을 두 곳에 싣는 제품) 그대로 둔다 |
| 그 밖의 문자열(자유 텍스트·오류 메시지·스팬 status 메시지) | `<redacted len=N>` |
| 시각(`*UnixNano`), ISO 시각 문자열(`event.timestamp` 등) | 모든 레코드에 같은 오프셋(밀리초 배수)을 뺀다 — 가장 이른 시각이 2026-01-01T00:00:00Z 부근. 상대 순서·간격 보존. 0 은 0 |

대응표를 만드는 salt 는 **실행마다 무작위이고 저장하지 않는다**. 시각 오프셋도 저장하지 않는다. 그래서 다시
돌리면 모든 가짜 값과 시각이 바뀐다 — 기대값이 이미 붙은 fixture 는 다시 굽지 말고 새 파일을 더한다.

값 모양 allowlist 는 완전하지 않다. 모르는 키의 한 토큰 값은 남는다. 그래서 4단계(사람 검토)를 건너뛰지 않는다.
새 제품·버전에서 민감한 키를 발견하면 `sanitize.py` 의 키 표에 더하고 `gate.py` 가 같은 표를 쓰는지 확인한다.

## 관문(`gate.py`)

하나라도 걸리면 커밋하지 않는다.

1. `@example.test` 가 아닌 이메일꼴
2. `/Users/`·`/home/`·`C:\`, 실행 사용자 이름과 홈 디렉터리 이름(자동), `--forbid` 로 준 문자열
3. 원본 `host.name` 값
4. 원본의 ID 값(8자 이상)과 그 안의 숫자 섞인 8자 이상 조각이 출력 어디에든 남음
5. `sk-`·`ghp_`·`gho_`·`ptt_`·`pit_`·`Bearer `·JWT꼴
6. 내용·경로·저장소·MCP 서버·커넥터·플러그인·마켓플레이스·스킬·에이전트 키의 값이 비었거나 자리표시자(또는 제품 상수)가 아님,
   `mcp__` 로 시작하는 원문, MCP 도구 이름의 원문. 그 원본 값(4자 이상)이 출력의 어느 문자열로든 남아 있음(서비스·scope·스팬·
   메트릭 이름 필드는 producer 식별자라 이 교차에서 뺀다)
7. 레코드 단위: 로그 `body` 가 비었거나 정확히 `<접두사>.<event.name>` 이 아님, `command_source` 가 builtin 이 아닌 레코드의
   `command_name` 이 자리표시자가 아님

익명화 전 산출물(`extract.py` 출력)을 넘기면 반드시 실패해야 한다 — 관문이 실제로 무엇을 잡는지 확인하는 방법이다.
