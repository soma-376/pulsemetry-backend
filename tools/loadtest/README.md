# 부하 시험 도구

dev 파이프라인(Gateway → Kafka → ingest → ClickHouse)에 계단식 부하를 걸어 두 층의 한계(① Gateway 수용, ② 파이프라인 지속 처리)를
찾는 도구다([ADR 0082](../../docs/adr/0082-부하-시험-도구는-캠페인-전용-tenant를-SQL로-시드하고-tools-loadtest에-둔다.md)).
캠페인마다 전용 tenant를 SQL로 시드하고, k6 Job이 설치마다 `pit_`을 telemetry JWT로 바꾼 뒤 실제 Claude Code·Codex OTLP 캡처를
템플릿으로 시각·ID만 바꿔 Gateway에 보낸다. k6 지표는 같은 Pod의 OTel Collector가 `:9404`로 내놓고 Prometheus가 pull한다.
Gateway 200은 Kafka 접수다 — 저장은 ClickHouse `FINAL` 조회로 따로 확인한다. 배포·실행·정리는 infra
[런북](../../../infra/docs/runbooks/dev-pipeline-loadtest.md)을 따른다. Gradle 밖이고 Python 표준 라이브러리와 k6만 쓴다.

| 경로 | 내용 |
|---|---|
| `generator/seed_sql.py` | 부하 tenant 시드·확인·폐기 SQL. `identities.py`(결정적 ID·`pit_` 파생), `kafka_partition.py`(Kafka 기본 partitioner) |
| `generator/build_templates.py` | 실제 OTLP 캡처 → `k6/data/templates.json`. 원천 선택과 자리표시자 규칙은 이 파일 머리말 |
| `k6/main.js`·`k6/lib/*.js` | 시나리오(smoke·stair·hold)·토큰 교환·payload·summary |
| `k6/selftest.js` | 네트워크 없는 자체 검사 |
| `local/run.sh` | 로컬 E2E — 실제 앱 셋·Kafka·ClickHouse·OTel Collector |
| `tests/` | 생성기 단위 시험 |

## 시드

```bash
# 새 캠페인에서만 만든다. 이미 있으면 noclobber(set -C)가 덮어쓰기를 거부한다 — 재적용에는 그 파일을 그대로 쓴다.
(set -C; umask 077; openssl rand 32 > $HOME/loadtest/<campaign>/seed-secret)
# 생성기가 성공했을 때만 psql 을 실행한다(&&). 파이프(`… | psql`)는 생성기 실패를 빈 입력의 psql 성공으로 가린다.
python3 tools/loadtest/generator/seed_sql.py --campaign <campaign> --installations 60 --partitions 6 \
  --secret-file $HOME/loadtest/<campaign>/seed-secret --identities-out $HOME/loadtest/<campaign>/identities.json > seed.sql \
  && psql -v ON_ERROR_STOP=1 -f seed.sql                                  # 사람이 DB 터널로 적용한다
python3 tools/loadtest/generator/seed_sql.py --campaign <campaign> --installations 60 --partitions 6 \
  --secret-file $HOME/loadtest/<campaign>/seed-secret --verify > verify.sql \
  && psql -v ON_ERROR_STOP=1 -At -f verify.sql                            # 8행 모두 t, 하나라도 f 면 종료 코드 3
python3 tools/loadtest/generator/seed_sql.py --campaign <campaign> --revoke > revoke.sql \
  && psql -v ON_ERROR_STOP=1 -f revoke.sql                                # 배수(lag 0) 뒤에만
```

- 캠페인 ID는 `^[a-z0-9-]{3,40}$`, tenant 이름은 `loadtest-<campaign>`이다. 같은 입력은 같은 SQL이다(재적용 안전).
- 설치 수는 partition 수의 배수다. key `"<tenantId>:<installationId>"`가 partition마다 같은 수로 가도록 고른다.
- 다음은 SQL이 `RAISE`로 롤백된다. 새 캠페인 ID를 쓰거나(설치 수·폐기) 처음 쓴 비밀 파일을 쓴다(비밀).
  - 기존 캠페인과 설치 집합이 다르다(설치 수 변경) — INSERT 전에 검사한다.
  - 설치별 활성 자격증명 해시가 이 시드 비밀과 다르다 — 커밋 전에 (설치, 해시) 쌍으로 대조한다.
  - 폐기한 캠페인이다.
- 충돌은 `ON CONFLICT (id)`로만 넘긴다. `credential_hash` 같은 다른 UNIQUE 충돌은 오류다.
- `--identities-out`이 `--secret-file`과 같은 파일이면 아무것도 쓰지 않고 실패한다.
- **시드 비밀**: 정확히 32바이트 원시 파일(`openssl rand 32`, `-hex` 아님). 저장소 밖에 두고 캠페인마다 새로 만든다.
  SQL에는 `sha256_hex(pit_)`만, identities에는 ID만 있다. 비밀을 잃으면 그 캠페인의 `pit_`을 다시 만들 수 없다 — 새 캠페인으로 시드한다.
- 데이터 삭제는 retention-worker다. `--as-of`를 미래로 줘야 하고, 그 뒤 그 tenant는 재사용할 수 없다(ADR 0082 Decision 5).

## payload 템플릿

```bash
python3 tools/loadtest/generator/build_templates.py           # 다시 만든다
python3 tools/loadtest/generator/build_templates.py --check   # 커밋본과 같은지(다르면 종료 1)
```

`libs/telemetry-adapter/src/test/resources/otlp-v2/`의 기대값이 있는 실캡처만 쓴다(`claude_code/real`·`claude_code/api-2.1.290`·
`codex/real`·`codex/completed`, 합성 제외). ConfigMap 1MiB 안에 들도록 파일마다 앞 3개 문서만 쓴다(`--max-per-file`). 산출물은 900KiB 미만이어야 한다.
템플릿마다 같은 캡처의 `*.expected.jsonl`에서 센 기대 행 수(`events`·`metricPoints`)가 있고, k6 summary가 200 응답만큼 합산한다.

## k6 설정

| env | 기본값 | 뜻 |
|---|---|---|
| `TESTID` | (필수) | `^[a-z0-9][a-z0-9-]{0,39}$`. 모든 지표의 `testid` 라벨 |
| `SCENARIO` | `smoke` | `smoke`(1 RPS × 60s) · `stair` · `hold` |
| `GATEWAY_URL` | `http://telemetry-gateway.pulsemetry-dev.svc:8082` | |
| `ENROLLMENT_URL` | `http://enrollment-api.pulsemetry-dev.svc:8080` | 토큰 교환 |
| `IDENTITIES_FILE`·`SEED_SECRET_FILE` | (실행에 필수) | seed_sql.py `--identities-out` · 32바이트 시드 비밀 |
| `START_RPS`·`STEP_RPS`·`MAX_RPS` | 5 · 5 · 60 | stair 단계. `MAX_RPS` ≤ 1000 |
| `RAMP`·`STEP_HOLD`·`COOLDOWN` | 30s · 5m · 30s | stair 상승·유지·끝 정리(0 RPS로) |
| `FINAL_FLUSH` | 25s | 끝난 뒤 teardown 에서 기다리는 시간. 마지막 지표가 collector 를 거쳐 Prometheus 에 수집되게 한다(내보내기 5s + scrape 10s). 0~300s. teardown 제한(`teardownTimeout`)은 이 값 + 60s 다 |
| `HOLD_RPS`·`HOLD_DURATION` | (hold에 필수) · 20m | |
| `MIX_SIGNAL` | `logs:70,metrics:20,traces:10` | |
| `MIX_PRODUCT` | `claude-code:50,codex:50` | |
| `GZIP` | 1 | |
| `TEMPLATES_FILE`·`SUMMARY_FILE` | `./data/templates.json` · (없음) | summary JSON을 파일로도 쓴다 |

- 계획 시간(stage 합)이 10200s(JWT 3h − 600s)를 넘으면 init에서 끝난다. VU는 `preAllocated = max(10, ⌈peak×0.4⌉)`, `max = max(50, peak×2)`.
- 안전 정지: Gateway 요청(`target:gateway`)의 오류율 ≥ 5%·p95 ≥ 4s, dropped ≥ 3000(1분 지연 평가), 401 즉시, 같은 VU에서 non-2xx 50회 연속. setup 의 토큰 교환은 임계값·합계에 넣지 않는다.
- 종료 코드: 0 통과 · 99 threshold 실패 · 107 설정·setup 오류 · 108 abort.
- 태그: `testid`·`target`(Gateway 요청만 `gateway`)·`step`(s01…)·`phase`(ramp·hold·cooldown·setup)·`signal`·`product`. 단계별 판정 값은 `phase:hold` 구간만 본다.
- summary: stdout의 `===LOADTEST-SUMMARY-BEGIN===` / `===LOADTEST-SUMMARY-END===` 사이 JSON 한 줄. 단계별 절대 시각·목표·실제 RPS·p95·p99·오류율,
  기대 events·metric points 합계, 단계별 완료 여부(`completed`)와 drop(`droppedIterations` = 예정 반복 `목표 RPS × 유지 초` − 유지 구간 요청 수,
  실행 전체 drop 이 0이면 0). 반복 하나가 요청 하나라 Prometheus 수집 시점과 무관한 k6 자체 집계다 — 결과 판정은 단계별 drop 을 여기서 읽는다.
  - 완료: 반복마다 시나리오 경과 시간을 `loadtest_progress_seconds` 로 남기고, 그 최댓값이 유지 구간 끝(허용 max(2s, 2/RPS))에 닿은 단계만
    완료로 본다. setup·teardown 이 들어가는 `testRunDurationMs` 는 쓰지 않는다.
  - 중단(`aborted: true`): 401·연속 non-2xx 로 멈추면 `loadtest_aborted` 카운터를, 안전 정지 임계값은 그 실패를 본다. 중단된 실행은 허용 없이
    유지 구간 끝 이후에 시작한 반복이 있는 단계만 완료다 — 끝 2초 안에서 멈춘 단계나 유지 2초 이하 단계를 완료로 세지 않는다.
  - 중단·미실행 단계는 `completed: false`, `droppedIterations: null` 이다. 결과 판정은 이런 단계를 두 층 모두의 한계 산정에서 뺀다
    (60초에 멈춘 10분 유지 단계를 통과로 세지 않는다). 토큰은 없다.
- `k6 inspect`는 시스템 env를 넘기지 않는다 — `--include-system-env-vars` 또는 `-e`로 준다.

OTel 출력 env: `K6_OTEL_EXPORTER_TYPE=http`, `K6_OTEL_HTTP_EXPORTER_ENDPOINT=127.0.0.1:4318`, `K6_OTEL_HTTP_EXPORTER_INSECURE=true`,
`K6_OTEL_METRIC_PREFIX=k6_`. Collector(prometheus exporter)를 지난 이름은 `k6_http_reqs_total`, `k6_http_req_duration_milliseconds_bucket`,
`k6_http_req_failed_total`, `k6_dropped_iterations_total`(drop이 있을 때만), `k6_loadtest_target_rps`, `k6_loadtest_accepted_requests_total`,
`k6_loadtest_expected_events_total`, `k6_loadtest_expected_metric_points_total` 등이다. 전체 목록은 로컬 E2E의 `evidence/k6-metric-names.txt`.

## 로컬 검증

```bash
python3 -m unittest discover -s tools/loadtest/tests
docker run --rm -v "$PWD/tools/loadtest/k6:/s:ro" -e K6_NO_USAGE_REPORT=true \
  grafana/k6:1.8.2@sha256:bd6ae8607c0a05d0dc0dc3f5753c464b0929baf083ef0f20b7eed845a3d9e6b4 run -q /s/selftest.js
tools/loadtest/local/run.sh <작업 디렉터리>     # Docker·JDK 25 필요. 약 8분
```

`local/run.sh`는 bootJar 셋을 빌드해 호스트에서 띄우고(enrollment JWT 발급 켬, ingest `kafka` 모드), 설치 12개·partition 6으로 시드한 뒤
smoke와 소형 stair(2→6 RPS)를 돌린다. 응답 없는 대상으로 dropped_iterations도 한 번 만든다. 판정 여섯(토큰 교환·k6 종료·lag 0·
ClickHouse FINAL 행 수 = summary 기대 합계·실패 이벤트와 토큰 원문 없음·k6 지표)이 하나라도 틀리면 종료 1이다. 포트는 4xxxx
(`scripts/telemetry-kafka-e2e`와 겹치지 않는다), 증거는 `<작업 디렉터리>/evidence/`. `LOADTEST_KEEP=1`이면 컨테이너를 남긴다.

## dev에 올릴 때

코드는 backend 커밋마다 ConfigMap 하나, 캠페인 입력(시드 비밀·identities)은 캠페인마다 Secret 하나다. ConfigMap 키에는 `/`를 쓸 수 없어
`lib.<이름>.js`·`data.templates.json`으로 넣고, Job 차트(infra `helm/dev/loadtest`)가 아래 표대로 `/scripts` 아래 경로에 되돌린다.

```bash
SHA=$(git rev-parse --short=12 HEAD)
K=tools/loadtest/k6
wc -c $K/main.js $K/lib/*.js $K/data/templates.json | tail -1   # 1048576 미만이어야 한다(현재 약 690KB)
kubectl create configmap loadtest-k6-$SHA -n pulsemetry-loadtest \
  --from-file=main.js=$K/main.js \
  --from-file=lib.config.js=$K/lib/config.js --from-file=lib.schedule.js=$K/lib/schedule.js \
  --from-file=lib.payload.js=$K/lib/payload.js --from-file=lib.auth.js=$K/lib/auth.js \
  --from-file=lib.summary.js=$K/lib/summary.js --from-file=data.templates.json=$K/data/templates.json
kubectl create secret generic loadtest-seed-<campaign> -n pulsemetry-loadtest \
  --from-file=seed-secret=$HOME/loadtest/<campaign>/seed-secret --from-file=identities.json=$HOME/loadtest/<campaign>/identities.json
```

| ConfigMap 키 | 마운트 경로 |
|---|---|
| `main.js` | `/scripts/main.js` |
| `lib.config.js`·`lib.schedule.js`·`lib.payload.js`·`lib.auth.js`·`lib.summary.js` | `/scripts/lib/<이름>.js` |
| `data.templates.json` | `/scripts/data/templates.json` |

Secret은 `/seed`에 마운트하고 `SEED_SECRET_FILE=/seed/seed-secret`, `IDENTITIES_FILE=/seed/identities.json`이다.
같은 캠페인으로 Job 두 개를 동시에 돌리지 않는다 — setup의 토큰 교환이 서로의 토큰을 `rotated`로 만든다.
