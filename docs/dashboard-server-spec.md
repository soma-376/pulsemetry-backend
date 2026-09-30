# Dashboard 서버 명세

`:apps:dashboard-api`가 제공하는 조회 API의 서버 명세다. 기본 포트는 8081이다.
사용자 로그인·온보딩 상태·정책 저장·팀/벤더/초대 명령은 [Enrollment 서버 명세](enrollment-server-spec.md)를 따른다.
레포 간 확정 계약은 [문서 허브](../../docs/contracts/README.md)가 우선한다.
현재 허브 dashboard 계약은 골격이며 이 문서는 구현된 HTTP와 제한을 기록한다.

## 1. 인증·책임·응답

- 조직 조회와 카탈로그에 `Authorization: Bearer <access_token>`을 사용한다. owner/admin만 접근한다.
- 인증은 enrollment-api가 발급한 JWT의 공개키 검증과 현재 사용자·세션 조회를 결합한다.
  로그아웃·폐기된 세션은 기존 AT로도 다음 요청부터 401이다.
- 조직 경로는 인증 주체의 조직과 일치해야 한다. 타 조직 접근은 404, 일반 구성원은 403이다.
- 인증 기본값은 비활성이며 이때 보호 경로를 모두 거부한다. 공개키·issuer·audience를 설정해 활성화한다.
- PostgreSQL enrollment/telemetry_ops와 ClickHouse 분석 원천은 읽기 전용이다.
  snapshot 및 조회용 캐시만 dashboard_cache에 쓴다. 관리 명령은 enrollment-api가 담당한다.
- 성공은 `application/json`, camelCase DTO이며 별도 공통 data 봉투가 없다. nullable 필드는 유지한다.
- 금액은 USD decimal 문자열이다. null을 0으로 바꾸거나 환산 비용을 실제 청구액으로 표현하지 않는다.
- 허용된 프론트 origin에 GET/OPTIONS CORS를 제공하며 쿠키 인증을 사용하지 않는다.
- `GET /v1/healthz`는 인증 없이 200 `{ "status": "ok" }`를 반환하는 생존 확인이다.

## 2. 조직별 조회 API

아래 경로 앞에는 `/api/v1/organizations/{organizationId}`를 붙인다. 모두 GET이다.
성공은 200 JSON이며 필드명은 camelCase다.

신규 조직은 생성 시 초기화한 빈 수집 요약을 읽어 개요에 `meta.dataState=never_observed`,
`ingest.status=empty`를 반환한다(ADR 0034). 사용량은 null이며 조회 과정에서 요약을 쓰지 않는다.
기존 조직에 요약도 백필 완료 근거도 없으면 이력 불명으로 503을 유지한다.

| 경로 | 쿼리 | 응답 타입 |
| --- | --- | --- |
| `/analytics/overview` | 기간, compare | OverviewResponse |
| `/analytics/teams` | 기간, compare, sort, limit=20(최대 50), cursor, snapshotId | TeamsResponse |
| `/analytics/teams/{teamId}` | 기간, compare, snapshotId | TeamDetailResponse |
| `/analytics/teams/{teamId}/users` | 기간, limit=12(최대 100), cursor, snapshotId | TeamUsersResponse |
| `/teams` | q, limit=50(최대 100), cursor, snapshotId | TeamDirectoryResponse |
| `/members/dashboard` | 기간 | MembersResponse |
| `/members` | 기간, q, limit=20(최대 100), cursor, snapshotId | MemberListResponse |
| `/members/unassigned` | 기간, limit=20(최대 100), cursor, snapshotId | MemberListResponse |
| `/seat-reclaim-candidates` | limit=20(최대 100), cursor, snapshotId | ReclaimCandidatesResponse |
| `/ingest-status` | 없음 | IngestStatusResponse |
| `/settings` | 없음 | SettingsResponse |
| `/vendors` | limit=20(최대 100), cursor, snapshotId | VendorsResponse |
| `/vendors/{vendorId}` | 없음 | VendorResponse |
| `/installations` | policyStatus=outdated, limit=20(최대 100), cursor, snapshotId | InstallationsResponse |
| `/operations/{operationId}` | 없음 | OperationResponse |

기간은 필수 `startDate`, `endDate` (`YYYY-MM-DD`, 종료일 포함, 1~366일)와 선택 `timeZone`이다.
시간대는 `Asia/Seoul`만 지원하며 생략 시에도 같은 값이다.
`compare`는 `prev_week`(기본), `prev_period`, `none`이다. 구성원 화면에는 비교가 없다.
`sort`는 `cost`(기본), `token`, `session`이다. `q`는 최대 200자다.
팀 경로의 `teamId=unassigned`는 미배정 팀을 뜻한다.

다음 페이지에는 동일 조건과 응답의 `nextCursor`·`snapshotId`를 사용한다.
409 `snapshot_expired`이면 첫 페이지부터 다시 조회한다. 개요는 응답에 snapshotId를 노출하지 않는다.

```ts
type Money = string; // USD decimal. null을 0으로 바꾸지 않는다.
type Page<T> = { items: T[]; totalCount: number; nextCursor: string | null };
type Section<T> = {
  availability: "available" | "partial" | "unavailable";
  reason: string | null; data: T | null;
};
type Usage = {
  activeUsers: number | null; sessionCount: number | null;
  equivalentCostUsd: Money | null;
  tokens: {
    inputUncached: number | null; output: number | null;
    cacheRead: number | null; cacheWrite: number | null; total: number | null;
  };
};
```

개요 최상위 필드는 `meta`, `comparison`, `ingest`, `usage`, `seats`, `alerts`, `trend`, `modelMix`, `waste`, `teamUsage`다.
`usage.current/previous`는 `Usage | null`, 추이의 비용·토큰은 null 가능하다.
`teamUsage`는 상위 팀·나머지 팀·미배정으로 구분하며 이벤트 시점 소속으로 집계한다.
팀 간 이동한 구성원이나 여러 도구를 쓰는 구성원을 조직 활성 사용자 수에서 중복 계산하지 않는다.

전체 응답 필드·nullable 타입:

- [개요 DTO](../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/OverviewResponse.kt), [공통 사용량 DTO](../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/AnalyticsTypes.kt)
- [팀 DTO](../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/TeamsResponses.kt)
- [구성원 DTO](../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/MembersResponses.kt)
- [설정 DTO](../apps/dashboard-api/src/main/kotlin/com/team376/pulsemetry/dashboard/analytics/SettingsResponses.kt)

### 현재 데이터의 해석

- 관측 완전성의 근거가 없어 현재 사용량은 `partial`이며 비교는 `unavailable`이다. 비교 없음은 `disabled`다.
- 서로 다른 토큰 의미 프로파일을 섞거나 필수 토큰 값이 누락되면 합계가 null일 수 있다.
- 환산 비용과 실제 청구액은 별개다. 인보이스 원천이 없으므로 실제 청구액은 null이다.
- 최근 수신만으로 수집 정상·장애를 확정하지 않는다. unknown/empty를 정상으로 바꾸지 않는다.
- 수동 계약의 좌석 수·월 단가는 저장·조회하지만 실제 벤더 좌석 사용/회수는 연결하지 않는다.
- 알림·회수 실행·기존 설치로의 정책 배포·보존 설정 조작은 capability=false 또는 unavailable 상태다.

### 작업 상태 조회

`GET /api/v1/organizations/{organizationId}/operations/{operationId}` — 명령이 접수한 뒤 요청 밖에서 끝나는 일의 상태다(ADR 0039).
이 앱은 읽기만 한다. 작업을 만들고 결과를 기록하는 쪽은 그 명령을 받은 앱과 실행 주체이며, 기록은 `enrollment.operations`·`operation_targets`에 있다.
snapshot을 쓰지 않는 현재 상태 조회다.

```ts
type OperationResponse = {
  operationId: string;
  kind: "seat_reclaim" | "seat_restore" | "installation_notification" | "retention_cleanup";
  status: "pending" | "running" | "awaiting_admin_action" | "succeeded" | "partially_failed" | "failed";
  createdAt: string;
  completedAt: string | null; // succeeded·partially_failed·failed일 때만 값이 있다
  results: {
    targetId: string; // 무엇의 ID인지는 kind가 정한다
    status: "pending" | "awaiting_admin_action" | "succeeded" | "failed";
    reason: string | null; // failed일 때의 실패 분류 코드
    action: string | null; // 관리자가 시스템 밖에서 해야 하는(했던) 조치의 코드
  }[];
  canRestore: boolean;
  restoreUntil: string | null;
  retention: {
    status: "running" | "incomplete" | "logically_deleted" | "failed";
    requestedBefore: string; deletedBefore: string | null;
    startedAt: string; finishedAt: string | null;
  } | null;
};
```

- 작업의 `status`는 대상 결과에서 계산한 값이다. 기다리는 대상이 남아 있으면 `running`, 기다리는 대상이 없고 조치 대기가 남아 있으면 `awaiting_admin_action`,
  모두 끝났으면 전부 성공일 때만 `succeeded`다. 성공이 하나도 없으면 `failed`, 섞였으면 `partially_failed`다. `results`는 작업에 넣은 순서다.
- `awaiting_admin_action`은 시스템이 끝낼 수 없는 대상이다. 관리자가 `action`의 조치를 시스템 밖에서 하고 확인해야 성공이 된다. 자동으로 성공이 되지 않는다.
- `Retry-After`(초)는 `pending`·`running`에만 싣는다. 값은 `pulsemetry.dashboard.retry-after`다. 조치 대기와 끝난 작업에는 없다 — 클라이언트는 헤더가 없으면 반복 조회를 멈춘다.
- `canRestore`는 성공한 대상이 있는 끝난 `seat_reclaim`이고 `restoreUntil` 전이며, 그 회수를 되돌리는 실패하지 않은 복원 작업이 없을 때만 true다.
- `retention`은 `retention_cleanup` 작업이 가리키는 가장 최근 삭제 실행(`telemetry_ops.retention_operations`)이다. 아직 실행된 적이 없으면 null이다.
  `logically_deleted`는 논리 삭제 완료이며 물리 제거 완료가 아니다. 삭제한 행 수와 상세 문구는 싣지 않는다.
- 그 조직에 없는 작업은 404 `not_found`다. 다른 조직의 작업, 없는 ID, UUID가 아닌 ID가 같은 응답이다. **실패한 작업은 404가 아니라 200과 `status=failed`다.**
- 이 저장소에는 아직 작업을 만드는 명령이 없다. 첫 명령이 생기기 전까지 이 조회가 돌려줄 작업은 없다.

## 3. 벤더와 플랜 카탈로그

로그인한 owner/admin에게 제공하는 공통 선택지다. 조직 경로 접두를 붙이지 않는다.
목록과 계약 저장 검증의 원천은 `enrollment.vendor_catalog_vendors`·`vendor_catalog_products`·`vendor_catalog_plans`다.
enrollment-persistence의 `VendorCatalog`가 매 요청 DB에서 활성 제품과 플랜을 읽는다(ADR 0035).
DB 편집은 서버 재시작 없이 반영되며 조직별 등록과 계약 이력은 바뀌지 않는다.
카탈로그 ID는 `claude_team`처럼 제품 종류를 나타내며, 조직에 등록한 vendorId UUID와 다르다.

### 3.1 벤더 검색·페이지

`GET /api/v1/vendor-catalog?q=anthropic&limit=20&cursor=...`

q는 선택, 최대 200자다. 앞뒤 공백을 제거하고 대소문자를 구분하지 않는 부분 문자열 검색으로
id·provider·displayName·product를 찾는다. limit 기본 20, 범위 1~100이다.
id 오름차순이며 다음 페이지는 같은 q와 응답 nextCursor를 보낸다.
cursor는 검색어·카탈로그 버전·마지막 ID를 담은 불투명 값이다.
`catalogVersion`은 공개 목록 내용의 SHA-256 문자열이다. 클라이언트는 숫자로 해석하지 않는다.
해석 불가·다른 검색어·다른 카탈로그 버전은 400 invalid_request다. 첫 페이지부터 다시 조회한다.

```json
{
  "catalogVersion": "<SHA-256>",
  "items": [{
    "id": "claude_team",
    "provider": "anthropic",
    "displayName": "Claude (Anthropic)",
    "product": "Claude Code · claude.ai",
    "allowsSeatTiers": true
  }],
  "totalCount": 1,
  "nextCursor": null
}
```

items에 플랜 전체나 단가를 포함하지 않는다. totalCount는 검색 결과 전체 개수다.

### 3.2 선택한 벤더의 플랜

`GET /api/v1/vendor-catalog/claude_team/plans`

```json
{
  "catalogVersion": "<SHA-256>",
  "vendor": {
    "id": "claude_team", "provider": "anthropic", "displayName": "Claude (Anthropic)",
    "product": "Claude Code · claude.ai", "allowsSeatTiers": true
  },
  "plans": [
    {"id":"team","displayName":"Team","billing":"seat","separateUsageBilling":true},
    {"id":"enterprise","displayName":"Enterprise","billing":"seat","separateUsageBilling":true}
  ]
}
```

없는 제품 ID는 404다. 플랜 ID는 해당 제품 안에서 해석한다(enterprise는 여러 제품에 존재).
카탈로그는 현재 앱에서 지원하는 입력 선택지이며 공급자의 실시간 가격표가 아니다.
제품별 `allowsSeatTiers`의 공식 근거와 플랜·계약별 예외는 [카탈로그 좌석 등급 근거](vendor-catalog-evidence.md)에 기록한다. V11부터 OpenAI도 복수 좌석 입력을 허용한다.
벤더별 좌석 조회·회수·복원과 실제 청구액 API의 공식 근거는 [벤더 좌석·청구 API 근거](vendor-connector-evidence.md)에 기록한다. 그 문서에 근거가 있는 벤더·기능만 커넥터로 구현한다.
프론트는 벤더 선택 시 조회하고 제품 ID별로 캐싱할 수 있다. 벤더 변경 시 이전 플랜 선택은 해제한다.
단가·좌석 수는 조직이 입력한다. 플랜 선택 없이 벤더만 등록하는 것도 허용한다.
기존 `/settings.catalog`는 호환을 위해 유지하되 같은 정의로 생성한다.

## 4. 오류와 데이터 일관성

```json
{"error":{"code":"invalid_request","message":"요청 형식이 올바르지 않습니다.","fieldErrors":[]},"requestId":"요청 ID"}
```

| 상태 | 코드·처리 |
| --- | --- |
| 400 | invalid_request — 날짜·검색·커서·limit 확인 |
| 401 | unauthenticated — 로그인/세션 갱신 |
| 403 | forbidden — 관리자 권한 필요 |
| 404 | not_found — 타 조직·없는 자원·계약 밖 경로 |
| 405 | method_not_allowed |
| 409 | snapshot_expired — 동일 조건 첫 페이지부터 재조회 |
| 500 | internal_error |
| 503 | unavailable — Retry-After 후 재시도 |

응답의 X-Request-Id를 로그 상관 키로 사용한다. 인증 저장소 장애도 503이며 토큰 오류로 처리하지 않는다.
개요는 요청마다 snapshot 하나에서 섹션을 집계한다. snapshot ID를 개요 응답에 노출하지 않는다.
목록의 snapshot/cursor는 조직·조건에 묶이며 유효기간은 10분이다.
조직·날짜·모델·팀의 합계는 같은 데이터 기준을 사용한다. 누락 비용을 제외한 부분합을 총액으로 표시하지 않는다.

## 5. 운영과 로컬 실행

개발 Compose의 `dev-seed`는 Spring 서버 없이 기존 원천 마이그레이션·A/B/C 시드와
조회용/캐시용 DB 계정을 준비한다. `docker compose up -d --build` 후 시드 종료 코드 0을 확인한다.
Compose가 인증 키를 `build/dev-auth`에 준비하며 서버 설정은 앱의 local 프로필이 제공한다.

```powershell
# DB와 enrollment 마이그레이션이 준비된 뒤 별도 터미널에서 실행
.\gradlew.bat :apps:dashboard-api:bootRun --args="--spring.profiles.active=local"
```

조회용/캐시용 DB 계정과 인증 키는 Compose가 준비한다. local 프로필이 공개키와 개발 계정 설정을 읽는다.
서버는 enrollment-api의 private key를 사용하지 않는다. issuer·audience·public key는 발급 서버와 일치해야 한다.
`pulsemetry.user-auth.enabled` 기본 false, 허용 origin은 user-auth.allowed-origins에 지정한다.
`pulsemetry.management.enabled`는 관리 가능한 UI capability를 나타내며 실제 쓰기는 enrollment-api에 보낸다.

일반 실행은 `:apps:dashboard-api:bootRun`이며 필요한 설정은 다음 그룹이다.

| 설정 그룹 | 내용 |
| --- | --- |
| `pulsemetry.user-auth` | enabled, issuer, audience, public-key-files, allowed-origins |
| `pulsemetry.dashboard.rds.source` | 원천 읽기 JDBC URL·계정 |
| `pulsemetry.dashboard.rds.cache` | dashboard_cache JDBC URL·쓰기 계정 |
| `pulsemetry.dashboard.clickhouse.source` | 원천 URL·database·계정·query-timeout·max-result-rows/bytes |
| `pulsemetry.dashboard.clickhouse.cache` | 캐시 URL·database·계정·query-timeout |
| `pulsemetry.dashboard.snapshot` | build-timeout·purge-grace·max-concurrent-builds·max-copy-rows/bytes·cleanup-interval |
| `pulsemetry.dashboard.members.idle-days` | 회수 후보 기준 기간 |
| `pulsemetry.dashboard.ingest` | 수집 상태 판정의 임계값 — window·delayed-after·down-after. 셋 다 기본값이 없다(아래 "공통 헤더 수집 현황") |
| `pulsemetry.dashboard.retry-after` | 일시 장애 재시도 간격 |

정확한 환경변수 이름은 [application.yaml](../apps/dashboard-api/src/main/resources/application.yaml)에 매핑돼 있다.
캐시 DB/스키마와 권한은 기동 전에 준비하고 캐시 테이블은 앱이 자기 마이그레이션으로 생성한다.
enrollment Flyway는 이 앱에서 실행하지 않는다. 새 원천 테이블은 enrollment-api가 먼저 적용해야 한다.
개발 환경에서는 Compose의 일회성 시드 도구도 동일한 마이그레이션을 실행한다(ADR 0030).
시드 계정과 데이터 준비는 [개발용 시드 가이드](../tools/dev-seed/README.md)를 따른다.

## 6. 검증과 구현 한계

`:apps:dashboard-api:test`가 카탈로그 검색·페이지·인증, 개요·팀·구성원·설정·snapshot을 검증한다.
카탈로그는 DB 기준 데이터이며 V10이 기존 목록을 한 번 초기화한다. 관리자 편집 API는 없으며 권한 있는 DB 작업으로 관리한다.
계약 없는 수동 벤더는 조회에 남으며 state=needs_review, contract=null이다.
관리 기능이 켜져 있으면 editContracts·editCollectionPolicy는 true이고 저장은 enrollment-api에 요청한다.
collectionPolicy.collectRawContent는 프롬프트·응답 중 하나라도 허용됐는지다. 도구 내용·API 원문은 이 선택과 별개다.
두 플래그가 다를 때 온보딩 조회는 null로 표현해 명시적 재선택을 받는다.
설정의 계약 좌석·월 요금과 개요의 좌석 집계는 별개이며 후자는 아직 unavailable이다.
실제 벤더 좌석 회수·알림 평가·원격 정책 갱신 완료·실제 청구액은 구현하지 않는다.

## 7. 등록 제품과 계약 정정

설정 `/settings`, `/vendors`, `/vendors/{vendorId}`는 조직에 등록한 제품만 반환한다.
ID는 관리 API와 같은 vendorId UUID다. 공급자 관측·기존 기간 약정으로 행을 추가하지 않는다.
계약 정정 후에는 새로운 snapshot으로 재조회한다. 기존 cursor는 그 조회 기준 시각을 유지한다.
합계는 contractStatus=active인 계약만 포함한다. 만료·시작 예정·미입력은 제외하며 UI에 제외 건수를 표시한다. 유효 계약이 없으면 월 계약액과 좌석 수는 0이다. 유효 계약 자체의 필요한 값이 누락되면 해당 합계는 null이다. 이는 유효 계약 기준 합계이며 실제 전체 지출이나 자동 해지·갱신을 의미하지 않는다.

### 7.1 식별자와 화면별 조회

| 값 | 의미 | 사용하는 곳 |
| --- | --- | --- |
| provider | 공급사 ID. 예: anthropic, openai | 카탈로그 표시·검색 |
| kind / 카탈로그 id | 제품 종류. 예: claude_team, openai_biz | 등록 요청·플랜 목록 조회 |
| vendorId | 조직이 등록한 제품의 UUID | 상세 조회·이름/계약 수정·삭제 |
| planId | 해당 제품 안의 플랜 ID | 계약 저장. 다른 제품의 플랜을 전용하지 않음 |
| version | 조회 당시 등록의 변경 버전 | 관리 명령의 expectedVersion 또는 If-Match |

개요의 사용량·추이는 `/analytics/overview`, 현재 등록 제품·좌석 계약은 `/settings`와 `/vendors`에서 읽는다.
개요와 설정의 계약 정보는 같은 원천이다. `/settings`에는 기간 필터를 적용하지 않는다.
계약 저장 후에는 설정뿐 아니라 개요의 계약 조회도 갱신해야 한다.

### 7.2 상태·합계·미제공 값

- 계약이 없으면 `contract=null`, `state=needs_review`다. 등록 행 자체는 남는다.
- 오늘(서울)이 계약 시작일~종료일 안이면 configured다. 종료일 null은 상한 없음이며 기간 밖은 needs_review다.
- `summary.monthlySeatFeeUsd`와 `contractedSeats`: 합계는 contractStatus=active인 계약만 포함한다. 만료·시작 예정·미입력은 제외하며 UI에 제외 건수를 표시한다. 유효 계약이 없으면 월 계약액과 좌석 수는 0이다. 유효 계약 자체의 필요한 값이 누락되면 해당 합계는 null이다. 이는 유효 계약 기준 합계이며 실제 전체 지출이나 자동 해지·갱신을 의미하지 않는다.
- 단가 0의 유효 계약은 무료로 입력된 값이며 미입력과 다르다. 표시할 때 null은 `-`, 숫자 0은 0으로 구분한다.
- 등록 UUID와 실제 좌석 배정·관측을 연결할 원천이 없어 activeUsers7d/30d·firstSeenAt/lastSeenAt 및 활성 좌석 합계는 null이다.
  계약 좌석에서 관측 사용자 수를 빼서 미사용 좌석이나 절감액을 만들지 않는다.
- 계약 없는 제품도 표시 이름을 바꿀 수 있다. 계약 비우기·제품 삭제·정정의 저장 규칙은 Enrollment 명세 §12를 따른다.

목록 페이지는 같은 snapshotId로 이어 읽고, 저장 후에는 첫 페이지부터 새 snapshot으로 읽는다.
관리 요청의 `version_conflict`와 조회의 `snapshot_expired`는 별개다. 전자는 사용자 입력과 최신 값을 확인하고,
후자는 기존 페이지를 섞지 않고 첫 페이지부터 조회한다.

### 계약 기간 상태 (`contractStatus`)

설정·벤더 목록·상세와 등록/정정 응답의 vendor에 `contractStatus: missing | scheduled | active | expired`를 반환한다.
기존 `state`는 유지한다(active만 configured, 나머지는 needs_review). 상태는 DB에 저장하지 않고
서울 시간의 조회 기준일과 계약 기간으로 계산한다. 페이지네이션은 동일 snapshot의 기준 시각을 사용한다.

| 값 | 의미 |
| --- | --- |
| missing | contract=null, 계약 미입력 |
| scheduled | 오늘이 effectiveFrom 이전 |
| active | 시작일 이후이며 종료일 당일까지. 종료일 null은 상한 없음 |
| expired | 오늘이 effectiveTo 이후 |

만료되어도 등록·계약 원문·이력은 보존하며 자동 삭제·해지·갱신하지 않는다. 기존 금액·좌석은
마지막 계약 정보로 표시한다. 합계는 contractStatus=active인 계약만 포함한다. 만료·시작 예정·미입력은 제외하며 UI에 제외 건수를 표시한다. 유효 계약이 없으면 월 계약액과 좌석 수는 0이다. 유효 계약 자체의 필요한 값이 누락되면 해당 합계는 null이다. 이는 유효 계약 기준 합계이며 실제 전체 지출이나 자동 해지·갱신을 의미하지 않는다.
갱신 등록은 지원 범위 밖이며 기존 PUT은 시작일을 보존하는 입력 정정이다.

## 공통 헤더 수집 현황

`GET /api/v1/organizations/{organizationId}/ingest-status`는 선택 기간과 무관한 조직의 현재 수집 상태다.
기존 설정 조회와 같은 owner/admin 조직 권한 검사를 거치며, 계약·manifest 유무와 무관하게 조회한다.
시각은 UTC ISO 8601이며 `asOf`는 조회 기준 시각이다. DB 쓰기나 새 집계 테이블은 없다.

```ts
type IngestStatusResponse = {
  organizationId: string;
  status: "empty" | "healthy" | "delayed" | "down" | "unknown";
  reason: string | null;
  asOf: string;
  lastReceivedAt: string | null;
  // 아래는 더한 필드다. 앞의 다섯은 처음 계약 그대로다.
  windowMinutes: number;
  activeInstallations: number | null;
  observedMembers: number | null;
  eligibleMembers: number | null;
  coverageRatio: number | null;
  coverageTargetMembers: number | null;   // coverageRatio의 분모
  coverageObservedMembers: number | null; // coverageRatio의 분자
};
```

개요·팀·구성원·설정 응답의 `ingest` 조각은 **같은 계산**의 결과다(ADR 0041). 조각의 키는 그대로이고
`coverageTargetMembers`·`coverageObservedMembers`는 이 응답에만 있다.

### 판정 (ADR 0041)

최근 수신 시각만으로 `healthy`·`delayed`·`down`을 추정하지 않는다. 근거는 설치 보고(enrollment 명세 §4.5)의 전달 결과다.
그 조직의 **활성 설치**만 본다. 위에서 아래로 먼저 맞는 줄이 답이다.

| 조건 | `status` | `reason` |
| --- | --- | --- |
| 수신 이력이 없다 | `empty` | null |
| 판정 대상이 있고 전부 중단 | `down` | `delivery_stalled` |
| 판정 대상이 있고 하나라도 지연·중단 | `delayed` | `delivery_delayed` |
| 판정 대상이 있고 모두 정상 | `healthy` | null |
| 판정 대상이 없고, 수집 중이던 설치의 가장 최근 보고가 `down-after`보다 오래됐다 | `down` | `installations_silent` |
| 판정 대상이 없고, 수집 중이던 설치가 창 밖에서 보고했다 | `unknown` | `installations_silent` |
| 수집 중이라고 보고한 설치가 없다(설치 없음 · 보고한 적 없음 · 수집 비활성이나 직결뿐) | `unknown` | `source_not_available` |
| 설치 보고를 읽지 못했다 | `unknown` | `source_not_available` |

- **판정 대상** = 창 안에 보고했고(`window`, 시작 시각 포함) 수집 중(경로 `local` · 전달기 가동 · 수신기 가동)이라고 보고한 설치.
  회사로 직접 보내는 설치와 전달·수신을 꺼 둔 설치는 판정 대상이 아니다 — "전부 중단"의 전부에 들지 않는다.
- 판정 대상 설치 하나의 상태:
  - 창 안에 끝난 손실 구간이 있고 마지막 전달 성공이 `down-after`보다 오래됐다 → 중단. 전달한 적이 없으면 수신기가 듣기 시작한 때부터 센다.
  - 창 안에 끝난 손실 구간이 있다 → 지연.
  - 전달 대기가 `delayed-after`보다 오래 이어졌다(`installation_heartbeats.pending_since`) → 지연.
  - 그 밖 → 정상. 대기 수가 0이 아니라는 것만으로, 마지막 전달이 오래됐다는 것만으로는 지연이 아니다.
- 수신 이력이 없으면 설치가 보고하고 있어도 `empty`다(ADR 0034의 뜻 그대로).
- 이력 판정 근거 자체가 없으면 기존 개요와 같이 503으로 실패하며 `empty`로 위장하지 않는다.

### 세는 값과 null

| 필드 | 뜻 | null인 때 |
| --- | --- | --- |
| `windowMinutes` | 설정 `pulsemetry.dashboard.ingest.window` | — |
| `activeInstallations` | 창 안에 설치 보고가 있는 활성 설치 수(경로를 가리지 않는다) | 그 조직의 활성 설치가 보고한 적이 없다 · 설치 보고를 읽지 못했다 |
| `observedMembers` | 창 안에 수신(ledger)이 있는 설치의 구성원 수 | 조회 실패 |
| `eligibleMembers` | 활성 구성원 수 | 조회 실패 |
| `coverageRatio` | `coverageObservedMembers ÷ coverageTargetMembers` | 분모가 0 · 수신이나 설치 보고를 읽지 못했다 |
| `coverageTargetMembers` | 활성 구성원 중, 마지막 보고가 수집 중이거나 직결인 활성 설치를 가진 사람 수 | 설치 보고를 읽지 못했다 |
| `coverageObservedMembers` | 그중, 그런 설치에서 창 안에 수신이 확인된 사람 수 | 수신이나 설치 보고를 읽지 못했다 |

- 커버리지의 분모에서 빠지는 것: 보고한 적 없는 설치, 수집 비활성으로 보고한 설치, 활성이 아닌 구성원, 설치가 없는 구성원.
- `coverageRatio`는 `observedMembers ÷ eligibleMembers`가 **아니다.** `eligibleMembers`에는 설치가 없는 사람이 들어 있다.
- 커버리지는 창 안의 수신을 센다. 데몬이 살아 있어도 그 사람이 창 안에 도구를 쓰지 않았으면 분자에 들지 않는다.
- 0은 "세어 보니 0"이다. 근거가 없으면 null이다.

### 임계값 설정

셋 다 기본값이 없다. 비거나 서로 맞지 않으면 기동하지 않는다.

| 설정 | 환경 변수 | 뜻 | 제약 |
| --- | --- | --- | --- |
| `pulsemetry.dashboard.ingest.window` | `PULSEMETRY_DASHBOARD_INGEST_WINDOW` | "지금"으로 보는 창 | 1분 이상의 분 단위. 설치 보고 주기(`pulsemetry.heartbeat.report-interval`)보다 길게 |
| `pulsemetry.dashboard.ingest.delayed-after` | `PULSEMETRY_DASHBOARD_INGEST_DELAYED_AFTER` | 전달 대기가 이보다 오래 이어지면 지연 | 0보다 크다 |
| `pulsemetry.dashboard.ingest.down-after` | `PULSEMETRY_DASHBOARD_INGEST_DOWN_AFTER` | 전달 성공(또는 설치 보고)이 이보다 오래 없으면 중단 | `delayed-after`보다 크다 |

local 프로필은 15분 · 5분 · 24시간이다. 개발 시드에는 살아 있는 데몬이 없어 시드 A는 기준일에서 하루가 지나면 `down`(`installations_silent`)이다.
