# soma 과거 화면 시드 운영

이 도구는 승인된 독립 `soma` 데이터셋을 **local 또는 dev**에 직접 적재한다. 기존 Compose의
`init/apply/reset` 경계와 별개이며 DB 마이그레이션·삭제·Cognito 계정 생성은 수행하지 않는다.
적재 경계·단계적 활성화·원래 사용량과 측정 기반 튜닝은
[허브 ADR 0023](https://github.com/soma-376/docs/blob/main/adr/0023-dev-historical-dashboard-dataset.md)을 따른다.
생성 행은 화면 검증용이며 실제 OTLP 수집 파이프라인을 통과했다는 증거가 아니다.

## 실행 파일과 입력

추가 Python 패키지는 필요 없다. Python 3.9 이상과 이미 설치된 `psql`을 사용한다.
Kotlin의 `historical-plan`으로 만든 `manifest.json`, `postgres.json`, 월별 배치 JSONL을 한 디렉터리로 보존한다.
manifest는 환경·기간·스냅샷 시각·조직 ID·OIDC 공개 연결·각 파일의 SHA-256을 고정한다.
local과 dev는 다른 조직 ID를 사용하므로 각 환경의 artifact를 생성해야 한다.

```sh
./gradlew :tools:dev-seed:installDist
tools/dev-seed/build/install/dev-seed/bin/dev-seed historical-plan \
  --environment dev --output /absolute/path/new-artifact \
  --end-date 2026-10-26 --snapshot-at 2026-10-27T03:45:00Z \
  --otlp-endpoint https://api-dev.pulsemetry.co.kr \
  --activity-profile rich --stage-alert-rules true
```

출력 디렉터리는 새 경로여야 한다. 스냅샷 날짜는 마지막 사용일 다음 날이며 KST 00:10 이후여야 한다.
importer는 실제 시각보다 미래인 스냅샷을 DB 접속 전에 거부한다.
dev는 기존 Cognito A 공개 연결을 사용한다. local 모의 IdP는 `--oidc-issuer`, `--oidc-client-id`,
`--oidc-secret-ref`로 지정하며 HTTP issuer는 local loopback에서만 허용한다.
이 명령은 DB에 접속하지 않는다. 재개할 때는 다시 생성하지 않고 같은 파일을 사용한다.

`--activity-profile`의 기본값 `rich`는 기존 soma 생성량과 파일 지문을 유지한다. 명시적인 `team-daily`는
50팀·400명·398설치·180일을 유지하면서 팀마다 매일 한 세션을 만든다. 팀 안에서 구성원을 순환하며,
평일은 세션당 2개, 주말은 1개 관측을 생성한다. 2026-04-30~2026-10-26이면 9,000세션·15,400관측이며,
기존 rich의 56,232세션·199,686관측보다 사용량이 적다. 두 프로필을 같은 규모의 부하 증거로 취급하지 않는다.
기존 A/C 시나리오, 모델별 토큰 의미·단가·수집 구간 계약은 바뀌지 않는다.

`--stage-alert-rules true`는 모든 시드 알림 규칙의 초기 `enabled`를 false로 쓰고, 원래 값과 판을 manifest의
`activationRules: [{ruleId, desiredEnabled, expectedVersion}]` 및 설정 해시에 보존한다. 기본값 false는
기존 파일 생성 동작의 호환용이다. 활성 규칙이 있는 새 운영 적재는 단계 준비 옵션으로 생성해야 한다.

대상 연결 파일의 예시는 다음과 같다. 실제 host와 CA 파일은 승인된 dev DB 접속 런북의 값으로 채운다.

```json
{
  "schemaVersion": 1,
  "environment": "dev",
  "targetId": "pulsemetry-dev-soma-history-v1",
  "postgres": {
    "host": "<원래 RDS hostname>",
    "hostaddr": "127.0.0.1",
    "port": 15432,
    "dbname": "controlplane",
    "user": "<승인된 DB 사용자>",
    "sslmode": "verify-full",
    "sslrootcert": "/absolute/path/rds-ca.pem",
    "passwordEnv": "SOMA_DEV_PG_PASSWORD",
    "psql": "/opt/homebrew/opt/libpq/bin/psql"
  },
  "clickhouse": {
    "url": "http://127.0.0.1:18123",
    "database": "default",
    "username": "default",
    "passwordEnv": "SOMA_DEV_CH_PASSWORD"
  },
  "monitorFile": "/absolute/path/private/dev-monitor.json"
}
```

비밀번호 원문·Cognito client secret은 JSON이나 명령 인자에 넣지 않는다. `passwordEnv` 대신 기존
`PGPASSFILE`을 사용할 수도 있다. local 대상은 PostgreSQL/ClickHouse 모두 loopback이어야 하며
`sslmode=disable`을 허용한다. PostgreSQL `hostaddr`를 지정할 때도 loopback IP여야 한다.
dev PostgreSQL은 원래 RDS hostname을 검증하는 `verify-full`과 CA가 필수이며,
dev ClickHouse는 HTTPS 또는 loopback 터널 주소만 허용한다.

## 순서

```sh
python3 tools/dev-seed/historical_seed.py plan \
  --manifest /absolute/path/artifact/manifest.json \
  --target /absolute/path/private/target.json \
  --state-dir /absolute/path/private/import-state

# 아래 각 명령에도 같은 세 인자를 전달한다.
# apply          최초 적재. 완료된 동일 실행에서는 검증만 한다.
# resume         중단된 실행을 읽기 대조한 뒤 없는 행만 입력한다.
# verify         PostgreSQL과 모든 ClickHouse 배치·월별 건수를 대조한다.
# activate-login 전체 검증 뒤 soma 한 조직의 SSO를 최초 활성화한다.
```

`plan`은 DB 스키마·핵심 컬럼 타입·서버 identity·조직 충돌·보존 경계를 **읽기 전용**으로 확인한다.
배포 이미지의 토큰 프로파일 지원, 실제 Cognito 계정·callback·runtime의 client secret 연결,
관측 지표·조회 한도 및 로컬 동일 규모 실측은 운영자가 별도 증거로 확인한 후 `apply`한다.
도구가 `plan`에 성공했다는 이유만으로 실제 SSO와 배포 호환성이 검증된 것은 아니다.
snapshot 복사 읽기량과 API 반환량 제한을 구분한다. 조회 한도·내부 로그 튜닝은 이 적재 도구가 실행하지
않으며, 별도 운영 설정과 복구 절차를 검증한다. 로컬 성공 시나리오와 설정·부하를 사용자에게 먼저 제시하고
확인받은 뒤 dev 권한·튜닝·데이터·SSO를 적용한다.

PostgreSQL의 새 조직은 SSO 비활성 상태로 한 트랜잭션에 넣는다. tenant 트리거가 만든 수집 요약은
해당 tenant 행만 UPDATE한다. 각 표의 건수·생성 필드를 commit 전에 확인한다. 다른 조직은 대상으로 삼지 않는다.
단계 준비된 알림 규칙도 비활성 상태이므로, 기존 dashboard 앱이 실행 중이어도 부분 적재를 완료 데이터로
평가하지 않는다. 전체 검증 뒤 `activate-login` 한 PostgreSQL 트랜잭션에서 규칙의 원래 enabled 값과 SSO를
함께 복원한다. 규칙의 version·updated_at·updated_by는 보존하며, 판 변경이나 혼합 활성화 상태는 중단한다.

ClickHouse는 테이블의 실제 UTC 월 파티션으로 나눈 파일을 순차 처리한다. 요청당 2,000행/4MiB 중 먼저
도달하는 한도를 적용하며 `async_insert=0`, 동시 1개, ACK 뒤 1초 대기다. 첫 5개 ACK 뒤 30초 관찰한다.
분석 행 INSERT는 서버의 retention fence를 다시 읽는다. 전체 파일을 float나 문자열 한 벌로 복제하지 않는다.

## 부하 관측 계약

dev의 `monitorFile`은 별도 관측 프로세스가 45초보다 짧은 주기로 원자적으로 교체해야 한다.
예시는 형식만 보여 주며 실제 정상 부하를 의미하지 않는다.

```json
{
  "observedAt": "2026-10-27T03:45:00Z",
  "validUntil": "2026-10-27T03:45:25Z",
  "memoryWorkingSetBytes": 536870912,
  "memoryLimitBytes": 2147483648,
  "readLatencyMs": 20.0,
  "baselineReadLatencyMs": 25.0,
  "pressureGuard": {
    "schemaVersion": 1,
    "identity": {"containerId": "<고정 컨테이너 ID>", "startedAt": "2026-10-27T00:00:00Z"},
    "baseline": {
      "observedAt": "2026-10-27T03:40:00Z",
      "memoryEvents": {"max": 0, "oom": 0, "oom_kill": 0}
    },
    "memoryEvents": {"max": 0, "oom": 0, "oom_kill": 0},
    "latched": false
  }
}
```

- 메모리는 현재 ClickHouse 컨테이너 working set과 현재 limit을 사용한다. CH `MemoryTracking`만으로 대체하지 않는다.
- 기준 읽기 지연은 적재 전 같은 읽기 probe로 측정한다. 직접 HTTP seed의 ACK 지연은 별도로 로그에 남는다.
- 관측 나이가 45초를 넘거나 파일/값이 없으면 중단한다. 선택적 `validUntil`은 원본 지표별 만료 시각 중 최솟값이며,
  파일 갱신 시각이 최근이어도 이를 넘으면 중단한다. 관측기가 실패하면 기존 파일도 제거해 정상으로 오인하지 않게 한다.
  메모리 **75% 이상**, 지연 **기준의 2배 이상**도 중단한다.
- 관측기는 같은 컨테이너의 `memory.current`와 working set을 구분해 기록하고 cgroup `memory.events`도 수집한다.
  working set이 낮거나 OOM이 없어도 `max` 증가는 hard limit의 압력을 뜻하므로 정상 부하로 간주하지 않는다.
  `max`·`oom`·`oom_kill` 중 하나라도 고정 기준과 달라지거나 관측기가 `latched=true`를 보고하면 중단한다.
  카운터 감소도 재시작·기준 변경을 감추지 않도록 중단한다.
- 첫 관측의 컨테이너 ID·시작 시각·기준 카운터와 기준 시각을 checkpoint에 고정한다. 재개 중 이 값이 바뀌면
  중단하며 `pressureStopLatched`를 보존한다. 이후 정상 샘플을 공급해도 자동으로 풀리지 않는다.
  기준 시각 자체는 오래되어도 되지만, 현재 샘플은 위 신선도 조건을 만족해야 한다. 관측기 재시작 시 기준과
  중단 기록을 새 정상값으로 초기화하지 않는다. 원인 확인과 운영 복구는 별도로 결정한다.
- dev에서 압력 계약·identity·카운터가 없는 기존 monitor는 거부한다. local에서도 `monitorFile`을 지정하면
  같은 규칙을 적용한다. 관측 파일 생략은 격리된 local 기능 테스트에만 사용한다.

### 명시적으로 선택하는 HTTP 응답 요약 지연

[허브 ADR 0024 (Accepted)](https://github.com/soma-376/docs/blob/main/adr/0024-dev-seed-http-summary-latency.md)에
따라 명시적으로 선택하는 모드다. dev 적용 확인은 실행별로 기록한다. 미지정 target은 위의 기존 왕복 지연
기준을 유지한다. 새 방식은 target의 다음 필드를 명시하고 그 target SHA를 실행 상태에 고정해야 한다.

```json
"monitorLatency": {
  "mode": "server_summary_elapsed_fixed_limit_v1",
  "staticLimitMs": 58.816084000000046
}
```

관측에는 동일한 `latencyMode`와 `proposedServerElapsedLimitMs`, `waitEndOfQuery: true`가 필요하다.
`measurement`는 `http_response_summary_elapsed_ns`, `aggregation`은 `median_of_5`다.
기존 `readLatencyMs`·`baselineReadLatencyMs` 또는 `serverBaselineMs`를 함께 발행하면 거부한다.
이 값은 실행에 고정하는 절대 상한이며, 기존 왕복 baseline을 서버 baseline으로 환산한 값이 아니다.

`latencyEvidence.rawProbes`에는 동일한 `SELECT count() FROM system.parts WHERE active` 요청 다섯 개의
서로 다른 query ID, 성공 여부, 요청 시작 간격(0.2초 이상), 시작·완료 시각과 전후 자원·오류 관측을 보존한다.
각 요청은 `wait_end_of_query=1`, `send_progress_in_http_headers=0`, HTTP 200, 요약 헤더 하나와
1024바이트 이하 정상 count 본문을 확인해야 한다. 관측기는 원문 `summaryRaw`, 파싱한 `summary`,
`serverElapsedNs`, `serverElapsedMs`, 전체 왕복 `clientElapsedMs`, `summaryHeaderCount`,
`requestSettings`, `probeSqlSha256`, `responseBytes`를 기록한다. 헤더 누락·중복, 본문/HTTP 오류와
timeout은 개별 요청에서 즉시 중단하고 실패값을 중앙값에서 제외하지 않는다.

소비자는 원문 JSON을 다시 파싱하여 elapsed_ns와 ms, 원시 목록과 양쪽 중앙값을 대조한다.
`serverElapsedMedianMs`가 target 상한 이상이면 중단한다. `clientElapsedMedianMs`는 진단값으로 보존한다.
`observedAt`은 창의 원래 시작 시각이며 `validUntil`은 그로부터 40초다. 발행 시각으로 만료를 늘리지 않는다.
메모리 75%, 원래 identity·압박 카운터와 기준, 오류 중단 조건은 각 요청 전후에도 적용한다.

HTTP 요약 elapsed는 서버의 응답 준비 구간을 포함하지만 순수 SQL·CPU 시간은 아니다. 최종 본문 전송과
전체 왕복 지연을 보호하는 상한도 아니다. 관측기는 원본 중단 기록을 보존하고 오류 시 영구 중단을 유지해야
하며, 새 정상 관측만으로 중단을 해제하면 안 된다. 검증용 target/state 복사본을 운영 원본과 구분한다.

## 재개와 보존

`state-dir`은 0700, checkpoint는 0600으로 보관한다. 파일 잠금과 동일 DB tenant의 PostgreSQL 세션 advisory
lock을 함께 유지한다. 대상 설정·artifact SHA와 DB identity가 바뀌면 자동 재개하지 않는다.
같은 DB를 다른 target 이름으로 실행해도 동일 tenant 잠금은 공유한다.

배치 전송 전에 `query_id`와 상태를 기록한다. HTTP 실패 뒤에는 먼저 해당 서버 쿼리가 종료됐는지 확인한다.
실행 중이면 중단하고, 종료됐으면 tenant·시간·ID 범위를 제한하여 저장된 모든 생성 필드를 대조한다.
동일 행은 건너뛰고 없는 행만 보낸다. 다른 내용이나 물리적 중복은 중단하며 자동 DELETE/재전송하지 않는다.
두 DB를 묶는 원자적 commit은 없으므로 checkpoint와 원본 artifact를 함께 보존해야 한다.
실패는 `errorHistory`에 시각과 메시지를 보존한다. `lastError`·`pausedAt`은 아직 해결되지 않은 실패 표시이며,
전체 검증이 성공하면 정리한다. 이전 checkpoint에 오류 이력이 없어도 기존 마지막 오류를 먼저 보존한다.

검증은 각 원천 배치와 월별·전체 건수를 확인한다. 검증 중에도 관측과 쿼리 속도 제한을 유지한다.
원천 행 대조는 `PREWHERE`로 tenant·시간·ID를 먼저 제한하고 `max_block_size=512`,
`preferred_block_size_bytes=1048576`으로 넓은 행의 읽기 부담을 줄인다. identity를 포함하여 최대 16열씩
순차 조회해 모든 생성 필드를 대조한다. 각 조회에서 `FINAL` 없이 물리적 중복을 검사하고 조회 사이
누락 ID 집합이 달라지면 중단한다. 해시 하나로 원문 검증을 대신하지 않는다.
긴 ID 목록을 가진 행 대조 SELECT에만 `log_queries_cut_to_length=4096`을 요청별로 적용한다. 쿼리·설정 로그는
계속 활성화하며 전역 설정·INSERT·일반 조회는 변경하지 않는다. 전송 전과 완료·실패 시에
`state-dir/verification-queries.jsonl`을 fsync하여 query ID, manifest·importer·전체 SQL의 SHA-256,
배치 ID·테이블·선택 열·SQL 바이트 수·결과 건수를 보존한다. 원본 artifact와 해당 importer를 함께 보관하면
서버 로그에서 잘린 SQL을 복원해 대조할 수 있다.
모든 배치가 `verified`이고 phase가 `loaded`인 채 최종 검증만 실패했다면 `verify`로 읽기 검증을 재개한다.
검증 완료 뒤 `activate-login`을 실행한다. 활성화 응답이 유실돼도 `activating` checkpoint와 DB를 대조해
활성화 완료 상태로 복구한다. 실제 SSO가 연결한 회원 `oidc_subject`는 재검증에서 덮어쓰지 않는다.

`activationRules`가 없는 이전 manifest는 읽기 전용 plan과 기존 활성 데이터의 verify가 가능하다. 다만
enabled=true인 규칙을 포함하는 이전 파일의 apply/resume/activate-login은 부분 평가 위험 때문에 거부한다.
해당 실행을 자동으로 새 파일로 바꾸거나 이미 기록된 checkpoint 해시를 덮어쓰지 않는다.

## 자동 검사

```sh
PYTHONPYCACHEPREFIX=/tmp/soma-seed-pycache \
  python3 -m unittest discover -s tools/dev-seed -p 'test_historical_seed.py' -v
```

부분 적재/ACK 유실 재개, 서버 실행 중 재전송 금지, 저장 내용 충돌·물리 중복, PG 잠금 연결 소실,
메모리·지연·지표 신선도·압력 카운터와 중단 유지, 요청별 로그 길이 제한과 전체 SQL 추적,
경로/해시 변조, Decimal·나노초 정밀도와 전송 바이트 제한을 검사한다.
