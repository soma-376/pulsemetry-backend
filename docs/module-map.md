# 모듈 지도

이 저장소의 Gradle 모듈이 무엇이고, 새 모듈을 어디에 어떤 이름으로 만드는지를 적는다.
`settings.gradle.kts`에 모듈을 추가하거나 패키지를 새로 만들기 전에 이 문서를 본다.

결정의 **배경과 대안**은 ADR에 있다. 이 문서는 "무엇이 어떻게 나뉘어 있는가"만 다룬다.
규칙의 근거가 필요하면 [ADR 0008](adr/0008-모듈-경계와-네임스페이스-규칙-확정.md)을 읽는다.

관련 결정 기록: [ADR 0002](adr/0002-멀티모듈-프로젝트-구축.md) ·
[ADR 0007](adr/0007-인증-계층으로-spring-security-사용.md) ·
[ADR 0008](adr/0008-모듈-경계와-네임스페이스-규칙-확정.md) ·
[허브 ADR 0004](../../docs/adr/0004-telemetry-pipeline-repo-merge.md)

---

## 1. 현재 모듈

```text
pulsemetry-backend
├── apps/
│   └── enrollment-api/              com.team376.pulsemetry.enrollment
└── libs/
    └── enrollment-persistence/      com.team376.pulsemetry.persistence.enrollment
                                     └ enrollment 스키마 14 테이블 · Flyway 마이그레이션
```

`settings.gradle.kts`의 `include`는 이 둘뿐이다. 아래 2절부터 나오는 모듈은 **아직 없다.**

## 2. 도메인 경계 — 쓰기 소유권

**한 테이블의 쓰기 로직은 한 모듈이 소유한다. 읽는 모듈은 몇 개든 좋다.**
도메인이란 한 모듈이 쓰기를 독점하는 테이블 묶음이다. 스키마의 진실원이 Flyway이므로(ADR 0004),
소유 질문은 "이 테이블의 `CREATE TABLE`이 어느 모듈 아래에 있는가"라는 파일 경로 질문으로 환원된다.

| 도메인 | 테이블 | 쓰기 소유 |
|---|---|---|
| directory | `tenants` · `members` · `teams` · `team_memberships` | 관리자 API (미구현) |
| enrollment | `invitations` · `installations` · `installation_credentials` · `telemetry_tokens` · `installation_manifest_assignments` | `enrollment-api` |
| policy | `manifests` | 관리자 API (미구현) |
| contract | `contracts` · `contract_term_commitments` · `contract_token_discounts` · `contract_memberships` | 관리자 API (미구현) |
| telemetry | ClickHouse `enriched_events` | `telemetry-ingest` (미구현) |

`installation_manifest_assignments`는 policy가 아니라 **enrollment 소유**다. 정책의 정의가 아니라
installation의 배포 상태를 담기 때문이다.

**도메인 경계가 아닌 것**: 인바운드 프로토콜(HTTP·OTLP·이벤트)은 배포 단위의 축이고, 읽기 전용 화면은
읽기 모델이며, 화면의 집계 축은 같은 테이블을 group by 하는 방향이고, 인증·로깅 같은 횡단 관심사는
공유 라이브러리다. 어느 쪽도 도메인을 새로 만들지 않는다.

## 3. 네임스페이스 규칙

**어떤 패키지도 두 모듈이 공급하지 않는다.** 패키지가 겹쳐도 빌드는 실패하지 않고 클래스패스 순서가
로드를 정하므로, 컴파일러가 잡아주지 않는다.

| 모듈 | 루트 패키지 |
|---|---|
| `:apps:<컨텍스트>-<인바운드>` | `com.team376.pulsemetry.<컨텍스트>` |
| `:libs:<컨텍스트>-<역할>` | `com.team376.pulsemetry.<역할>.<컨텍스트>` |
| `:libs:<역할>` (컨텍스트에 속하지 않는 횡단 모듈) | `com.team376.pulsemetry.<역할>` |

- `<인바운드>`는 그 배포 단위가 무엇에 의해 깨어나는지다 — `api`(HTTP) · `ingest`(OTLP) ·
  `worker`(이벤트·스케줄) · `mcp`. **접미사는 배포 단위를 구별할 뿐 패키지에는 반영하지 않는다.**
- 한 컨텍스트에 배포 단위가 둘 이상이 되면 그때 `com.team376.pulsemetry.<컨텍스트>.<인바운드>`로
  한 단계 내린다. 기계적인 rename이므로 그 시점까지 미뤄도 비용이 늘지 않는다.
- 모든 모듈은 `com.team376.pulsemetry` 아래에 둔다. Spring Boot 컴포넌트 스캔이
  `@SpringBootApplication` 패키지를 기준으로 삼으므로, 이것이 `@EntityScan` 없이 동작하는 전제다.
- **애플리케이션 모듈의 메인 클래스만 예외로 루트(`com.team376.pulsemetry`)에 둔다** — 스캔 출발점이다.
  앱끼리는 의존하지 않으므로 두 앱이 한 클래스패스에 오르지 않는다.
- `<역할>`로 확정된 것은 `-persistence` · `-event`와 횡단 모듈 `security`뿐이다. 그 밖의 역할 이름은
  필요한 시점에 정한다.

## 4. 의존 방향

- `:apps:*`는 `:libs:*`에만 의존한다.
- **`:apps:*`끼리는 의존하지 않는다.** 앱 사이의 런타임 협력은 이벤트이고, 정의가 갈라지면 안 되는
  코드는 `:libs:`로 올린다.
- `:libs:*`는 `:apps:*`에 의존하지 않는다. `:libs:*` 사이의 의존은 단방향만 둔다.
- `project()` 간선은 기본 `implementation`이고, `api()`는 그 타입이 소비자의 계약일 때만 쓴다
  (`:libs:enrollment-persistence`가 JPA·JDBC를 `api()`로 노출한 것이 선례다).
- 아웃바운드 기술이 둘 이상이면 라이브러리를 기술별로 나눈다(`-persistence` / `-messaging`).

## 5. 텔레메트리 파이프라인이 들어올 자리

[허브 ADR 0004](../../docs/adr/0004-telemetry-pipeline-repo-merge.md)가 파이프라인을 이 저장소로
병합하기로 정했다. **배포 단위는 앱 하나와 OTel Collector 컨테이너 하나**이고, 파이프라인의 단계는
배포 경계가 아니라 위 규칙에 따른 **모듈 경계**로 나뉜다.

```text
apps/
└── telemetry-ingest/                com.team376.pulsemetry.telemetry
                                     앱은 조립만 한다 — 인증 배선 · 수신 · 단계 호출 · 적재 호출
libs/
├── security/                        com.team376.pulsemetry.security
│                                    ptt_ 검증 · 신원 헤더 부여 (ADR 0007)
├── telemetry-normalizer/            com.team376.pulsemetry.normalizer.telemetry
│                                    OTLP 판독 · 벤더 어댑터 · 정규화 모델
├── telemetry-enrichment/            com.team376.pulsemetry.enrichment.telemetry
│                                    조직 결합 (team_memberships as-of 조인)
└── telemetry-persistence/           com.team376.pulsemetry.persistence.telemetry
                                     ClickHouse 스키마 · 적재
```

- 앱은 collector의 **앞**(인증·신원 헤더 부여)과 **뒤**(정규화·보강·적재) 양쪽에 선다. 두 경로는 같은
  앱의 서로 다른 엔드포인트다.
- **마스킹은 모듈이 아니다.** collector 설정의 `redaction`·`secrets` processor가 담당하며 앱이
  재구현하지 않는다.
- `:libs:security`는 컨텍스트에 속하지 않는 횡단 모듈이므로 `<컨텍스트>-<역할>`이 아니라 `<역할>`
  형태를 쓴다. OTLP 경로와 관리자 API 경로가 같은 인증 코드를 공유할 자리가 여기다.
- 이 컨텍스트의 배포 단위는 하나이므로 3절의 내림 조항(`<컨텍스트>.<인바운드>`)은 발동하지 않는다.

## 6. 모듈을 언제 만드는가

**두 번째 소비자가 실제로 생겼을 때 승격한다.** 예측으로 나누지 않는다.

- `:libs:enrollment-persistence`의 도메인별 분할(directory · enrollment · policy · contract)은
  예정돼 있고, 트리거는 **관리자 API 앱의 첫 커밋**이다. 같은 시점에
  `InvitationAdminService.createInvitedMember()`의 `members` 직접 쓰기를 directory 모듈이 제공하는
  연산 호출로 바꾼다.
- 모듈 간 테스트 지원 코드는 `java-test-fixtures`로 공유한다. 테스트 전용 모듈을 따로 만들지 않는다.
- `:apps`·`:libs` 디렉터리 자체는 빌드 스크립트를 갖지 않는다. 루트가 `buildFile.exists()`로 건너뛴다.
