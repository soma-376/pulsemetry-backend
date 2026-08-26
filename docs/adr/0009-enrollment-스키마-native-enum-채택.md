# 0009. enrollment 스키마의 enum 을 PostgreSQL native enum 으로 표현한다.

## Status
Accepted (ADR 0004 의 "varchar + CHECK 이식" 결정을 대체한다. 진실원 = Flyway 등 나머지 결정은 유지)

## Context
E2E 검증(soma-376/E2E-FLOW-VERIFICATION.md B3)에서 이 서버와 텔레메트리 파이프라인이
**같은 enrollment DB 를 보지 않는 문제**가 확인됐다. 공유 RDB 는 infra 가 띄우는
RDS `controlplane` 으로 정해져 있고, 파이프라인의 auth-proxy(`DATABASE_URL`)와
post-processor(`ENRICHMENT_PG_DSN`)는 이미 그 DB 를 가리킨다. 그런데 그 안의
enrollment 스키마는 아무도 부트스트랩하지 않으며(infra AGENTS.md 5장 (H), ADR-0023
Follow-up), dev 에서는 파이프라인 저장소의 `sql/rds/schema.sql` 을 psql 로 수동
삽입하는 우회가 안내되어 있다. 그 DDL 은 상태값을 **PostgreSQL native enum 10종**으로
표현한다.

이 서버의 Flyway V1 은 ADR 0004 에 따라 같은 상태값을 varchar + CHECK 로 표현했다.
값의 집합은 동일하지만 물리 타입이 달라서:

- 수동 부트스트랩된 스키마 위에서 Hibernate `ddl-auto: validate` 가 기동을 막고,
  `@Enumerated(STRING)` 의 varchar 파라미터 INSERT 도 42804 로 깨진다.
- 즉 두 DDL 이 병존하는 한, 이 서버는 공유 DB 의 기존 스키마 위에서 동작할 수 없고,
  DROP 후 재생성 같은 파괴적 절차 없이는 스키마 소유를 넘겨받을 수도 없다.

파이프라인 소비자(auth-proxy·enricher)는 상태값을 텍스트로만 비교하므로 어느 물리
타입이든 동작한다. 물리 타입에 민감한 쪽은 이 서버뿐이다.

## Decision
- **dbml 의 enum 을 PostgreSQL native enum 으로 이식한다.** V1 이 파이프라인
  `sql/rds/schema.sql` 과 같은 10종(`tenant_status`, `team_status`, `member_role`,
  `member_status`, `installation_status`, `platform_type`, `ai_vendor`,
  `contract_type`, `contract_status`, `token_type`)을 `enrollment` 스키마 안에 만들고,
  해당 컬럼을 enum 타입으로 선언한다. CHECK 제약은 제거한다.
- JPA 매핑은 `@Enumerated(EnumType.STRING)` + `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`
  + `@Column(columnDefinition = "<enum 타입명>")` 으로 한다.
- uuid PK 에 `DEFAULT gen_random_uuid()` 를 둔다 — 수동 INSERT 픽스처와의 호환이며,
  애플리케이션은 여전히 식별자를 직접 생성한다.
- `spring.flyway.baseline-on-migrate: true` 를 켠다. 수동 부트스트랩된(비어 있지 않은)
  스키마는 **DROP 없이** baseline 으로 얹고(V1 스킵), 빈 DB 는 V1 부터 적용한다.
- 파이프라인 DDL 에 없는 백엔드 필수 불변식은 **V2 이후 신규 마이그레이션**으로
  분리한다 — baseline 된 DB 에도 적용되게 하기 위해서다. manifests 부분 유니크
  인덱스가 V2, telemetry_tokens 활성 토큰 부분 유니크 인덱스가 V3 다.
- **이후 DDL 의 권위는 이 저장소의 Flyway 다.** 파이프라인의 `sql/rds/schema.sql` 은
  그쪽 단독 개발용 픽스처로 병존하며, 드리프트가 생기면 Flyway 가 기준이다.

## Alternatives
### A. varchar + CHECK 를 유지하고 수동 스키마를 DROP 후 Flyway 로 재생성한다
- 장점: ADR 0004 를 그대로 유지한다. JPA 매핑이 단순하다.
- 단점: 공유 DB 의 기존 스키마·데이터를 파괴하는 1회성 운영 절차가 필요하다.
- 탈락 이유: 파괴적 동작을 두지 않기로 결정했다. 수동 부트스트랩이 이미 이뤄진 DB 를
  안전하게 넘겨받을 수 없다.

### B. 파이프라인 저장소의 DDL·DSN 을 백엔드 DB 에 맞춰 고친다
- 장점: 백엔드 코드가 그대로다.
- 단점: 수정 책임이 파이프라인·infra 저장소로 번진다.
- 탈락 이유: 이번 변경의 책임 범위는 백엔드 저장소뿐이다.

## Consequences/Tradeoffs
### Positive
- 공유 RDS 의 기존(수동 부트스트랩) 스키마 위에 DROP 없이 안착하고, 빈 DB 에서는
  Flyway 가 처음부터 생성한다 — 어느 경로든 이후 마이그레이션 권위가 Flyway 로 수렴한다.
- 파이프라인 소비자와 물리 스키마가 일치해 B3(배선 단절)의 스키마 축이 닫힌다.

### Negative
- enum 라벨 추가는 `ALTER TYPE ... ADD VALUE` 마이그레이션이 필요하다(트랜잭션 제약
  있음). varchar + CHECK 보다 값 변경 비용이 크다.
- V1 재작성으로 Flyway 체크섬이 바뀌어, 기존 로컬 DB 는 `docker compose down -v` 로
  리셋해야 한다.

## Follow-up
- 공유 RDS 접속 레시피와 부트스트랩 절차는 `docs/enrollment-server-spec.md` §9 에 있다.
- 수용 기준: enroll 로 발급한 토큰으로 ALB `/v1/traces` POST → 2xx (B1·B2·B3 동시 입증).
