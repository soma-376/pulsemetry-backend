# AGENTS.md — pulsemetry-backend

Pulsemetry는 Claude Code·Codex 등 개발 AI 도구의 사용량과 비용을 조직 → 팀 → 구성원 축으로 모아 보여주는
사내 통합·가시화 플랫폼이다.

- 제품·아키텍처·레포 간 계약의 **단일 출처는 `soma-376/docs`**다. 형제 체크아웃 `../docs`를 우선 참조한다.
- 기능 작업 전에는 `spec` 스킬, 설계 관련 작업 전에는 `adr` 스킬을 쓴다.
- **코드와 ADR이 어긋나면 ADR이 기준이다.** 결정을 바꾸려면 `adr-new`로 개정 ADR을 먼저 쓴다.
- git 작업은 `CONVENTION.md`를 따른다 (`conventions` 스킬).
- 스킬이 보이지 않으면 형제 `../agent-skills` 클론 여부를 확인하고, 없으면 사용자에게 클론을 안내한다.
- 문서·주석은 한국어, 코드·파일명은 영어.
- **사용자의 명시 요청 없이 `git push` 하지 않는다.**

---

## 이 레포는 무엇인가

Kotlin + Spring Boot, Gradle 멀티모듈. 시스템 아키텍처의 **Auth Service** 자리를 맡는다.

```
apps/enrollment-api/         enrollment · manifest · 부트스트랩 서빙 (HTTP)
apps/telemetry-ingest/       조립 앱 — OTLP 수신부터 적재까지 한 프로세스. 배선만 한다
libs/enrollment-persistence/ JPA 엔티티 · 리포지토리 · Flyway 마이그레이션
libs/security/               횡단 인증 라이브러리 — OTLP 경로 ptt_ 검증 · telemetry token 해시
libs/telemetry-collector/    파이프라인 수집 단계 — OTLP 수신 · 마스킹 · 신원 스탬프 · 원본 아카이브
libs/telemetry-adapter/      파이프라인 변환 단계 — OTLP 읽기 · 정규화 모델 · 벤더별 매핑
libs/telemetry-enricher/     파이프라인 보강 단계 — 팀 소속 as-of 조인 · provider 주석
libs/telemetry-persistence/  파이프라인 적재 단계 — ClickHouse 스키마 소유 · JSONEachRow sink
```

**소유하는 것**: `POST /v1/enroll`, `POST /v1/installations/telemetry-token`, `POST /v1/invitations`,
부트스트랩 스크립트·바이너리 서빙(`GET /windows|/unix|/bin/{f}`), manifest 저장,
그리고 **enrollment 스키마의 진실원(Flyway)**.

**아직 없지만 이 레포의 몫인 것** — 다른 레포로 보내지 않는다. 여기서 만들거나, 여기로 가져온다.

| 항목 | 상태 | 근거 |
|---|---|---|
| 사람 계정·로그인 | 미구현 | 이 레포가 **Auth Service**다. Spring Security가 AT·RT를 직접 발급한다(ADR-0007 — Cognito 미사용). `members.cognito_user_sub`는 제거됐고(`V4`) 비밀번호 자리는 `members.password_hash`다 — 담을 곳만 있고 로그인 경로는 아직 없다. 얹힐 자리는 `:libs:security`이고 모듈은 이미 서 있다 |
| manifest 작성 API | 미구현 (현재 수동 INSERT) | manifest 저장은 이미 이 레포 소유 |
| 대시보드 API | **소재 미정** — 이 레포의 모듈인지 별도 레포인지 | 확정 ADR은 아직 없다 |
| 텔레메트리 파이프라인 이관 | **코드는 끝났다. 배포만 남았다** | 인증(PROJ-102) · 수집(PROJ-114) · 변환(PROJ-103) · 보강과 적재(PROJ-104)에 이어 **조립 앱 `:apps:telemetry-ingest`(PROJ-105)까지 섰다.** 로컬에서는 다섯 모듈이 한 요청에서 돈다 — 남은 것은 infra 가 이 앱을 배포하고 collector 컨테이너를 내리는 일이다(PROJ-106) |

**파이프라인은 이 레포의 단일 앱이다**(허브 ADR 0004·0005 — 배포 단위 하나, OTel Collector 바이너리 없음).
모듈 구성은 `docs/module-map.md`가 담는다. **이식은 끝났고 배포만 남았다** — 위 표의 마지막 행이 상태다.
전환 전까지는 실제 OTLP 트래픽을 auth-proxy와 collector 컨테이너가 받으므로, 허브
`contracts/telemetry-ingest.md` §1·§3·§4의 현행 서술은 infra가 전환을 끝낸 뒤에 고친다(허브 ADR 0005 Follow-up).

**이 레포가 소유하지 않는 것** — 요청이 오면 올바른 레포를 알린다.

| 항목 | 소유 레포 |
|---|---|
| AWS 리소스, 태스크 정의, **현행 파이프라인의** 배포 collector 설정 | `infra` |
| 로컬 수신기·데몬·벤더 도구 배선, manifest **계약 스키마 파일** | `telemetryctl` |
| 스키마 다이어그램(dbml) | `rdb-schema` — 단 **마이그레이션 진실원은 이 레포의 Flyway**다 |

`ai-telemetry-pipeline`의 `sql/rds/*`는 dev 편의용이며 이 레포의 Flyway가 진실원이다.

## ⚠️ 루트의 마크다운·HTML은 스크래치다 — 신뢰 금지

`PLAN.md` · `PR.md` · `RALPH-PLAN.md` · `DOMAIN-BOUNDARY-NOTES.md` · `PLAN-ADR-0008.md` ·
`enrollment-api-branch-review.md` · `PR2-*.html`은 **작업 중 메모이지 스펙이 아니다.**

소스 주석 다수가 존재한 적 없는 `PLAN.md §6.2 / A5 / R4 / L11`을 인용한다.
**그 인용도 스펙이 아니다.** 주석이 가리키는 문서는 실재하지 않는다.

**후속(미착수)** — 이 인용들을 실제 권위 문서의 절(명세 `docs/enrollment-server-spec.md`,
`docs/adr/`, 허브 계약)로 교체하거나 삭제하는 정리가 남아 있다. 규모는 소스 기준
**44개 파일 / 58곳**(`grep -rn 'PLAN\.md' apps libs`)이라 PROJ-79 정합 라운드에서
의도적으로 유보했다. 정리 전까지는 어떤 `PLAN.md §…` 인용도 근거로 읽지 않는다 —
같은 내용이 필요하면 위 권위 문서 셋에서 찾는다.

**권위 있는 문서는 넷뿐이다.**

| 문서 | 담는 것 |
|---|---|
| `docs/enrollment-server-spec.md` | 서버 측 상세 명세 |
| `docs/module-map.md` | 모듈 구성·네임스페이스·의존 방향 — 모듈을 추가하기 전에 본다 |
| `docs/adr/` (0001–0009) | 설계 결정 |
| `../docs/contracts/enrollment-api.md` | telemetryctl과의 **계약** — 경계에 걸리는 변경은 여기가 기준 |

## 명령어

```bash
./gradlew build                                   # 전체 빌드
./gradlew :apps:enrollment-api:test               # 테스트
./gradlew :apps:enrollment-api:bootRun            # enrollment 서버 (8080)
./gradlew :apps:telemetry-ingest:bootRun          # OTLP 수집 서버 (4316)
docker compose up -d                              # 로컬 Postgres · ClickHouse
```

로컬에서 파이프라인 전체를 돌리는 절차는 `docs/enrollment-server-spec.md` 10절에 있다.

## 이 레포에서 특히 조심할 것

- **토큰 해시 방식.** `telemetry_token`만 HMAC-SHA256(`pulsemetry.token-hash-secret`)이고,
  초대 코드와 `installation_token`은 무염 SHA-256이다. **auth-proxy가 같은 키·같은 연산으로 조회하므로
  한쪽만 고치는 PR을 열지 않는다** (`../docs/contracts/enrollment-api.md` §4).
  연산 자체는 `:libs:security`의 `TelemetryTokenHasher` 한 벌뿐이다 — 발급(`:apps:enrollment-api`)과
  검증(`:libs:security`)이 같은 클래스를 쓴다. **고정 벡터 테스트를 지우지 마라.** auth-proxy가
  폐기될 때까지 크로스레포 계약의 앵커다.
- **enroll 응답은 정확히 4키다.** 클라이언트가 `DisallowUnknownFields`로 파싱하므로
  **필드를 추가하면 배포된 전 클라이언트가 깨진다.** 이 제약은 중첩 manifest까지 적용된다.
- **스키마 enum은 native enum**이다(ADR-0009가 ADR-0004의 varchar+CHECK를 대체). 진실원은 여전히 Flyway.
- 모듈 경계·네임스페이스 규칙은 ADR-0008(파이프라인 단계는 ADR-0010이 개정)이 정하고,
  현재 구성과 이름은 `docs/module-map.md`가 담는다.
  모듈을 추가하기 전에 둘 다 본다.
- **정규화 golden fixture의 기대값을 손으로 고치지 마라.** `libs/telemetry-adapter/src/test/resources/otlp/`의
  `*.normalized.jsonl`은 구 파이프라인의 Python normalizer가 구운 것이고, 그것이 이식의 오라클이다.
  기대값이 바뀌어야 하면 **먼저 구 레포에서 다시 굽고**(`scripts/regen-golden.py`) 그 변화가 의도된
  것인지 따진 다음 가져온다. 현행 결함 넷도 일부러 고정돼 있다 — 그 README가 목록을 담는다.
- **`_ingest.source_record_id`와 `record_id`는 다른 것이다.** 앞은 원본 추적용 해시이고 뒤가
  ClickHouse ReplacingMergeTree의 **멱등 키**다. 뒤의 해시 재료는 Python `str()` 표기라
  `None`·`True`처럼 적힌다 — Kotlin 기본 표기로 바꾸면 전 이벤트의 키가 바뀐다(`RecordId`·`Stringify` KDoc).
  기본값을 명시해 보내는 클라이언트는 `source_record_id`가 갈리고, non-optional 스칼라(`count`·
  `timeUnixNano` 등)의 명시 기본값은 `record_id`까지 갈릴 수 있다(ADR-0013 Negative).
- **`:libs:` 모듈에 `@Component`·`@Configuration`을 달지 않고 Boot starter도 끌지 않는다**(ADR-0011).
  컴포넌트 스캔 루트가 저장소 전체라, 라이브러리의 빈은 그 라이브러리를 올린 **모든** 앱에서 살아난다.
  starter 하나가 인증을 켠 적 없는 앱의 엔드포인트를 전부 잠글 수 있다. 조립은 앱이 한다.
- **OTel 컴포넌트를 이식할 때는 상위 저장소가 사양서다.** 운영 버전은 `v0.157.0`이고 형제 클론의
  워킹트리는 그보다 앞서 있으므로 **태그를 찍어 읽는다**(`git show v0.157.0:<path>`).
  특히 `redaction`의 `blocked_values`는 v0.157.0에서 **적용 순서가 비결정적**이었다(Go 맵 순회).
  이식본은 상위가 그 뒤에 고친 **선언 순서**를 따르고, `MaskingRules` KDoc이 근거를 담는다 —
  **그 목록의 순서를 바꾸면 마스킹 결과가 바뀐다.**
- **`enriched_events`의 DDL은 멱등 문장만 허용된다**(ADR 0015). 진실원은
  `libs/telemetry-persistence/src/main/resources/clickhouse/`의 `V*.sql`이고 기동 시 전량이 다시 돈다.
  **`V1`을 고치지 마라** — `CREATE TABLE IF NOT EXISTS`는 이미 있는 테이블에 아무 일도 하지 않아
  변경이 조용히 무시된다. 컬럼은 `V2` 파일에 `ALTER TABLE … ADD COLUMN IF NOT EXISTS`로 더한다.
  `IF NOT EXISTS`를 빠뜨린 문장은 **첫 기동에서는 성공하고 두 번째 기동에서 죽는다.**
- **적재의 JSON 표기 규칙이 두 개다.** 행 한 줄은 화이트리스트 **삽입 순서**이고, 그 안의
  `enrichment_json` 문자열만 **키 정렬**이다(`TelemetryJson.compact` / `.sorted`). 섞으면 저장되는
  값이 바뀐다. 어댑터에도 같은 성격의 인코더가 둘 있지만 `internal`이라 쓸 수 없다 — 그 중복은
  ADR 0014 Negative가 근거를 적어 뒀다.
- **`enrichment_json`에는 no-op provider 스텁 셋(github·jira·ai_analysis)의 빈 항목도 들어간다.**
  구 registry가 발견된 모든 provider에 항상 항목을 쓰기 때문이고, 스텁을 지우면 저장되는 값이
  현행과 달라진다. 그래서 아무것도 하지 않는 클래스 셋이 일부러 남아 있다.
- **metrics는 마스킹하지 않는다**(`Signal.METRICS.masked = false`). 현행 설정을 그대로 옮긴 것이고
  허브 계약 §5가 M6로 등록한 결함이다. 고치는 것은 별도 티켓이며 ADR 0012 Negative가 대가를 적어 뒀다.
- **상태 코드가 곧 크로스레포 계약이다**(허브 ADR 0006). 영구 실패는 **400**, 일시 실패는
  **503 + `Retry-After`** 다. telemetryctl 데몬이 4xx만 즉시 폐기하고 5xx는 전부 재시도하므로,
  영구 실패를 5xx로 돌리면 스키마 오류가 매 push마다 재시도 예산을 태우고도 드러나지 않는다.
  ClickHouse 응답의 4xx는 영구, `5xx`·`429`·`408`은 일시다 — **이 목록을 넓히지 마라.**
- **신원 스탬핑은 마스킹 뒤·아카이브 앞이다**(ADR 0016). 검증된 `tenant.id`가 `record_id` 해시의
  재료이고 그것이 ReplacingMergeTree의 멱등 키라, 신원 없는 원본을 재처리하면 실시간 경로와
  **다른 키**가 나와 중복으로 쌓인다. 순서를 뒤집지 마라.
- **`:libs:` 는 Boot starter를 끌지 않지만 조립 앱은 켠다**(ADR 0016). ADR 0011의 검사 대상은
  `:apps:enrollment-api`의 클래스패스다. `:apps:telemetry-ingest`의
  `spring-boot-starter-security`는 규칙 위반이 아니라 의도된 선택이다.
- **정규화 불변 규칙 다섯과 `enrichment_json` 승격 금지의 소유자는 ADR 0017이다.** KDoc은 규칙을
  반복하지 않고 그 번호를 가리킨다. 규칙을 바꾸려면 ADR 0017을 개정하고 golden을 다시 굽는다.
- **예외 → 상태 매핑은 `IngestPipeline` KDoc의 표 하나다.** 정규화 실패·보강 영구 오류·ClickHouse 4xx가
  400, 일시 장애와 분류되지 않은 예외가 503이다. 행을 옮기면 허브 `contracts/telemetry-ingest.md` §8을
  같은 커밋에서 고친다.
- **`:apps:telemetry-ingest`의 OTLP 밖 경로는 기본 닫힘이다.** 둘째 `SecurityFilterChain`이 `/v1/healthz`만
  열고 나머지는 `denyAll`이다. 관리 엔드포인트를 얹으려면 그 체인에 경로를 명시한다.
- ADR을 추가하면 `0018`부터. 파일명은 **한국어 슬러그**. 인덱스는 `docs/adr/README.md` —
  Status 첫 토큰이 바뀌면 같은 커밋에서 표를 갱신한다.
