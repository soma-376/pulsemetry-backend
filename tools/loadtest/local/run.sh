#!/usr/bin/env bash
# 부하 시험 로컬 E2E — 시드 생성기·k6 시나리오·OTel sidecar 경로를 실제 Gateway·Kafka·ingest·ClickHouse 로 끝까지 확인한다(ADR 0082).
#
#   tools/loadtest/local/run.sh <작업 디렉터리>
#
# scripts/telemetry-kafka-e2e/run.sh 를 복사해 고쳤다(원본은 고치지 않는다). 데몬·프록시·telemetryctl 빌드를 빼고,
# compose 프로젝트 이름과 호스트 포트를 원본과 겹치지 않게 바꿨다. 앱 기동 설정(JWT 발급·kafka 모드·공개키)은 원본과 같다.
# 작업 디렉터리에 로그·키·시드·증거가 남는다. 저장소에는 아무것도 쓰지 않는다.
# 필요한 것: Docker, JDK 25, openssl, curl, python3. macOS 기본 bash(3.2)에서 돈다.
# 환경변수: LOADTEST_KEEP=1 이면 끝난 뒤 컨테이너를 남긴다.
# bash 3.2 는 `$이름` 바로 뒤의 한글 바이트를 이름으로 읽는다 — 한글 앞의 변수는 `${이름}` 으로 쓴다.
#
# 판정(하나라도 틀리면 종료 1):
#   1. setup 토큰 교환이 실행마다 설치 수만큼 성공
#   2. k6 종료 0(threshold 통과)
#   3. consumer group lag 0(배수 대기 최대 120s)
#   4. ClickHouse FINAL 행 수 = summary 의 기대 합계(events·metric points)
#   5. gateway·ingest 로그에 실패 이벤트·토큰 원문 없음
#   6. k6-otel.prom 에 k6 지표가 있다(이름을 evidence/k6-metric-names.txt 에 남긴다). dropped_iterations 는 drop 이 생길 때만
#      나오므로, 응답하지 않는 대상으로 짧게 돌리는 drop 확인 실행을 따로 한다(ClickHouse 대조에는 넣지 않는다).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/tools/loadtest/local"
WORK=$(mkdir -p "${1:?작업 디렉터리를 인자로 준다}" && cd "$1" && pwd)
PROJECT=pulsemetry-loadtest-local
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then JAVA_HOME=$(/usr/libexec/java_home -v 25 2> /dev/null || true); fi
[[ -n "${JAVA_HOME:-}" ]] || { echo "JAVA_HOME(JDK 25)을 정한다"; exit 1; }
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"
COMPOSE=(docker compose -p "$PROJECT" -f "$HERE/compose.yaml")
K6_IMAGE=grafana/k6:1.8.2@sha256:bd6ae8607c0a05d0dc0dc3f5753c464b0929baf083ef0f20b7eed845a3d9e6b4

ENROLLMENT_PORT=48080
INGEST_PORT=44316
GATEWAY_PORT=48082
DB_URL=jdbc:postgresql://localhost:45432/pulsemetry
CLICKHOUSE_URL=http://localhost:48123
KAFKA=localhost:49092
TOPIC=pulsemetry-loadtest-telemetry-ingress
GROUP=pulsemetry-loadtest-telemetry-ingest
COLLECTOR_METRICS=http://127.0.0.1:49404/metrics
HASH_SECRET=loadtest-token-hash-secret
KID=tk-loadtest
CAMPAIGN=local-e2e
INSTALLATIONS=12
PARTITIONS=6

# 이전 실행의 결과를 남기지 않는다 — evidence 는 이번 실행의 것만이다.
rm -rf "$WORK/logs" "$WORK/evidence" "$WORK/archive" "$WORK/k6-input" "$WORK/run.log"
mkdir -p "$WORK/logs" "$WORK/keys" "$WORK/archive" "$WORK/evidence" "$WORK/k6-input"
chmod 700 "$WORK/keys" "$WORK/k6-input"
# 앱의 상대 경로 기본값이 작업 디렉터리에 떨어지게 한다.
cd "$WORK"
exec > >(tee -a "$WORK/run.log") 2>&1

step() { printf '\n=== %s %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }
fail() { printf '실패: %s\n' "$*"; exit 1; }

APPS="enrollment ingest gateway"
set_pid() { eval "PID_$1=$2"; }
pid_of() { eval "echo \${PID_$1:-}"; }

stop_app() {
	local name=$1 pid
	pid=$(pid_of "$1")
	[[ -n "$pid" ]] || return 0
	kill -TERM "$pid" 2>/dev/null || true
	local waited=0
	while kill -0 "$pid" 2>/dev/null; do
		sleep 1; waited=$((waited + 1))
		(( waited < 90 )) || { kill -KILL "$pid"; echo "$name 을 강제 종료했다"; break; }
	done
	set_pid "$name" ""
}

# 종료 코드를 보존한다 — 정리 명령의 상태가 스크립트의 종료 코드를 덮지 않게.
cleanup() {
	local rc=$?
	[[ -n "${TARPIT_PID:-}" ]] && kill "$TARPIT_PID" 2> /dev/null || true
	docker rm -f "$PROJECT-k6" > /dev/null 2>&1 || true
	for name in $APPS; do stop_app "$name" || true; done
	if [[ "${LOADTEST_KEEP:-0}" != 1 ]]; then "${COMPOSE[@]}" down -v --remove-orphans > /dev/null 2>&1 || true; fi
	rm -rf "$LOCK_DIR"
	exit "$rc"
}

# 같은 호스트의 실행은 Compose 프로젝트·컨테이너 이름·포트를 공유한다. 잠금을 잡은 실행만 환경을 만들고 지운다.
# 정리 훅보다 먼저 잡아, 잠금을 못 잡은 실행은 남의 환경을 건드리지 않고 끝난다(macOS 에 flock 이 없어 mkdir 을 쓴다).
LOCK_DIR="${TMPDIR:-/tmp}/$PROJECT.lock"
if ! mkdir "$LOCK_DIR" 2> /dev/null; then
	echo "다른 로컬 E2E 가 실행 중이다(잠금 $LOCK_DIR, pid $(cat "$LOCK_DIR/pid" 2> /dev/null || echo '?')). 끝난 뒤 다시 실행한다 — 프로세스가 없는데 남은 잠금이면 그 디렉터리를 지운다"
	exit 1
fi
echo $$ > "$LOCK_DIR/pid"
trap cleanup EXIT

wait_http() {
	local url=$1 want=${2:-200} tries=0
	until [[ "$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)" == "$want" ]]; do
		sleep 1; tries=$((tries + 1)); (( tries < 120 )) || fail "$url 가 $want 를 돌려주지 않는다"
	done
}

jar_of() { ls "$ROOT/apps/$1/build/libs/"*.jar | grep -v -- '-plain.jar$' | head -1; }

public_keys_json() { printf '{"pulsemetry":{"telemetry-jwt":{"public-key-files":{"%s":"%s"}}}}' "$KID" "$WORK/keys/$KID.pem"; }

start_enrollment() {
	env PULSEMETRY_DB_URL=$DB_URL PULSEMETRY_TOKEN_HASH_SECRET=$HASH_SECRET PULSEMETRY_ADMIN_API_TOKEN=loadtest-admin \
		PULSEMETRY_PUBLIC_BASE_URL=http://localhost:$ENROLLMENT_PORT SERVER_PORT=$ENROLLMENT_PORT MANAGEMENT_SERVER_PORT=49480 \
		PULSEMETRY_TELEMETRY_JWT_ISSUE_ENABLED=true PULSEMETRY_TELEMETRY_JWT_ACTIVE_KID=$KID \
		PULSEMETRY_TELEMETRY_JWT_PRIVATE_KEY_FILE="$WORK/keys/private.pem" SPRING_APPLICATION_JSON="$(public_keys_json)" \
		"$JAVA" -jar "$(jar_of enrollment-api)" > "$WORK/logs/enrollment.log" 2>&1 &
	set_pid enrollment $!
	wait_http "http://localhost:$ENROLLMENT_PORT/api/v1/healthz"
	echo "enrollment(JWT 발급 켬) 기동"
}

start_ingest() {
	env PULSEMETRY_DB_URL=$DB_URL PULSEMETRY_CLICKHOUSE_URL=$CLICKHOUSE_URL PULSEMETRY_ARCHIVE_DIR="$WORK/archive" \
		MANAGEMENT_SERVER_PORT=49416 PULSEMETRY_INGEST_PORT=$INGEST_PORT PULSEMETRY_INGEST_MODE=kafka PULSEMETRY_KAFKA_BOOTSTRAP_SERVERS=$KAFKA \
		PULSEMETRY_KAFKA_TOPIC=$TOPIC PULSEMETRY_KAFKA_CONSUMER_GROUP=$GROUP \
		"$JAVA" -jar "$(jar_of telemetry-ingest)" > "$WORK/logs/ingest.log" 2>&1 &
	set_pid ingest $!
	wait_http "http://localhost:$INGEST_PORT/api/v1/healthz"
	echo "ingest(kafka 모드) 기동"
}

start_gateway() {
	env MANAGEMENT_SERVER_PORT=49482 PULSEMETRY_GATEWAY_PORT=$GATEWAY_PORT PULSEMETRY_KAFKA_BOOTSTRAP_SERVERS=$KAFKA PULSEMETRY_KAFKA_TOPIC=$TOPIC \
		SPRING_APPLICATION_JSON="$(public_keys_json)" \
		"$JAVA" -jar "$(jar_of telemetry-gateway)" > "$WORK/logs/gateway.log" 2>&1 &
	set_pid gateway $!
	wait_http "http://localhost:$GATEWAY_PORT/api/v1/healthz"
	echo "gateway 기동"
}

psql_local() { "${COMPOSE[@]}" exec -T postgres psql -U pulsemetry -d pulsemetry -v ON_ERROR_STOP=1 "$@"; }

clickhouse() { curl -s --data-binary "$1" "$CLICKHOUSE_URL/"; }

# k6 를 dev Job 과 같은 env 로 돈다 — 앱은 호스트(host.docker.internal), collector 는 compose 네트워크.
# 시드 비밀이 0600 이라 호스트 사용자로 실행한다.
run_k6() {
	local label=$1; shift
	docker run --rm --name "$PROJECT-k6" --network "${PROJECT}_default" --add-host host.docker.internal:host-gateway \
		--user "$(id -u):$(id -g)" \
		-v "$ROOT/tools/loadtest/k6:/scripts:ro" -v "$WORK/k6-input:/input:ro" -v "$WORK/evidence:/evidence" \
		-e K6_NO_USAGE_REPORT=true -e K6_OTEL_EXPORTER_TYPE=http -e K6_OTEL_HTTP_EXPORTER_ENDPOINT=otel-collector:4318 \
		-e K6_OTEL_HTTP_EXPORTER_INSECURE=true -e K6_OTEL_METRIC_PREFIX=k6_ -e K6_OTEL_FLUSH_INTERVAL=1s -e K6_OTEL_EXPORT_INTERVAL=2s -e FINAL_FLUSH=3s \
		-e TESTID="local-$label" -e GATEWAY_URL="${K6_GATEWAY_URL:-http://host.docker.internal:$GATEWAY_PORT}" -e ENROLLMENT_URL="http://host.docker.internal:$ENROLLMENT_PORT" \
		-e IDENTITIES_FILE=/input/identities.json -e SEED_SECRET_FILE=/input/seed-secret -e SUMMARY_FILE="/evidence/summary-$label.json" \
		"$@" "$K6_IMAGE" run -q -o opentelemetry /scripts/main.js > "$WORK/logs/k6-$label.log" 2>&1
}

consumer_lag() {
	"${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:49092 --describe --group $GROUP 2> /dev/null \
		| tee "$WORK/evidence/consumer-group.txt" | awk '$2 == "'$TOPIC'" { n += 1; end += $5; if ($6 ~ /^[0-9]+$/) lag += $6; else if ($5 != 0) unknown += 1 }
			END { if (n == 0) print "none"; else if (unknown > 0) print end "/unknown(" unknown ")"; else print end "/" lag }'
}
# LAG 가 숫자가 아니면(커밋한 offset 이 없음) 그 partition 은 판단할 수 없다. 레코드가 있는(LOG-END>0) partition 이면
# 0 으로 세지 않고 unknown 으로 남겨 배수 대기를 계속하고 판정 3을 실패시킨다. 빈 partition(LOG-END=0)만 0 이다.

FAILURES=0
verdict() {
	local ok=$1; shift
	if [[ "$ok" == 1 ]]; then echo "판정 통과: $*"; else echo "판정 실패: $*"; FAILURES=$((FAILURES + 1)); fi
	echo "$([[ "$ok" == 1 ]] && echo PASS || echo FAIL) $*" >> "$WORK/evidence/judgement.txt"
}

step "0. 빌드 — bootJar 셋, 테스트 RSA 키, 임시 시드 비밀"
(cd "$ROOT" && ./gradlew -q :apps:enrollment-api:bootJar :apps:telemetry-ingest:bootJar :apps:telemetry-gateway:bootJar)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/keys/private.pem" 2> /dev/null
openssl pkey -in "$WORK/keys/private.pem" -pubout -out "$WORK/keys/$KID.pem"
openssl rand 32 > "$WORK/k6-input/seed-secret"
chmod 600 "$WORK/k6-input/seed-secret" "$WORK/keys/private.pem"

step "1. Compose — PostgreSQL·ClickHouse·Kafka(6 partition)·OTel Collector"
"${COMPOSE[@]}" down -v --remove-orphans > /dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --wait postgres clickhouse kafka
"${COMPOSE[@]}" up kafka-init --exit-code-from kafka-init
"${COMPOSE[@]}" up -d otel-collector
wait_http "$COLLECTOR_METRICS"
"${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:49092 --describe --topic $TOPIC | tee "$WORK/evidence/topic.txt"

step "2. enrollment 기동(Flyway 가 스키마를 만든다)과 부하 tenant 시드"
start_enrollment
python3 "$ROOT/tools/loadtest/generator/seed_sql.py" --campaign $CAMPAIGN --installations $INSTALLATIONS --partitions $PARTITIONS \
	--secret-file "$WORK/k6-input/seed-secret" --identities-out "$WORK/k6-input/identities.json" > "$WORK/evidence/seed.sql" 2> "$WORK/evidence/seed.txt"
cat "$WORK/evidence/seed.txt"
TENANT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["tenantId"])' "$WORK/k6-input/identities.json")
psql_local -q < "$WORK/evidence/seed.sql"
python3 "$ROOT/tools/loadtest/generator/seed_sql.py" --campaign $CAMPAIGN --installations $INSTALLATIONS --partitions $PARTITIONS \
	--secret-file "$WORK/k6-input/seed-secret" --verify 2> /dev/null | psql_local -At -F ' ' | tee "$WORK/evidence/seed-verify.txt"
grep -q ' f$' "$WORK/evidence/seed-verify.txt" && fail "시드 확인에 틀린 행이 있다"

step "3. ingest(kafka 모드)·Gateway 기동"
start_ingest
start_gateway

step "4. k6 smoke(1 RPS × 60s)"
SMOKE_RC=0
run_k6 smoke -e SCENARIO=smoke || SMOKE_RC=$?
echo "k6 smoke 종료 $SMOKE_RC"

step "5. k6 소형 stair(2→6 RPS, +2, 상승 5s, 유지 20s, 정리 5s)"
STAIR_RC=0
run_k6 stair -e SCENARIO=stair -e START_RPS=2 -e STEP_RPS=2 -e MAX_RPS=6 -e STEP_HOLD=20s -e RAMP=5s -e COOLDOWN=5s || STAIR_RC=$?
echo "k6 stair 종료 $STAIR_RC"

step "6. drop 확인 — 응답하지 않는 대상(호스트 :48099)에 40 RPS × 15s, VU 가 소진되어 iteration 이 버려진다"
# 연결을 받기만 하고 응답하지 않는다. 요청은 k6 timeout(10s)까지 묶이고, 필요한 동시성(40×10)이 maxVUs(80)를 넘어 drop 이 생긴다.
python3 -c 'import socket
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("0.0.0.0", 48099)); s.listen(1024)
held = []
while True:
    held.append(s.accept()[0])' &
TARPIT_PID=$!
DROP_RC=0
K6_GATEWAY_URL=http://host.docker.internal:48099 run_k6 drop -e SCENARIO=hold -e HOLD_RPS=40 -e HOLD_DURATION=15s || DROP_RC=$?
kill "$TARPIT_PID" 2> /dev/null || true; TARPIT_PID=""
DROPPED=$(python3 -c 'import json,sys
try: print(int(json.load(open(sys.argv[1]))["totals"]["droppedIterations"]))
except Exception: print(0)' "$WORK/evidence/summary-drop.json")
STEP_DROPPED=$(python3 -c 'import json,sys
try: print(int(json.load(open(sys.argv[1]))["steps"][0]["droppedIterations"]))
except Exception: print(-1)' "$WORK/evidence/summary-drop.json")
echo "k6 drop 종료 $DROP_RC(실패 threshold 99 가 정상), dropped_iterations ${DROPPED}, summary 단계별 drop ${STEP_DROPPED}"

step "7. 배수 대기(lag 0, 최대 120s)와 k6 지표 수집"
curl -s "$COLLECTOR_METRICS" > "$WORK/evidence/k6-otel.prom"
lag=none; waited=0
while (( waited <= 120 )); do
	lag=$(consumer_lag)
	[[ "$lag" != none && "${lag#*/}" == 0 ]] && break
	sleep 2; waited=$((waited + 2))
done
echo "consumer group log-end 합/lag 합: $lag (${waited}s)"

step "8. 판정"
: > "$WORK/evidence/judgement.txt"
for label in smoke stair; do
	exchanges=$(python3 -c 'import json,sys
try: print(json.load(open(sys.argv[1])).get("tokenExchanges", 0))
except Exception: print(0)' "$WORK/evidence/summary-$label.json")
	verdict $([[ "$exchanges" == "$INSTALLATIONS" ]] && echo 1 || echo 0) "1. $label 토큰 교환 $exchanges/$INSTALLATIONS"
done
verdict $([[ $SMOKE_RC == 0 && $STAIR_RC == 0 ]] && echo 1 || echo 0) "2. k6 종료 smoke=$SMOKE_RC stair=$STAIR_RC"
verdict $([[ "$lag" != none && "${lag#*/}" == 0 ]] && echo 1 || echo 0) "3. consumer lag $lag"

# 4. lag 0 이면 ingest 가 INSERT 를 끝낸 뒤 offset 을 확정했다. FINAL 은 같은 관측을 한 행으로 센다.
read -r EXPECTED_EVENTS EXPECTED_POINTS ACCEPTED < <(python3 -c 'import json,sys
e = p = a = 0
for path in sys.argv[1:]:
    t = json.load(open(path))["totals"]; e += t["expectedEvents"]; p += t["expectedMetricPoints"]; a += t["acceptedRequests"]
print(e, p, a)' "$WORK/evidence/summary-smoke.json" "$WORK/evidence/summary-stair.json")
ACTUAL_EVENTS=$(clickhouse "SELECT count() FROM telemetry_events FINAL WHERE tenant_id = '$TENANT'")
ACTUAL_POINTS=$(clickhouse "SELECT count() FROM telemetry_metric_points FINAL WHERE tenant_id = '$TENANT'")
clickhouse "SELECT 'events' AS t, signal, count() FROM telemetry_events FINAL WHERE tenant_id = '$TENANT' GROUP BY signal
	UNION ALL SELECT 'metric_points', signal, count() FROM telemetry_metric_points FINAL WHERE tenant_id = '$TENANT' GROUP BY signal FORMAT TSV" \
	| tee "$WORK/evidence/clickhouse-counts.tsv"
echo "200 응답 ${ACCEPTED}건 — 기대 events ${EXPECTED_EVENTS}·metric points ${EXPECTED_POINTS}, ClickHouse events ${ACTUAL_EVENTS}·metric points ${ACTUAL_POINTS}" \
	| tee "$WORK/evidence/clickhouse-compare.txt"
if [[ "$ACTUAL_EVENTS" == "$EXPECTED_EVENTS" && "$ACTUAL_POINTS" == "$EXPECTED_POINTS" ]]; then
	verdict 1 "4. ClickHouse 행 수 정확 일치(events $ACTUAL_EVENTS, metric points $ACTUAL_POINTS)"
else
	# 기대값은 어댑터의 정규화 후 기대 출력(*.expected.jsonl)에서 센 행 수이고 payload 는 요청마다 ID 를 새로 만든다 —
	# 합쳐질 행이 없으므로 events·metric points 모두 정확히 같아야 한다. 모자라면 유실·미처리다.
	verdict 0 "4. ClickHouse 행 수 불일치 — events $ACTUAL_EVENTS/$EXPECTED_EVENTS, metric points $ACTUAL_POINTS/$EXPECTED_POINTS"
fi

grep -h 'event=telemetry_' "$WORK/logs/gateway.log" "$WORK/logs/ingest.log" | sed -E 's/.*(event=telemetry_[a-z_]+).*/\1/' | sort | uniq -c \
	> "$WORK/evidence/events.txt" || true
if grep -h -E 'event=telemetry_(permanent_failure|retry_exhausted|commit_failed|publish_failed)|permanent|retry_exhausted' "$WORK/logs/gateway.log" "$WORK/logs/ingest.log" > "$WORK/evidence/failure-lines.txt"; then
	verdict 0 "5. gateway·ingest 로그에 실패 이벤트가 있다($(wc -l < "$WORK/evidence/failure-lines.txt" | tr -d ' ')줄, evidence/failure-lines.txt)"
elif grep -l -E 'eyJ[A-Za-z0-9_-]{10,}\.|ptt_[A-Za-z0-9_-]{20,}|pit_[A-Za-z0-9_-]{20,}' "$WORK/logs/"*.log "$WORK/evidence/"*.json > "$WORK/evidence/leak-files.txt"; then
	verdict 0 "5. 로그·summary 에 토큰 원문이 있다($(tr '\n' ' ' < "$WORK/evidence/leak-files.txt"))"
else
	verdict 1 "5. gateway·ingest 실패 이벤트 없음, 로그·summary 에 토큰 원문 없음"
fi

grep -o -E '^k6_[a-zA-Z0-9_]+' "$WORK/evidence/k6-otel.prom" | sort -u > "$WORK/evidence/k6-metric-names.txt"
missing=""
for pattern in '^k6_http_reqs_total$' '^k6_http_req_duration.*_bucket$' '^k6_dropped_iterations_total$' '^k6_loadtest_target_rps$'; do
	grep -q -E "$pattern" "$WORK/evidence/k6-metric-names.txt" || missing="$missing $pattern"
done
grep -q 'testid="local-stair"' "$WORK/evidence/k6-otel.prom" || missing="$missing testid-label"
(( DROPPED > 0 )) || missing="$missing drop-run-dropped-0"
(( STEP_DROPPED > 0 )) || missing="$missing summary-step-dropped-$STEP_DROPPED"
verdict $([[ -z "$missing" ]] && echo 1 || echo 0) "6. k6-otel.prom 지표$([[ -n "$missing" ]] && echo " 누락:$missing" || echo " 있음") — 이름은 evidence/k6-metric-names.txt"

step "완료 — 판정 실패 ${FAILURES}건"
cat "$WORK/evidence/judgement.txt"
if (( FAILURES > 0 )); then exit 1; fi
