# 0024. 조직별 보존 삭제 경계는 RDS 가 진실원이고 분석 INSERT 는 ClickHouse fence 를 서버에서 다시 검사한다.

## Status

Proposed — 허브 [ADR 0007](../../../docs/adr/0007-dashboard-snapshots-and-telemetry-lifetime-summary.md)(분석 snapshot 과 수집 생애주기 요약의 저장소 분리) 채택 시 Accepted. [ADR 0020](0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md) §8 과 [ADR 0021](0021-수집-운영-기록은-ledger-와-telemetry-ops-스키마에-두고-enrollment-api-가-적용한다.md) 이 후속으로 미룬 "삭제를 실행하는 작업·경계 저장·진행 중인 쓰기와의 조정"을 이 ADR 이 정한다. 두 ADR 의 결정은 유효하다.
§1 의 "보존 기간 수치를 이 저장소가 정하지 않는다"와 §5 의 "조직 설정 변경을 이 명령으로 잇는 제품 흐름은 범위 밖"은 [ADR 0047](0047-집계-보존-단축은-보존-정리-요청으로-남기고-보존-작업의-요청-모드가-실행한다.md)이 부분 개정한다(조직이 고른 보존 기간을 요청으로 남기고 이 작업의 요청 모드가 실행한다). 삭제의 순서와 주체는 그대로다.

## Context

- **삭제 원칙은 이미 있다.** ADR 0020 §8 — 조직별 보존 삭제는 두 분석 테이블에 `tenant_id = {t} AND source_time < {경계}` 로 하고, 조건에
  `row_version`·`record_status`·`normalizer_rev` 를 넣지 않는다. 경계는 tenant 별로 영속하고 단조롭게만 움직이며 적재·재처리·조회가 모두 같은 경계를
  집행한다. 경계를 읽지 못하면 과거 행을 허용하는 쪽으로 실패하지 않는다. 월 파티션(`toYYYYMM(source_time)`)은 여러 tenant 가 공유하므로 한 조직의
  삭제에 `DROP PARTITION` 을 쓰지 않는다.
- **경계를 둘 자리도 있다.** RDS `telemetry_ops.tenant_retention_boundary(tenant_id, deleted_before, policy_epoch, updated_at)` 이고(ADR 0021 §2),
  쓰기는 보존 작업, 읽기는 ingest·재처리·조회 계층이다(§4). 이 테이블에 쓰는 코드는 아직 없다.
- **snapshot 쪽 집행은 이미 있다.** [ADR 0023](0023-대시보드-snapshot-은-dashboard-cache-의-불변-복사본과-manifest-이고-대시보드-앱이-그-DDL-을-적용한다.md)
  — build 는 시작 때 경계와 epoch 를 읽어 `source_time >= deleted_before` 인 원본만 복사하고 manifest 에 epoch 를 적는다. 공개 CAS 와 매 조회가 지금의
  epoch 와 비교하므로 경계가 바뀌면 기존 snapshot 은 공개되지 못하거나 409 가 된다. 현재 상태 조회(설정의 공급자별 사용)도 원본을 읽을 때 같은 경계를 건다.
- **분석 테이블에 쓰는 코드는 한 벌이다.** `TelemetryEventsSink`·`TelemetryMetricPointsSink` 가 두 테이블의 유일한 쓰기 주체이고(ADR 0020 §1),
  push 하나가 테이블마다 HTTP INSERT 하나다(`IngestPipeline`). 아카이브 재처리 경로는 아직 없다.
- **검사와 INSERT 사이의 경쟁은 sink 직전 검사로 닫히지 않는다.** ingest 가 RDS 에서 경계를 읽은 뒤 ClickHouse 에 INSERT 하는 사이에 보존 작업이 경계를
  올리고 DELETE 를 끝내면, 늦게 끝난 INSERT 가 지운 범위의 행을 다시 남긴다. 두 저장소를 묶는 트랜잭션은 없다. 클라이언트 쪽 timeout 은 서버에서
  INSERT 가 끝났다는 근거가 아니다 — `ClickHouseHttpClient` 의 timeout 은 응답 헤더 도착까지만 재고, 연결을 끊은 뒤에도 서버가 받은 본문을 적용하지
  않았다고 말할 근거가 클라이언트에는 없다.
- **ClickHouse 24.8(`clickhouse-server:24.8-alpine`, 단일 서버)에서 측정한 사실.** 이 ADR 의 테스트(`RetentionFenceEvidenceTest`)가 재현한다.
  1. `INSERT INTO t (…) SELECT … FROM input('…') WHERE source_time >= (다른 테이블을 읽는 스칼라 서브쿼리) FORMAT JSONEachRow` 는 그 서브쿼리를
     **INSERT 안에서** 평가하고 조건을 통과한 행만 쓴다. 응답의 `X-ClickHouse-Summary.written_rows` 는 통과한 행 수다.
  2. HTTP INSERT 는 쿼리 텍스트를 파싱한 뒤에야 `system.processes` 에 등록되고, 서버는 파싱 전에 본문을 `max_query_size` 바이트(또는 끝)까지 먼저
     읽는다. 등록되기 전의 INSERT 는 다른 테이블을 아직 읽지 않았다.
  3. 등록된 INSERT 는 스칼라 서브쿼리를 평가하는 동안에도 `system.processes` 에 보인다 — **등록이 평가보다 먼저다.**
  4. 스칼라 서브쿼리는 쿼리 시작 때 **한 번** 평가된다. 같은 본문에서 나중에 도착한 행도 그 값으로 판정된다 — 그 사이 읽은 테이블이 바뀌어도 그렇다.
- **ClickHouse 는 단일 노드다**(ADR 0023 Follow-up 이 같은 전제를 적었다). 분석 INSERT 를 받는 서버가 하나다.
- **Dashboard API 는 원본 삭제 권한을 갖지 않는다**(ADR 0022 §4 · 허브 ADR 0007 §6 — 분석 원본의 삭제 책임은 정책을 검증하는 보존 정리 worker 이고
  Dashboard API 는 작업 요청·상태 조회만 한다). ingest 계정은 DDL 권한 없이 분석 테이블 INSERT 와 두 운영 테이블 쓰기만 한다(ADR 0021 §4).

결정하지 않으면 보존 작업을 만들 수 없고, 만들더라도 진행 중인 INSERT 와의 경쟁 때문에 "삭제했다"는 기록이 사실과 다를 수 있다.

## Decision

### 1. 경계와 epoch — RDS 가 진실원이고 MAX 로만 움직인다

- 진실원은 RDS `telemetry_ops.tenant_retention_boundary` 다. **행이 없으면 경계가 없고 epoch 는 0 이다.**
- 경계는 `TenantRetentionBoundaryStore.advance(tenant, 후보)` 하나로만 움직인다(`:libs:telemetry-ops-persistence` — DDL 이 있는 모듈, ADR 0021 §3).
  문장 하나의 upsert 이고, 후보가 지금 경계보다 **늦을 때만** `deleted_before = 후보`, `policy_epoch = policy_epoch + 1` 로 바꾼다. 첫 행은 epoch 1 이다.
  후보가 같거나 이르면 아무것도 바꾸지 않고 epoch 도 그대로다 — 보존 기간 연장·작업 재실행·재기동이 경계를 되돌리지 않고, 기존 snapshot 을 괜히
  무효화하지도 않는다. 경계를 낮추는 연산은 두지 않는다. 행의 제거는 tenant 삭제 절차의 몫이다.
- 경계 정밀도는 컬럼과 같은 마이크로초다. **마이크로초 아래 자리가 있는 후보는 거부한다** — 내리면 정책보다 덜 지우고, 올리면 더 지운다.
- 후보는 보존 작업이 계산한다 — asOf 가 속한 KST(`Asia/Seoul`) 날짜에서 N 개월 전의 같은 날(그 날이 없는 달이면 말일)의 KST 00:00 을 UTC 로 바꾼 값이다.
  대시보드 v1 이 조회 기간을 해석하는 시간대와 같다. N 과 asOf 는 작업의 입력이다 — 보존 기간 수치·기본값을 이 저장소가 정하지 않는다.

### 2. 쓰기 fence — ClickHouse `telemetry_retention_fence`

- ClickHouse 에 `telemetry_retention_fence(tenant_id, deleted_before DateTime64(6, 'UTC'), policy_epoch UInt64, fenced_at)` 를 둔다.
  `ReplacingMergeTree(policy_epoch) ORDER BY tenant_id`, 파티션·TTL 없음. DDL 은 `:libs:telemetry-persistence` 의 다음 번호 멱등 파일이다(ADR 0015) —
  그래서 쓰기 소유도 그 모듈이고(ADR 0008 규칙 1), 쓰는 코드는 `RetentionFence` 다. 조립하는 것은 보존 작업뿐이다. ingest 는 이 테이블을 읽기만 한다.
- **분석 INSERT 는 서버에서 fence 를 다시 검사한다.** 두 sink 의 INSERT 는
  `WHERE source_time >= ifNull((SELECT maxOrNull(deleted_before) FROM telemetry_retention_fence WHERE tenant_id = {tenant}), 1900-01-01)` 를 갖는다.
  fence 도 MAX 로만 움직이므로 `max` 가 현재 값이고 `FINAL` 이 필요 없다. 행이 없으면 제한이 없다(DateTime64 의 하한 — 1970 년 이전 `source_time` 도 남는다).
- **분석 INSERT 는 동기 INSERT 다**(`async_insert = 0` 을 문장에 고정). 비동기 버퍼는 INSERT 를 process list 밖으로 빼서 §4 의 확인을 무너뜨린다.
- fence 는 RDS 경계의 사본이다. 보존 작업만, RDS 에 커밋한 값을 **커밋 뒤에** 쓴다. 그래서 fence 가 RDS 보다 앞서는 일은 없고, 뒤처지는 경우(작업이 RDS 를
  올린 뒤 fence 를 쓰기 전에 멈춤)는 §4 의 순서가 흡수한다 — 작업은 fence 를 쓰고 확인하기 전에는 DELETE 하지 않는다.
- INSERT 의 `query_id` 는 `analysis-insert:{tenant}:{epoch}:{무작위}` 다. `epoch` 는 쓰는 쪽이 이 push 직전에 RDS 에서 읽은 값이다. 진단·작업 기록용이고,
  §4 의 확인은 이 태그에 기대지 않는다.

### 3. 집행 지점 — 읽지 못하면 쓰지도 공개하지도 않는다

| 지점 | 집행 | 경계를 읽지 못하면 |
|---|---|---|
| ingest — sink 직전 | push 마다 RDS 경계를 읽고(캐시 없음), `source_time < deleted_before` 인 관측을 INSERT 에 싣지 않는다. INSERT 는 §2 의 fence 조건을 서버에서 다시 건다 | 503 — 아무것도 쓰지 않고 수집 운영 기록도 남기지 않는다(일시 실패 규칙, ADR 0021 §1) |
| 재처리 | 같은 sink 를 쓴다. sink 는 경계 인자 없이는 쓰지 않는다 — 재처리 경로가 생기면 배치마다 경계를 새로 읽어 넘긴다 | 재처리 실패 |
| snapshot build | ADR 0023 그대로 — 시작 때 경계·epoch 를 읽어 원본을 거르고, 공개 CAS 가 epoch 를 비교한다 | build 실패(503) |
| 대시보드 조회 | snapshot payload 는 manifest epoch 가 지금 epoch 와 같을 때만 읽는다(ADR 0023 §4). 현재 상태 조회는 원본에 경계를 건다 | 503 |

- ingest 는 경계를 **캐시하지 않는다** — push 마다 PK 조회 하나다. 캐시하면 무효화 규칙과 지연 상한이 필요하고, 그 지연 동안 ledger 의 거부 수가 틀린다.
- sink 는 받은 경계로 행을 **한 번 더 거른다** — 경계를 넘긴 호출자가 거르기를 잊어도 경계 이전 행은 INSERT 에 실리지 않는다. 경계의 tenant 와 다른 행이 섞이면
  호출자의 결함이라 요청을 보내지 않고 실패한다(503).
- 경계 조회 실패는 `TelemetryOpsUnavailableException`(일시, 503)이거나 분류되지 않은 예외(503)다. `IngestPipeline` 의 예외 → 상태 표에서 행이 옮겨 가지 않는다.

### 4. 삭제 순서와 fence 확인

보존 작업은 tenant 하나에 대해 다음을 **이 순서로** 한다. 어느 단계든 실패하면 완료를 기록하지 않고 멈추며, 같은 입력으로 다시 실행하면 이어서 끝난다.

1. **경계 발효와 snapshot 무효화.** `advance(tenant, 후보)`. 커밋되는 순간부터 ingest 의 sink 직전 검사와 새 snapshot build 가 새 경계를 쓰고, 기존
   snapshot 은 epoch 비교로 더는 공개되지도 읽히지도 않는다(ADR 0023 §4) — **이 비교가 무효화다.** 보존 작업은 `dashboard_cache` 에 쓰지 않는다.
   그 snapshot 의 물리 행은 대시보드 앱의 snapshot 정리 작업이 무효화·만료된 snapshot 과 같은 규칙(생성 뒤 유효기간과 유예가 지나면)으로 지운다.
2. **fence 기록.** RDS 에 커밋된 `(deleted_before, policy_epoch)` 를 `telemetry_retention_fence` 에 쓴다(동기 INSERT). 이미 있으면 같은 값이라 무해하다.
3. **drain.** fence INSERT 가 반환된 **뒤에** `system.processes` 에서 `query_kind = 'Insert'` 인 쿼리의 `query_id` 집합 S 를 읽고, S 의 어느 것도 목록에 남지
   않을 때까지 기다린다. 근거는 서버의 process list 다 — Context 의 측정 2·3·4 에 따라, 그 순간 목록에 없는 INSERT 는 이미 끝났거나(그 파트는 DELETE 가
   덮는다) 나중에 등록되어 새 fence 를 읽는다. S 는 테이블·태그를 가리지 않는 모든 INSERT 다 — 태그를 빠뜨린 쓰기가 있어도 놓치지 않는다.
   **timeout·취소 신호는 근거가 아니다.** 작업의 대기 상한 안에 S 가 비지 않으면 완료하지 않고 멈춘다. 경계와 fence 는 그대로 남아 계속 집행된다.
4. **DELETE.** 두 분석 테이블에 `DELETE FROM … WHERE tenant_id = {t} AND source_time < {deleted_before}` 를 동기로 실행한다. 다른 조건을 넣지 않는다(ADR 0020 §8).
5. **검증.** 두 테이블에서 같은 조건의 행 수가 0 인지 확인한다. 0 이 아니면 3 부터 다시 한다.
6. **기록.** 논리 삭제 완료를 적는다 — 경계·epoch·관측 고유 수와 물리 revision 행 수(DELETE 전에 센다)·완료 시각. **물리 제거 완료는 적지 않는다** —
   lightweight delete 의 디스크 회수는 뒤의 merge 이고, 이 작업에는 그것을 판정할 근거가 없다.

수신 ledger 와 tenant 생애 요약은 이 작업의 대상이 아니다.

### 5. 보존 작업의 위치와 트리거

- **별도 실행 단위 `:apps:retention-worker` 다.** 서버가 아니라 tenant 하나를 처리하고 종료 코드로 끝나는 일회성 프로세스다. 계정은 셋으로 좁다 —
  RDS `telemetry_ops.tenant_retention_boundary` 와 작업 기록 테이블의 쓰기, ClickHouse 두 분석 테이블의 DELETE·SELECT 와 fence 의 INSERT·`system.processes` 읽기.
  - ingest 의 내부 작업으로 두지 않는다 — ingest 계정에 분석 테이블 DELETE 와 경계 쓰기를 더해야 하고, ingest 인스턴스 수만큼 작업이 겹친다.
  - `:apps:enrollment-api` 에 두지 않는다 — ClickHouse 에 닿지 않는 앱에 원본 삭제 권한을 새로 준다.
  - Dashboard API 에 두지 않는다(Context).
- **트리거는 내부 명령뿐이다** — 배포 환경의 일회성 실행에 tenant·보존 개월 수·asOf 를 인자로 준다. HTTP 진입점을 두지 않는다. 조직 설정 변경을 이 명령으로 잇는
  제품 흐름(설정 변경 API·작업 ID)은 이 ADR 의 범위 밖이다.
- 작업 기록은 `telemetry_ops` 의 새 테이블이다(다음 번호 Flyway 파일, 적용 주체는 ADR 0021 §3 그대로).

### 6. ledger 와 요약 — 경계 이전 관측은 거부로 센다

- sink 직전 검사로 뺀 관측은 수신 ledger 의 `rejected_count` 에 더하고, ledger 의 `source_time` 범위에서 뺀다 — 그 두 값의 정의가 "분석 테이블에 넣지 않은
  수"와 "분석 테이블로 간 관측의 범위"이기 때문이다(ADR 0021 §1). tenant 요약의 `first_observed_at` 은 ledger 항목에서 오므로 경계 이전의 지연 도착으로
  과거로 넓어지지 않는다.
- 수신 사실은 그대로 기록한다 — 모든 관측이 경계 이전이어도 push 는 성공(200)이고 ledger 행이 남는다.

### 7. 되돌리지 않는다

- 보존 기간 연장은 지운 기록을 복원하지 않는다. 아카이브 재처리도 §3 의 sink 를 지나므로 경계를 우회하지 못한다. 예외 복원은 경계 변경을 포함한 별도의
  명시적 절차이고 v1 의 자동 경로에 두지 않는다.

## Alternatives

### A. sink 직전의 RDS 검사만 둔다
- 장점: 저장소가 하나이고 ClickHouse 쪽 변경이 없다.
- 단점: 검사와 INSERT 사이의 경쟁이 남는다(Context). 늦게 끝난 INSERT 를 막을 방법이 시간 대기뿐이다.
- 탈락 이유: "구 경계로 검사한 쓰기가 끝났다"는 근거를 얻을 수 없다.

### B. INSERT 동안 RDS 경계 행에 공유 잠금을 쥔다(`FOR SHARE`)
- 장점: 경계 갱신이 진행 중인 쓰기를 기다린다.
- 단점: 잠금 해제는 ClickHouse 에서 INSERT 가 끝났다는 뜻이 아니다 — writer 가 죽으면 잠금은 풀리지만 서버가 받은 본문은 계속 적용될 수 있다. ClickHouse
  왕복 동안 RDS 커넥션을 쥐어 풀이 마른다(`IngestPipeline` 이 `@Transactional` 을 금지하는 이유와 같다).
- 탈락 이유: 근거가 클라이언트 쪽 상태다.

### C. writer 등록·heartbeat·lease 테이블
- 장점: 진행 중인 writer 를 명시적으로 센다.
- 단점: lease 만료는 시간이고, 만료가 INSERT 종료를 뜻하지 않는다. 등록과 INSERT 사이에 같은 경쟁이 다시 생긴다.
- 탈락 이유: B 와 같다.

### D. DELETE 뒤 일정 시간을 기다렸다 다시 지운다
- 장점: 구현이 가장 작다.
- 단점: 기다린 시간보다 오래 걸린 INSERT 가 남는다. 완료를 선언할 근거가 시간뿐이다.
- 탈락 이유: 허브 ADR 0007 §6 이 금지한 "timeout 만으로 종료를 가정"이다.

### E. 경계를 ClickHouse 에만 둔다
- 장점: INSERT 의 서버 검사와 진실원이 같다.
- 단점: 원자적 MAX upsert 와 epoch 증가, snapshot 공개 CAS 가 RDS 의 조건부 갱신에 기대고 있다(ADR 0021 대안 D · ADR 0023). 두 저장소에 걸친 CAS 가 된다.
- 탈락 이유: 진실원을 옮기면 ADR 0023 의 공개 규칙이 성립하지 않는다.

### F. fence 를 스칼라 서브쿼리 대신 JOIN 으로 건다
- 장점: 여러 tenant 가 섞인 배치에도 쓸 수 있다.
- 단점: push 하나는 tenant 하나다. INSERT 의 컬럼 목록과 이름 해석이 복잡해지고, 오른쪽 테이블을 읽는 시점을 따로 측정해야 한다.
- 탈락 이유: 필요 없는 일반성이다.

## Consequences/Tradeoffs

### Positive
- 삭제 완료의 근거가 서버의 process list 와 삭제 뒤 검증이다. 시간·timeout 에 기대지 않는다.
- 삭제가 끝나기 전에도 경계는 발효 즉시 쓰기·공개·조회에서 집행된다.
- 경계 이전 관측의 거부가 ledger 에 정확히 남는다(경합 창 밖에서).
- 재처리 경로가 생겨도 sink 가 경계 인자를 요구하므로 우회할 수 없다.

### Negative
- 모든 분석 INSERT 가 fence 테이블을 한 번 읽는다. 행은 tenant 당 경계 이동 수만큼이라 작다.
- 경계가 두 곳에 있다. 뒤처짐은 §4 의 순서가 흡수하지만, 누군가 fence 를 손으로 고치면 서버 검사가 틀린다 — fence 는 보존 작업만 쓴다.
- drain 은 모든 INSERT 를 기다린다. 긴 INSERT 가 있으면 작업이 늦게 끝나거나 대기 상한에 걸려 다시 실행해야 한다.
- 측정 2·3·4 는 ClickHouse 의 구현 동작이다. 버전을 올리면 `RetentionFenceEvidenceTest` 가 다시 통과해야 한다.
- 복제·분산 ClickHouse 에서는 성립하지 않는다 — process list 가 서버마다 따로이고 fence 의 가시성이 복제 지연을 탄다.
- fence 이동과 겹친 push 에서 서버 검사가 뺀 행은 ledger 에 거부로 세지 않는다 — ledger 는 INSERT 전의 판정이다. 그 행은 어차피 경계 이전이라 DELETE 대상이다.
- fence 테이블이 없는 채로 INSERT 가 가면 `UNKNOWN_TABLE`(404) → 영구 오류다. 다른 분석 테이블과 같은 위험이고 `ClickHouseSchema.ensureApplied` 가 막는다.

## Follow-up

- **완료** — 보존 작업 `:apps:retention-worker`·작업 기록 테이블(`telemetry_ops.retention_operations`)·drain·검증의 구현.
- 조직 설정 변경을 보존 작업으로 잇는 제품 흐름과 작업 ID·상태 조회 — 프론트 계약과 합의한다.
- 물리 제거 SLA.
- ClickHouse 복제·분산 — 모든 replica 의 process list 와 fence 가시성을 기준으로 §4 를 다시 정한다.
- tenant 삭제 절차 — 경계·fence 행의 제거를 포함한다.
- 아카이브 재처리 경로 — §3 표의 재처리 행을 그대로 따른다.

## Acceptance Criteria

- `TenantRetentionBoundaryStoreTest` — 없는 행은 경계 없음·epoch 0, 늦은 후보만 경계를 옮기고 epoch 를 1 올린다, 같거나 이른 후보는 아무것도 바꾸지 않는다,
  마이크로초 아래 자리는 거부한다.
- `AnalysisWriteBoundaryTest` — sink 가 경계 이전 행을 싣지 않는다, 넘긴 경계가 낡아도 fence 이전 행은 서버가 쓰지 않는다, fence 가 없으면 1970 년 이전 행도 남는다,
  tenant 가 섞인 배치는 요청 전에 실패한다.
- `RetentionFenceEvidenceTest` — Context 의 측정 1·3·4 를 24.8 이미지에서 재현한다.
- ingest — 경계 이전의 지연 도착·재전송이 분석 테이블에 남지 않고 ledger 의 거부로 세어진다, 경계 조회 실패는 503 이고 아무것도 쓰지 않는다.
- snapshot — build 도중 epoch 가 바뀌면 공개 CAS 가 실패한다(`SnapshotLifecycleTest`), build 가 경계 이전 관측을 복사하지 않는다(`SnapshotBuilderTest`).

## References

- [ADR 0008](0008-모듈-경계와-네임스페이스-규칙-확정.md) · [ADR 0015](0015-clickhouse-ddl-은-번호-붙은-멱등-파일이고-기동-시-적용한다.md) · [ADR 0020](0020-정규화-계약-2판은-관측을-식별하고-의미-컬럼으로-교체-저장한다.md) §1·§8 · [ADR 0021](0021-수집-운영-기록은-ledger-와-telemetry-ops-스키마에-두고-enrollment-api-가-적용한다.md) §1·§2·§4 · [ADR 0022](0022-대시보드-API-는-별도-앱이고-인증은-포트-뒤에서-기본-거부한다.md) §4 · [ADR 0023](0023-대시보드-snapshot-은-dashboard-cache-의-불변-복사본과-manifest-이고-대시보드-앱이-그-DDL-을-적용한다.md) §4
- 허브 [ADR 0007](../../../docs/adr/0007-dashboard-snapshots-and-telemetry-lifetime-summary.md) §6
- 코드: `IngestPipeline` · `TelemetryEventsSink` · `TelemetryMetricPointsSink` · `AnalysisInsert` · `TenantRetentionBoundaryStore` · `RetentionFence`
