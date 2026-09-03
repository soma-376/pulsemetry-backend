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
apps/enrollment-api/         Spring Boot 애플리케이션 (현재 유일한 app)
libs/enrollment-persistence/ JPA 엔티티 · 리포지토리 · Flyway 마이그레이션
libs/security/               횡단 인증 라이브러리 — OTLP 경로 ptt_ 검증 · telemetry token 해시
libs/telemetry-collector/    파이프라인 수집 단계 — OTLP 수신 · 마스킹 · 원본 아카이브
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
| 텔레메트리 파이프라인 이관 | 일부 착수 — 도착지는 `:apps:telemetry-ingest`와 단계별 `:libs:` 모듈 | 허브 ADR 0004(병합 채택). **인증 계층 `:libs:security`(PROJ-102)와 수집 단계 `:libs:telemetry-collector`(PROJ-114)는 모듈 이식만 끝났다** — 조립 앱이 없어 둘 다 트래픽을 안 받는다. 변환·보강·적재는 미착수 |

**파이프라인 전체 병합은 채택됐다(허브 ADR 0004 — ADR-0006은 `Superseded by 허브 ADR 0004`).**
파이프라인 로직 전체와 ClickHouse 스키마 소유권이 이 레포로 온다. **배포 단위는 하나이고 OTel Collector
바이너리를 쓰지 않는다** — 수집·마스킹도 이 레포가 구현한다(허브 ADR 0005). 단계는 배포 경계가 아니라
모듈 경계로 나뉘며, 구성은 `docs/module-map.md`가 담는다.
**동작하는 파이프라인은 여전히 `ai-telemetry-pipeline`에 있다.** 이식은 인증 계층
(`:libs:security`, PROJ-102)과 수집 단계(`:libs:telemetry-collector`, PROJ-114)까지 끝났고,
그 모듈들을 조립할 앱이 아직 없어 **실제 OTLP 트래픽은 계속 auth-proxy와 collector 컨테이너가
받는다.** 변환·보강·적재는 진행 전이다.
`../docs/contracts/telemetry-ingest.md`의 검증 주체·신원 전파 서술은 전환이 실제로 끝난 시점에 고친다
(허브 ADR 0005 Follow-up이 "그전까지 현행 서술이 사실"로 못박았다).

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
./gradlew :apps:enrollment-api:bootRun            # 로컬 실행
docker compose up -d                              # 로컬 Postgres
```

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
- **`:libs:` 모듈에 `@Component`·`@Configuration`을 달지 않고 Boot starter도 끌지 않는다**(ADR-0011).
  컴포넌트 스캔 루트가 저장소 전체라, 라이브러리의 빈은 그 라이브러리를 올린 **모든** 앱에서 살아난다.
  starter 하나가 인증을 켠 적 없는 앱의 엔드포인트를 전부 잠글 수 있다. 조립은 앱이 한다.
- **OTel 컴포넌트를 이식할 때는 상위 저장소가 사양서다.** 운영 버전은 `v0.157.0`이고 형제 클론의
  워킹트리는 그보다 앞서 있으므로 **태그를 찍어 읽는다**(`git show v0.157.0:<path>`).
  특히 `redaction`의 `blocked_values`는 v0.157.0에서 **적용 순서가 비결정적**이었다(Go 맵 순회).
  이식본은 상위가 그 뒤에 고친 **선언 순서**를 따르고, `MaskingRules` KDoc이 근거를 담는다 —
  **그 목록의 순서를 바꾸면 마스킹 결과가 바뀐다.**
- **metrics는 마스킹하지 않는다**(`Signal.METRICS.masked = false`). 현행 설정을 그대로 옮긴 것이고
  허브 계약 §5가 M6로 등록한 결함이다. 고치는 것은 별도 티켓이며 ADR 0012 Negative가 대가를 적어 뒀다.
- ADR을 추가하면 `0013`부터. 파일명은 **한국어 슬러그**. 인덱스는 `docs/adr/README.md` —
  Status 첫 토큰이 바뀌면 같은 커밋에서 표를 갱신한다.
