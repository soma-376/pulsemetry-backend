# 0023. 대시보드 snapshot 은 dashboard_cache 의 불변 복사본과 RDS manifest 이고 대시보드 앱이 그 DDL 을 적용한다.

## Status

Proposed — 허브 [ADR 0007](../../../docs/adr/0007-dashboard-snapshots-and-telemetry-lifetime-summary.md)(분석 snapshot 과 수집 생애주기 요약의 저장소 분리) 채택 시 Accepted. [ADR 0022](0022-대시보드-API-는-별도-앱이고-인증은-포트-뒤에서-기본-거부한다.md) §4 의 ClickHouse 캐시 계정 권한을 이 ADR 이 넓힌다(§3).

## Context

[ADR 0022](0022-대시보드-API-는-별도-앱이고-인증은-포트-뒤에서-기본-거부한다.md)는 대시보드 앱이 자기 캐시(`dashboard_cache`)에만 쓰고 분석 원본은 읽기만 한다고 정했다.
조회가 무엇을 읽는지는 정하지 않았다. 이 저장소의 사실은 다음과 같다.

- **분석 원본은 읽을 때마다 최신 선택이 필요하다.** `telemetry_events`·`telemetry_metric_points` 는 `ReplacingMergeTree(row_version)` 이고
  최신 행은 `FINAL` 뒤에만 보인다([ADR 0020](0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md) §7). 재처리가 같은 관측을
  언제든 새 `row_version` 으로 교체한다(§3). 그래서 한 화면의 endpoint 들이 각자 `FINAL` 을 돌리면, 그 사이에 들어온 교체 때문에 개요의 조직 합계와
  팀 목록의 합이 어긋나고, 목록의 다음 페이지가 앞 페이지와 겹치거나 빠질 수 있다.
- **사용량과 관측 일자의 근거가 다르다.** KPI 는 `signal='log'`·`event_type='model.response.usage'`·`usage_role='primary'`·`record_status='active'`·
  `mapping_status='mapped'` 인 최신 행에서만 나온다(ADR 0020 §7). 날짜마다 "관측이 있었는가"는 그보다 넓다 — 메트릭 point 와 매핑되지 않은
  generic 관측도 그 날 수집이 있었다는 근거다. 메트릭 point 는 사용량 행보다 훨씬 많다(export 주기마다 series 수만큼).
  두 근거를 서로 다른 시점에 읽으면 그 사이의 교체·삭제로 사용량과 관측 상태가 갈라진다.
- **ClickHouse 에는 조건부 갱신 트랜잭션이 없고, RDS 에는 있다.** 원본 선택을 공개할지 말지(완결됐는가, 그 사이 삭제 경계가 바뀌지 않았는가)는
  조건부 갱신으로만 경합 없이 판정된다.
- **삭제 경계는 이미 있다.** `telemetry_ops.tenant_retention_boundary(deleted_before, policy_epoch)` 가 tenant 별 경계와 정책 epoch 를 담는다
  ([ADR 0021](0021-수집-운영-기록은-ledger-와-telemetry-ops-스키마에-두고-enrollment-api-가-적용한다.md)). 쓰는 코드는 아직 없다.
- **DDL 적용 규약.** ClickHouse DDL 은 번호 붙은 멱등 파일이고 그 테이블을 쓰는 앱이 기동 때 전량 적용한다
  ([ADR 0015](0015-clickhouse-ddl-은-번호-붙은-멱등-파일이고-기동-시-적용한다.md)). RDS 는 Flyway 다
  ([ADR 0004](0004-Flyway-마이그레이션과-varchar-CHECK-스키마-관리.md)) — `enrollment` 는 그 쓰기 소유 앱인 `:apps:enrollment-api` 가 적용하고,
  `telemetry_ops` 는 쓰는 주체가 둘(ingest 의 요약, 보존 정리의 경계)이고 ingest 가 DDL 을 돌리지 않아야 해서 `:apps:enrollment-api` 가 대신 적용한다(ADR 0021 §3).
  RDS 스키마의 enum 은 native enum 이다([ADR 0009](0009-enrollment-스키마-native-enum-채택.md)).
- **허브 ADR 0007(Proposed)의 틀.** ClickHouse 논리 DB `dashboard_cache` 의 일반 MergeTree `snapshot_usage`, RDS `dashboard_cache.snapshots` manifest,
  하나의 원본 결과 스트림에서 사용량과 관측 일자를 함께 만들기, `building → ready | failed` 와 공개 CAS(같은 build 가 building · 미무효화 · 시작 때의 삭제
  정책 epoch 와 같음), `expires_at = ready_at + 10분`, 물리 정리는 TTL. SQL·마이그레이션 세부는 구현 명세로 넘겼다.

정하지 않으면 endpoint 마다 원본을 다시 읽거나, 관측 일자를 얻으려고 원본을 두 번 읽거나, 공개 판정을 ClickHouse 행의 존재로 대신하게 된다.

## Decision

### 1. ClickHouse `dashboard_cache` — 한 번 읽고 둘로 가른다

- 논리 DB 이름은 설정(`pulsemetry.dashboard.clickhouse.cache.database`)이고 DB 자체는 infra 가 만든다. DDL 파일은 테이블만 만든다.
  진실원은 `apps/dashboard-api/src/main/resources/clickhouse/dashboard-cache/V*.sql` 이고 ADR 0015 의 규약(모든 문장이 `IF NOT EXISTS`,
  배포된 파일은 고치지 않고 다음 번호로 더한다)을 그대로 진다.
- **`snapshot_intake`(`Null` 엔진)가 build 의 유일한 입구다.** build 는 `INSERT INTO snapshot_intake SELECT …` **한 문장**으로 두 분석 테이블을
  각각 `FINAL` 로 **한 번씩만** 읽는다(`UNION ALL` 두 갈래 — 테이블마다 한 번). 입구는 아무것도 저장하지 않고, 그 문장이 만든 블록을 구체화 뷰 둘이
  나눠 받는다.
  - `snapshot_intake_usage` → **`snapshot_usage`**: 사용량 대표 행(`is_usage`)만. 관측당 한 행이다.
  - `snapshot_intake_days` → **`snapshot_observed_days`**: 입구의 **모든** 행(사용량·generic·메트릭 point)을 `(observed_date, origin)` 별 개수로
    접는다. 관측 일자 집합은 이 테이블에서만 읽는다.
  같은 블록이 두 뷰로 가므로 사용량과 관측 일자는 같은 원본 결과에서 나온다. 관측 일자를 얻으려고 원본을 다시 읽지 않고, 메트릭 point 를 한 건씩
  복사하지도 않는다.
- **`snapshot_member_activity`** 는 기간 밖 기준 시각(asOf)까지의 구성원별 마지막 사용 시각이다. 관측 일자 집합이 아니라 별도의 고정 입력이다.
- 네 테이블은 모두 **일반 `MergeTree`** 다 — 복사본은 쓰인 뒤 바뀌지 않으므로 교체 엔진이 필요 없다. 정렬 키는
  `(tenant_id, snapshot_id, build_id, …)` 로 시작한다. 모든 조회는 ready manifest 의 `snapshot_id` **와** `build_id` 로 거른다 —
  행의 존재로 snapshot 의 유효성이나 만료를 판정하지 않는다.
- `snapshot_usage` 의 열은 사용량 계산에 필요한 것만 복사한다.

  | 열 | 뜻 |
  |---|---|
  | `tenant_id` · `snapshot_id` · `build_id` | 소유·식별 |
  | `observation_id` · `installation_id` · `source_time` | 관측 식별과 발생 시각(ADR 0020 §2) |
  | `in_current` · `in_previous` | 현재·비교 기간 소속. **겹친 기간의 관측도 한 번만 복사**하고 둘 다 참으로 단다 |
  | `product` · `surface` · `service_name` · `product_version` · `workload_kind` · `usage_scope` | 제품·producer 판(의미 미검증 행을 판별로 묶는 키 — ADR 0020 §7) |
  | `session_id` · `session_id_namespace` | 세션 distinct 재료 |
  | `member_id` · `team_id_as_of` | 구성원·이벤트 당시 대표 팀(ADR 0020 §5) |
  | `model` · `provider` · `model_id` · `model_resolution_version` | 원본 모델명(바꾸지 않는다)과 build 때 해석한 공급자·모델 ID·해석 규칙 판 |
  | `tokens_input` · `tokens_output` · `tokens_cache_read` · `tokens_cache_create` · `tokens_input_uncached` · `tokens_total_derived` | 토큰 성분(null 은 미보고 — 0 으로 채우지 않는다) |
  | `input_semantics` · `output_semantics` · `semantics_profile` | 성분 합산 가능 여부의 근거 |
  | `cost_reported_usd` · `reported_cost_basis` · `cost_estimated_usd` · `pricing_version` | 보고 비용과 추정 비용(`Decimal(38, 12)`), 가격 판 |
  | `quality_flags` | null 판정·진단 근거(`multi_team_membership` 등) |
  | `purge_after` | 물리 정리 시각(아래) |

- **물리 정리는 TTL 이다.** 네 테이블 모두 `PARTITION BY toDate(purge_after)`, `TTL purge_after`, `ttl_only_drop_parts = 1`.
  `purge_after` = build 시작 + build 제한 시간 + API 수명 10분 + 정리 유예다. 제한 시간과 유예는 기본값 없는 설정(`pulsemetry.dashboard.snapshot.build-timeout`·
  `pulsemetry.dashboard.snapshot.purge-grace`)이다. 그래서 공개 전에 복사한 행이 API 만료보다 먼저 지워지지 않는다. API 의 유효기간을 TTL 에 맞춰
  늘리지 않는다 — 만료는 manifest 가 정한다.

### 2. RDS `dashboard_cache` — manifest 와 참조 복제

- 진실원은 `apps/dashboard-api/src/main/resources/db/dashboard-cache/V*.sql` 이다. `enrollment` 와 다른 Flyway 인스턴스이고 이력은
  `dashboard_cache.flyway_schema_history` 다. `enrollment`·`telemetry_ops` 에 외래 키를 걸지 않는다 — 다른 소유자의 스키마이고, 캐시 행이 그 삭제를 막으면 안 된다.
- **`snapshots` 가 manifest 다.** snapshot 하나에 build 하나다 — 실패한 build 를 같은 snapshot 에 다시 채우지 않고, 재시도는 새 snapshot·새 build 다.

  | 범주 | 열 |
  |---|---|
  | 식별·소유 | `snapshot_id`(불투명, 무작위 16바이트의 base64url 22자) · `build_id`(UUID) · `tenant_id` · `requested_by`(요청 구성원) · `access_scope`(재사용이 허용된 권한 범위 — 합의 전에는 `organization` 뿐) |
  | 분석 범위 | `query_contract`(조회 계약 판) · `time_zone` · `compare_mode` · 현재 기간 `current_start_date`·`current_end_date` · 비교 기간 `previous_start_date`·`previous_end_date`(`none` 이면 null) |
  | 참조 | `as_of`(기간 밖 조회의 기준 시각) · `deleted_before`(적용한 삭제 경계, 없으면 null) · `policy_epoch`(build 시작 때의 삭제 정책 epoch, 경계 행이 없으면 0) · `model_resolution_version` · `pricing_versions`(복사한 행의 가격 판 집합 — 둘 이상이면 혼재) |
  | 수명 | `status`(`building`·`ready`·`failed`) · `created_at` · `build_deadline` · `ready_at` · `expires_at` · `failed_at`·`failure_reason` · `invalidated_at`·`invalidation_reason` |
  | 검증 | `usage_rows` · `observed_day_rows`(공개 때 확인한 행 수) |

  CHECK 가 불변식을 건다 — `ready` 는 `ready_at`·`expires_at`·두 행 수가 있고, `failed` 는 `failed_at` 이 있으며, 비교 기간은 `compare_mode` 가
  `none` 일 때만 없고, 기간은 역전되지 않는다. 행이 0개인 정상 snapshot 도 `ready` manifest 를 갖는다.
- **참조 데이터는 snapshot 에 묶인 복제 테이블이다.** `snapshot_teams`(팀 표시 — 이름·보관 여부, 사용 이력에 나오는 과거 팀 포함)와
  `snapshot_members`(로스터 — 무사용 구성원 포함, 계정·표시 이름·역할·상태·현재 팀·갱신 시각). 둘 다 `snapshots` 를 `ON DELETE CASCADE` 로 참조한다.
  역할·상태는 `enrollment` 의 값을 그대로 복제한다 — 화면 어휘로의 대응은 조회 때 한다(ADR 0022 §3). 계약·좌석처럼 아직 쓰지 않는 참조는
  그것을 처음 쓰는 endpoint 가 다음 번호의 마이그레이션으로 더한다.
- 상태·비교 방식·역할·구성원 상태는 이 스키마의 native enum 이다(ADR 0009 와 같은 이유).

### 3. 적용 주체와 계정

- **대시보드 앱이 기동 때 두 캐시 스키마를 적용한다** — ClickHouse 는 ADR 0015 규약의 멱등 파일 전량, RDS 는 위 Flyway 인스턴스. 각 캐시 계정으로 한다.
  적용에 실패하면 기동이 실패한다 — `:apps:telemetry-ingest` 는 아카이브를 위해 저장소 장애를 견디며 떠야 하지만(ADR 0016), 이 앱은 캐시 없이 할 수 있는
  일이 없다.
  - `telemetry_ops` 처럼 `:apps:enrollment-api` 가 적용하지 않는 것은 사정이 달라서다. `dashboard_cache` 의 쓰는 주체는 이 앱 하나이고, 그 쓰기 소유 앱이
    자기 DDL 을 적용하는 것은 `enrollment`(enrollment-api)·ClickHouse 분석 테이블(ingest)과 같은 모양이다. enrollment-api 가 적용하게 하면 인증 서비스가
    대시보드 SQL 을 싣고, 그 SQL 을 담을 라이브러리가 하나 더 생긴다.
  - 그래서 `dashboard_cache` 의 쓰기 소유는 이 앱이다(모듈 지도 2절). 두 번째 쓰는 주체(예: 별도 배포 단위의 정리 worker)가 생기면 그때
    `:libs:dashboard-persistence` 로 내린다(ADR 0022 §1).
- **ClickHouse 캐시 계정은 분석 테이블을 SELECT 한다.** build 가 서버 쪽 `INSERT … SELECT` 라 행이 앱을 지나지 않고, 그 문장을 실행하는 계정이 원본을 읽어야
  한다. 이 계정의 권한은 `dashboard_cache` 의 테이블·뷰 생성과 읽기·쓰기, 두 분석 테이블의 SELECT 다. 분석 테이블에는 여전히 쓰지 못한다 — ADR 0022 §4 의
  표를 이만큼 넓힌다. 원천 읽기 계정(`clickhouse.source`)은 그대로다.
- **RDS 캐시 계정은 `telemetry_ops.tenant_retention_boundary` 를 SELECT 한다.** 공개 CAS 가 현재 삭제 정책 epoch 를 같은 문장 안에서 비교해야 하기
  때문이다. 권한은 `dashboard_cache` 스키마의 생성·읽기·쓰기와 그 한 테이블의 SELECT 다.

### 4. 읽기와 공개의 규칙

- snapshot 을 읽는 모든 요청은 먼저 manifest 를 본다 — tenant 가 같고, `status = 'ready'` 이고, 무효화되지 않았고, `expires_at` 이 지나지 않았고,
  `policy_epoch` 가 **지금의** 삭제 정책 epoch 와 같을 때만 payload 를 읽는다. 하나라도 어긋나면 `409 snapshot_expired` 다. 삭제 경계가 바뀌면 이 비교만으로
  기존 snapshot 이 무효가 된다 — 경계를 바꾸는 쪽이 캐시에 쓸 필요가 없다.
- 공개는 RDS 의 조건부 갱신 한 문장이다 — 같은 build 가 아직 `building` 이고, 무효화되지 않았고, 마감(`build_deadline`) 전이고, 현재 epoch 가
  시작 epoch 와 같을 때만 `ready` 가 되고 `expires_at = ready_at + 10분` 이 적힌다. 하나라도 어긋나면 그 build 는 `failed` 다.
- **공개 전 검증.** 조회에 쓸 ClickHouse 연결로 build 의 행을 세어, 복사 문장에 서버가 보고한 쓴 행 수(`written_rows` — 입구와 모든 뷰 대상에 쓴 행의 합)와
  맞춘다. 입구의 모든 행은 관측 일자의 개수로 한 번씩 세이므로 `written_rows = Σ observations + 사용량 행 + 관측 일자 행` 이어야 한다. 어긋나면 공개하지
  않는다. 이 저장소가 쓰는 ClickHouse 는 단일 노드라 그 연결이 곧 조회 경로다.
- **한도.** tenant 별 동시 build 수(셈과 manifest 쓰기를 tenant advisory lock 아래 한 트랜잭션에서 한다 — 마감이 지난 building 은 세지 않는다), 복사 문장이
  원본에서 읽는 행·바이트(`max_rows_to_read`·`max_bytes_to_read`, `read_overflow_mode = throw`), 실행 시간(build 제한 시간). 모두 기본값 없는 설정이다.
  넘으면 build 는 `failed` 이고 부분 결과를 공개하지 않는다.
- **생성 중의 응답.** snapshot 은 그것을 처음 요구한 요청 안에서 동기로 만든다. 동시 한도·상한 초과·저장소 장애·공개 CAS 거부는 `503 unavailable` +
  `Retry-After` 다. 생성 중·실패 상태의 HTTP 표현은 프론트와 합의 전이며 이것이 합의 전 기본값이다.
- **재사용.** 요청이 snapshot ID 를 실으면 그 manifest 의 현재 기간·시간대가 요청과 같아야 하고, 비교를 쓰는 화면이면 비교 방식도 같아야 한다. 다르면 409 다 —
  날짜·필터가 바뀌면 새 조회다. cursor 는 그 snapshot ID 와 목록 범위(endpoint·필터·정렬)에 묶이고, 다르면 400 `invalid_cursor` 다.
- **무효화.** 권한·정책이 바뀌어 기존 snapshot 을 보여 줄 수 없을 때 tenant 의 snapshot 을 무효화하는 수단을 둔다(이벤트 원천이 없어 호출자가 부른다).
  무효화와 별개로 매 요청이 조직·행위 권한을 다시 검사하므로 회수된 권한은 곧바로 403 이다.
- **정리 작업.** 주기(기본값 없는 설정)마다 마감이 지난 `building` 을 `failed`(`abandoned`)로 바꾸고, 실패한 build 와 물리 정리 시각이 지난 build 의 캐시 행을
  지운 뒤 manifest 를 지운다. 자기 캐시 행만 지운다. 늦거나 빠져도 응답은 달라지지 않는다 — 유효기간은 manifest 가 정한다.
- 실패했거나 결과가 불확실한 build 의 행은 어떤 조회도 읽지 않는다(`build_id` 가 ready manifest 에 없다). 그 행은 TTL 또는 정리 작업이 지운다.
- **팀 귀속은 이벤트 당시다.** 팀별 합계는 사용 행의 `source_time` 당시 대표 팀(`team_id_as_of` — ADR 0020 §5)으로 묶는다. 현재 팀 매핑으로
  다시 귀속하지 않는다. 응답의 `attributionBasis = "event_time"` 이 이것이다.

## Alternatives

### A. 앱이 원본 결과를 받아 두 저장소로 나눠 쓴다
- 장점: 캐시 계정이 원본을 읽지 않는다. 관측 일자를 앱 안에서 접는다.
- 단점: 사용량 행 전부가 앱을 두 번(받고 다시 보내고) 지난다. 기간이 길면 네트워크·메모리가 build 시간을 지배한다.
- 탈락 이유: 같은 결과를 서버 안에서 만들 수 있는데 행을 앱으로 끌어낼 이유가 없다.

### B. 모든 관측을 staging 테이블에 고정한 뒤 두 번 파생한다
- 장점: 파생이 불변 입력에서 나온다는 것이 테이블로 보인다.
- 단점: 메트릭 point 와 generic 관측을 한 건씩 복사한다 — 그 수가 사용량 행보다 훨씬 많다.
- 탈락 이유: 관측 일자에는 날짜별 개수만 필요하다.

### C. 한 INSERT 안에서 원본을 두 번 참조한다(사용량 갈래와 일자 갈래)
- 장점: 뷰가 필요 없다.
- 단점: 같은 문장이어도 테이블 참조마다 따로 읽는다. "한 번 읽는다"를 구조로 보장하지 못한다.
- 탈락 이유: 입구 하나에서 가르면 보장이 구조에서 나온다.

### D. manifest 를 ClickHouse 에 둔다
- 장점: 저장소가 하나다.
- 단점: 조건부 갱신이 없어 공개와 무효화·epoch 변경의 경합을 판정하지 못한다. 앱 재시작·다른 인스턴스가 같은 상태를 읽어야 한다는 요구는 둘 다 만족한다.
- 탈락 이유: 공개 CAS 를 할 수 없다.

### E. RDS 캐시 DDL 을 `:apps:enrollment-api` 가 적용한다(ADR 0021 방식)
- 장점: RDS DDL 을 돌리는 프로세스가 하나로 모인다.
- 단점: 쓰는 주체가 하나인 스키마를 위해 인증 서비스가 대시보드 SQL 과 라이브러리를 싣는다. 캐시 스키마를 바꿀 때마다 두 앱을 함께 배포한다.
- 탈락 이유: ADR 0021 이 그 방식을 택한 이유(ingest 의 DDL 금지, 쓰는 주체 둘)가 여기에는 없다.

### F. snapshot 을 교체 엔진(ReplacingMergeTree)에 둔다
- 장점: 같은 관측을 두 번 넣어도 하나로 보인다.
- 단점: 불변 복사본에 교체가 필요 없고, 교체 엔진은 다시 `FINAL` 을 요구한다. 재시도는 새 build 라 중복 삽입 자체가 없다.
- 탈락 이유: 쓸모없는 비용이다.

## Consequences/Tradeoffs

### Positive
- 같은 snapshot 을 읽는 모든 endpoint 가 같은 payload·참조 판을 쓴다. 원본의 교체·재처리가 그 사이에 끼어들지 못한다.
- 사용량과 관측 일자가 한 번의 원본 읽기에서 나온다는 것이 DDL 구조로 보장된다. 메트릭 point 를 복사하지 않는다.
- 공개·무효화·만료 판정이 RDS 한 곳에 있어 앱 재시작과 여러 인스턴스가 같은 상태를 본다.
- 삭제 경계가 바뀌면 캐시에 쓰지 않고도 기존 snapshot 이 무효가 된다.

### Negative
- ClickHouse 캐시 계정이 분석 테이블을 읽는다 — 쓰기 권한은 여전히 없지만 ADR 0022 §4 의 처음 표보다 넓다.
- 구체화 뷰 둘이 한 INSERT 안에서 차례로 쓴다. 두 번째가 실패하면 첫 번째의 행이 남는다.
  - 완화: 그 build 는 공개되지 않고(행 수 확인·공개 CAS), 남은 행은 TTL 이 지운다.
- snapshot 마다 기간 전체의 사용량 행을 복사한다. 긴 기간·큰 조직에서 build 시간과 임시 저장량이 커진다.
  - 완화: 복사 행·바이트·시간 상한과 tenant 별 동시 build 수를 설정으로 두고, 넘으면 부분 결과를 공개하지 않는다.
- 이 앱이 기동 때 두 저장소에 DDL 을 돌리므로 캐시 계정에 생성 권한이 필요하다.
- 참조 복제 테이블의 역할·상태 enum 이 `enrollment` 의 값을 복제한다. `enrollment` 가 값을 더하면 이 스키마도 같은 값을 더해야 복사가 실패하지 않는다.

## Follow-up

- 허브 ADR 0007 이 채택되면 Accepted 로 올린다.
- ClickHouse 에 replica 가 생기면 공개 전 검증을 조회할 replica 에서 하거나 검증한 replica 로 라우팅한다(지금은 단일 노드).
- 계약·좌석 참조, 가격 프로파일·모델 별칭 표가 생기면 그 판을 manifest 에 고정한다(지금은 해석 규칙 판과 가격 판 집합만).
- `dataThrough` 의 의미와 개요가 snapshot 을 고르는 방식은 프론트와 합의한다(지금 개요는 snapshot ID 를 노출하지 않는다).
- 상한 값은 대표 규모의 측정으로 정한다.
