# Dashboard 실행 복구 검증

기준: DashboardRuns와 DashboardScenarioRuns. 자동 재시도는 구현하지 않는다.

| 조건 | 기대 동작 | 검증 |
|---|---|---|
| 여러 워커가 대기 작업을 동시에 획득 | SKIP LOCKED와 claim token으로 단일 소유 | DB 테스트: 6개 워커·3개 작업 |
| 취소 후 늦은 완료/진행 갱신 | 상태를 덮어쓰지 않고 false | 기존 DB 테스트 |
| 완료 후 중복 완료/진행 갱신 | succeeded와 최초 결과 유지 | 새 DB 테스트 |
| running의 lease 만료 | 다음 claim에서 failed, worker_lease_expired | 기존·새 DB 테스트 |
| 새 저장소 인스턴스의 대기 작업 | 기존 queued를 획득·완료 | 새 DB 테스트 |
| 실행 전 계정·팀 권한 변경 | 현재 권한 재검증 후 실패 | 기존 DB 테스트 |

## 운영 시 해석

- lease는 claim과 progress 시 DB now 기준 2분으로 설정한다. 실행 중 서버가 멈추면 즉시 실패하지 않으며, 기한 경과 후 동작 중인 워커의 다음 claim에서 실패 처리된다.
- queued는 DB에 남아 다음 워커가 처리한다. 만료된 running은 자동으로 queued로 되돌리지 않는다. 재실행은 새 실행 요청으로 생성하며 당시 권한·감사·동시 실행 제한을 다시 적용한다.
- failed의 error와 finished_at, succeeded의 결과를 RUN-GET으로 확인한다. failed 결과를 임의로 succeeded로 바꾸거나 claim_token을 재사용하지 않는다.
- 동시 실행 제한은 tenant별 queued/running 3개다. 워커가 모두 멈춘 동안 만료 running도 정리 전에는 이 수에 포함될 수 있다.

## 프로세스 재기동 E2E 방법

격리된 테스트 API 프로세스를 SIGKILL로 종료한다. 종료 뒤 기존 성공 실행의 입력을 복사해 queued와 lease가 만료된 running 상태를 DB에 준비하고 동일 DB·키로 서버를 재기동한다. queued의 성공과 만료 running의 실패를 DB 및 실제 frontend 로그인·공통 클라이언트 RUN-GET으로 검증한다.

이 방법은 중단 복구 상태를 의도적으로 준비한다. 실제 조회 도중의 임의 시점 장애, DB 자체 재시작, 네트워크 분단, 다중 프로세스 부하 시험을 대신하지 않는다. 기동 전후 로그는 build/e2e/auth-settings/backend.log와 backend-restarted.log로 분리한다.

검증 결과: `5455685`에서 dashboard 263건과 프로세스 재기동 E2E 통과. `result.json`의 verifiedRecovery에 결과를 기록한다.

## ClickHouse 무응답 시험

격리된 ClickHouse 컨테이너를 docker pause로 일시 정지한 상태에서 실제 frontend 공통 클라이언트로 S1-3 실행을 생성한다. RUN-GET에서 failed/query_timeout과 result=null을 확인하고 finally에서 컨테이너를 unpause한다. ping 복구 후 새 실행을 생성해 succeeded가 되는지, 이전 실패 실행은 그대로인지 확인한다.

이 시험은 연결 대상이 응답하지 않는 상황과 조회 기한을 다룬다. ClickHouse 데이터를 삭제하거나 운영 서비스를 정지하지 않는다. 실제 데이터베이스 재시작·데이터 손실·복제 장애·네트워크 분단 토폴로지는 별도 범위다.

검증 결과: `b125f8a` 기준 E2E 통과. verifiedClickHouseOutage에 query_timeout·부분 결과 없음·복구 후 새 실행 성공·이전 실패 보존을 기록했다.
