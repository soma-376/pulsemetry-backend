<a id="dashboard-서버-명세"></a>

# Dashboard 서버 운영·실행 안내

HTTP 명세는 [페이지·기능별 API 문서](api/README.md)로 이전했다. 이 문서는 서버 책임·설정·운영·검증 절차와 기존 링크의 진입점을 유지한다.


`:apps:dashboard-api`가 제공하는 조회 API의 서버 명세다. 기본 포트는 8081이다.
사용자 로그인·온보딩 상태·정책 저장·팀/벤더/초대 명령은 [페이지·기능별 API 문서](api/README.md)를 따른다.
레포 간 확정 계약은 [문서 허브](../../docs/contracts/README.md)가 우선한다.
현행 공통 계약은 [dashboard API 계약](../../docs/contracts/dashboard-api.md), 채택 근거는 [허브 ADR 0021](../../docs/adr/0021-dashboard-runtime-and-deployment-contract.md)이다.
JWT 키 원본·버전 고정·파일 공급은 [허브 ADR 0022](../../docs/adr/0022-user-jwt-key-provisioning.md)가 보완한다.
dev 백엔드의 EKS 전용 실행과 이미지 공급·수동 배포 분리는 [허브 ADR 0023](../../docs/adr/0023-dev-backend-eks-only.md)이 정한다. backend dev CI는 세 앱의 ARM64 이미지를 게시하며, 운영자는 확인한 immutable digest로 [infra EKS 런북](../../infra/docs/runbooks/dev-eks-manual-deployment.md)에 따라 배포한다. enrollment의 원천 Flyway 적용을 확인한 뒤 ingest·dashboard를 배포한다. prod와 별도 frontend의 ECS 배포는 이 dev 전환 범위 밖이다.
이 문서는 그 계약을 구현하는 설정과 코드 근거를 기록한다. 문서 채택은 AWS 배포·실 DB 권한 적용·E2E 완료를 뜻하지 않는다.

## API 목록

| 번호 | API | 기능 |
| --- | --- | --- |
| 01 | [GET `/api/v1/organizations/{organizationId}/analytics/overview`](api/endpoints/01-get-overview.md) | 개요 조회 |
| 02 | [GET `/api/v1/organizations/{organizationId}/analytics/teams`](api/endpoints/02-get-team-analytics.md) | 팀 분석 목록 |
| 03 | [GET `/api/v1/organizations/{organizationId}/analytics/teams/{teamId}`](api/endpoints/03-get-team-detail.md) | 팀 분석 상세 |
| 04 | [GET `/api/v1/organizations/{organizationId}/analytics/teams/{teamId}/users`](api/endpoints/04-get-team-users.md) | 팀 사용자 |
| 05 | [GET `/api/v1/organizations/{organizationId}/teams`](api/endpoints/05-list-teams.md) | 현재 팀 선택지 |
| 06 | [GET `/api/v1/organizations/{organizationId}/members/dashboard`](api/endpoints/06-get-members-dashboard.md) | 구성원 첫 화면 |
| 07 | [GET `/api/v1/organizations/{organizationId}/members`](api/endpoints/07-list-members.md) | 구성원 목록 |
| 08 | [GET `/api/v1/organizations/{organizationId}/members/unassigned`](api/endpoints/08-list-unassigned-members.md) | 미배정 구성원 |
| 09 | [GET `/api/v1/organizations/{organizationId}/seat-reclaim-candidates`](api/endpoints/09-list-reclaim-candidates.md) | 회수 후보 |
| 10 | [GET `/api/v1/organizations/{organizationId}/members/{memberId}/seats`](api/endpoints/10-get-member-seats.md) | 구성원 좌석 |
| 11 | [GET `/api/v1/organizations/{organizationId}/vendors/{vendorId}/seats`](api/endpoints/11-list-vendor-seats.md) | 제품 좌석 |
| 12 | [GET `/api/v1/organizations/{organizationId}/settings`](api/endpoints/12-get-settings.md) | 설정 첫 화면 |
| 13 | [GET `/api/v1/organizations/{organizationId}/vendors`](api/endpoints/13-list-vendors.md) | 등록 제품 목록 |
| 14 | [GET `/api/v1/organizations/{organizationId}/vendors/{vendorId}`](api/endpoints/14-get-vendor.md) | 등록 제품 상세 |
| 15 | [GET `/api/v1/organizations/{organizationId}/installations`](api/endpoints/15-list-installations.md) | 설치 현황 |
| 16 | [GET `/api/v1/organizations/{organizationId}/alerts`](api/endpoints/16-list-alerts.md) | 알림 목록 |
| 17 | [GET `/api/v1/organizations/{organizationId}/alerts/{alertId}`](api/endpoints/17-get-alert.md) | 알림 상세 |
| 18 | [GET `/api/v1/organizations/{organizationId}/operations/{operationId}`](api/endpoints/18-get-operation.md) | 작업 상태 |
| 19 | [GET `/api/v1/organizations/{organizationId}/ingest-status`](api/endpoints/19-get-ingest-status.md) | 현재 수집 상태 |
| 20 | [GET `/api/v1/vendor-catalog`](api/endpoints/20-list-vendor-catalog.md) | 카탈로그 검색 |
| 21 | [GET `/api/v1/vendor-catalog/{vendorId}/plans`](api/endpoints/21-list-vendor-plans.md) | 제품 플랜 조회 |
| 76 | [GET `/api/v1/healthz`](api/endpoints/76-get-dashboard-health.md) | 생존 확인 |

## 5. 운영과 로컬 실행

개발 Compose의 `dev-seed`는 Spring 서버 없이 기존 원천 마이그레이션·A/B 기본 시드와
조회용/캐시용 DB 계정을 준비한다. C는 예외 회귀 검증에서 명시할 때만 생성한다(ADR 0059). `docker compose up -d --build` 후 시드 종료 코드 0을 확인한다.
Compose가 개발 fixture 인증 키를 `build/dev-auth`에 준비하며 서버 설정은 앱의 local 프로필이 제공한다. `prepareAuthKeys()`는 키쌍이 모두 없을 때만 생성하고 기존 파일을 보존하며 한 파일만 있으면 실패한다. 이 로컬 동작은 배포 환경의 Secrets Manager 초기화·버전 공급·파일 권한 검증을 수행하지 않는다.

```powershell
# DB와 enrollment 마이그레이션이 준비된 뒤 별도 터미널에서 실행
.\gradlew.bat :apps:dashboard-api:bootRun --args="--spring.profiles.active=local"
```

조회용/캐시용 DB 계정과 인증 키는 Compose가 준비한다. local 프로필이 공개키와 개발 계정 설정을 읽는다.
서버는 enrollment-api의 private key를 사용하지 않는다. issuer·audience·public key는 발급 서버와 일치해야 한다.
`pulsemetry.user-auth.enabled` 기본 false, 허용 origin은 user-auth.allowed-origins에 지정한다.
`pulsemetry.management.enabled`는 관리 가능한 UI capability를 나타내며 실제 쓰기는 enrollment-api에 보낸다.
`pulsemetry.mail.enabled`(`PULSEMETRY_MAIL_ENABLED`, 기본 false)는 이 앱에서 메일을 보내지 않는다 — 설치 업데이트 안내를 보낼 수 있는지의 표시에만 쓴다.
enrollment-api와 같은 값을 준다(local 프로필은 둘 다 true).

### 5.1 설정 전달과 필수값

일반 실행은 `:apps:dashboard-api:bootRun`이다. 설정 식별자의 진실원은 [application.yaml](../apps/dashboard-api/src/main/resources/application.yaml), 기동 검증은 `DashboardApiProperties`다. 배포 환경에는 local 프로필을 켜지 않고 아래 값을 명시한다. local 프로필의 계정·수치·키는 개발 편의값이며 배포 기본값이 아니다.

| 설정·환경변수 | 기본값·기동 검증 |
| --- | --- |
| `server.port` / `PULSEMETRY_DASHBOARD_PORT` | 8081. dev EKS의 container port와 Service targetPort도 이 계약을 따른다 |
| `pulsemetry.user-auth.enabled` / `PULSEMETRY_USER_AUTH_ENABLED` | false. 활성하지 않으면 업무 API 인증은 거부된다 |
| `pulsemetry.user-auth.issuer` / `PULSEMETRY_USER_AUTH_ISSUER` | 빈 값. 활성 시 비어 있으면 실패; enrollment 자체 JWT issuer와 동일 |
| `pulsemetry.user-auth.audience` / `PULSEMETRY_USER_AUTH_AUDIENCE` | 빈 값. 활성 시 비어 있으면 실패; enrollment 자체 JWT audience와 동일 |
| `pulsemetry.user-auth.public-key-files.local` / `PULSEMETRY_USER_AUTH_PUBLIC_KEY_FILE` | 빈 경로. 활성 시 비어 있지 않은 키 맵과 읽을 수 있는 RSA 2048비트 이상 X509 PEM 필요. 실제 `kid` 맵은 §5.3 |
| `pulsemetry.user-auth.allowed-origins` / `PULSEMETRY_USER_AUTH_ALLOWED_ORIGINS` | 빈 목록. 브라우저 origin을 정확히 지정 |
| `pulsemetry.management.enabled` / `PULSEMETRY_MANAGEMENT_ENABLED` | false. 쓰기 capability 표시에 사용하며 명령은 enrollment로 전달 |
| `pulsemetry.mail.enabled` / `PULSEMETRY_MAIL_ENABLED` | false. 설치 업데이트 안내 capability 표시에만 사용; enrollment와 동일하게 지정 |

현재 infra [runtime 입력 검사](../../infra/lib/dev/runtime-input.cjs)는 관리·메일 추가 Secret 공급이 없어 `managementEnabled` 또는 `mailEnabled`가 true이면 거부한다. 위 플래그의 앱 지원과 dev EKS 활성화 완료는 다르며, 메일 준비 조건은 [메일 발송 운영](mail-operations.md#3-배포-준비-조건--인프라-작업에-넘기는-것)을 따른다.

네 연결의 설정을 모두 전달한다. 각 `PASSWORD`는 설정 항목이지만 구현은 빈 문자열을 허용한다. 배포 시 준비된 DB 계정의 인증 정책에 맞춰 값을 주입하며, 빈 값이 기동 검증에서 거부된다고 가정하지 않는다. 비밀은 런타임 secret 주입을 쓰고 이미지·일반 environment·문서·output에 값을 기록하지 않는다.

| 연결 | 정확한 환경변수 | 기동 검증·설정 |
| --- | --- | --- |
| ClickHouse 원천 | `PULSEMETRY_DASHBOARD_CLICKHOUSE_SOURCE_URL`, `PULSEMETRY_DASHBOARD_CLICKHOUSE_SOURCE_DATABASE`, `PULSEMETRY_DASHBOARD_CLICKHOUSE_SOURCE_USERNAME`, `PULSEMETRY_DASHBOARD_CLICKHOUSE_SOURCE_PASSWORD`, `PULSEMETRY_DASHBOARD_CLICKHOUSE_SOURCE_QUERY_TIMEOUT`, `PULSEMETRY_DASHBOARD_CLICKHOUSE_SOURCE_MAX_RESULT_ROWS`, `PULSEMETRY_DASHBOARD_CLICKHOUSE_SOURCE_MAX_RESULT_BYTES` | URL·username 비어 있지 않음, database 식별자 `[A-Za-z_][A-Za-z0-9_]*`, timeout ≥ 1초, 결과 행·바이트 상한 ≥ 1 |
| ClickHouse 캐시 | `PULSEMETRY_DASHBOARD_CLICKHOUSE_CACHE_URL`, `PULSEMETRY_DASHBOARD_CLICKHOUSE_CACHE_DATABASE`, `PULSEMETRY_DASHBOARD_CLICKHOUSE_CACHE_USERNAME`, `PULSEMETRY_DASHBOARD_CLICKHOUSE_CACHE_PASSWORD`, `PULSEMETRY_DASHBOARD_CLICKHOUSE_CACHE_QUERY_TIMEOUT` | URL·username 비어 있지 않음, database는 위 식별자 형식, timeout ≥ 1초 |
| RDS 원천 | `PULSEMETRY_DASHBOARD_RDS_SOURCE_URL`, `PULSEMETRY_DASHBOARD_RDS_SOURCE_USERNAME`, `PULSEMETRY_DASHBOARD_RDS_SOURCE_PASSWORD` | JDBC URL·username 비어 있지 않음. `pulsemetry.dashboard.rds.source.connection-timeout`은 YAML에서 3초, 전용 환경변수 매핑 없음 |
| RDS 캐시 | `PULSEMETRY_DASHBOARD_RDS_CACHE_URL`, `PULSEMETRY_DASHBOARD_RDS_CACHE_USERNAME`, `PULSEMETRY_DASHBOARD_RDS_CACHE_PASSWORD` | JDBC URL·username 비어 있지 않음. `pulsemetry.dashboard.rds.cache.connection-timeout`도 YAML에서 3초 |

다음 운영 수치는 모두 기본값이 없다. Duration은 `10s`, `1m`, `24h`처럼 바인딩 가능한 값으로 지정한다.

| 정확한 환경변수 | 기동 검증 | 운영 시 정하는 기준 |
| --- | --- | --- |
| `PULSEMETRY_DASHBOARD_RETRY_AFTER` | ≥ 1초 | 503 및 진행 중 작업의 `Retry-After` |
| `PULSEMETRY_DASHBOARD_SNAPSHOT_BUILD_TIMEOUT` | ≥ 1초 | snapshot build와 복사 SQL 제한 시간 |
| `PULSEMETRY_DASHBOARD_SNAPSHOT_PURGE_GRACE` | ≥ 0 | 만료 후 물리 복사본을 남길 유예 |
| `PULSEMETRY_DASHBOARD_SNAPSHOT_MAX_CONCURRENT_BUILDS` | ≥ 1 | tenant별 동시 build 수 |
| `PULSEMETRY_DASHBOARD_SNAPSHOT_MAX_COPY_ROWS` | ≥ 1 | 복사 문장의 읽기 행 상한 |
| `PULSEMETRY_DASHBOARD_SNAPSHOT_MAX_COPY_BYTES` | ≥ 1 | 복사 문장의 읽기 바이트 상한 |
| `PULSEMETRY_DASHBOARD_SNAPSHOT_CLEANUP_INTERVAL` | ≥ 1초 | failed·abandoned·만료 캐시 정리 주기 |
| `PULSEMETRY_DASHBOARD_MEMBERS_IDLE_DAYS` | 7·14·30·60 중 하나 | 조직 저장 정책이 없을 때 회수 후보 기준 |
| `PULSEMETRY_DASHBOARD_INGEST_WINDOW` | ≥ 1분이며 정수 분 | enrollment `pulsemetry.heartbeat.report-interval`보다 길게 |
| `PULSEMETRY_DASHBOARD_INGEST_DELAYED_AFTER` | > 0 | 전달 지연 판정 기준 |
| `PULSEMETRY_DASHBOARD_INGEST_DOWN_AFTER` | `delayed-after`보다 큼 | 수집 중단 판정 기준 |
| `PULSEMETRY_DASHBOARD_COMPLETENESS_SETTLE_AFTER` | > 0 | 데몬 재시도 전체와 서버 적재 시간보다 길게 |
| `PULSEMETRY_DASHBOARD_SEATS_STALE_AFTER` | > 0 | enrollment 벤더 동기화 간격보다 길게 |
| `PULSEMETRY_DASHBOARD_ALERTS_EVALUATION_INTERVAL` | > 0 | 알림 평가 주기 |
| `PULSEMETRY_DASHBOARD_ALERTS_LEASE` | > 0 | snapshot들을 포함한 한 평가 회차보다 길게 |

상한·주기는 대표 규모의 측정으로 정한다. heartbeat·벤더 동기화·평가 회차와의 비교는 운영 준비 조건이며 다른 앱의 설정과 교차 검증하는 기동 검사는 없다. 원천 결과 상한·복사 상한을 넘으면 일부를 정상값으로 반환하지 않고 실패한다.

### 5.2 저장소 준비와 DDL 책임

원천과 캐시의 키·계정을 합치지 않는다. RDS source는 `SourceStoreConfig.sourceDataSource()`가 `@Primary`로 제공하며 JPA·기본 `JdbcClient`·사용자 인증이 이를 쓴다. 원천 계정은 SELECT만 갖고 PostgreSQL `readOnlyMode=always`를 추가한다. ClickHouse 원천 조회는 `ClickHouseSourceReader.query()`가 `readonly=2`와 상한을 요청마다 싣기 때문에 계정 프로필은 `readonly=2` 이하여야 한다(`readonly=1`은 설정 변경을 거부한다).

| 연결 | 준비할 권한과 소재 |
| --- | --- |
| RDS source | `enrollment`·`telemetry_ops` USAGE와 필요한 테이블 SELECT. 현재 회원·조직·세션 조회의 `enrollment.members`, `enrollment.tenants`, `enrollment.user_sessions`를 포함하며 마이그레이션으로 추가된 조회 대상의 권한도 준비 |
| RDS cache | `dashboard_cache` 스키마의 DDL·읽기·쓰기·정리와 `telemetry_ops` USAGE 및 `tenant_retention_boundary` SELECT. 최초 캐시 V1 마이그레이션의 `CREATE SCHEMA IF NOT EXISTS`는 기존 스키마에도 DB CREATE 권한을 검사하므로 최초 적용 계정에 DB CREATE를 준비 |
| ClickHouse source | 원천 논리 DB의 분석 테이블과 수신 ledger SELECT만 허용 |
| ClickHouse cache | 자기 캐시 논리 DB의 테이블·뷰 DDL·읽기·쓰기·정리와 snapshot 복사의 두 분석 테이블 SELECT. 원천에 INSERT·UPDATE·DELETE 권한을 주지 않음 |

RDS source와 cache는 **같은 RDS 논리 DB**의 원천(`enrollment`·`telemetry_ops`)과 캐시(`dashboard_cache`) 스키마에 연결한다. `SnapshotManifestStore.publish()`는 cache 연결에서 현재 `telemetry_ops.tenant_retention_boundary`를 같은 SQL의 하위 쿼리로 읽어 공개 CAS를 수행한다. ClickHouse source와 cache도 **같은 ClickHouse 서버**에 둔다. `SnapshotCopySql`의 서버 측 `INSERT … SELECT`가 cache 연결로 source database를 읽는다. 연결 URL이 같아도 계정과 설정 키는 각각 유지한다.

인프라가 먼저 DB·계정·권한 및 ClickHouse 캐시 논리 DB를 준비한다. RDS 캐시 스키마 이름은 코드 상수 `RdsCacheSchema.SCHEMA = dashboard_cache`다. 원천 `enrollment`·`telemetry_ops` Flyway는 enrollment-api가 먼저 적용한다. dashboard는 `spring.flyway.enabled=false`, `hibernate.ddl-auto=validate`이며 원천 마이그레이션을 실행하지 않는다.

현재 [RDS 캐시 V1 SQL](../apps/dashboard-api/src/main/resources/db/dashboard-cache/V1__dashboard_cache_schema.sql)의 `CREATE SCHEMA IF NOT EXISTS dashboard_cache`는 최초 적용 때 DB CREATE를 요구한다. [PostgreSQL 16의 `CreateSchemaCommand()`](https://raw.githubusercontent.com/postgres/postgres/REL_16_STABLE/src/backend/commands/schemacmds.c)는 권한 검사 후 기존 스키마이면 건너뛴다. Flyway 자체의 기존 스키마 검사는 생성을 건너뛰지만 이 V1 문장을 제거하지 않는다. V1이 이미 이력에 적용된 DB의 모든 재기동에 DB CREATE가 필요하다는 뜻은 아니다. DB CREATE는 새 스키마 생성까지 허용하므로 원천 테이블 쓰기 권한과 구별하고 배포 권한 검토에 포함한다.

그 뒤 dashboard의 `CacheSchemaInitializer.afterPropertiesSet()`가 RDS 캐시 Flyway(`classpath:db/dashboard-cache`, `dashboard_cache.flyway_schema_history`)부터 실행하고 ClickHouse 캐시 DDL(`ClickHouseCacheSchema.MIGRATIONS`, `/clickhouse/dashboard-cache/`)을 매 기동마다 전량 적용한다. 두 적용 중 하나라도 실패하면 기동에 실패한다. ClickHouse DDL은 DB를 만들지 않고 멱등 테이블·뷰 문장만 실행하며 이미 배포한 파일을 수정하지 않고 다음 번호로 추가한다.

알림 평가는 자기 RDS 스키마에 기록하되 snapshot 정리 대상에서 제외한다(ADR 0051). 알림 확인 명령은 enrollment가 소유하므로 그 계정에는 `dashboard_cache.alerts` SELECT가 별도로 필요하다. dashboard에 enrollment 쓰기 계정이나 DB master 계정을 재사용하지 않는다.

개발 Compose의 `prepareDashboardStore()`와 시드 도구는 계정·원천 마이그레이션을 준비하는 로컬 도구다. 실제 배포 DB의 권한이나 적용 완료를 증명하지 않는다. [개발용 시드 가이드](../tools/dev-seed/README.md)를 따른다.

### 5.3 인증과 경로 소유권

`SecurityConfig.dashboardAuthenticator()`는 `UserJwt.verifier()`와 `UserAccessVerifier.verify()`를 연결한다. 자체 JWT의 서명·issuer·audience·`kid`를 확인한 뒤 source RDS로 회원·조직 활성 상태, 현재 역할, 세션 소속·만료·폐기를 확인한다. 잘못된 자격은 401 `unauthenticated`, DB 등 인증 조회 장애는 503 `unavailable`이다. 현재 인가 정책은 조직 관리자(owner·admin) 조회만 허용하며 `GET /api/v1/auth/me`·로그인·refresh·logout은 enrollment가 소유한다.

`PULSEMETRY_USER_AUTH_PUBLIC_KEY_FILE`은 `public-key-files.local`의 **경로만** 바꾼다. 발급자가 `active-kid=key-2026-09`처럼 다른 `kid`를 쓰면 YAML 또는 `SPRING_APPLICATION_JSON`으로 `public-key-files`의 정확한 키 이름을 설정해야 한다. 키 교체·origin·검증 예시는 [사용자 인증 운영](user-auth-operations.md)의 dashboard 설정을 따른다.

배포 공개키 원본은 환경별 고정 ARN의 **`kid → X.509 PEM` JSON 키링 Secret**이다. dev EKS는 기존 원본과 선택한 공개키 VersionId를 유지하며 infra의 Pod Identity·ASCP/CSI가 키링을 kid별 PEM 파일로 공급한다. ECS 제거를 이유로 원본을 재생성하지 않는다. dashboard는 Secrets Manager를 직접 조회하지 않고 `kid → 파일 경로` 맵만 읽는다. 키를 이미지에 넣거나 PEM 원문을 환경변수로 전달하지 않는다. private-key-file·active-kid·개인키 Secret 권한은 이 앱에 주지 않는다. 배포 기록의 개인키/공개키 VersionId·active-kid와 파일 맵은 enrollment 공급까지 하나의 묶음으로 관리하지만 이 앱에는 공개키와 검증 설정만 전달한다.

`SecurityConfig.dashboardAuthenticator()`·`UserJwt.verifier()`의 기동 검사는 issuer/audience·유효 공개키 맵·RSA 2048비트 이상과 실제 파일 파싱을 확인한다. 파일 누락·빈 파일·읽기 불가·잘못된 PEM은 실패하지만 빈 경로 항목은 제외하므로 다른 유효 키가 남으면 그 항목만으로 기동이 실패하지 않는다. 개인키가 없어 발급자 키쌍을 비교하거나 올바른 PEM의 잘못된 kid 배치를 기동 때 판별할 수 없다. 공급자는 모든 기대 kid의 파일·VersionId 대응을 검증하고, 실제 발급 AT를 모든 enrollment·dashboard 검증 인스턴스에서 확인해야 한다.

교체는 모든 검증 인스턴스에 신·구 공개키 적용·검증 → 모든 enrollment signer의 개인키·kid 전환 → 마지막 구 키 발급 후 330초 이상 대기 → 구 공개키 제거 순서다. dev EKS의 모든 replica와 철거 전에 실제로 남아 있는 ECS verifier/signer도 포함한다. 파일은 기동 때 한 번 읽으므로 자동 reload는 없다. 롤백도 공개키를 먼저 복구하고 마지막 새 키 발급 후 330초 동안 새 공개키를 유지한다. 등록 부분 실패·키쌍/kid 불일치·교체/롤백의 [수락 조건](user-auth-operations.md#jwt-키-공급의-수락-조건)을 따른다. infra의 EKS CSI 파일 공급 코드는 구현됐지만, 실제 Secret 등록·선택 버전·mount 권한·배포 및 인증 검증의 완료는 별도 증거로 확인한다.

전체 path·HTTP method·OPTIONS 소유권은 [허브 계약의 공통 라우팅 표](../../docs/contracts/dashboard-api.md)가 단일 출처다. 이 문서의 API 목록은 HTTP 상세로 가는 길잡이이며 ALB 규칙을 만들 때 접두만으로 추정하지 않는다. 대표 충돌의 구현 근거는 다음과 같다.

| 요청 | 소유와 코드 근거 |
| --- | --- |
| GET 조직 `/teams`, `/members`, `/vendors` | dashboard `TeamsController.directory()`, `MembersController.members()`, `SettingsController.vendors()` |
| 같은 조직 경로의 생성·변경·삭제 | enrollment의 관리 controller가 소유; dashboard `SecurityConfig` CORS는 GET·OPTIONS만 허용 |
| GET 조직 `/onboarding`, `/invitations`, GET `/api/v1/auth/me` | enrollment의 `ManagementController.onboarding()`·`ManagementController.invitations()`·`CurrentUserController.me()`; GET이라는 이유로 dashboard에 보내지 않음 |
| OPTIONS 공유 URI | HTTP method는 OPTIONS. 허브 표에서 dashboard 소유인 GET의 preflight만 dashboard로 전달. enrollment 소유 GET(`/onboarding`, `/invitations`, `/api/v1/auth/me` 등)과 해당 쓰기 method의 preflight는 enrollment로 전달. 각 서비스는 origin·method·header를 다시 검증 |
| GET `/api/v1/healthz` | 각 서비스 내부 health check. 외부 ALB의 public route로 등록하지 않음 |

Dashboard CORS는 `pulsemetry.user-auth.allowed-origins`에 정확히 일치하는 origin, GET·OPTIONS, `Authorization`·`Content-Type`·`X-Request-Id`를 허용한다. 응답 노출 헤더는 `X-Request-Id`·`Retry-After`·`ETag`, `allowCredentials=false`다. ALB의 OPTIONS 전달과 앱의 CORS 승인 모두 확인해야 브라우저 조회가 성립한다.

## 6. 검증과 구현 한계

`:apps:dashboard-api:test`가 카탈로그 검색·페이지·인증, 개요·팀·구성원·설정·snapshot을 검증한다.
카탈로그는 DB 기준 데이터이며 V10이 기존 목록을 한 번 초기화한다. 관리자 편집 API는 없으며 권한 있는 DB 작업으로 관리한다.
계약 없는 수동 벤더는 조회에 남으며 state=needs_review, contract=null이다.
관리 기능이 켜져 있으면 editContracts·editCollectionPolicy·editAlertRules는 true이고 저장은 enrollment-api에 요청한다.
collectionPolicy.collectRawContent는 프롬프트·응답 중 하나라도 허용됐는지다. 도구 내용·API 원문은 이 선택과 별개다.
두 플래그가 다를 때 온보딩 조회는 null로 표현해 명시적 재선택을 받는다.
설정의 계약 좌석·월 요금은 계약의 값이고, 보유·활성 좌석과 개요의 좌석 집계는 좌석 원장의 값이다(위 "좌석 원장 조회").
알림 발송(메일 등 — 알림은 저장·조회·확인뿐, 위 "알림")·원격 정책 갱신 완료·좌석 구독료의 실제 청구액은 구현하지 않는다(종량 지출은 §7.2 — ADR 0050). 좌석 회수·복원은 enrollment-api 명령이고 이 앱은 가능 여부와 작업 결과를 읽는다(ADR 0049).



## 이전 API 절 안내

기존 절 번호·앵커로 들어온 경우 아래 링크에서 상세 명세를 확인한다.

<a id="1-인증책임응답"></a>

## 1. 인증·책임·응답

→ [공통 HTTP 규칙](api/common.md)

<a id="2-페이지별-조직-조회-api"></a>

## 2. 페이지별 조직 조회 API

→ [API 길잡이](api/README.md)

<a id="21-개요-페이지"></a>

### 2.1 개요 페이지

→ [개요](api/overview.md)

<a id="22-팀-분석-페이지"></a>

### 2.2 팀 분석 페이지

→ [API 길잡이](api/README.md)

<a id="팀-목록"></a>

#### 팀 목록

→ [API 길잡이](api/README.md)

<a id="제품별-사용-adr-0045"></a>

### 제품별 사용 (ADR 0045)

→ [공통 응답 스키마](api/common-schemas.md)

<a id="현재-데이터의-해석"></a>

### 현재 데이터의 해석

→ [공통 응답 스키마](api/common-schemas.md)

<a id="정책-적용-현황과-업데이트-안내-adr-0043"></a>

### 정책 적용 현황과 업데이트 안내 (ADR 0043)

→ [설치 현황·업데이트 안내](api/installations.md)

<a id="조직-정책-설정-adr-0046"></a>

### 조직 정책 설정 (ADR 0046)

→ [수집 정책·조직 정책 설정](api/collection-policy.md)

<a id="알림-규칙-허브-adr-0008"></a>

### 알림 규칙 (허브 ADR 0008)

→ [알림·알림 규칙](api/alerts.md)

<a id="알림-adr-0051-56"></a>

### 알림 (ADR 0051 §5·§6)

→ [알림·알림 규칙](api/alerts.md)

<a id="기간-완전성과-비교-adr-0042"></a>

### 기간 완전성과 비교 (ADR 0042)

→ [공통 응답 스키마](api/common-schemas.md)

<a id="팀-누적-세션-adr-0042"></a>

### 팀 누적 세션 (ADR 0042)

→ [팀 분석·관리](api/teams.md)

<a id="좌석-원장-조회-adr-0048"></a>

### 좌석 원장 조회 (ADR 0048)

→ [좌석 원장·회수·복원](api/seats.md)

<a id="작업-상태-조회"></a>

### 작업 상태 조회

→ [비동기 작업](api/operations.md)

<a id="3-벤더와-플랜-카탈로그"></a>

## 3. 벤더와 플랜 카탈로그

→ [벤더·플랜 카탈로그](api/vendor-catalog.md)

<a id="31-벤더-검색페이지"></a>

### 3.1 벤더 검색·페이지

→ [벤더·플랜 카탈로그](api/vendor-catalog.md)

<a id="32-선택한-벤더의-플랜"></a>

### 3.2 선택한 벤더의 플랜

→ [벤더·플랜 카탈로그](api/vendor-catalog.md)

<a id="4-오류와-데이터-일관성"></a>

## 4. 오류와 데이터 일관성

→ [공통 HTTP 규칙](api/common.md)

<a id="7-등록-제품과-계약-정정"></a>

## 7. 등록 제품과 계약 정정

→ [등록 제품·벤더 계약](api/vendors.md)

<a id="71-식별자와-화면별-조회"></a>

### 7.1 식별자와 화면별 조회

→ [등록 제품·벤더 계약](api/vendors.md)

<a id="72-상태합계미제공-값"></a>

### 7.2 상태·합계·미제공 값

→ [등록 제품·벤더 계약](api/vendors.md)

<a id="73-관측-지표-adr-0044"></a>

### 7.3 관측 지표 (ADR 0044)

→ [등록 제품·벤더 계약](api/vendors.md)

<a id="74-좌석-원천-adr-0048"></a>

### 7.4 좌석 원천 (ADR 0048)

→ [좌석 원장·회수·복원](api/seats.md)

<a id="계약-기간-상태-contractstatus"></a>

### 계약 기간 상태 (`contractStatus`)

→ [등록 제품·벤더 계약](api/vendors.md)

<a id="공통-헤더-수집-현황"></a>

## 공통 헤더 수집 현황

→ [수집 상태](api/ingest-status.md)

<a id="판정-adr-0041"></a>

### 판정 (ADR 0041)

→ [수집 상태](api/ingest-status.md)

<a id="세는-값과-null"></a>

### 세는 값과 null

→ [수집 상태](api/ingest-status.md)

<a id="임계값-설정"></a>

### 임계값 설정

→ [수집 상태](api/ingest-status.md)

<a id="구성원의-사용-예정-제품"></a>

### 구성원의 사용 예정 제품

→ [구성원 조회·편집](api/members.md)
