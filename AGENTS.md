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
apps/enrollment-api/         사용자 인증 · 온보딩·조직 관리 · enrollment · manifest · 부트스트랩 서빙 (HTTP)
apps/telemetry-ingest/       조립 앱 — OTLP 수신부터 적재까지 한 프로세스. 배선만 한다
apps/dashboard-api/          분석·설정·카탈로그 조회 API — 원천은 읽기만, 쓰기는 자기 캐시뿐. 사용자 인증 어댑터 (ADR 0026)
apps/retention-worker/       조직별 보존 삭제 — 서버가 아닌 일회성 실행. 분석 원본 DELETE 권한은 여기뿐 (ADR 0024)
libs/enrollment-persistence/ JPA 엔티티 · 리포지토리 · Flyway 마이그레이션
libs/security/               횡단 인증 라이브러리 — 사용자 JWT·세션·암호 검증, OTLP 경로 ptt_ 검증 · telemetry token 해시
libs/telemetry-collector/    파이프라인 수집 단계 — OTLP 수신 · 마스킹 · 신원 스탬프 · 원본 아카이브
libs/telemetry-adapter/      파이프라인 변환 단계 — 관측 모델 2판 · 제품 프로파일(Codex · Claude Code) · 가격 단계
libs/telemetry-enricher/     파이프라인 보강 단계 — member_id · 대표 팀 as-of · provider 주석
libs/telemetry-persistence/  파이프라인 적재 단계 — ClickHouse 스키마 소유 · 분석 테이블 · 수신 ledger sink
libs/telemetry-ops-persistence/ 수집 운영 기록의 RDS 쪽 — telemetry_ops 스키마 · 생애 요약 · 백필
libs/vendor-connector/       벤더 좌석 커넥터 — 포트 · 커넥터 설명 · 조립 검사 (ADR 0048). Spring 없음
```

**소유하는 것**: `POST /v1/enroll`, `POST /v1/installations/telemetry-token`, `GET /v1/manifest`, `POST /v1/invitations`,
설치 보고 `POST /v1/installations/{installationId}/heartbeat`, 데몬 업데이트 확인 `GET /api/v1/check-updates`, 도입 문의 `POST /v1/inquiries`,
부트스트랩 스크립트·바이너리 서빙(`GET /windows|/unix|/bin/{f}`), manifest 저장, 조직 관리 명령(`/api/v1/organizations/{id}/…` — 명세 §12·§13),
그리고 **enrollment 스키마의 진실원(Flyway)**. 조회(분석·설정·좌석·알림·작업 상태)는 `:apps:dashboard-api`가 맡는다.

**현재 구현과 남은 범위** — 작업 트리의 코드 기준이며, 운영 배포나 실제 DB 적용 완료를 뜻하지 않는다.

| 항목 | 상태 | 근거 |
|---|---|---|
| 사람 계정·로그인 | 구현됨. 설정으로 활성화 | enrollment-api가 로그인·갱신·로그아웃·현재 사용자 조회를 제공하고 dashboard-api가 JWT와 현재 세션을 검증한다. ADR 0018·0026, `docs/user-auth-operations.md` 참고. OIDC/SAML SSO는 미구현(별도 작업 PROJ-186 — 이 저장소에 아직 없다) |
| 수집 정책·온보딩 | 최초 생성·수정·완료 상태 구현 | `PUT /collection-policy`가 최초 manifest를 만들거나 새 판을 저장하고, 회수 기준·집계 보존을 manifest 와 따로 저장한다. 서버가 기존 설치에 밀어 넣지 않는다. telemetryctl 기본 브랜치의 데몬은 새 정책을 스스로 받지 않는다 — 이미 설치된 기기는 다시 설치해야 새 판을 받는다(ADR 0053). ADR 0029·0032·0033·0046 |
| 설치 보고·업데이트 확인 | 서버 구현됨. 데몬 쪽은 업데이트 확인만 있다 | 설치 보고 수신(수집 구간·적용 판), 업데이트 확인(telemetryctl 릴리스 디렉터리와 `SHA256SUMS`로 판 확인), 정책 적용 현황·업데이트 안내 메일. telemetryctl 기본 브랜치에는 보고 송신·RT 재조회·CLI 로그인이 없어 그 데몬의 설치는 적용 판 확인 불가다. ADR 0040·0043·0053, 허브 `contracts/daemon-updates.md` |
| 메일 | 구현됨. 설정으로 활성화 | 초대 메일·문의 통지·설치 업데이트 안내를 outbox 로 적재하고 enrollment-api 의 발송 작업이 SMTP로 보낸다. ADR 0037·0038·0043 |
| 좌석·벤더 연결·청구 | 구현됨. 벤더 연결은 설정으로 활성화 | 좌석 원장·수동 기록·CSV, 커넥터 동기화(Claude Enterprise·Cursor Enterprise·Copilot·Gemini)·회수·복원, 벤더 청구 누계. 실계정 검증은 남았다(`docs/vendor-connector-verification.md`). ADR 0048·0049·0050 |
| 알림 | 구현됨 | 규칙 켜기·모델·도구 목록(enrollment-api), 주기 평가·개요 미확인 수·확인(dashboard-api 평가 + enrollment-api 확인). 한도 초과는 근거가 없어 켤 수 없다. ADR 0051 |
| 대시보드 API | 개요·팀·구성원·설정·카탈로그·수집 상태·좌석·알림·작업 상태 조회 구현 | `docs/dashboard-server-spec.md` 참고. 관리 쓰기는 enrollment-api가 맡는다. 기간 완전성·비교(ADR 0042), 수집 상태 판정(ADR 0041). API 구현과 프론트 전체 배선·E2E 완료는 별개 |
| 텔레메트리 파이프라인 이관 | **코드는 끝났다. 배포만 남았다** | 인증(PROJ-102) · 수집(PROJ-114) · 변환(PROJ-103) · 보강과 적재(PROJ-104)에 이어 **조립 앱 `:apps:telemetry-ingest`(PROJ-105)까지 섰다.** 적재는 정규화 계약 2판(ADR 0020)의 분석 테이블 둘(`telemetry_events` · `telemetry_metric_points`)이고 구 `enriched_events` 는 새 행을 받지 않는다. 수신 ledger · 생애 요약(ADR 0021)은 허브 ADR 0007 채택 전까지 `pulsemetry.telemetry.ops.enabled` 로 끈다. 로컬에서는 다섯 모듈이 한 요청에서 돈다 — 남은 것은 infra 가 이 앱을 배포하고 collector 컨테이너를 내리는 일이다(PROJ-106) |

**파이프라인은 이 레포의 단일 앱이다**(허브 ADR 0004·0005 — 배포 단위 하나, OTel Collector 바이너리 없음).
모듈 구성은 `docs/module-map.md`가 담는다. **이식은 끝났고 배포만 남았다** — 위 표의 마지막 행이 상태다.
전환 전까지는 실제 OTLP 트래픽을 auth-proxy와 collector 컨테이너가 받으므로, 허브
`contracts/telemetry-ingest.md` §1·§3·§4의 현행 서술은 infra가 전환을 끝낸 뒤에 고친다(허브 ADR 0005 Follow-up).

**이 레포가 소유하지 않는 것** — 요청이 오면 올바른 레포를 알린다.

| 항목 | 소유 레포 |
|---|---|
| AWS 리소스, 태스크 정의, **현행 파이프라인의** 배포 collector 설정 | `infra` |
| 로컬 수신기·데몬·벤더 도구 배선, enroll 봉투·manifest **계약 스키마 파일**(`enrollment-envelope`·`enrollment-manifest`) | `telemetryctl` |
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

**현재 구현을 확인할 문서** — 제품·레포 간 계약의 우선순위는 문서 허브를 따른다.

| 문서 | 담는 것 |
|---|---|
| `docs/enrollment-server-spec.md` | 설치·설치 보고·업데이트 확인·사용자 인증·온보딩·조직 관리(좌석·벤더 연결·알림 규칙 포함)·문의·메일 명세 |
| `docs/dashboard-server-spec.md` | 분석·설정·카탈로그·수집 상태·좌석·알림·작업 상태 조회 명세 |
| `docs/vendor-connector-evidence.md` · `docs/vendor-connector-verification.md` | 벤더 커넥터의 공식 문서 근거와 실계정 검증 절차 |
| `tools/mock-vendor/README.md` | 로컬 모의 벤더 서버(벤더 연결·회수·복원 E2E) |
| `docs/user-auth-operations.md` | 사용자 인증 활성화·키·세션 운영 |
| `tools/dev-seed/README.md` | Docker 전용 시드 생성·적재·검증 |
| `docs/frontend-e2e-scenarios.md` | 실제 API E2E 목표 시나리오. 통과 보고서가 아님 |
| `docs/module-map.md` | 모듈 구성·네임스페이스·의존 방향 — 모듈을 추가하기 전에 본다 |
| `docs/adr/` | 설계 결정. 최신 목록과 상태는 `docs/adr/README.md` 참고 |
| `../docs/contracts/enrollment-api.md` | telemetryctl과의 **계약** — 경계에 걸리는 변경은 여기가 기준 |

## 명령어

```bash
./gradlew build                                   # 전체 빌드
./gradlew :apps:enrollment-api:test               # 테스트
./gradlew :apps:enrollment-api:bootRun            # enrollment 서버 (8080)
./gradlew :apps:telemetry-ingest:bootRun          # OTLP 수집 서버 (4316)
./gradlew :apps:dashboard-api:bootRun             # 분석 조회 API (8081) — PULSEMETRY_DASHBOARD_RETRY_AFTER 필수
./gradlew :apps:retention-worker:bootRun --args='--tenant=<uuid> --retention-months=<N> --as-of=<ISO-8601>'  # 보존 삭제 한 번 — 설정 전부 필수, 종료 코드 0·1·2·3
./gradlew :apps:retention-worker:bootRun --args='--requests'  # 요청 모드 — 저장된 보존 정리 요청을 차례로 실행(ADR 0047). requests.lease·max-runs 필수
docker compose up -d --build                      # 로컬 DB·마이그레이션·A/B/C 시드·개발 인증 키 준비
docker compose ps -a dev-seed                     # 일회성 준비 작업의 Exited (0) 확인
```

Spring 서버는 Compose와 별도로 실행한다. local 프로필·시드 보존·선택 초기화 절차는
`tools/dev-seed/README.md`를 따른다. 서버 재시작이나 프론트 fixture 갱신은 기존 시드를 초기화하지 않는다.

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
- **dbml 의 enum 10종은 native enum**이다(ADR-0009가 ADR-0004의 varchar+CHECK를 대체). 진실원은 여전히 Flyway.
  그 뒤에 더한 서버 전용 상태 값은 `varchar + CHECK` 다 — `EnrollmentSchemaMigrationTest`가 enum 집합을 정확히 고정한다.
- 모듈 경계·네임스페이스 규칙은 ADR-0008(파이프라인 단계는 ADR-0010이 개정)이 정하고,
  현재 구성과 이름은 `docs/module-map.md`가 담는다.
  모듈을 추가하기 전에 둘 다 본다.
- **정규화 fixture의 기대값은 명세에서 쓴다.** `libs/telemetry-adapter/src/test/resources/otlp-v2/`가
  정규화 계약 2판의 fixture다(ADR 0020 §9). 구현을 돌린 출력을 기대값으로 붙여 넣지 않는다. 실캡처는
  `scripts/otlp-fixtures/`의 익명화 도구와 관문을 통과한 추출본만 넣는다. 제품 프로파일을 검증 완료로
  표시하려면 원천 근거와 fixture가 **둘 다** 있어야 한다(ADR 0020 부록 C · 제품별 `PROFILE-EVIDENCE.md`).
- **분석 행의 키는 `observation_id`이고 `analysis_hash`는 키가 아니다**(ADR 0020 §2·§3). `observation_id`의
  재료(검증된 `tenant_id`·`installation_id`, signal, 원본 `service.name`, `source_time`, 네이티브 ID 또는
  canonical fingerprint)를 바꾸면 전 관측의 키가 바뀐다 — `ObservationIds.IDENTITY_VERSION`을 올리는 일이다.
  같은 관측의 교체는 `row_version = (normalizer_rev << 32) | ingest_seq`가 정하고, 매핑·스키마·의미·가격·
  보강 규칙을 바꾸면 `RowVersioning.NORMALIZER_REV`를 올린다.
- **`:libs:` 모듈에 `@Component`·`@Configuration`을 달지 않고 Boot starter도 끌지 않는다**(ADR-0011).
  컴포넌트 스캔 루트가 저장소 전체라, 라이브러리의 빈은 그 라이브러리를 올린 **모든** 앱에서 살아난다.
  starter 하나가 인증을 켠 적 없는 앱의 엔드포인트를 전부 잠글 수 있다. 조립은 앱이 한다.
- **OTel 컴포넌트를 이식할 때는 상위 저장소가 사양서다.** 운영 버전은 `v0.157.0`이고 형제 클론의
  워킹트리는 그보다 앞서 있으므로 **태그를 찍어 읽는다**(`git show v0.157.0:<path>`).
  특히 `redaction`의 `blocked_values`는 v0.157.0에서 **적용 순서가 비결정적**이었다(Go 맵 순회).
  이식본은 상위가 그 뒤에 고친 **선언 순서**를 따르고, `MaskingRules` KDoc이 근거를 담는다 —
  **그 목록의 순서를 바꾸면 마스킹 결과가 바뀐다.**
- **ClickHouse DDL은 멱등 문장만 허용된다**(ADR 0015). 진실원은
  `libs/telemetry-persistence/src/main/resources/clickhouse/`의 `V*.sql`이고 기동 시 전량이 다시 돈다.
  **배포된 `V*`를 고치지 마라** — `CREATE TABLE IF NOT EXISTS`는 이미 있는 테이블에 아무 일도 하지 않아
  변경이 조용히 무시된다. 컬럼은 다음 번호 파일에 `ALTER TABLE … ADD COLUMN IF NOT EXISTS`로 더한다.
  `IF NOT EXISTS`를 빠뜨린 문장은 **첫 기동에서는 성공하고 두 번째 기동에서 죽는다.**
  대시보드 캐시(`apps/dashboard-api/src/main/resources/clickhouse/dashboard-cache/`)도 같은 규약이다(ADR 0023).
- **분석 행은 모든 컬럼을 명시하는 typed 인코더로 쓴다**(ADR 0020 §1 · `AnalysisRowWriter`). 정수는 Double을
  거치지 않고, `Decimal(38, 12)`·`DateTime64(9)`(1900년 이전)·`FixedString(64)`·UInt 범위를 넘는 값은 **적재
  전에 거부**한다 — ClickHouse는 그런 값을 오류 없이 절삭·왜곡한다. Float64는 문자열로 보내 INSERT가
  `toFloat64`로 정밀 파싱한다(24.8의 입력 포맷 float 파서는 1 ULP 어긋난다 — `AnalysisInsert` KDoc).
  `enrichment_json` 문자열만 키 정렬 표기다(`EnrichmentJson`).
- **`enrichment_json`에는 no-op provider 스텁 셋(github·jira·ai_analysis)의 빈 항목도 들어간다.**
  등록된 모든 provider가 항상 항목을 쓰기 때문이고(ADR 0017 규칙 8 — 스텁은 `EnrichmentProvider.annotate`의
  기본 빈 맵), 스텁을 지우면 저장되는 값이 달라진다. 그래서 아무것도 하지 않는 클래스 셋이 일부러 남아 있다.
- **metrics도 마스킹한다**(`Signal.METRICS.masked = true` — 허브 계약 §5 M6 해소, ADR 0012 Follow-up).
  상위 redaction v0.157.0은 metrics의 resource·scope·data point 속성만 보지만, 이식본은 exemplar의
  `filteredAttributes`까지 덮는다(`AttributeWalker` KDoc). 마스킹 정책(규칙·순서·대상 시그널)을 바꾸면
  `MaskingPolicy.VERSION`을 올린다 — 아카이브 영수증과 분석 행의 `masking_version`이 그 값이다.
- **상태 코드가 곧 크로스레포 계약이다**(허브 ADR 0006). 영구 실패는 **400**, 일시 실패는
  **503 + `Retry-After`** 다. telemetryctl 데몬이 4xx만 즉시 폐기하고 5xx는 전부 재시도하므로,
  영구 실패를 5xx로 돌리면 스키마 오류가 매 push마다 재시도 예산을 태우고도 드러나지 않는다.
  ClickHouse 응답의 4xx는 영구, `5xx`·`429`·`408`은 일시다 — **이 목록을 넓히지 마라.**
- **신원 스탬핑은 마스킹 뒤·아카이브 앞이다**(ADR 0016). 검증된 `tenant_id`·`installation_id`가
  `observation_id`의 재료이고 재처리는 아카이브 원본의 신원으로 문맥을 재현하므로, 신원 없는 원본을
  재처리하면 실시간 경로와 **다른 키**가 나와 중복으로 쌓인다. 순서를 뒤집지 마라. 같은 키가 여러 번 와도
  **전부** 덮어쓴다 — 첫 항목만 덮어쓰면 뒤의 자기신고가 남는다.
- **`:libs:` 는 Boot starter를 끌지 않지만 조립 앱은 켠다**(ADR 0016). ADR 0011의 검사 대상은
  `:apps:enrollment-api`의 클래스패스다. `:apps:telemetry-ingest`의
  `spring-boot-starter-security`는 규칙 위반이 아니라 의도된 선택이다.
- **정규화 불변 규칙과 `enrichment_json` 승격 금지는 ADR 0017이 소유하고, ADR 0020이 그 일부를 대체했다** —
  규칙 2·4·6을 대체하고 규칙 7의 승격 목록을 `member_id`·`team_id_as_of`·`team_ids_as_of`로 넓혔다. 규칙
  1·3·5·8은 유효하다. KDoc은 규칙을 반복하지 않고 그 번호를 가리킨다. 규칙을 바꾸려면 해당 ADR을 개정하고
  `otlp-v2` 기대값을 명세에서 다시 쓴다.
- **예외 → 상태 매핑은 `IngestPipeline` KDoc의 표 하나다.** 정규화 실패·보강 영구 오류·ClickHouse 4xx·적재 전
  거부가 400, 일시 장애(보강·ClickHouse·수집 운영 기록)와 분류되지 않은 예외가 503이다. 영구 실패 push도
  수신 ledger·요약에 기록한 뒤 400이고, 기록이 실패하면 503이다(ADR 0021). 행을 옮기면 허브
  `contracts/telemetry-ingest.md` §8을 같은 커밋에서 고친다.
- **`:apps:telemetry-ingest`의 OTLP 밖 경로는 기본 닫힘이다.** 둘째 `SecurityFilterChain`이 `/v1/healthz`만
  열고 나머지는 `denyAll`이다. 관리 엔드포인트를 얹으려면 그 체인에 경로를 명시한다. 예외는 ERROR
  디스패치 하나다 — 내부 오류는 원래 서버 오류 응답을 보존한다. 외부의 계약 밖 요청은
  `denyAll`을 유지하며 `404 text/plain`으로 거부한다(허브 계약 §8).
- **인증 조회의 DB 장애는 401도 403도 아니다.** 데몬은 그 둘을 같은 칸에 두고 토큰을 폐기·재발급한다.
  필터에 넘긴 `TelemetryTokenUnavailableHandler`가 503 + `Retry-After`를 쓴다. 예외를 컨테이너까지
  흘리지 마라.
- **분석 INSERT 의 fence 조건과 `async_insert = 0` 을 빼지 마라**(ADR 0024). 두 분석 sink 의 INSERT
  (`AnalysisInsert.fenced`)는 서버에서 `telemetry_retention_fence` 를 다시 읽어 tenant 경계 이전 행을 쓰지 않는다.
  보존 작업이 "구 경계로 검사한 쓰기가 끝났다"고 판정하는 근거가 이 조건과 process list 다(시작 때 한 번 평가 —
  `RetentionFenceEvidenceTest`). ingest 는 push 마다 RDS 경계를 읽고(캐시 없음, 설정으로 끄지 않음) 읽지 못하면 503 이다.
  sink 는 `AnalysisWriteBoundary` 없이 쓰지 않는다 — 재처리 경로도 같은 sink 를 지난다. 경계는 MAX 로만 움직인다
  (`TenantRetentionBoundaryStore.advance`). ClickHouse 이미지를 올리면 증거 테스트가 먼저 통과해야 한다.
  삭제는 `:apps:retention-worker` 만 한다 — 발효 → fence → drain → DELETE → 남은 행 0 확인의 순서를 바꾸지 마라(ADR 0024 §4).
  그 앱은 JPA·Flyway 자동설정을 `spring.autoconfigure.exclude` 로 끈다 — 분석 테이블 모듈이 enrollment-persistence 를 끌어온다.
  조직이 집계 보존을 줄이면 저장 명령이 보존 정리 요청을 남기고, 그 앱의 요청 모드(`--requests`)가 같은 순서로 실행한다(ADR 0047) —
  enrollment-api·dashboard-api 에서 원본을 지우지 마라. 요청 모드는 기존 인자 모드와 종료 코드를 바꾸지 않는다.
- **계약 테스트는 원격 telemetryctl 기본 브랜치에 있는 스키마만 원본으로 읽는다.** PR CI 는 그 브랜치의 `contracts` 를 체크아웃해
  `PULSEMETRY_CONTRACTS_DIR` 로 넘긴다(`.github/workflows/build.yml` — 이 워크플로를 고쳐 맞추지 않는다). 지금 그곳에는
  `enrollment-envelope`·`enrollment-manifest` 둘뿐이고 `ContractSchemas` 는 그 둘만 등록한다. 원격에 스키마가 없는 API 는
  명세가 오라클이다 — 설치 보고는 `docs/enrollment-server-spec.md` §4.5 의 표, 재조회 봉투와 AT 클레임은 §11·§11.1 의 표,
  업데이트 확인은 원격 telemetryctl 의 `internal/updatecheck/client.go` 가 읽는 것. 원격에 없는 스키마를 로컬 체크아웃에서 읽거나
  저장소로 복사해 오라클로 쓰지 마라 — 로컬에서 녹색이고 CI 에서 죽는다. Gradle 의 기본 계약 경로는 형제 `../telemetryctl/contracts` 이므로,
  CI 와 같은 조건으로 돌리려면 `PULSEMETRY_CONTRACTS_DIR` 을 원격 기본 브랜치의 `contracts` 로 둔다.
- **`GET /v1/manifest`는 사용자 RT만 받고 한 서버 트랜잭션에서 정책과 토큰을 회전한다**(ADR 0019 · 허브 ADR 0008).
  AT·`pit_`·`ptt_`는 받지 않는다. 로컬 적용 완료를 보장하지 않으며 OTLP는 여전히 `ptt_`다. 상태를 바꾸는 GET이라
  캐시·프리페치·자동 재시도를 걸지 않는다. 저장된 정책은 빌드 때 jar에 넣은 telemetryctl 원본 스키마로 검증한다 —
  그래서 `:apps:enrollment-api`의 Gradle 빌드와 이미지 빌드(named context `telemetry-contracts`)에는 계약 디렉터리가 필요하다.
- **벤더 자격증명은 암호문으로만 저장한다**(ADR 0048 §6). 키는 `pulsemetry.vendor-connections.credential-keys`(키 ID → Base64 32바이트)이고 관리 응답·메일 키와
  따로다. 응답·로그·예외 메시지·테스트 fixture에 평문을 싣지 않는다 — 연결 명령이 PUT인 것도 멱등 기록(요청 해시·응답)을 남기지 않기 위해서다.
  dashboard-api는 `VendorConnections`로 비밀 아닌 열만 읽는다. 옛 키는 `vendor_connections.credential_key_id`가 그 키를 쓰는 행이 없을 때만 뺀다.
- **메일은 outbox 다**(ADR 0037). 업무 쓰기와 같은 트랜잭션에서 적재하고 enrollment-api 의 발송 작업이 선점해 보낸다 — 명령 안에서 SMTP를 부르지 않는다.
  초대 코드는 메일 본문과 수락 링크의 fragment 에만 싣는다(ADR 0038). 발급은 발송이 아니다 — `delivery` 상태를 따로 낸다.
- **비동기 명령은 공통 작업 기록이다**(ADR 0039). 접수(202)는 완료가 아니다 — 결과는 dashboard-api 의 작업 상태 조회로 본다. 조치 대기(`awaiting_admin_action`)는 관리자의 확인으로만 끝난다.
- **설치 보고는 `installation_token`(무염 SHA-256)으로 인증하고 경로의 설치 ID와 대조한다**(ADR 0040). 수집 구간이 기간 완전성(ADR 0042)과 수집 상태 판정(ADR 0041)의 근거다 —
  최신 수신 시각만으로 정상·완전을 주장하지 않는다.
- **좌석 원장의 원천 우선순위**(ADR 0048): 연결된 등록 제품은 커넥터 동기화가 권위이고, 수동 기록은 커넥터 없는 플랜·연결 전에만 쓴다. 구매 수량으로 좌석을 만들지 않는다.
  원장은 벤더가 받아들였거나 관리자가 조치를 확인했을 때만 바뀐다(ADR 0049). 실제 청구액은 벤더 비용·지출 API 의 누계뿐이다 — 환산 비용·계약액을 복사하지 않는다(ADR 0050).
- **알림은 근거가 있는 규칙만 켠다**(ADR 0051). 평가 전제가 깨진 회차는 0건이 아니라 "평가하지 않음"과 사유다. 평가 기록은 dashboard-api 가 RDS `dashboard_cache` 에 쓴다 —
  snapshot 정리 작업이 지우지 않게 한다(ADR 0022 §2 를 ADR 0051 이 개정). 확인 기록은 enrollment 스키마이고 확인을 되돌리는 명령은 없다.
  좌석 원장의 우선순위(연결이 있으면 커넥터가 권위, 수동은 연결 전의 임시)는 ADR 0048 §3의 표이고 `SeatLedgerTest`가 그 표를 덮는다.
- ADR 번호는 `docs/adr/README.md`의 다음 미사용 번호를 확인한다. 파일명은 **한국어 슬러그**. 인덱스는 `docs/adr/README.md` —
  Status 첫 토큰이 바뀌면 같은 커밋에서 표를 갱신한다.
