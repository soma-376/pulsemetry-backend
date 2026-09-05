# 모듈 지도

이 저장소의 Gradle 모듈이 무엇이고, 새 모듈을 어디에 어떤 이름으로 만드는지를 적는다.
`settings.gradle.kts`에 모듈을 추가하거나 패키지를 새로 만들기 전에 이 문서를 본다.

결정의 **배경과 대안**은 ADR에 있다. 이 문서는 "무엇이 어떻게 나뉘어 있는가"만 다룬다.
규칙의 근거가 필요하면 [ADR 0008](adr/0008-모듈-경계와-네임스페이스-규칙-확정.md)을 읽는다.

관련 결정 기록: [ADR 0002](adr/0002-멀티모듈-프로젝트-구축.md) ·
[ADR 0007](adr/0007-인증-계층으로-spring-security-사용.md) ·
[ADR 0008](adr/0008-모듈-경계와-네임스페이스-규칙-확정.md) ·
[ADR 0010](adr/0010-파이프라인-단계를-모듈-경계로-나눈다.md) ·
[ADR 0011](adr/0011-라이브러리-모듈은-spring-조립을-앱에-위임한다.md) ·
[ADR 0013](adr/0013-정규화-입력은-protobuf-이고-원본-해시는-정규-json-으로-되살린다.md) ·
[ADR 0014](adr/0014-단계-모듈-사이에-데이터-타입-간선을-둔다.md) ·
[ADR 0015](adr/0015-clickhouse-ddl-은-번호-붙은-멱등-파일이고-기동-시-적용한다.md) ·
[ADR 0016](adr/0016-조립-앱은-인증-체인과-단계-호출을-배선하고-스키마-적용-실패를-견딘다.md) ·
[ADR 0017](adr/0017-정규화-불변-규칙과-enrichment-json-승격-금지는-이-저장소가-정한다.md) ·
[허브 ADR 0004](../../docs/adr/0004-telemetry-pipeline-repo-merge.md) ·
[허브 ADR 0005](../../docs/adr/0005-single-app-telemetry-topology.md) ·
[허브 ADR 0006](../../docs/adr/0006-otlp-ingest-retry-and-status-contract.md)

---

## 1. 현재 모듈

```text
pulsemetry-backend
├── apps/
│   ├── enrollment-api/              com.team376.pulsemetry.enrollment
│   └── telemetry-ingest/            com.team376.pulsemetry.telemetry
│                                    OTLP 수신부터 적재까지 한 프로세스 — 조립만 한다
└── libs/
    ├── enrollment-persistence/      com.team376.pulsemetry.persistence.enrollment
    │                                └ enrollment 스키마 14 테이블 · Flyway 마이그레이션
    ├── security/                    com.team376.pulsemetry.security
    │                                └ OTLP 경로의 ptt_ 검증 · telemetry token 해시
    ├── telemetry-collector/         com.team376.pulsemetry.telemetry.collector
    │                                OTLP 수신 · 상태 매핑 · OTLP/JSON 코덱
    │                                ├ masking/    blocked_values 14종 값 마스킹
    │                                └ archive/    제품별 원본 적재 (S3 · 파일)
    ├── telemetry-adapter/           com.team376.pulsemetry.telemetry.adapter
    │                                OTLP 읽기 · record_id 생성 · call_id 페어링
    │                                ├ model/      공통 스키마 (봉투 · payload · enum)
    │                                └ source/     벤더별 매핑 (claude_code · codex)
    ├── telemetry-enricher/          com.team376.pulsemetry.telemetry.enricher
    │                                사원 정보 결합 — as-of 조인 · provider 주석
    │                                └ provider/   EnrichmentProvider 와 그 구현
    └── telemetry-persistence/       com.team376.pulsemetry.persistence.telemetry
                                     ClickHouse 스키마 · 적재 — 쓰기 소유 모듈
```

`settings.gradle.kts`의 `include`는 이 여덟뿐이다. **5절이 예고한 모듈이 전부 섰다.**

`:apps:telemetry-ingest`는 조립 앱이다(PROJ-105). 도메인 로직을 담지 않는다 — 빈 등록·필터 체인
배선·설정 바인딩과 단계 호출이 전부이고, 그것이 ADR 0011이 라이브러리에서 걷어낸 몫이다.
기동·배선 정책은 [ADR 0016](adr/0016-조립-앱은-인증-체인과-단계-호출을-배선하고-스키마-적용-실패를-견딘다.md),
상태 코드 계약은 [허브 ADR 0006](../../docs/adr/0006-otlp-ingest-retry-and-status-contract.md)이 담는다.

`:libs:security`에는 아직 **OTLP 경로의 `ptt_` 검증만** 있다(PROJ-102). 관리자 API 경로의 AT 검증은
PROJ-107이 같은 모듈에 얹는다. 하위 패키지는 그때 나눈다 — 지금은 내용물 묶음이 하나뿐이라
3절의 판정 기준이 나눌 근거를 주지 않는다.

`:libs:telemetry-collector`는 5절이 예고한 단계 모듈 중 첫 번째다(PROJ-114). 하위 패키지는 5절이
정한 대로 `masking/`·`archive/` 둘이고, 수신 관련 타입은 모듈 루트 패키지에 둔다.
HTTP 라우팅과 인증 체인은 `:apps:telemetry-ingest`가 붙인다.
**검증된 신원을 리소스 속성으로 승격하는 것도 이 모듈이다**(`IdentitySource`) — 변환 단계가
`tenant.id`·`developer.installation_id`를 거기서 읽고, 그 값이 `record_id` 해시의 재료다.
심는 자리는 마스킹 뒤·아카이브 앞이다(ADR 0016).
적재 대상은 [ADR 0012](adr/0012-원본-아카이브를-S3에-쓰고-파일-구현은-로컬에만-남긴다.md)가 정했다 —
배포는 S3, 로컬 dev는 파일이고 어느 쪽을 쓸지는 조립 앱이 고른다.

`:libs:telemetry-adapter`는 두 번째 단계 모듈이다(PROJ-103). 하위 패키지는 5절이 정한 대로
`model/`·`source/` 둘이고, 읽기·키 생성·페어링은 모듈 루트 패키지에 둔다.
**입력은 수집 단계가 넘겨주는 protobuf 요청이다**
([ADR 0013](adr/0013-정규화-입력은-protobuf-이고-원본-해시는-정규-json-으로-되살린다.md)).
`model/`의 봉투와 payload 타입은 **모듈 경계를 넘는 공개 API**다 — 보강·적재 단계가 그대로 받는다.
정규화가 지키는 불변 규칙 다섯(프롬프트 원문 미취급 · billable 합산 · 없으면 `null` · `call_id` 조인 키 ·
신호 간 조인은 다운스트림)은 [ADR 0017](adr/0017-정규화-불변-규칙과-enrichment-json-승격-금지는-이-저장소가-정한다.md)이
소유한다.
**단계 모듈은 이웃의 seam 인터페이스를 구현하지 않지만, 공개된 데이터 타입은 `project()` 간선으로
직접 받는다**([ADR 0014](adr/0014-단계-모듈-사이에-데이터-타입-간선을-둔다.md)). 수집의
`SignalConsumer`에 변환을 잇는 배선은 여전히 조립 앱의 몫이다(ADR 0011).

`:libs:telemetry-enricher`는 세 번째 단계 모듈이다(PROJ-104). 하위 패키지는 `provider/` 하나이고,
`Enriched`와 `Enricher`는 모듈 루트 패키지에 둔다. **RDS를 읽는 provider는 `org` 하나뿐이며**
PROJ-101이 만든 `TeamMembershipRepository.findActiveTeamMembershipsByInstallationId`와
`TeamMembership.coversAt`를 그대로 쓴다 — 읽기 전용이고 `team_memberships`의 쓰기 소유는 그대로다.

`:libs:telemetry-persistence`는 단계가 아니라 **역할** 모듈이라 어순이 뒤집힌다(ADR 0010).
`enriched_events`의 DDL과 쓰기를 소유하고, ClickHouse HTTP 인터페이스를 JDK `HttpClient`로 직접
부른다 — 드라이버를 넣으면 자체 오류 매핑이 상태 코드별 처분을 덮는데, 그 분류가 곧 HTTP
계약이다(허브 ADR 0006 — 연결 계열과 `5xx`·`429`·`408`은 일시 장애, 그 밖의 4xx는 영구 오류).

## 2. 도메인 경계 — 쓰기 소유권

**한 테이블의 쓰기 로직은 한 모듈이 소유한다. 읽는 모듈은 몇 개든 좋다.**
도메인이란 한 모듈이 쓰기를 독점하는 테이블 묶음이다. RDB 스키마의 진실원이 Flyway이므로(ADR 0004),
소유 질문은 "이 테이블의 `CREATE TABLE`이 어느 모듈 아래에 있는가"라는 파일 경로 질문으로 환원된다.

| 도메인 | 테이블 | 쓰기 소유 |
|---|---|---|
| directory | `tenants` · `members` · `teams` · `team_memberships` | 관리자 API (미구현) |
| enrollment | `invitations` · `installations` · `installation_credentials` · `telemetry_tokens` · `installation_manifest_assignments` | `enrollment-api` |
| policy | `manifests` | 관리자 API (미구현) |
| contract | `contracts` · `contract_term_commitments` · `contract_token_discounts` · `contract_memberships` | 관리자 API (미구현) |
| telemetry | ClickHouse `enriched_events` | `:libs:telemetry-persistence` |

**쓰기 소유는 모듈이다**(ADR 0008 규칙 1). 표가 앱 이름을 적은 행은 그 도메인의 쓰기가 아직 앱에
직접 있다는 뜻이고, 규칙 5의 승격 트리거가 당겨지면 모듈로 내려간다. telemetry는 새 도메인이라
처음부터 모듈이 소유한다([ADR 0010](adr/0010-파이프라인-단계를-모듈-경계로-나눈다.md)).

**ClickHouse는 Flyway가 다루지 않는다** — 구현 모듈이 10.24.0에서 멈춰 이 저장소가 해석하는
`flyway-core` 12.x 계열에 없다. `enriched_events`의 DDL 진실원은
`libs/telemetry-persistence/src/main/resources/clickhouse/`의 `V*.sql`이고, 적용은 기동 시 전량이다
([ADR 0015](adr/0015-clickhouse-ddl-은-번호-붙은-멱등-파일이고-기동-시-적용한다.md)가
허브 ADR 0004 Follow-up이 넘긴 이 항목을 닫는다). **모든 문장은 `IF NOT EXISTS` 형태여야 하고,
`V1`을 고치는 대신 새 번호 파일을 더한다** — 그것이 원장 테이블과 분산 락을 대신하는 규약이다.

**이 표는 테이블만 다룬다.** Raw Signal Object Storage에는 `CREATE TABLE`이 없어 규칙 1의 판정법이
닿지 않으므로 표에 넣지 않는다. 그 쓰기 주체는 `:libs:telemetry-collector`의 `archive` 패키지다(5절).

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

**이름의 어형은 층마다 다르다**([ADR 0010](adr/0010-파이프라인-단계를-모듈-경계로-나눈다.md)).

| 층 | 규칙 | 예 |
|---|---|---|
| 단계 모듈 · 루트 패키지 | 허브 [`glossary.md`](../../docs/glossary.md)가 확정한 노드 이름. 품사를 따지지 않는다 | `collector` · `adapter` · `enricher` |
| 역할 모듈 | 추상명사 | `persistence` · `security` · `event` |
| 하위 패키지 | 인터페이스와 그 구현만 모이면 인터페이스 이름의 소문자형, 다른 타입이 섞이면 추상명사 | `provider` · `source` · `model` · `masking` |
| 클래스 | 행위자·개념명사 | `EnrichmentProvider` · `OtlpReceiver` · `SecretMasker` |
| 함수 | 동사 | `enrich()` |

한 낱말이 층마다 다른 형태로 나타난다 — 모듈은 `enricher`, 그 안의 SPI 패키지는 `provider`,
함수는 `enrich()`다. **계약으로 굳은 이름은 이 규칙보다 우선한다** — ClickHouse 컬럼
`enrichment_json`은 그대로 둔다.

## 4. 의존 방향

- `:apps:*`는 `:libs:*`에만 의존한다.
- **`:apps:*`끼리는 의존하지 않는다.** 앱 사이의 런타임 협력은 이벤트이고, 정의가 갈라지면 안 되는
  코드는 `:libs:`로 올린다.
- `:libs:*`는 `:apps:*`에 의존하지 않는다. `:libs:*` 사이의 의존은 단방향만 둔다.
- `project()` 간선은 기본 `implementation`이고, `api()`는 그 타입이 소비자의 계약일 때만 쓴다
  (`:libs:enrollment-persistence`가 JPA·JDBC를 `api()`로 노출한 것이 선례다).
  **판정법: 그 모듈의 public 함수·생성자 시그니처에 나타나는 타입은 계약이다.** 생성자 인자도
  포함한다 — 소비자가 그 타입 없이는 객체를 만들 수 없다. `:libs:security`·`:libs:telemetry-enricher`가
  리포지토리를 생성자로 받으므로 `:libs:enrollment-persistence`를 `api()`로 두는 것이 그 예다.
  반대로 테스트에서만 쓰는 타입은 `testImplementation`이다(`:libs:telemetry-adapter`의
  `opentelemetry-proto`).
- **단계 모듈 사이에도 데이터 타입 간선을 둔다**
  ([ADR 0014](adr/0014-단계-모듈-사이에-데이터-타입-간선을-둔다.md)). 방향은 데이터 흐름과 같고
  단방향이다 — `adapter ← enricher ← persistence`. 금지되는 것은 **이웃의 seam 인터페이스를
  구현하는 것**이고, 그 배선은 조립 앱이 한다. 타입을 복제하거나 `-event` 모듈로 빼지 않는다.
- 아웃바운드 기술이 둘 이상이면 라이브러리를 기술별로 나눈다(`-persistence` / `-messaging`).
- **`:libs:*`는 Spring 스테레오타입을 두지 않고 Boot starter도 끌지 않는다**
  ([ADR 0011](adr/0011-라이브러리-모듈은-spring-조립을-앱에-위임한다.md)). 컴포넌트 스캔 루트가
  저장소 전체라 라이브러리에 붙은 `@Component`는 그 라이브러리를 올린 **모든** 앱에서 살아난다.
  빈 등록과 필터 체인 배선은 앱의 몫이고, 라이브러리는 값을 생성자로 받는다.
  예외는 JPA 엔티티·Spring Data 리포지토리 인터페이스와 `testFixtures`뿐이다.

## 5. 텔레메트리 파이프라인이 들어올 자리

[허브 ADR 0004](../../docs/adr/0004-telemetry-pipeline-repo-merge.md)가 파이프라인을 이 저장소로
병합하기로, [허브 ADR 0005](../../docs/adr/0005-single-app-telemetry-topology.md)가 전 계층을
**단일 애플리케이션**으로 띄우기로 정했다. OTel Collector 바이너리를 쓰지 않으므로 수집과 마스킹도
이 저장소의 모듈이다. 단계는 배포 경계가 아니라 위 규칙에 따른 **모듈 경계**로 나뉜다.

```text
apps/
└── telemetry-ingest/                com.team376.pulsemetry.telemetry          ← 있다 (1절)
                                     앱은 조립만 한다 — 필터 체인 배선 · 단계 호출
libs/
├── security/                        com.team376.pulsemetry.security          ← 있다 (1절)
│                                    OTLP 경로의 ptt_ 검증 · 관리자 API 경로의 AT 검증 (ADR 0007)
├── telemetry-collector/             com.team376.pulsemetry.telemetry.collector   ← 있다 (1절)
│                                    OTLP 수신
│                                    ├ masking/    서버 마스킹 — 허브 Masker 노드의 소재
│                                    └ archive/    마스킹 후 원본의 외부 저장소 적재
├── telemetry-adapter/               com.team376.pulsemetry.telemetry.adapter    ← 있다 (1절)
│                                    OTLP 읽기 · record_id 생성 · call_id 페어링 · 공시가 환산
│                                    (재처리 읽기는 아직 없다 — 재처리 리더가 생길 때 이 모듈에 붙는다)
│                                    ├ model/      공통 스키마
│                                    └ source/     벤더별 매핑 (claude_code · codex)
├── telemetry-enricher/              com.team376.pulsemetry.telemetry.enricher    ← 있다 (1절)
│                                    사원 정보 결합
│                                    └ provider/   EnrichmentProvider 와 그 구현
└── telemetry-persistence/           com.team376.pulsemetry.persistence.telemetry ← 있다 (1절)
                                     ClickHouse 스키마 · 적재 — 쓰기 소유 모듈
```

- **인증이 가장 앞이다.** 필터 체인이 통과시킨 요청만 수집 단계에 닿는다. 폐기된 토큰이나 정지된
  tenant의 요청처럼 거부될 데이터가 외부 저장소에 적재되지 않게 하려면 이 순서여야 한다(허브 ADR 0005).
- **신원은 `SecurityContextHolder`에서 얻는다.** 단계 사이에 신원 헤더를 실어 나르지 않는다.
- **마스킹은 `telemetry.collector.masking` 패키지다.** 허브가 Masker를 별도 노드로 그리지만 이
  저장소에서는 수집 모듈 안에 둔다 — 정규식 규칙 묶음이라 모듈 하나를 지탱할 부피가 아니다.
  클라이언트 마스킹을 신뢰해 서버 마스킹을 생략하지 않는다는 원칙은 그대로다.
- **단계 모듈의 패키지는 `<컨텍스트>.<단계>` 어순이다**(ADR 0010). 3절의 `<역할>.<컨텍스트>` 역전은
  `-persistence`처럼 여러 컨텍스트에 같은 역할이 생기는 모듈에만 쓴다 — `adapter.telemetry`는
  묶일 짝이 영원히 없다.
- **단계 모듈은 테이블을 소유하지 않아도 된다**(ADR 0010이 규칙 1에 더한 분할 축).
  **테이블** 쓰기 소유는 `:libs:telemetry-persistence` 하나다 — 규칙 1의 판정법이 `CREATE TABLE`의
  위치이므로 그 판정은 테이블에만 걸린다.
- **외부 저장소 쓰기는 별개다.** 마스킹 직후 원본을 Object Storage에 쓰는 것은
  `telemetry-collector`의 `archive` 패키지이고, 허브 [`overview.md`](../../docs/architecture/overview.md)
  3절이 그 쓰기를 Masker에게 주었다. 적재 모듈로 미룰 수 없다 — 변환이 실패해도 원본이 남아 있어야
  재처리(흐름 D)의 복구 원천이 성립한다. 두 저장소의 쓰기 주체가 각각 하나라는 제약은 그대로다.
- **소비자가 조립 앱 하나뿐인데도 지금 모듈로 나눈다.** 경계가 이미 `ai-telemetry-pipeline` 구현에서
  실측됐고 이음매가 특성화 테스트로 고정돼 있기 때문이다(ADR 0010이 규칙 5에 더한 단서).
- `:libs:security`는 컨텍스트에 속하지 않는 횡단 모듈이므로 `<컨텍스트>-<역할>`이 아니라 `<역할>`
  형태를 쓴다. OTLP 경로와 관리자 API 경로가 같은 인증 코드를 공유할 자리가 여기다.
- 이 컨텍스트의 배포 단위는 하나이므로 3절의 내림 조항(`<컨텍스트>.<인바운드>`)은 발동하지 않는다.

## 6. 모듈을 언제 만드는가

**두 번째 소비자가 실제로 생겼을 때 승격한다.** 예측으로 나누지 않는다.
단 **경계가 이미 실측된 경우는 예외다** — 5절의 단계 모듈이 그렇고, 이벤트 payload 모듈도 그렇다
(ADR 0008 규칙 4 · [ADR 0010](adr/0010-파이프라인-단계를-모듈-경계로-나눈다.md)).

- `:libs:enrollment-persistence`의 도메인별 분할(directory · enrollment · policy · contract)은
  예정돼 있고, 트리거는 **관리자 API 앱의 첫 커밋**이다. 같은 시점에
  `InvitationAdminService.createInvitedMember()`의 `members` 직접 쓰기를 directory 모듈이 제공하는
  연산 호출로 바꾼다.
- 모듈 간 테스트 지원 코드는 `java-test-fixtures`로 공유한다. 테스트 전용 모듈을 따로 만들지 않는다.
- `:apps`·`:libs` 디렉터리 자체는 빌드 스크립트를 갖지 않는다. 루트가 `buildFile.exists()`로 건너뛴다.
