# 모듈 지도

이 저장소의 Gradle 모듈이 무엇이고, 새 모듈을 어디에 어떤 이름으로 만드는지를 적는다.
`settings.gradle.kts`에 모듈을 추가하거나 패키지를 새로 만들기 전에 이 문서를 본다.

결정의 **배경과 대안**은 ADR에 있다. 이 문서는 "무엇이 어떻게 나뉘어 있는가"만 다룬다.
HTTP 계약은 [Enrollment 서버 명세](enrollment-server-spec.md)와 [Dashboard 서버 명세](dashboard-server-spec.md)에 나눈다.
온보딩·정책·초대 명령은 enrollment-api/management, 카탈로그 조회는 dashboard-api/api,
공통 카탈로그와 온보딩 영속성은 enrollment-persistence/management가 소유한다(ADR 0029).
카탈로그 기준 데이터는 `enrollment.vendor_catalog_vendors`·`vendor_catalog_products`·`vendor_catalog_plans`에 있다(ADR 0035).
V10이 초기 목록을 넣고 V11이 OpenAI 복수 좌석 입력을 허용한다. 조회 API와 계약 검증은 같은 DB를 읽는다.
조직별 등록은 `managed_vendors`, 계약·표시 이름·보관 이력은 `vendor_contract_versions`로 유지한다.
V9의 활성 제품 유일 제약과 계약 정정 규칙은 Enrollment 명세 §12·§14를 따른다.
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
[ADR 0020](adr/0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md) ·
[ADR 0021](adr/0021-수집-운영-기록은-ledger-와-telemetry-ops-스키마에-두고-enrollment-api-가-적용한다.md) ·
[ADR 0022](adr/0022-대시보드-API-는-별도-앱이고-인증은-포트-뒤에서-기본-거부한다.md) ·
[ADR 0023](adr/0023-대시보드-snapshot-은-dashboard-cache-의-불변-복사본과-manifest-이고-대시보드-앱이-그-DDL-을-적용한다.md) ·
[ADR 0024](adr/0024-조직별-보존-삭제-경계는-RDS-가-진실원이고-분석-INSERT-는-ClickHouse-fence-를-서버에서-다시-검사한다.md) ·
[허브 ADR 0004](../../docs/adr/0004-telemetry-pipeline-repo-merge.md) ·
[허브 ADR 0005](../../docs/adr/0005-single-app-telemetry-topology.md) ·
[허브 ADR 0006](../../docs/adr/0006-otlp-ingest-retry-and-status-contract.md)

---

## 1. 현재 모듈

개발 전용 `:tools:dev-seed` (`com.team376.pulsemetry.devseed`) 하나가 시드 생성·적재·검증과 Compose 진입점을 소유한다.
서버는 시드 모듈을 의존하거나 실행하지 않는다. 초기화와 수동 관리는 모두 Docker에서 실행한다([ADR 0031](adr/0031-개발-시드는-Docker에서만-실행한다.md)).
기존 enrollment·telemetry_ops·telemetry-persistence의 마이그레이션을 호출하며 스키마 소유권은 바꾸지 않는다.
Compose가 인증 키도 준비하며, 각 앱의 local 프로필이 읽는다. 로컬 실행용 스크립트는 두지 않는다.

```text
pulsemetry-backend
├── apps/
│   ├── enrollment-api/              com.team376.pulsemetry.enrollment
│   │                                ├ auth/           사용자 인증 HTTP·필터·키 설정
│   │                                ├ inquiry/        로그인 전 도입 문의 접수 HTTP·출처별 제한 필터
│   │                                ├ installation/   설치 보고(heartbeat) 수신 — 본문 해석·적용 확인·기록 (ADR 0040)
│   │                                ├ mail/           메일 설정 바인딩·SMTP 발송 구현·발송 작업의 주기 실행 (ADR 0037)
│   │                                ├ update/         데몬 업데이트 확인 — 배포 바이너리의 판과 해시 확인·SemVer 비교 (허브 ADR 0011)
│   │                                └ management/     온보딩·정책·팀·초대·제품·계약 관리 HTTP
│   ├── telemetry-ingest/            com.team376.pulsemetry.telemetry
│   │                                OTLP 수신부터 적재까지 한 프로세스 — 조립만 한다
│   ├── dashboard-api/               com.team376.pulsemetry.dashboard
│   │                                분석 조회 API — 원천은 읽기만, 쓰기는 자기 캐시뿐 (ADR 0022)
│   │                                ├ api/            HTTP 표현 계층 — 화면별 컨트롤러
│   │                                ├ analytics/      공통 계산기(축별 합계·null 규칙) · 화면별 응답 조립
│   │                                ├ authentication/ 인증 포트 · 기본 거부 구현 · 필터 · 역할 대응
│   │                                ├ authorization/  인가 포트 · 기본 정책(관리자만) · 조직 경로 공통 관문
│   │                                ├ organization/   조직(tenant) 읽기
│   │                                ├ request/        요청 ID · 조회 파라미터 해석(기간·비교·목록 cursor)
│   │                                ├ source/         원천 읽기 — ClickHouse 읽기 전용 클라이언트
│   │                                ├ cache/          dashboard_cache — 캐시 클라이언트 · 두 캐시 스키마 적용 (ADR 0023)
│   │                                ├ snapshot/       snapshot — build(원본 한 번 선택·참조 복제·공급자·모델 해석) · 공개 CAS · 만료 · 정리
│   │                                ├ store/          ClickHouse 연결 · 파라미터 · 저장소 실패 분류(원천·캐시 공용)
│   │                                ├ error/          오류 본문 · 코드 · 예외 매핑
│   │                                └ config/
│   └── retention-worker/            com.team376.pulsemetry.retention
│                                    조직별 보존 삭제 — 일회성 실행, 분석 원본 DELETE 는 여기뿐 (ADR 0024)
│                                    └ config/
├── tools/
│   └── dev-seed/                    com.team376.pulsemetry.devseed
│                                    Docker 전용 개발 데이터·인증 키 초기화와 시드 관리
└── libs/
    ├── enrollment-persistence/      com.team376.pulsemetry.persistence.enrollment
    │                                └ enrollment 엔티티·사용자 인증 저장소·관리 명령·문의 접수·메일 outbox·공통 작업 기록·설치 보고·설치 업데이트 안내(ADR 0043)·DB 카탈로그 · Flyway 마이그레이션
    ├── security/                    com.team376.pulsemetry.security
    │                                └ 사용자 JWT·세션·암호 검증과 OTLP 경로의 ptt_ 검증 · telemetry token 해시
    ├── telemetry-collector/         com.team376.pulsemetry.telemetry.collector
    │                                OTLP 수신 · 상태 매핑 · OTLP/JSON 코덱
    │                                ├ masking/    blocked_values 14종 값 마스킹
    │                                └ archive/    제품별 원본 적재 (S3 · 파일)
    ├── telemetry-adapter/           com.team376.pulsemetry.telemetry.adapter
    │                                └ observation/ 정규화 계약 2판의 관측 모델 (ADR 0020)
    │                                  ├ profile/   제품 프로파일 SPI · 엄격한 필드 읽기
    │                                  ├ codex/     Codex 프로파일
    │                                  ├ claudecode/ Claude Code 프로파일
    │                                  ├ semantics/ 토큰 의미 프로파일 · 파생 토큰
    │                                  └ pricing/   가격 단계 (추정 비용)
    ├── telemetry-enricher/          com.team376.pulsemetry.telemetry.enricher
    │                                사원 정보 결합 — member_id · 대표 팀 as-of · provider 주석
    │                                ├ provider/   EnrichmentProvider 와 그 구현
    │                                └ observation/ 관측 보강 (ADR 0020 §5)
    ├── telemetry-persistence/       com.team376.pulsemetry.persistence.telemetry
    │                                ClickHouse 스키마 · 분석 테이블 sink · 수신 ledger sink · 보존 fence·삭제 — 쓰기 소유 모듈
    └── telemetry-ops-persistence/   com.team376.pulsemetry.persistence.telemetryops
                                     RDS telemetry_ops 스키마(수집 운영 기록) · 생애 요약 · 백필 · 삭제 경계 · 보존 작업 기록
```

`settings.gradle.kts`의 `include`는 위 모듈들이다. **5절이 예고한 모듈이 전부 섰다.**
`:libs:telemetry-ops-persistence`는 5절 밖에서 더해졌다 — 수집 운영 기록의 RDS 쪽이 ClickHouse와 아웃바운드
기술이 달라 나뉜다([ADR 0021](adr/0021-수집-운영-기록은-ledger-와-telemetry-ops-스키마에-두고-enrollment-api-가-적용한다.md)).

`:apps:telemetry-ingest`는 조립 앱이다(PROJ-105). 도메인 로직을 담지 않는다 — 빈 등록·필터 체인
배선·설정 바인딩과 단계 호출이 전부이고, 그것이 ADR 0011이 라이브러리에서 걷어낸 몫이다.
기동·배선 정책은 [ADR 0016](adr/0016-조립-앱은-인증-체인과-단계-호출을-배선하고-스키마-적용-실패를-견딘다.md),
상태 코드 계약은 [허브 ADR 0006](../../docs/adr/0006-otlp-ingest-retry-and-status-contract.md)이 담는다.

`:apps:dashboard-api`는 분석 조회 API다([ADR 0022](adr/0022-대시보드-API-는-별도-앱이고-인증은-포트-뒤에서-기본-거부한다.md), Proposed).
원천 스키마를 소유한 라이브러리(`enrollment`·`telemetry-ops`·`telemetry`의 `-persistence`)를 **읽기 소비자**로만 쓰므로 2절의
쓰기 소유 표는 바뀌지 않고, `:libs:enrollment-persistence`의 분할 트리거(6절)도 당겨지지 않는다. 화면별 조회는 모듈이 아니라 이 앱 안의
패키지다. 인증 포트(`DashboardAuthenticator`)에는 `:libs:security`의 사용자 검증을 잇는 어댑터가 구현돼 있다(ADR 0026).
`pulsemetry.user-auth.enabled=false`이면 보호 요청을 거부하며, 활성화하면 공개키와 현재 계정·세션으로 AT를 검증한다.
필터 체인은 `/api/v1/organizations/**`와 `/api/v1/vendor-catalog/**`를 보호하고 `/v1/healthz`만 공개한다.
계약 밖 경로는 404로 거부한다.
`dashboard_cache`의 쓰는 주체는 이 앱 하나라 쓰기 소유가 앱에 있고, DDL도 이 앱이 기동 때 캐시 계정으로 적용한다 — ClickHouse는
`clickhouse/dashboard-cache/`의 멱등 파일 전량(ADR 0015 규약), RDS는 `db/dashboard-cache/`의 별도 Flyway 인스턴스(이력
`dashboard_cache.flyway_schema_history`)다([ADR 0023](adr/0023-대시보드-snapshot-은-dashboard-cache-의-불변-복사본과-manifest-이고-대시보드-앱이-그-DDL-을-적용한다.md)).

`:apps:retention-worker`는 조직별 보존 삭제 작업이다([ADR 0024](adr/0024-조직별-보존-삭제-경계는-RDS-가-진실원이고-분석-INSERT-는-ClickHouse-fence-를-서버에서-다시-검사한다.md), Proposed).
서버가 아니라 명령 하나(`--tenant --retention-months --as-of`)를 실행하고 종료 코드로 끝난다. 쓰는 대상 — 경계·작업 기록(RDS `telemetry_ops`),
fence·두 분석 테이블의 DELETE(ClickHouse) — 의 코드는 각 쓰기 소유 모듈(`:libs:telemetry-ops-persistence`·`:libs:telemetry-persistence`)에
있고 이 앱은 조립만 한다. DDL 은 적용하지 않는다. 분석 테이블 모듈이 보강 단계를 타고 `:libs:enrollment-persistence`를 끌어오므로
JPA·Flyway 자동설정을 끈다 — 켜 두면 대상 DB 의 `public`에 Flyway 이력 테이블이 생긴다.
두 번째 쓰는 주체가 생기면 `:libs:dashboard-persistence`로 내린다.
원천 연결은 앱이 직접 세운다 — RDS는 `pulsemetry.dashboard.rds.source`로 만든 읽기 전용 주 DataSource(JPA·`JdbcClient`가 쓴다, Flyway는 끈다),
ClickHouse는 `source/`의 읽기 전용 클라이언트다. 적재 모듈의 `ClickHouseHttpClient`와 따로 두는 것은 요구가 반대라서다(계정 인증·요청마다의
`readonly`·결과 상한·행 해석이 필요하고 쓰기가 없다).

`:libs:security`에는 아직 **OTLP 경로의 `ptt_` 검증만** 있다(PROJ-102). 관리자 API 경로의 AT 검증은
PROJ-107이 같은 모듈에 얹는다. 하위 패키지는 그때 나눈다 — 지금은 내용물 묶음이 하나뿐이라
3절의 판정 기준이 나눌 근거를 주지 않는다.

`:libs:telemetry-collector`는 5절이 예고한 단계 모듈 중 첫 번째다(PROJ-114). 하위 패키지는 5절이
정한 대로 `masking/`·`archive/` 둘이고, 수신 관련 타입은 모듈 루트 패키지에 둔다.
HTTP 라우팅과 인증 체인은 `:apps:telemetry-ingest`가 붙인다.
**검증된 신원을 리소스 속성으로 승격하는 것도 이 모듈이다**(`IdentitySource`) — 아카이브 원본이 신원을 담아야
재처리가 원래 문맥(`observation_id` 재료의 `tenant_id`·`installation_id`)을 재현한다. live 경로의 정규화는 같은
신원을 영수증(`ArchiveReceipt`)에서 받는다. 심는 자리는 마스킹 뒤·아카이브 앞이다(ADR 0016).
적재 대상은 [ADR 0012](adr/0012-원본-아카이브를-S3에-쓰고-파일-구현은-로컬에만-남긴다.md)가 정했다 —
배포는 S3, 로컬 dev는 파일이고 어느 쪽을 쓸지는 조립 앱이 고른다.

`:libs:telemetry-adapter`는 두 번째 단계 모듈이다(PROJ-103). 정규화 계약 2판
([ADR 0020](adr/0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md))으로 전환한 뒤 하위 패키지는
`observation/` 하나이고, 그 아래를 제품 프로파일(`codex/`·`claudecode/`), 프로파일 SPI(`profile/`), 토큰 의미
(`semantics/`), 가격(`pricing/`)으로 나눈다. 구 `model/`·`source/`는 지웠다.
**입력은 수집 단계가 넘겨주는 protobuf 요청이다**
([ADR 0013](adr/0013-정규화-입력은-protobuf-이고-원본-해시는-정규-json-으로-되살린다.md)).
관측 타입(`EventObservation`·`MetricPointObservation`·`ObservationEnvelope`)은 **모듈 경계를 넘는 공개 API**다 —
보강·적재 단계가 그대로 받는다. 정규화 불변 규칙은
[ADR 0017](adr/0017-정규화-불변-규칙과-enrichment-json-승격-금지는-이-저장소가-정한다.md)이 소유하고 ADR 0020이
그중 규칙 2·4·6을 대체했다.
**단계 모듈은 이웃의 seam 인터페이스를 구현하지 않지만, 공개된 데이터 타입은 `project()` 간선으로
직접 받는다**([ADR 0014](adr/0014-단계-모듈-사이에-데이터-타입-간선을-둔다.md)). 수집의
`SignalConsumer`에 변환을 잇는 배선은 여전히 조립 앱의 몫이다(ADR 0011).

`:libs:telemetry-enricher`는 세 번째 단계 모듈이다(PROJ-104). 하위 패키지는 `provider/`·`observation/` 둘이다.
보강은 `observation/`의 `ObservationEnricher`가 한다. **RDS를 읽는 것은 이 클래스 하나뿐이며** 구성원은
installation 단독 조회(`InstallationRepository.findMemberIdById`), 대표 팀은 팀 상태를 보지 않는 소속 이력
(`TeamMembershipRepository.findAllByMemberId`)을 `TeamMembership.coversAt`으로 잘라 정한다(ADR 0020 §5) — 읽기
전용이고 `team_memberships`의 쓰기 소유는 그대로다. `enrichment_json`의 provider 항목은 `provider/`의
`EnrichmentProvider.annotate`가 쓴다.

`:libs:telemetry-persistence`는 단계가 아니라 **역할** 모듈이라 어순이 뒤집힌다(ADR 0010).
ClickHouse 테이블(정규화 2판의 `telemetry_events`·`telemetry_metric_points`, 수신 ledger
`telemetry_ingest_ledger`, 보존 삭제의 쓰기 fence `telemetry_retention_fence`, 새 행을 받지 않고 보존만 하는 `enriched_events`)의 DDL과 쓰기를 소유하고, ClickHouse HTTP 인터페이스를 JDK `HttpClient`로 직접
부른다 — 드라이버를 넣으면 자체 오류 매핑이 상태 코드별 처분을 덮는데, 그 분류가 곧 HTTP
계약이다(허브 ADR 0006 — 연결 계열과 `5xx`·`429`·`408`은 일시 장애, 그 밖의 4xx는 영구 오류).

## 2. 도메인 경계 — 쓰기 소유권

**한 테이블의 쓰기 로직은 한 모듈이 소유한다. 읽는 모듈은 몇 개든 좋다.**
도메인이란 한 모듈이 쓰기를 독점하는 테이블 묶음이다. RDB 스키마의 진실원이 Flyway이므로(ADR 0004),
소유 질문은 "이 테이블의 `CREATE TABLE`이 어느 모듈 아래에 있는가"라는 파일 경로 질문으로 환원된다.

| 도메인 | 테이블 | 쓰기 소유 |
|---|---|---|
| directory | `tenants` · `members` · `teams` · `team_memberships` | `enrollment-api`가 진입, `:libs:enrollment-persistence`에 사용자·팀 관리 저장 구현 |
| enrollment | `invitations` · `installations` · `installation_credentials` · `telemetry_tokens` · `installation_manifest_assignments` | `enrollment-api` |
| policy / onboarding | `manifests` · `organization_onboarding` · tenants의 완료 시각 | `:libs:enrollment-persistence` — enrollment-api의 최초 정책·정책 수정·완료 명령 |
| legacy contract | `contracts` · `contract_term_commitments` · `contract_token_discounts` · `contract_memberships` | 기존 기간 약정. 좌석 계약 관리 API에서 수정·환산하지 않음 |
| registered product / seat contract | `managed_vendors` · `vendor_contract_versions` · `management_commands` | `:libs:enrollment-persistence` — 등록·정정·이름 변경·보관·멱등 명령 저장 |
| user authentication | `user_sessions` · `user_refresh_tokens` · `user_authorization_codes` · `auth_attempts` | `:libs:enrollment-persistence`의 인증 저장소. 정책·검증은 `:libs:security`, HTTP 조립은 enrollment-api |
| vendor catalog | `vendor_catalog_vendors` · `vendor_catalog_products` · `vendor_catalog_plans` · `vendor_catalog_observed_products` | `:libs:enrollment-persistence`의 Flyway가 초기화. 관리자 편집 API는 없음. 관측 제품 매핑(`vendor_catalog_observed_products`)은 dashboard-api가 읽기 전용 계정으로 읽는다 (ADR 0044) |
| mail | `mail_outbox` | `:libs:enrollment-persistence`의 메일 outbox(`mail`) — 업무 쓰기가 같은 트랜잭션에서 적재하고, enrollment-api의 발송 작업이 선점해 결과를 기록 (ADR 0037) |
| inquiry | `inquiries` · `inquiry_attempts` | `:libs:enrollment-persistence`의 문의 저장소(`inquiry`) — enrollment-api의 공개 접수 명령. 조직에 속하지 않으며 조직·계정·초대를 만들지 않음 |
| installation report | `installation_heartbeats` · `installation_collection_segments` | `:libs:enrollment-persistence`의 설치 보고 저장소(`installation`) — enrollment-api가 데몬 heartbeat를 받아 기록. `installations.last_seen_at`·`installation_manifest_assignments.applied_at`은 enrollment 도메인 그대로 enrollment-api가 씀 (ADR 0040). dashboard-api는 읽기 전용 계정으로 읽어 수집 상태를 판정함 (ADR 0041) |
| operation | `operations` · `operation_targets` | `:libs:enrollment-persistence`의 공통 작업 기록(`operation`) — 작업을 만드는 명령과 그 실행 주체가 생성·전이를 기록하고, dashboard-api는 읽기 전용 계정으로 조회만 함 (ADR 0039). 지금 생산자는 enrollment-api의 설치 업데이트 안내(`installation`의 `InstallationNotifier` — 메일 발송 결과를 대상 결과로 옮김, ADR 0043) 하나 |
| telemetry | ClickHouse `enriched_events` · `telemetry_events` · `telemetry_metric_points` · `telemetry_ingest_ledger` · `telemetry_retention_fence` | `:libs:telemetry-persistence` |
| telemetry ops | RDS `telemetry_ops.tenant_ingest_summary` · `tenant_summary_backfill` · `tenant_retention_boundary` · `retention_operations` | `:libs:telemetry-ops-persistence` |
| dashboard cache | RDS `dashboard_cache.snapshots` · `snapshot_teams` · `snapshot_members` · `snapshot_complete_days`(ADR 0042) · `vendor_observation_sets` · `vendor_observations`(설정의 벤더 관측 고정, ADR 0044), ClickHouse `dashboard_cache.snapshot_usage` · `snapshot_observed_days` · `snapshot_member_activity`(+ 입구 `snapshot_intake`·뷰 둘) | `:apps:dashboard-api` |

**쓰기 소유는 모듈이다**(ADR 0008 규칙 1). 표가 앱 이름을 적은 행은 그 도메인의 쓰기가 아직 앱에
직접 있다는 뜻이고, 규칙 5의 승격 트리거가 당겨지면 모듈로 내려간다. telemetry는 새 도메인이라
처음부터 모듈이 소유한다([ADR 0010](adr/0010-파이프라인-단계를-모듈-경계로-나눈다.md)).

**ClickHouse는 Flyway가 다루지 않는다** — 구현 모듈이 10.24.0에서 멈춰 이 저장소가 해석하는
`flyway-core` 12.x 계열에 없다. `enriched_events`와 정규화 2판의 두 분석 테이블
([ADR 0020](adr/0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md))의 DDL 진실원은
`libs/telemetry-persistence/src/main/resources/clickhouse/`의 `V*.sql`이고, 적용은 기동 시 전량이다
([ADR 0015](adr/0015-clickhouse-ddl-은-번호-붙은-멱등-파일이고-기동-시-적용한다.md)가
허브 ADR 0004 Follow-up이 넘긴 이 항목을 닫는다). **모든 문장은 `IF NOT EXISTS` 형태여야 하고,
`V1`을 고치는 대신 새 번호 파일을 더한다** — 그것이 원장 테이블과 분산 락을 대신하는 규약이다.

**`telemetry_ops`는 `enrollment`와 다른 Flyway 인스턴스다** — 스키마·이력(`telemetry_ops.flyway_schema_history`)·
SQL 위치(`db/telemetry-ops`)가 따로이고, 실행은 `:apps:enrollment-api` 기동이 한다. `:apps:telemetry-ingest`는
Flyway를 끈 채로 두며 이 DDL도 실행하지 않는다([ADR 0021](adr/0021-수집-운영-기록은-ledger-와-telemetry-ops-스키마에-두고-enrollment-api-가-적용한다.md)).

`telemetry_ops` V3의 조직 생성 트리거도 `:libs:telemetry-ops-persistence`가 소유한다.
`enrollment.tenants`에 연결하므로 enrollment 마이그레이션을 먼저 실행한다. 신규 조직의 빈 요약을
같은 트랜잭션에서 초기화하며 기존 이력은 덮어쓰지 않는다([ADR 0034](adr/0034-조직-생성과-빈-수집-요약을-원자적으로-초기화한다.md)).

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
│                                    정규화 — 관측 모델 2판 · 제품 프로파일 · 가격 단계
│                                    (재처리 읽기는 아직 없다 — 재처리 리더가 생길 때 이 모듈에 붙는다)
│                                    └ observation/ 관측 모델과 그 하위 패키지 (1절)
├── telemetry-enricher/              com.team376.pulsemetry.telemetry.enricher    ← 있다 (1절)
│                                    사원 정보 결합
│                                    ├ provider/   EnrichmentProvider 와 그 구현
│                                    └ observation/ 관측 보강
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
