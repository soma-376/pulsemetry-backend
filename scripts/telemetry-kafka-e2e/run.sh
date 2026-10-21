#!/usr/bin/env bash
# 로컬 E2E — 수정하지 않은 telemetryctl 데몬으로 Gateway·Kafka·ingest 경로를 끝까지 확인한다 (docs/telemetry-kafka-e2e.md).
#
#   scripts/telemetry-kafka-e2e/run.sh <작업 디렉터리>
#
# 작업 디렉터리에 로그·키·바이너리·증거가 남는다. 저장소에는 아무것도 쓰지 않는다.
# 필요한 것: Docker(arm64 또는 amd64), JDK 25, Go(telemetryctl 빌드), openssl, curl, python3. macOS 기본 bash(3.2)에서 돈다.
# 환경변수: TELEMETRYCTL_DIR(기본 ../telemetryctl), E2E_KEEP=1 이면 끝난 뒤 컨테이너를 남긴다.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mkdir -p "${1:?작업 디렉터리를 인자로 준다}" && cd "$1" && pwd)
TELEMETRYCTL_DIR=${TELEMETRYCTL_DIR:-$ROOT/../telemetryctl}
PROJECT=pulsemetry-telemetry-kafka-e2e
# 빌드·실행 JDK 는 이미지와 같은 25 다. macOS 면 java_home 으로 찾는다.
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then JAVA_HOME=$(/usr/libexec/java_home -v 25 2> /dev/null || true); fi
[[ -n "${JAVA_HOME:-}" ]] || { echo "JAVA_HOME(JDK 25)을 정한다"; exit 1; }
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"
COMPOSE=(docker compose -p "$PROJECT" -f "$ROOT/scripts/telemetry-kafka-e2e/compose.yaml")

ENROLLMENT_PORT=38080
INGEST_PORT=34316
GATEWAY_PORT=38082
DB_URL=jdbc:postgresql://localhost:35432/pulsemetry
CLICKHOUSE_URL=http://localhost:38123
KAFKA=localhost:39092
TOPIC=pulsemetry-e2e-telemetry-ingress
GROUP=pulsemetry-e2e-telemetry-ingest
HASH_SECRET=e2e-token-hash-secret
KID=tk-e2e
CODE=E2EK-AFKA-TEST
TENANT=0e2e0000-0000-4000-8000-000000000001
MEMBER=0e2e0000-0000-4000-8000-000000000002

mkdir -p "$WORK/logs" "$WORK/keys" "$WORK/bin" "$WORK/archive" "$WORK/evidence" "$WORK/binaries"
export E2E_BIN_DIR="$WORK/bin"
# 앱의 상대 경로 기본값(./binaries 등)이 작업 디렉터리에 떨어지게 한다.
cd "$WORK"
exec > >(tee -a "$WORK/run.log") 2>&1

step() { printf '\n=== %s %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }
fail() { printf '실패: %s\n' "$*"; exit 1; }

# 호스트에서 띄운 앱의 PID. bash 3.2 에는 연관 배열이 없어 이름별 변수로 둔다.
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
		(( waited < 90 )) || { kill -KILL "$pid"; fail "$name 이 90초 안에 끝나지 않았다"; }
	done
	echo "$name 종료(SIGTERM, ${waited}초)"
	set_pid "$name" ""
}

cleanup() {
	for name in $APPS; do stop_app "$name" || true; done
	if [[ "${E2E_KEEP:-0}" != 1 ]]; then "${COMPOSE[@]}" down -v --remove-orphans > /dev/null 2>&1 || true; fi
}
trap cleanup EXIT

wait_http() {
	local url=$1 want=${2:-200} tries=0
	until [[ "$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)" == "$want" ]]; do
		sleep 1; tries=$((tries + 1)); (( tries < 120 )) || fail "$url 가 $want 를 돌려주지 않는다"
	done
}

jar_of() { ls "$ROOT/apps/$1/build/libs/"*.jar | head -1; }

start_enrollment() {
	local label=$1; shift
	env PULSEMETRY_DB_URL=$DB_URL PULSEMETRY_TOKEN_HASH_SECRET=$HASH_SECRET PULSEMETRY_ADMIN_API_TOKEN=e2e-admin \
		PULSEMETRY_PUBLIC_BASE_URL=http://localhost:$ENROLLMENT_PORT SERVER_PORT=$ENROLLMENT_PORT "$@" \
		"$JAVA" -jar "$(jar_of enrollment-api)" > "$WORK/logs/enrollment-$label.log" 2>&1 &
	set_pid enrollment $!
	wait_http "http://localhost:$ENROLLMENT_PORT/api/v1/healthz"
	echo "enrollment($label) 기동"
}

public_keys_json() { printf '{"pulsemetry":{"telemetry-jwt":{"public-key-files":{"%s":"%s"}}}}' "$KID" "$WORK/keys/$KID.pem"; }

start_ingest() {
	local label=$1 mode=$2; shift 2
	env PULSEMETRY_DB_URL=$DB_URL PULSEMETRY_CLICKHOUSE_URL=$CLICKHOUSE_URL PULSEMETRY_ARCHIVE_DIR="$WORK/archive" \
		MANAGEMENT_SERVER_PORT=39416 PULSEMETRY_INGEST_PORT=$INGEST_PORT PULSEMETRY_INGEST_MODE=$mode PULSEMETRY_KAFKA_BOOTSTRAP_SERVERS=$KAFKA \
		PULSEMETRY_KAFKA_TOPIC=$TOPIC PULSEMETRY_KAFKA_CONSUMER_GROUP=$GROUP "$@" \
		"$JAVA" -jar "$(jar_of telemetry-ingest)" > "$WORK/logs/ingest-$label.log" 2>&1 &
	set_pid ingest $!
	wait_http "http://localhost:$INGEST_PORT/api/v1/healthz"
	echo "ingest($label, mode=$mode) 기동"
}

start_gateway() {
	env MANAGEMENT_SERVER_PORT=39482 PULSEMETRY_GATEWAY_PORT=$GATEWAY_PORT PULSEMETRY_KAFKA_BOOTSTRAP_SERVERS=$KAFKA PULSEMETRY_KAFKA_TOPIC=$TOPIC \
		SPRING_APPLICATION_JSON="$(public_keys_json)" \
		"$JAVA" -jar "$(jar_of telemetry-gateway)" > "$WORK/logs/gateway.log" 2>&1 &
	set_pid gateway $!
	wait_http "http://localhost:$GATEWAY_PORT/api/v1/healthz"
	echo "gateway 기동"
}

psql_e2e() { "${COMPOSE[@]}" exec -T postgres psql -U pulsemetry -d pulsemetry -v ON_ERROR_STOP=1 -At "$@"; }

clickhouse() { curl -s --data-binary "$1" "$CLICKHOUSE_URL/"; }

daemon_exec() { "${COMPOSE[@]}" exec -T daemon bash -c "source /tmp/e2e-env.sh; $1"; }

otlp_target() { daemon_exec "echo 'host.docker.internal:$1' > /tmp/e2e-otlp-target"; sleep 1; }

start_daemon() {
	daemon_exec "setsid nohup /opt/e2e-bin/telemetryctl daemon --listen 4318 < /dev/null > /tmp/daemon-$1.log 2>&1 &"
	local tries=0
	until daemon_exec "curl -s -o /dev/null http://127.0.0.1:4318/v1/logs" > /dev/null 2>&1; do
		sleep 1; tries=$((tries + 1)); (( tries < 60 )) || fail "데몬 수신기가 뜨지 않는다"
	done
	echo "데몬($1) 기동"
}

# 프로세스 이름으로 찾는다 — -f 로 명령줄을 찾으면 이 bash -c 자신도 걸린다.
stop_daemon() {
	daemon_exec "pkill -TERM -x telemetryctl || true; for i in \$(seq 1 30); do pgrep -x telemetryctl > /dev/null || exit 0; sleep 1; done; exit 1" \
		|| fail "데몬이 30초 안에 끝나지 않았다"
	echo "데몬 종료(SIGTERM)"
}

# 데몬의 로컬 수신기에 Claude Code 2.1.x 의 로그 두 건(user_prompt·api_request)을 보낸다 — 벤더 도구가 보내는 것과 같은 경로·모양이다
# (libs/telemetry-adapter 의 otlp-v2/claude_code/real 실캡처와 같은 속성). 적재 확인은 api_request(model.response.usage)로 한다 —
# manifest 가 프롬프트 수집을 끄면 데몬이 user_prompt 의 body 를 비우고, 그 행은 generic 으로 적재된다(docs/telemetry-kafka-e2e.md).
otlp_claude_logs() {
	python3 - "$1" <<'PY'
import datetime, json, sys, time, uuid
session = sys.argv[1]
now = time.time_ns()
stamp = datetime.datetime.fromtimestamp(now / 1e9, datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
prompt_id = str(uuid.uuid4())

def record(name, offset, attrs):
    values = [{"key": k, "value": v if isinstance(v, dict) else {"stringValue": v}} for k, v in attrs.items()]
    return {"timeUnixNano": str(now + offset), "observedTimeUnixNano": str(now + offset),
            "body": {"stringValue": "claude_code." + name}, "attributes": values}

common = {"session.id": session, "event.timestamp": stamp, "prompt.id": prompt_id}
records = [
    record("user_prompt", 0, {**common, "event.name": "user_prompt", "event.sequence": "1", "prompt_length": "7", "prompt": "e2e-prompt-text"}),
    record("api_request", 1000, {**common, "event.name": "api_request", "event.sequence": "2", "model": "claude-haiku-4-5-20251001",
                                 "input_tokens": "1066", "output_tokens": "28", "cache_read_tokens": "0", "cache_creation_tokens": "0",
                                 "cost_usd": {"doubleValue": 0.001206}, "duration_ms": "1056", "ttft_ms": "738",
                                 "request_id": "req_e2e" + uuid.uuid4().hex[:20], "client_request_id": str(uuid.uuid4())}),
]
print(json.dumps({"resourceLogs": [{"resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "claude-code"}},
                                                                {"key": "service.version", "value": {"stringValue": "2.1.290"}}]},
                                    "scopeLogs": [{"scope": {"name": "com.anthropic.claude_code.events", "version": "2.1.290"},
                                                   "logRecords": records}]}]}, separators=(",", ":")))
PY
}

send_log() {
	local session=$1 body code
	body=$(otlp_claude_logs "$session")
	code=$(daemon_exec "TOKEN=\$(secret-tool lookup service pulsemetry username local-ingest); curl -s -o /dev/null -w '%{http_code}' -H \"Authorization: Bearer \$TOKEN\" -H 'X-Pulsemetry-Local: 1' -H 'Content-Type: application/json' --data-binary '$body' http://127.0.0.1:4318/v1/logs")
	echo "데몬 수신기 응답: $code (session=$session)"
	[[ "$code" == 200 ]] || fail "데몬 수신기가 받지 않았다"
}

wait_row() {
	local session=$1 tries=0
	until [[ "$(clickhouse "SELECT count() FROM telemetry_events FINAL WHERE session_id = '$session' AND event_type = 'model.response.usage'")" -ge 1 ]]; do
		sleep 2; tries=$((tries + 1)); (( tries < 45 )) || fail "ClickHouse 에 $session 행이 없다"
	done
	# 같은 push 의 두 행(받은 시각이 같다)을 함께 남긴다 — user_prompt 는 body 가 비워져 session_id 가 없다.
	clickhouse "SELECT session_id, original_name, event_type, mapping_status, tenant_id, installation_id, toString(received_time) FROM telemetry_events FINAL WHERE tenant_id = '$TENANT' AND received_time IN (SELECT received_time FROM telemetry_events FINAL WHERE session_id = '$session') ORDER BY original_name FORMAT TSV" \
		| tee -a "$WORK/evidence/clickhouse-rows.tsv"
}

step "0. 빌드 — bootJar 셋, 수정하지 않은 telemetryctl(linux), 키쌍"
(cd "$ROOT" && ./gradlew -q :apps:enrollment-api:bootJar :apps:telemetry-ingest:bootJar :apps:telemetry-gateway:bootJar)
git -C "$TELEMETRYCTL_DIR" status --porcelain | grep -q . && fail "telemetryctl 작업 트리에 변경이 있다 — 수정하지 않은 빌드가 아니다"
TELEMETRYCTL_REV=$(git -C "$TELEMETRYCTL_DIR" rev-parse HEAD)
arch=$(docker info --format '{{.Architecture}}'); [[ "$arch" == aarch64 ]] && goarch=arm64 || goarch=amd64
(cd "$TELEMETRYCTL_DIR" && GOOS=linux GOARCH=$goarch CGO_ENABLED=0 go build -o "$WORK/bin/telemetryctl" ./cmd/telemetryctl)
echo "telemetryctl $TELEMETRYCTL_REV (linux/$goarch, 수정 없음)" | tee "$WORK/evidence/telemetryctl.txt"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/keys/private.pem" 2> /dev/null
openssl pkey -in "$WORK/keys/private.pem" -pubout -out "$WORK/keys/$KID.pem"

step "1. Compose — PostgreSQL·ClickHouse·Kafka(KRaft)·topic·데몬 컨테이너"
"${COMPOSE[@]}" down -v --remove-orphans > /dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --wait postgres clickhouse kafka
"${COMPOSE[@]}" up kafka-init --exit-code-from kafka-init
"${COMPOSE[@]}" up -d --build daemon
"${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:39092 --describe --topic $TOPIC \
	| tee "$WORK/evidence/topic.txt"

step "2. enrollment(JWT 발급 꺼짐 — ptt_) 기동과 시드"
start_enrollment ptt
code_hash=$(printf '%s' "$CODE" | shasum -a 256 | cut -d' ' -f1)
psql_e2e <<SQL
INSERT INTO enrollment.tenants (id, name) VALUES ('$TENANT', 'E2E 조직');
INSERT INTO enrollment.members (id, tenant_id, email, role, status) VALUES ('$MEMBER', '$TENANT', 'e2e@example.com', 'owner', 'active');
INSERT INTO enrollment.invitations (tenant_id, target_member_id, created_by_member_id, code_hash, expires_at)
	VALUES ('$TENANT', '$MEMBER', '$MEMBER', '$code_hash', now() + interval '1 day');
INSERT INTO enrollment.manifests (tenant_id, version, manifest, is_active, created_by_member_id, activated_at) VALUES ('$TENANT', 1, '{
  "schema_version": 1, "config_revision": 1,
  "otlp": {"endpoint": "http://localhost:38090", "protocol": "http/protobuf", "compression": "none", "timeout_ms": 10000},
  "signals": {"logs": true, "metrics": true, "traces": true},
  "privacy": {"collect_user_prompts": false, "collect_assistant_responses": false, "collect_tool_details": false,
              "collect_tool_content": false, "collect_user_email": false, "collect_raw_api_bodies": false}
}'::jsonb, true, '$MEMBER', now());
SQL

step "3. ingest(dual — 기존 HTTP + Consumer)·Gateway 기동, OTLP 대상은 기존 HTTP ingest"
start_ingest dual dual PULSEMETRY_TOKEN_HASH_SECRET=$HASH_SECRET SPRING_APPLICATION_JSON="$(public_keys_json)"
start_gateway
otlp_target $INGEST_PORT

step "4. telemetryctl enroll 과 데몬 기동 — 서버 URL 은 /api 접두(클라이언트가 /v1/... 를 붙인다)"
daemon_exec "/opt/e2e-bin/telemetryctl enroll --invite $CODE --server http://localhost:$ENROLLMENT_PORT/api" | tee "$WORK/evidence/enroll.txt"
start_daemon phase1

step "5. 전환 전 — ptt_ 로 기존 HTTP ingest 에 적재"
send_log e2e-before-switch
wait_row e2e-before-switch

step "6. 공개키 선배포 뒤 JWT 발급 전환, ALB 대상을 Gateway 로 — 데몬은 ptt_ 401 → 재발급 → JWT 재전송"
stop_app enrollment
start_enrollment jwt PULSEMETRY_TELEMETRY_JWT_ISSUE_ENABLED=true PULSEMETRY_TELEMETRY_JWT_ACTIVE_KID=$KID \
	PULSEMETRY_TELEMETRY_JWT_PRIVATE_KEY_FILE="$WORK/keys/private.pem" SPRING_APPLICATION_JSON="$(public_keys_json)"
otlp_target $GATEWAY_PORT
send_log e2e-after-switch
wait_row e2e-after-switch

step "7. ingest 를 kafka 전용으로 — 해시 키·공개키 없이 뜨고 OTLP 경로는 404"
stop_app ingest
start_ingest kafka kafka
otlp_404=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' http://localhost:$INGEST_PORT/v1/logs)
echo "kafka 모드 ingest 의 /v1/logs: $otlp_404" | tee -a "$WORK/evidence/modes.txt"
[[ "$otlp_404" == 404 ]] || fail "kafka 모드에서 OTLP 경로가 열려 있다"

step "8. 짧은 수명(60초) JWT — 만료 뒤 401 → 재발급 → 재전송"
stop_app enrollment
start_enrollment short PULSEMETRY_TELEMETRY_JWT_ISSUE_ENABLED=true PULSEMETRY_TELEMETRY_JWT_ACTIVE_KID=$KID \
	PULSEMETRY_TELEMETRY_JWT_PRIVATE_KEY_FILE="$WORK/keys/private.pem" SPRING_APPLICATION_JSON="$(public_keys_json)" \
	PULSEMETRY_TELEMETRYJWT_LIFETIME=PT60S  # pulsemetry.telemetry-jwt.lifetime
# 데몬은 토큰을 메모리에만 둔다 — 다시 띄워 짧은 토큰을 새로 받게 한다.
stop_daemon
start_daemon phase2
send_log e2e-short-first
wait_row e2e-short-first
echo "만료(60초) + 시계 오차(30초)를 기다린다"
sleep 95
send_log e2e-short-expired
wait_row e2e-short-expired

step "9. 증거 — 데몬·Gateway·ingest 로그, 자격 기록, 토큰 누출 검사"
daemon_exec "cat /tmp/daemon-phase1.log /tmp/daemon-phase2.log" > "$WORK/logs/daemon.log"
grep -E 'forward:' "$WORK/logs/daemon.log" | tee "$WORK/evidence/daemon-forward.txt" || true
grep -h 'event=telemetry_' "$WORK/logs/gateway.log" "$WORK/logs/ingest-"*.log | tee "$WORK/evidence/events.txt" || true
psql_e2e -c "SELECT token_format, signing_kid, revoke_reason, (revoked_at IS NOT NULL) AS revoked,
	extract(epoch FROM expires_at - issued_at)::int AS lifetime_s FROM enrollment.telemetry_tokens ORDER BY issued_at" \
	| tee "$WORK/evidence/telemetry-tokens.txt"
consumed=$("${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:39092 --describe --group $GROUP 2> /dev/null \
	| tee "$WORK/evidence/consumer-group.txt" | awk '$2 == "'$TOPIC'" { lag += $6; end += $5 } END { print end "/" lag }')
echo "Consumer group 의 log-end 합/lag 합: $consumed"

# 기대한 일이 실제로 일어났는지 본다 — 하나라도 없으면 실패다.
expect() { grep -q -E "$2" "$1" || fail "기대한 증거가 없다: $3"; echo "확인: $3"; }
expect "$WORK/logs/gateway.log" 'event=telemetry_gateway_rejected stage=authenticate reason=jwt_malformed status=401' "Gateway 가 ptt_ 를 401(jwt_malformed)로 거부"
expect "$WORK/logs/gateway.log" 'event=telemetry_gateway_rejected stage=authenticate reason=jwt_expired status=401' "Gateway 가 만료 JWT 를 401(jwt_expired)로 거부"
expect "$WORK/logs/daemon.log" '상위 인증 거부 \(HTTP 401\) — 토큰 갱신 후 재시도' "데몬이 401 뒤 토큰을 갱신하고 재시도"
expect "$WORK/evidence/telemetry-tokens.txt" '^jwt\|tk-e2e\|rotated\|t\|10800$' "3시간 JWT 발급 뒤 회전(rotated)"
expect "$WORK/evidence/telemetry-tokens.txt" '^jwt\|tk-e2e\|rotated\|t\|60$' "60초 JWT 발급 뒤 만료 재발급으로 회전"
expect "$WORK/evidence/telemetry-tokens.txt" '^opaque\|\|rotated\|t\|' "전환 전 ptt_ 의 회전"
[[ "${consumed#*/}" == 0 ]] || fail "Consumer lag 이 0 이 아니다: $consumed"
if grep -h 'event=telemetry_\(permanent_failure\|retry_exhausted\|commit_failed\|publish_failed\)' "$WORK/logs/"*.log; then fail "실패 이벤트가 있다"; fi
echo "확인: 실패 이벤트(permanent·retry_exhausted·commit·publish) 없음"
if grep -E 'eyJ[A-Za-z0-9_-]{10,}\.|ptt_[A-Za-z0-9_-]{20,}|pit_[A-Za-z0-9_-]{20,}' "$WORK/logs/"*.log; then fail "로그에 토큰 원문이 있다"; fi
echo "확인: 로그 전체에 토큰 원문 없음" | tee -a "$WORK/evidence/leak.txt"

step "10. SIGTERM — Gateway·ingest 가 graceful 로 끝난다"
stop_app gateway
stop_app ingest
grep -h -E 'Graceful shutdown complete|Commencing graceful shutdown' "$WORK/logs/gateway.log" "$WORK/logs/ingest-kafka.log" \
	| tee "$WORK/evidence/shutdown.txt"

step "완료"
