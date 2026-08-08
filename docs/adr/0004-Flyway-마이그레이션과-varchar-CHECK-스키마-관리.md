# 0004. 스키마의 진실원을 Flyway 마이그레이션에 두고 enum 을 varchar + CHECK 로 표현한다.

## Status
Accepted

## Context
스키마 설계는 `rdb-schema/dbdiagram.dbml` 한 파일에 그려져 있고, 이 파일은 팀이 함께 보는 설계도다.
그러나 dbdiagram 은 다이어그램 도구이지 마이그레이션 도구가 아니다.
운영 DB 를 어떤 순서로 어떻게 바꿀지, 이미 적용된 변경이 무엇인지는 dbml 이 답해 주지 않는다.

한편 dbml 은 상태를 `Enum` 타입으로 표현한다(`tenant_status`, `platform_type` 등 10종).
PostgreSQL 로 그대로 옮기면 `CREATE TYPE ... AS ENUM` 이 된다.

애플리케이션 쪽에서는 Hibernate 의 `ddl-auto` 로 스키마를 만들 수도 있다.
엔티티만 고치면 테이블이 따라오므로 초기 개발 속도가 빠르다.

## Decision
- **스키마의 진실원은 Flyway SQL 마이그레이션이다.** `dbdiagram.dbml` 은 설계 참조용 읽기 전용 문서로 두고,
  DDL 을 dbml 에 맞추지도, dbml 을 DDL 에 맞춰 고치지도 않는다.
  의도적으로 달라진 지점은 별도 문서에 기록한다.
- Hibernate 의 `ddl-auto` 는 `validate` 로 고정한다. `create`·`update` 는 쓰지 않는다.
  Hibernate 는 스키마를 **만들지 않고**, 엔티티와 실제 스키마가 어긋나면 기동을 막는 역할만 한다.
- dbml 의 enum 컬럼은 **`varchar(n)` + `CHECK (col IN (...))`** 으로 이식한다.
  JPA 는 `@Enumerated(EnumType.STRING)` 으로 매핑한다.
- 제약과 인덱스에는 이름을 붙인다(`pk_` `uq_` `fk_` `ck_` `ix_` `ux_`).
- 마이그레이션에 시드 데이터를 넣지 않는다. 로컬 개발용 데이터는 `local` 프로파일 시더가 넣는다.
- 통합 테스트는 Testcontainers 로 띄운 실제 PostgreSQL 에서만 돈다.

## Alternatives
### A. Hibernate `ddl-auto: update` 로 스키마를 관리한다
- 장점: 엔티티만 고치면 스키마가 따라오므로 초기 개발이 빠르고, 마이그레이션 파일을 쓸 필요가 없다.
- 단점: 컬럼 삭제·타입 변경·데이터 이관을 하지 못하고 조용히 건너뛴다. 적용 이력이 남지 않아 운영 DB 가 어떤 상태인지 코드만 보고 알 수 없다. 환경마다 스키마가 미묘하게 갈라진다.
- 탈락 이유: 운영 DB 에 되돌릴 수 없는 변경을 자동으로 가하는 방식이다. 무엇이 언제 적용됐는지 추적할 수 없다는 점만으로도 운영 후보가 아니다.

### B. Liquibase 를 쓴다
- 장점: XML·YAML 로 DB 중립적인 변경을 기술할 수 있고, 롤백 스크립트 생성과 조건부 실행 같은 기능이 풍부하다.
- 단점: 추상화 계층이 하나 더 생겨 실제 실행되는 SQL 이 눈에 보이지 않는다. 우리가 쓰는 jsonb·부분 유니크 인덱스 같은 PostgreSQL 전용 기능은 결국 raw SQL 로 적게 된다.
- 탈락 이유: DB 중립성이 필요 없다. PostgreSQL 을 계속 쓸 것이고, 그렇다면 SQL 을 그대로 쓰는 Flyway 가 읽기 쉽고 리뷰하기 쉽다.

### C. PostgreSQL native enum(`CREATE TYPE ... AS ENUM`)을 쓴다
- 장점: 타입 수준에서 값이 강제되고 저장 공간이 작다. dbml 의 표현과 1:1 로 대응해 설계도와 DDL 이 똑같아 보인다.
- 단점: 값 추가는 되지만 **삭제·순서 변경이 안 되고**, 값 변경에는 타입 재생성과 컬럼 캐스팅이 따른다. JDBC 드라이버와 Hibernate 사이의 타입 매핑에도 별도 설정이 필요하다.
- 탈락 이유: 상태 값은 제품이 자라면서 가장 자주 바뀌는 부분이다. 값 하나 고치는 데 타입 재생성이 필요한 구조를 초기에 박아 두면 나중에 마이그레이션 비용이 계속 발생한다. `varchar + CHECK` 는 제약 하나만 갈아 끼우면 된다.

### D. 테스트를 H2 등 임베디드 DB 로 돌린다
- 장점: 컨테이너가 없어도 되고 테스트가 빠르며 CI 설정이 단순하다.
- 단점: jsonb, 부분 유니크 인덱스(`WHERE is_active`), 스키마(schema) 분리, `CHECK` 동작이 PostgreSQL 과 다르다. 우리 스키마의 핵심 제약이 전부 검증 대상에서 빠진다.
- 탈락 이유: "tenant 당 활성 manifest 는 하나" 같은 불변식이 부분 유니크 인덱스로 표현되는데, H2 는 그것을 검증해 주지 못한다. 통과하는 테스트가 실제 동작을 보장하지 못하면 테스트의 의미가 없다.

## Consequences/Tradeoffs
### Positive
- 운영 DB 의 변경 이력이 `flyway_schema_history` 와 SQL 파일로 남아 리뷰와 추적이 가능하다.
- 실행되는 SQL 이 파일에 그대로 있어 리뷰어가 DDL 을 눈으로 확인할 수 있다.
- 엔티티와 스키마가 어긋나면 `validate` 가 기동 시점에 잡는다. 마이그레이션을 빠뜨린 채 배포되지 않는다.
- 상태 값을 추가·삭제할 때 `CHECK` 제약 하나만 바꾸면 된다.
- PostgreSQL 전용 기능(jsonb, 부분 유니크 인덱스)을 마음껏 쓰고, 테스트가 그것을 실제로 검증한다.

### Negative
- 스키마를 바꿀 때 마이그레이션 파일과 엔티티를 **둘 다** 고쳐야 한다.
- `varchar + CHECK` 는 native enum 보다 저장 공간을 더 쓰고, 값 목록이 DDL·엔티티 두 곳에 존재한다.
  - 상수 이름이 곧 DB 값이므로 대소문자가 어긋나면 INSERT 가 실패한다. 이 위험은 통합 테스트로 막는다.
- 설계도(dbml)와 DDL 이 두 벌로 존재하므로 의도적 차이를 계속 기록해야 한다.
- 테스트에 Docker 가 반드시 필요하다. Docker 가 없는 환경에서는 빌드가 실패한다.
  - 실패시키는 편이 낫다고 판단했다. 조용히 다른 DB 로 넘어가면 검증의 의미가 사라진다.

## Follow-up
- `enrollment.manifests` 에는 dbml 에 없는 `UNIQUE (tenant_id) WHERE is_active` 부분 유니크 인덱스를 추가했다.
  enroll 이 "tenant 의 활성 manifest" 를 단수로 가정하므로 DB 가 그것을 보장하게 한다.
- `teams`·`team_memberships`·`contracts*` 테이블은 DDL 로는 이식했지만 이 API 가 쓰지 않으므로 엔티티를 만들지 않았다.
  대시보드 서버가 생기면 그때 엔티티를 추가한다.
- 마이그레이션이 늘어나면 `V1` 을 baseline 으로 접는 시점을 검토한다.
