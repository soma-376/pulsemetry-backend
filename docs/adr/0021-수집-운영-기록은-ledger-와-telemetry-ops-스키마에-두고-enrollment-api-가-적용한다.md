# 0021. 수집 운영 기록은 ClickHouse ledger 와 RDS telemetry_ops 스키마에 두고 그 RDS DDL 은 enrollment-api 가 적용한다.

## Status

Proposed — 허브 [ADR 0007](../../../docs/adr/0007-dashboard-snapshots-and-telemetry-lifetime-summary.md)(분석 snapshot 과 수집 생애주기 요약의 저장소 분리) 채택 시 Accepted. 채택 전에는 이 ADR 이 만드는 테이블에 쓰는 수신 기록 코드를 운영 경로에 연결하지 않는다.

조직 생성 시 빈 요약을 같은 트랜잭션에서 초기화하는 규칙은 [ADR 0034](0034-조직-생성과-빈-수집-요약을-원자적으로-초기화한다.md)가 보완한다. 실제 수신 기록의 활성화 조건은 유지한다.

## Context

[ADR 0020](0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md) 의 분석 테이블은
**관측**을 담는다. 대시보드가 필요로 하는 것 중 관측으로는 답할 수 없는 것이 있다.

- **언제 무엇을 받았는가.** 설치별 마지막 수신 시각, 그 날 어느 설치가 무언가를 보냈는가.
  분석 행은 `source_time` 이 없으면 저장되지 않고(ADR 0020 §2), 같은 관측의 재전송은 한 행으로 교체된다.
  수신 사실 자체는 남지 않는다. `installations.last_seen_at` 컬럼은 있지만 그것을 쓰는 코드가 없고,
  한 값이라 과거 구간을 복원할 수 없다.
- **이 조직은 한 번이라도 수집한 적이 있는가.** 분석 행과 수신 기록은 보존 정책으로 지워진다. 남은 행의
  최솟값·최댓값으로 최초 수신을 판단하면, 보존 정리 뒤 오래된 조직이 "수집한 적 없음"으로 되돌아간다.
- **이 조직의 기록을 어디까지 지웠는가.** ADR 0020 §8 은 조직별 보존 삭제를 영속 경계로 집행한다고 정했지만
  그 경계를 둘 자리가 없다.

허브 ADR 0007(Proposed)은 이 기록들을 원본 분석 저장소와 분리하는 안이다 — 수신 ledger 는 수집 단계가,
tenant 생애 요약은 telemetry ingest 가 쓰고, RDS 에 `enrollment` 와 분리된 `telemetry_ops` 스키마를 두며,
**DDL 진실원은 backend 의 버전 관리 마이그레이션이고 ingest 프로세스 기동이 DDL 을 실행하는 경로를 만들지
않는다**(권한 분리가 취지다). 구체 SQL·모듈·적용 주체는 구현 결정으로 넘겼다.

이 저장소의 현재 사실은 다음과 같다.

- RDS 마이그레이션은 Flyway 가 한다([ADR 0004](0004-Flyway-마이그레이션과-varchar-CHECK-스키마-관리.md)).
  SQL 은 `:libs:enrollment-persistence` 의 `db/migration`(V1–V4)에 있고, 기동 시 그것을 실행하는
  프로세스는 `:apps:enrollment-api` 하나다(`spring.flyway.schemas: enrollment`, 이력은
  `enrollment.flyway_schema_history`). `:apps:telemetry-ingest` 는 Flyway 를 끈다
  ([ADR 0016](0016-조립-앱은-인증-체인과-단계-호출을-배선하고-스키마-적용-실패를-견딘다.md)).
- Spring Boot 의 Flyway 자동설정은 `Flyway` 타입 빈이 이미 있으면 물러난다. 두 번째 스키마를 위해
  `Flyway` 빈을 하나 더 선언하면 `enrollment` 마이그레이션이 조용히 멈춘다.
- ClickHouse DDL 은 `:libs:telemetry-persistence` 의 번호 붙은 멱등 파일이고 기동 시 전량 적용된다
  ([ADR 0015](0015-clickhouse-ddl-은-번호-붙은-멱등-파일이고-기동-시-적용한다.md)). 멱등 규약 때문에 DDL 에
  운영 수치(TTL 일수 등)를 박으면 바꿀 때마다 새 파일이 필요하다.
- 쓰기 소유는 `CREATE TABLE` 이 있는 모듈이다([ADR 0008](0008-모듈-경계와-네임스페이스-규칙-확정.md) 규칙 1,
  [모듈 지도](../module-map.md) 2절). 아웃바운드 기술이 둘 이상이면 라이브러리를 기술별로 나눈다(모듈 지도 4절).
  `:libs:telemetry-persistence` 는 ClickHouse HTTP 로만 말한다.
- `:libs:` 는 Spring 스테레오타입을 두지 않고 Boot starter 를 끌지 않는다
  ([ADR 0011](0011-라이브러리-모듈은-spring-조립을-앱에-위임한다.md)).

정하지 않으면 수집 기록 writer가 쓸 테이블이 없고, 누가 RDS DDL 을 돌리는지가 배포마다 사람의 기억에 달린다.
ingest 에 Flyway 를 켜는 가장 쉬운 길은 허브 ADR 0007 이 막은 길이다.

## Decision

### 1. 수신 ledger — ClickHouse `telemetry_ingest_ledger`

- 진실원은 `libs/telemetry-persistence/src/main/resources/clickhouse/V3__telemetry_ingest_ledger.sql`
  이고 `ClickHouseSchemaMigrator.MIGRATIONS` 에 등록한다(ADR 0015). 쓰기 소유는 `:libs:telemetry-persistence` 다.
- 한 행은 **(receipt, signal, archive product)** 하나다. 인증·마스킹·아카이브를 통과한 push 의 수신 사실이며
  분석 테이블이 아니고 정규화 결과도 아니다 — `observation_id`·`row_version` 이 없다.

  | 컬럼 | 타입 | 의미 |
  |---|---|---|
  | `tenant_id` | `LowCardinality(String)` | 검증된 tenant |
  | `installation_id` | `String` | 검증된 installation |
  | `received_time` | `DateTime64(9, 'UTC')` | receipt 의 서버 수신 시각 |
  | `receipt_id` | `String` | 서버가 수신마다 만드는 receipt 식별자(UUID) |
  | `signal` | `LowCardinality(String)` | `log`/`span`/`metric` |
  | `product` | `LowCardinality(String)` | 아카이브 product 구간(모르는 서비스는 그 경로의 값) |
  | `source_time_min` · `source_time_max` | `Nullable(DateTime64(9, 'UTC'))` | 그 행에서 유효한 `source_time` 의 범위. 하나도 없으면 null |
  | `record_count` | `UInt32` | 받은 레코드 수(로그 레코드·스팬·metric point) |
  | `rejected_count` | `UInt32` | 분석 테이블에 넣지 않은 레코드 수 — `source_time` 을 얻지 못한 것과 스팬 허용 목록 밖으로 버린 것(ADR 0020 §2) |
  | `archive_ref` | `Nullable(String)` | archive receipt 의 객체 참조. 없으면 null |
  | `masking_version` | `String` | 그 push 에 적용한 마스킹 규칙 버전 |

- 엔진은 `ReplacingMergeTree`, `PARTITION BY toYYYYMM(received_time)`,
  `ORDER BY (tenant_id, installation_id, received_time, receipt_id, signal, product)` 다. 같은 receipt 의
  **저장 재시도**가 같은 행을 다시 써도 한 행으로 수렴한다. 서로 다른 receipt 는 키가 달라 합쳐지지 않는다.
- **HTTP 재전송은 새 receipt 다.** 같은 본문이 다시 와도 새 수신 사실로 남긴다. 본문 hash 로 수신을 합치지
  않는다. `record_count` 는 수신량이지 사용량·관측 수가 아니므로 합산해 그렇게 부르지 않는다.
- 인증·마스킹·아카이브를 통과한 push 는 **이후 정규화가 400 이어도 기록한다**(`source_time_min/max` null,
  `rejected_count` = 받은 수). 기록의 내구성을 확인하지 못하면 성공(200)을 돌려주지 않는다 — 일시 실패로
  503 이다(허브 ADR 0006 표 안에서). 아카이브 재처리는 새 push 가 아니므로 ledger 행을 만들지 않는다.
- ledger 가 있다는 것은 수신했다는 뜻이지 어댑터·보강·적재가 성공했다는 뜻이 아니다. 설치가 켜져 있었는데
  조용했는지는 답하지 못한다.
- **보존.** ledger 는 조직의 집계 보존 기간이 아니라 **공통 운영 보존 기간**을 따른다. DDL 에 TTL 을 두지 않는다
  — 기간은 운영 설정이고, 멱등 DDL 에 수치를 박으면 바꿀 때마다 새 파일이 필요하다. 집행은 운영 보존
  작업이 `received_time` 월 파티션 중 **전부 경계보다 오래된 것**을 `DROP PARTITION` 한다(ledger 는 모든
  tenant 가 같은 정책이므로 파티션 삭제가 허용된다). 경계는 필수 설정 `pulsemetry.telemetry.ops.ledger-retention`
  (기본값 없음)으로 정하고, 값이 없으면 그 작업은 시작하지 않는다. 운영 조회 창(최근 수신 설치 등)보다 짧게
  두지 않는다. 작업 자체는 보존 작업을 만드는 변경이 구현한다.

### 2. RDS `telemetry_ops` 스키마

`enrollment` 와 같은 RDS 인스턴스의 **별도 스키마**다. 계정·조직 설정을 담지 않는다.

- **`tenant_ingest_summary`** — tenant 당 한 행, 생애주기 요약.

  | 컬럼 | 타입 | 의미 |
  |---|---|---|
  | `tenant_id` | `uuid` PK | 검증된 tenant |
  | `first_received_at` | `timestamptz` NULL | 인증·마스킹을 통과한 최초 live push 의 서버 수신 시각. null 이면 이 요약이 기록한 수신이 없다 |
  | `first_observed_at` | `timestamptz` NULL | 받은 관측 중 유효한 `source_time` 의 최솟값(마이크로초로 내림). 시각을 얻지 못한 push 만 있으면 null. 늦게 온 과거 데이터로 앞당겨질 수 있다 |
  | `last_received_at` | `timestamptz` NULL | 마지막 live push 의 서버 수신 시각. 재처리 실행 시각이 아니다 |
  | `has_pre_ledger_history` | `boolean` NOT NULL, 기본 false | 이 기록이 생기기 전의 분석 이력이 있는 tenant 를 백필이 표시한 것. 시각을 지어내지 않는다 |
  | `updated_at` | `timestamptz` NOT NULL | 마지막 갱신 시각 |

  갱신은 NULL 을 고려한 원자적 MIN/MAX upsert 다 — 동시 요청·재시도가 최초 시각을 뒤로 옮기지 못한다.
  쓰기 경로는 출처를 구분한다: `live` 만 수신 시각과 `first_observed_at` 을 움직이고, 아카이브 재처리(`replay`)는
  아무 시각도 움직이지 않으며, 누락 요약 복구(`recovery`)만 원래 receipt 의 수신 시각을 쓴다. 분석·ledger 의
  보존 정리는 요약을 초기화하지 않는다.
- **`tenant_summary_backfill`** — 요약 도입 전 이력의 백필 완료 기록.

  | 컬럼 | 타입 | 의미 |
  |---|---|---|
  | `backfill` | `text` PK | 백필 이름(원천별 하나) |
  | `source` | `text` NOT NULL | 읽은 원천(예: 기존 분석 테이블 이름) |
  | `completed_at` | `timestamptz` NOT NULL | 완료 시각 |
  | `tenants_marked` | `integer` NOT NULL | `has_pre_ledger_history` 를 표시한 tenant 수 |

  행이 있으면 그 백필이 끝났다는 뜻이다. **요약 행이 없고 완료 기록도 없으면 "수집한 적 없음"으로 해석하지
  않는다** — 조회 계층은 판정을 거부한다. 요약 조회에 실패한 경우도 같다.
- **`tenant_retention_boundary`** — tenant 별 영속 삭제 경계(ADR 0020 §8).

  | 컬럼 | 타입 | 의미 |
  |---|---|---|
  | `tenant_id` | `uuid` PK | tenant |
  | `deleted_before` | `timestamptz` NOT NULL | 이 시각보다 이전의 `source_time` 을 가진 분석 행은 지워졌거나 지워질 대상이다 |
  | `policy_epoch` | `bigint` NOT NULL | 삭제 정책의 세대. 경계가 바뀔 때마다 오른다 |
  | `updated_at` | `timestamptz` NOT NULL | 마지막 갱신 시각 |

  경계는 단조롭게만 움직인다(`GREATEST`). 이 ADR 은 테이블만 만든다 — 경계를 쓰는 작업, 적재·조회의 집행,
  진행 중 쓰기와의 조정은 후속 결정이다.
- **`enrollment.tenants` 에 외래 키를 걸지 않는다.** (1) tenant 삭제는 분석·ledger·요약·아카이브의 각 소유자에게
  전파되는 별도 절차다 — FK 의 `RESTRICT` 는 그 절차 전에 `enrollment` 삭제를 막고, `CASCADE` 는 그 절차가
  통제해야 할 운영 기록을 암묵적으로 지운다. (2) FK 검사는 참조 행에 잠금을 잡는다 — ingest 의 매 요약 upsert 가
  계정 쓰기 경로의 `tenants` 행과 잠금을 다투게 된다. (3) 요약을 쓰는 tenant 는 이미 토큰 인증으로 검증된
  값이다. 고아 행의 정리는 tenant 삭제 절차의 몫이다.

### 3. RDS DDL 의 위치와 적용 주체

- **SQL 은 새 모듈 `:libs:telemetry-ops-persistence`**(패키지 `com.team376.pulsemetry.persistence.telemetryops`)의
  `src/main/resources/db/telemetry-ops/V*.sql` 에 둔다. `CREATE TABLE` 이 이 모듈에 있으므로 `telemetry_ops`
  테이블의 쓰기 소유도 이 모듈이다 — 요약 writer 와 경계 저장 코드가 여기에 온다.
  - `:libs:telemetry-persistence` 에 두지 않는 이유: 그 모듈은 ClickHouse HTTP 모듈이고, RDS 는 다른 아웃바운드
    기술이다(모듈 지도 4절). 또 마이그레이션을 돌리는 앱이 그 모듈을 의존하면 보강·어댑터·protobuf 가 딸려 온다.
  - `:libs:enrollment-persistence` 에 두지 않는 이유: 그 모듈은 `enrollment` 스키마의 쓰기 소유자다. 파이프라인이
    쓰는 테이블의 `CREATE TABLE` 을 거기 두면 규칙 1 에 따라 쓰기 소유가 `enrollment` 쪽으로 넘어간다.
  - 두 번째 소비자를 기다리지 않는 이유: 경계가 이미 정해져 있다 — 스키마와 계정 권한이 `enrollment` 와
    분리되고(허브 ADR 0007), 아웃바운드 기술이 ClickHouse 와 다르다. 소비자는 마이그레이션을 돌리는 앱,
    요약을 쓰는 ingest, 요약과 경계를 읽는 조회 계층이다.
- 이 모듈은 Spring 스테레오타입과 Boot starter 없이, 마이그레이션을 실행하는 평범한 클래스
  `TelemetryOpsSchemaMigrator(dataSource)` 를 내보낸다(ADR 0011). 그 클래스는 Flyway API 로 스키마
  `telemetry_ops`, 이력 테이블 `telemetry_ops.flyway_schema_history`, 위치 `classpath:db/telemetry-ops` 를
  고정해 실행한다. `baseline-on-migrate` 는 쓰지 않는다 — 이 스키마에는 수동으로 부트스트랩된 과거가 없다.
- **적용 주체는 `:apps:enrollment-api` 의 기동이다.** 이미 RDS 마이그레이션을 실행하는 유일한 프로세스이므로
  새 실행 주체를 배포에 더하지 않는다. 앱은 `TelemetryOpsSchemaMigrator` 를 빈으로 조립하고 기동 중에 한 번
  실행한다. **`Flyway` 타입 빈으로 선언하지 않는다** — Boot 의 `enrollment` 마이그레이션이 물러난다. 실행이
  실패하면 `enrollment` 마이그레이션과 같이 기동이 실패한다.
- `:apps:telemetry-ingest` 는 계속 Flyway 를 끄고, 이 마이그레이터를 조립하지 않는다. ingest 기동은 RDS DDL 을
  실행하지 않는다. 테이블이 아직 없을 때 ingest 가 어떻게 응답하는지는 writer 를 연결하는 변경이 정한다
  (일시 실패 503 표 안에서).
- `enrollment` 의 V1–V4 와 이력은 섞이지 않는다 — 스키마·이력 테이블·SQL 위치가 모두 다르다.

### 4. 쓰기 권한 경계

| 대상 | 쓰기 | 읽기 |
|---|---|---|
| ClickHouse `telemetry_ingest_ledger` | ingest(INSERT), 운영 보존 작업(파티션 삭제) | 조회 계층 |
| `telemetry_ops.tenant_ingest_summary` | ingest(INSERT·UPDATE), tenant 삭제 절차 | 조회 계층 |
| `telemetry_ops.tenant_summary_backfill` | 백필 실행(ingest 쪽 구성) | 조회 계층 |
| `telemetry_ops.tenant_retention_boundary` | 보존 작업 | ingest·재처리·조회 계층 |
| `telemetry_ops` DDL | 마이그레이션 계정(`:apps:enrollment-api`) | — |

- ingest 의 `enrollment` 접근은 읽기 전용 그대로다. `installations.last_seen_at` 을 요약 대용으로 쓰지 않는다.
- 계정·역할의 이름과 GRANT 는 배포 환경이 정한다. 마이그레이션에 역할 이름을 넣지 않는다.

## Alternatives

### A. ingest 가 기동 시 `telemetry_ops` 마이그레이션을 실행한다

- 장점: 테이블을 쓰는 프로세스가 스스로 스키마를 보장한다. 배포 순서 문제가 없다.
- 단점: ingest 계정에 DDL 권한이 필요하다. 수집 경로의 계정이 스키마를 바꿀 수 있게 된다.
- 탈락 이유: 허브 ADR 0007 이 명시적으로 막은 경로다.

### B. 전용 마이그레이션 실행 수단을 만든다(one-off 작업·별도 앱)

- 장점: 스키마 변경이 어떤 서비스의 기동과도 묶이지 않는다. 계정을 마이그레이션 전용으로 분리할 수 있다.
- 단점: 배포에 새 실행 단위가 생겨 infra 변경이 필요하다. 그러면 `enrollment` 마이그레이션도 같은 곳으로
  옮길지를 함께 정해야 하는데, 그것은 이 ADR 이 닫을 수 없는 크로스레포 결정이다(ADR 0015 가 ClickHouse
  에 대해 같은 이유로 기각했다).
- 탈락 이유: 이 저장소 안에서 닫히지 않는다. 옮기는 결정이 생기면 이 마이그레이터 클래스를 그대로 부른다.

### C. `enrollment` 마이그레이션에 위치를 하나 더한다(같은 Flyway, 같은 이력)

- 장점: 설정 한 줄이다.
- 단점: 두 스키마의 버전 번호와 이력이 한 줄로 섞인다. `enrollment` 의 V1 은 baseline 대상이라
  (`baseline-on-migrate`) 기존 DB 에서 번호 충돌과 건너뛰기가 생길 수 있다. 쓰기 소유 모듈도 흐려진다.
- 탈락 이유: 이력을 분리한다는 전제를 깬다.

### D. 요약과 경계를 ClickHouse 에 둔다

- 장점: 한 저장소에 모인다.
- 단점: ClickHouse 에는 행 단위의 원자적 MIN/MAX upsert 와 단조 경계 갱신에 맞는 트랜잭션이 없다. 동시
  요청이 최초 시각을 뒤로 옮기지 못하게 하려면 RDS 의 행 잠금이 필요하다.
- 탈락 이유: 요약의 핵심 보증을 지키지 못한다. ledger 처럼 추가만 하는 기록은 ClickHouse 가 맞다.

## Consequences/Tradeoffs

### Positive

- 분석 보존 정리 뒤에도 조직의 수집 이력과 최초·마지막 수신이 남는다.
- 수신 사실과 관측이 분리된다 — `source_time` 이 없는 push 도 수신 이력에 남는다.
- ingest 계정은 DDL 권한 없이 두 운영 테이블에만 쓴다. `enrollment` 쓰기 경계는 그대로다.
- 운영 보존 기간이 DDL 이 아니라 설정이라 바꿀 때 마이그레이션이 필요 없다.

### Negative

- **인증 서비스가 무관한 스키마의 마이그레이션을 실행한다.** `:apps:enrollment-api` 가
  `:libs:telemetry-ops-persistence` 를 의존하고, 그 계정이 `telemetry_ops` 의 소유자가 된다. 다른 계정의 GRANT 는
  배포 환경이 해야 한다.
- **배포 순서가 생긴다.** `telemetry_ops` 테이블은 `:apps:enrollment-api` 가 새 버전으로 뜬 뒤에야 있다. 그 전에
  ingest writer 가 연결되면 쓰기가 실패한다 — 연결하는 변경이 일시 실패로 다뤄야 한다.
- **ledger 파티션 삭제는 월 단위다.** 보존 기간이 N 일이면 최대 약 한 달 더 남는다. 더 짧은 보존이 필요하면
  파티션 키를 바꿔야 하고 그것은 멱등 DDL 로 할 수 없다.
- **`first_observed_at` 은 마이크로초로 내린다.** RDS `timestamptz` 가 나노초를 담지 못한다. 분석 행의
  `source_time` 과 나노초 단위로 비교하지 않는다.
- 모듈이 하나 늘고, 그 모듈의 첫 커밋에는 마이그레이션만 있다. writer 는 뒤따르는 변경에서 온다.
- FK 가 없어 삭제된 tenant 의 운영 행이 남을 수 있다. tenant 삭제 절차가 생기기 전까지는 사람이 정리한다.

## Follow-up

- 허브 ADR 0007 채택 → 이 ADR 을 Accepted 로 바꾼다.
- ledger 와 요약의 writer, 백필 실행 방식은 수집 운영 기록 writer 를 만드는 변경이 구현한다.
- `tenant_retention_boundary` 를 쓰는 보존 작업과 경계의 집행(적재 직전·조회), 진행 중 쓰기와의 조정은 후속 ADR.
- 운영 보존 작업(ledger 파티션 삭제)과 `pulsemetry.telemetry.ops.ledger-retention` 의 배포 값.
- 마이그레이션 실행을 전용 수단으로 옮기는 결정이 생기면 `TelemetryOpsSchemaMigrator` 를 그대로 옮긴다.
- tenant 삭제 절차 — 요약·경계·ledger 의 정리.

## Acceptance Criteria

- `telemetry_ingest_ledger` 가 두 번 적용해도 성공하고 위 컬럼을 갖는다.
- 빈 PostgreSQL 에서 `:apps:enrollment-api` 가 뜨면 `telemetry_ops` 의 세 테이블과
  `telemetry_ops.flyway_schema_history` 가 생기고, `enrollment.flyway_schema_history` 에는 `telemetry_ops` 의
  버전이 없다. 마이그레이터를 한 번 더 실행해도 아무것도 적용하지 않는다.
- `:apps:telemetry-ingest` 의 설정에서 Flyway 가 여전히 꺼져 있고 이 마이그레이터를 조립하지 않는다.

## References

- 허브 [ADR 0007](../../../docs/adr/0007-dashboard-snapshots-and-telemetry-lifetime-summary.md) §5·§6
- [ADR 0004](0004-Flyway-마이그레이션과-varchar-CHECK-스키마-관리.md) · [ADR 0008](0008-모듈-경계와-네임스페이스-규칙-확정.md) · [ADR 0011](0011-라이브러리-모듈은-spring-조립을-앱에-위임한다.md) · [ADR 0015](0015-clickhouse-ddl-은-번호-붙은-멱등-파일이고-기동-시-적용한다.md) · [ADR 0016](0016-조립-앱은-인증-체인과-단계-호출을-배선하고-스키마-적용-실패를-견딘다.md) · [ADR 0020](0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md)
- [모듈 지도](../module-map.md) 2절·4절·6절
