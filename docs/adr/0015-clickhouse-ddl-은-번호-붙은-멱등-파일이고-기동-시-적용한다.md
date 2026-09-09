# 0015. ClickHouse DDL 은 번호 붙은 멱등 파일이고 기동 시 전량 적용한다.

## Status

Accepted

## Context

허브 [ADR 0004](../../../docs/adr/0004-telemetry-pipeline-repo-merge.md)가 ClickHouse 스키마
소유권을 이 저장소로 옮기면서 적용 메커니즘은 열어 두고 Follow-up 으로 넘겼다.

> ClickHouse DDL의 적용 메커니즘을 backend에서 정한다. 현재 Flyway 버전으로는 적용할 수 없으므로
> 진실원과 적용 경로를 같은 결정으로 묶는다. `flyway-database-clickhouse`가 12.x 이상으로 재개되면
> 이 항목을 재검토한다.

PROJ-99 도 이 항목을 열어 둔 채 닫았고, [모듈 지도](../module-map.md) 2절이 *"`enriched_events`의
DDL 진실원과 적용 경로는 아직 정해지지 않았다"* 로 그것을 적어 두었다. PROJ-104 가 적재 모듈을
만드는 시점이 그 결정의 자리다.

### Flyway 는 이 스키마를 다루지 못한다

[ADR 0004](0004-Flyway-마이그레이션과-varchar-CHECK-스키마-관리.md)가 "스키마의 진실원은 Flyway
마이그레이션"으로 못박았지만 그 진술의 사정거리는 PostgreSQL 이다. 허브 ADR 0004 의 Negative 가
사정을 적어 두었다 — 구현 모듈 `flyway-database-clickhouse` 는 10.24.0 에서 배포가 멈췄고, 이
저장소가 Spring Boot 4.1.0 으로 해석하는 `flyway-core` 12.x 계열에는 그 모듈이 없다.

### 현행 방식은 버전이 없다

이식 원본은 `CREATE TABLE IF NOT EXISTS` 한 문장을 담은 파일을 애플리케이션이 기동 시 적용한다
(`sink_clickhouse.ensure_schema`). 원장 테이블도, 체크섬도, 번호도 없다. 그래서 **컬럼을 바꿔도
이미 테이블이 있는 환경에서는 아무 일도 일어나지 않는다** — `IF NOT EXISTS` 가 조용히 통과시킨다.
`dev` 는 볼륨이 없어 `down` 후 `up` 이 곧 리셋이라 이 결함이 드러나지 않았다.

### 이미 기각된 대안이 하나 있다

이미지의 `initdb` 마운트는 이식 원본 운영에서 기각됐다 — init 임시 서버가 본 서버의 포트 바인드와
레이스해 리스너 없이 뜨는 플레이크가 있었다.

### 조율 문제가 따라온다

배포에서 앱 인스턴스가 여럿이면 기동이 겹친다. PostgreSQL 의 Flyway 는 advisory lock 으로 그것을
직렬화하지만 **ClickHouse 에는 그런 잠금이 없다.** 적용기를 두려면 잠금을 대신할 무엇이 필요하다.

### 정하지 않으면

적재 모듈이 DDL 파일을 들고도 그것을 적용할 자리가 없어, 컬럼 변경이 배포마다 사람의 기억에 달린다.
그리고 ADR 0008 규칙 1 의 판정법("`CREATE TABLE` 이 어느 모듈 아래에 있는가")이 가리킬 파일이
없으므로 telemetry 도메인의 쓰기 소유도 근거를 잃는다.

## Decision

- **진실원은 `libs/telemetry-persistence/src/main/resources/clickhouse/` 아래의 `V*.sql` 이다.**
  그 위치가 곧 telemetry 도메인의 쓰기 소유 근거다 — ADR 0008 규칙 1 의 판정법을 그대로 적용한
  결과이며, [ADR 0010](0010-파이프라인-단계를-모듈-경계로-나눈다.md)이 이미 같은 말을 했다.
- **모든 문장은 멱등이어야 한다** — `CREATE TABLE IF NOT EXISTS` ·
  `ALTER TABLE … ADD COLUMN IF NOT EXISTS` 형태다. 이 규약이 이 결정의 핵심이다.
- **적용은 기동 시 전량이다.** `ClickHouseSchemaMigrator` 가 `V1` 부터 순서대로 전부 실행한다.
  이미 적용된 문장은 멱등이라 아무 일도 하지 않는다.
- **원장 테이블도 체크섬도 분산 락도 두지 않는다.** 멱등 규약이 그것을 대신한다 — 두 인스턴스가
  동시에 기동해도 결과가 같으므로 조율할 것이 없다.
- **적용 순서는 코드의 목록이 정한다.** `ClickHouseSchemaMigrator.MIGRATIONS` 에 파일 이름을 적고,
  클래스패스를 훑지 않는다. 순서가 파일시스템이나 jar 항목 순서에 좌우되면 안 되고, 무엇이
  적용되는지가 리뷰에 보여야 한다.
- **`V1` 은 이식 원본의 DDL 과 문장이 같다.** 동작 동일성이 이식의 판정 기준이므로 첫 버전에서
  스키마를 바꾸지 않는다. `V1` 을 고치지 않는다 — 컬럼 변경은 언제나 새 파일이다.
- **파괴적 변경은 이 경로로 하지 않는다.** 컬럼 삭제·타입 변경·`ORDER BY` 변경은 멱등으로 적을 수
  없다. 필요해지면 운영 런북을 별도로 쓴다.
- **언제 부를지와 실패했을 때 어떻게 할지는 조립 앱이 정한다**(ADR 0011). 적용기는 평범한
  클래스이고 빈이 아니다.

## Alternatives

### A. 현행 그대로 — 단일 파일, 버전 없음

- 장점: 이식 분량이 가장 적고 동작이 현행과 완전히 같다.
- 단점: 컬럼을 바꿔도 기존 환경에서 조용히 무시되는 결함이 그대로 남는다. 그 결함은 dev 가 볼륨
  없이 도는 동안에는 드러나지도 않는다.
- 탈락 이유: 이 티켓이 정하기로 한 것이 바로 그 적용 경로다. 결함을 고정하는 것은 **값**에 대한
  원칙이지 운영 메커니즘에 대한 원칙이 아니다. 게다가 번호를 붙여도 `V1` 이 현행과 같은 문장이라
  적재되는 값은 하나도 달라지지 않는다.

### B. 원장 테이블 + 체크섬 — Flyway 를 손으로 흉내 낸다

- 장점: 무엇이 언제 적용됐는지가 남고, 손으로 바꾼 스키마를 체크섬이 잡는다.
- 단점: ClickHouse 에 advisory lock 이 없어 **동시 기동을 조율할 수단을 따로 만들어야 한다.**
  `ReplacingMergeTree` 원장에 두 인스턴스가 같은 버전을 쓰는 경쟁을 막으려면 결국 외부 잠금이
  필요하고, 그러면 잠금을 어디 둘지가 새 결정이 된다.
- 탈락 이유: 멱등 규약이 그 조율 문제를 애초에 없앤다. 체크섬은 좋지만 지금 사는 값보다 비싸다.

### C. one-off ECS task 등 앱 밖에서 적용한다

- 장점: 기동 경로와 스키마 변경이 분리되고, 파괴적 변경도 같은 자리에서 다룰 수 있다.
- 단점: infra 에는 RDS Flyway 를 어디서 실행할지도 아직 정해져 있지 않다. 여기에 ClickHouse 까지
  얹으면 크로스레포 결정이 된다.
- 탈락 이유: 이 티켓 안에서 닫히지 않는다. 허브 ADR 0004 Follow-up 은 결정 주체를 backend 로
  지정했고, backend 혼자 닫을 수 있는 형태가 A·B·D 다.

### D. Flyway 의 ClickHouse 지원을 쓴다

- 장점: PostgreSQL 쪽과 도구가 같아진다.
- 단점: `flyway-database-clickhouse` 가 10.24.0 에서 멈춰 이 저장소가 해석하는 12.x 계열에 없다.
  Flyway 버전을 내리면 PostgreSQL 마이그레이션이 함께 끌려 내려간다.
- 탈락 이유: 지금은 선택지가 아니다. 허브 ADR 0004 Follow-up 이 재개 조건을 이미 걸어 두었다.

## Consequences/Tradeoffs

### Positive

- **컬럼 추가 경로가 생겼다.** `V2` 를 더하면 기존 환경에서도 실제로 적용된다 — 현행의 진짜 결함이
  닫힌다.
- 잠금도 원장도 없어 움직이는 부품이 적다. 동시 기동이 저절로 안전하다.
- DDL 파일의 위치가 쓰기 소유의 근거가 되어 ADR 0008 규칙 1 의 판정이 리뷰에서 그대로 통한다.
- 적재되는 값은 하나도 달라지지 않는다. `V1` 이 이식 원본과 같은 문장이다.

### Negative

- **체크섬도 드리프트 감지도 없다.** 누군가 `clickhouse-client` 로 스키마를 바꿔 두면 아무도
  모른다. PostgreSQL 쪽 Flyway 가 주는 보증이 여기에는 없다.
- **파괴적 변경을 다룰 수 없다.** 컬럼 삭제·타입 변경·`ORDER BY` 변경에는 런북이 필요하고,
  그 런북은 아직 없다. 특히 `ORDER BY` 는 멱등 키의 정의라 바꾸려면 테이블을 다시 만들어야 한다.
- **멱등 규약을 강제할 수단이 리뷰뿐이다.** `IF NOT EXISTS` 를 빠뜨린 문장은 첫 기동에서는 성공하고
  **두 번째 기동에서** 죽는다. 완화책은 적용기를 두 번 돌리는 테스트다 —
  `EnrichedEventsSinkTest.applyingTheSchemaTwiceIsSafe` 가 그 자리다.
- 문장 분해가 세미콜론 기준이라 **문자열 리터럴 안의 세미콜론을 견디지 못한다.** DDL 만 담는
  파일이라 지금은 닿지 않는다. `--` 로 시작하는 주석 줄은 분해 전에 벗기므로 헤더에 세미콜론이
  있거나 꼬리 주석이 와도 안전하다 — `ClickHouseSchemaMigratorTest` 가 그것을 고정한다.
- 기동마다 DDL 이 전부 다시 실행된다. 파일이 늘면 기동이 그만큼 느려진다 — 문장 수가 수십을 넘으면
  다시 본다.

## Follow-up

- `flyway-database-clickhouse` 가 12.x 이상으로 재개되면 이 결정을 재검토한다. 허브 ADR 0004
  Follow-up 이 건 조건이며, 그때 체크섬과 드리프트 감지를 함께 얻을 수 있는지 본다.
- **파괴적 변경 런북**은 실제로 필요해질 때 쓴다. `ORDER BY` 변경이 가장 먼저 올 후보다 —
  현행 정렬 키가 `event_id` 하나뿐이라 tenant·시각으로 자르는 대시보드 질의가 전부 풀스캔이다.
- **완료** — 적용기를 부르는 자리와 실패 정책은 [ADR 0016](0016-조립-앱은-인증-체인과-단계-호출을-배선하고-스키마-적용-실패를-견딘다.md)이
  정했다. 기동 시 다섯 번 재시도, 실패해도 뜨고, 적재 직전에 한 스레드만 다시 시도한다.
- [모듈 지도](../module-map.md) 2절의 "아직 정해지지 않았다" 서술을 이 결정으로 교체한다.

## Acceptance Criteria

- DDL 이 적재 모듈 아래에 있다.

  ```bash
  ls libs/telemetry-persistence/src/main/resources/clickhouse/
  # V1__enriched_events.sql
  ```

- 저장소의 어떤 Flyway 마이그레이션도 ClickHouse 를 다루지 않는다.

  ```bash
  grep -rn "enriched_events" libs/enrollment-persistence/src/main/resources/db/migration/
  # (결과 없음)
  ```

- 스키마 적용이 멱등이다 — `EnrichedEventsSinkTest.applyingTheSchemaTwiceIsSafe` 가 green 이다.

## References

- 허브 [ADR 0004](../../../docs/adr/0004-telemetry-pipeline-repo-merge.md) Follow-up — 이 결정을 backend 에 넘긴 자리
- [ADR 0004](0004-Flyway-마이그레이션과-varchar-CHECK-스키마-관리.md) — PostgreSQL 스키마의 진실원
- [ADR 0008](0008-모듈-경계와-네임스페이스-규칙-확정.md) 규칙 1 — 쓰기 소유의 판정법
- [ADR 0010](0010-파이프라인-단계를-모듈-경계로-나눈다.md) — telemetry 의 쓰기 소유는 적재 모듈이다
- [ADR 0011](0011-라이브러리-모듈은-spring-조립을-앱에-위임한다.md) — 적용기를 언제 부를지는 앱이 정한다
- [모듈 지도](../module-map.md) 2절
