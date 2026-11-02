#!/usr/bin/env python3
"""격리된 로컬 인프라와 다섯 앱 이미지의 HTTP·Kafka·집계·보존 삭제 smoke."""
import argparse
import base64
import datetime as dt
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import sys
import time
import uuid
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
WORK = None
PROJECT = None
IMAGE_PREFIX = None
IMAGE_TAG = None
APPS = ['enrollment-api', 'telemetry-ingest', 'telemetry-gateway', 'dashboard-api', 'retention-worker']
COMPOSE = []


def call(args, data=None):
    return subprocess.run(args, input=data, check=True, capture_output=True).stdout.decode().strip()


def compose(*args, data=None):
    return call(COMPOSE + list(args), data)


def sql(query):
    return compose('exec', '-T', 'postgres', 'psql', '-U', 'pulsemetry', '-d', 'pulsemetry', '-v', 'ON_ERROR_STOP=1', '-At', data=query.encode())


def ch(query):
    return compose('exec', '-T', 'clickhouse', 'clickhouse-client', '--query', query)


def endpoint(service, port):
    return f'http://{service}:{port}'


def request(url, method='GET', body=None, token=None, headers=None):
    headers = dict(headers or {})
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    program = '''import base64,json,sys,urllib.request,urllib.error
x=json.load(sys.stdin)
req=urllib.request.Request(x['url'],data=base64.b64decode(x['body']) if x['body'] is not None else None,headers=x['headers'],method=x['method'])
try:
 r=urllib.request.urlopen(req,timeout=40)
except urllib.error.HTTPError as e:
 r=e
with r:
 print(json.dumps([r.status,base64.b64encode(r.read()).decode(),dict(r.headers)]))
'''
    payload = {'url': url, 'method': method, 'headers': headers, 'body': base64.b64encode(body).decode() if body is not None else None}
    result = json.loads(compose('exec', '-T', 'probe', 'python', '-c', program, data=json.dumps(payload).encode()))
    return result[0], base64.b64decode(result[1]), result[2]


def wait_http(service, port, path='/api/v1/healthz'):
    url = endpoint(service, port) + path
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        try:
            if request(url)[0] == 200:
                print(service + ': HTTP ready', flush=True)
                return
        except (OSError, subprocess.CalledProcessError):
            pass
        time.sleep(1)
    raise RuntimeError(service + ': readiness timeout')


def prepare():
    WORK.mkdir(mode=0o700, exist_ok=False)
    (WORK / 'keys').mkdir()
    for key in ('user', 'telemetry'):
        call(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(WORK / f'keys/{key}-private.pem')])
        call(['openssl', 'pkey', '-in', str(WORK / f'keys/{key}-private.pem'), '-pubout', '-out', str(WORK / f'keys/{key}-public.pem')])
        (WORK / f'keys/{key}-private.pem').chmod(0o644)
    state = {name: str(uuid.uuid4()) for name in ('tenant', 'member', 'session', 'other_tenant')}
    state['hash_secret'] = secrets.token_hex(32)
    state['admin_secret'] = secrets.token_hex(32)
    (WORK / 'state.json').write_text(json.dumps(state))
    (WORK / 'state.json').chmod(0o600)
    db = {'spring.datasource.url': 'jdbc:postgresql://postgres:5432/pulsemetry', 'spring.datasource.username': 'pulsemetry', 'spring.datasource.password': 'pulsemetry'}
    auth = {'pulsemetry.user-auth.enabled': True, 'pulsemetry.user-auth.issuer': 'http://localhost:8080', 'pulsemetry.user-auth.audience': 'pulsemetry-web', 'pulsemetry.user-auth.public-key-files': {'local': '/keys/user-public.pem'}, 'pulsemetry.user-auth.allowed-origins': ['http://localhost:3000']}
    keys = {'pulsemetry.telemetry-jwt.public-key-files': {'smoke': '/keys/telemetry-public.pem'}}
    kafka = {'pulsemetry.telemetry.kafka.bootstrap-servers': 'kafka:29092', 'pulsemetry.telemetry.kafka.topic': 'proj306-ingress', 'pulsemetry.telemetry.kafka.consumer-group': 'proj306-ingest', 'pulsemetry.telemetry.kafka.security': 'plaintext'}
    config = {}
    config['enrollment-api'] = {**db, **auth, **keys,
        'pulsemetry.user-auth.active-kid': 'local', 'pulsemetry.user-auth.private-key-file': '/keys/user-private.pem',
        'pulsemetry.user-auth.allowed-redirect-uris': ['http://localhost:3000/auth/callback'],
        'pulsemetry.admin.api-token': state['admin_secret'], 'pulsemetry.token-hash-secret': state['hash_secret'],
        'pulsemetry.management.enabled': True, 'pulsemetry.management.response-encryption-key': base64.b64encode(secrets.token_bytes(32)).decode(),
        'pulsemetry.management.onboarding-otlp-endpoint': 'http://localhost:4316',
        'pulsemetry.management.invitation-accept-url': 'http://localhost:3000/login', 'pulsemetry.management.installation-page-url': 'http://localhost:3000/install',
        'pulsemetry.telemetry-jwt.issue-enabled': True, 'pulsemetry.telemetry-jwt.active-kid': 'smoke', 'pulsemetry.telemetry-jwt.private-key-file': '/keys/telemetry-private.pem',
        'pulsemetry.mail.enabled': True, 'pulsemetry.mail.provider': 'smtp', 'pulsemetry.mail.from': 'smoke@example.test',
        'pulsemetry.mail.encryption-key': base64.b64encode(secrets.token_bytes(32)).decode(), 'pulsemetry.mail.dispatch-interval': '1s',
        'pulsemetry.mail.retry-interval': '5s', 'pulsemetry.mail.max-attempts': 3, 'pulsemetry.mail.send-timeout': '5s',
        'pulsemetry.mail.smtp.host': 'mailpit', 'pulsemetry.mail.smtp.port': 1025, 'pulsemetry.mail.smtp.username': 'smoke', 'pulsemetry.mail.smtp.password': 'smoke', 'pulsemetry.mail.smtp.starttls': False}
    config['telemetry-ingest'] = {**db, **keys, **kafka, 'pulsemetry.token-hash-secret': state['hash_secret'], 'pulsemetry.telemetry.ingest.mode': 'dual', 'pulsemetry.telemetry.archive.type': 'file', 'pulsemetry.telemetry.archive.dir': '/tmp/archive', 'pulsemetry.telemetry.clickhouse.url': 'http://clickhouse:8123', 'pulsemetry.telemetry.ops.enabled': True}
    config['telemetry-gateway'] = {**keys, **kafka}
    dash = {**auth, 'pulsemetry.management.enabled': True, 'pulsemetry.mail.enabled': True}
    values = {'retry-after': '2s', 'snapshot.build-timeout': '30s', 'snapshot.purge-grace': '1m', 'snapshot.max-concurrent-builds': 4, 'snapshot.max-copy-rows': 100000, 'snapshot.max-copy-bytes': 67108864, 'snapshot.cleanup-interval': '1m', 'members.idle-days': 14, 'ingest.window': '15m', 'ingest.delayed-after': '5m', 'ingest.down-after': '24h', 'completeness.settle-after': '1h', 'seats.stale-after': '26h', 'alerts.evaluation-interval': '1m', 'alerts.lease': '10m'}
    for mode in ('source', 'cache'):
        values.update({f'rds.{mode}.url': db['spring.datasource.url'], f'rds.{mode}.username': 'pulsemetry_dashboard_' + mode, f'rds.{mode}.password': 'local-dashboard-' + mode,
            f'clickhouse.{mode}.url': 'http://clickhouse:8123', f'clickhouse.{mode}.database': 'default' if mode == 'source' else 'dashboard_cache',
            f'clickhouse.{mode}.username': 'pulsemetry_dashboard_' + mode, f'clickhouse.{mode}.password': 'local-dashboard-' + mode, f'clickhouse.{mode}.query-timeout': '10s'})
    values.update({'clickhouse.source.max-result-rows': 100000, 'clickhouse.source.max-result-bytes': 67108864})
    dash.update({'pulsemetry.dashboard.' + key: value for key, value in values.items()})
    config['dashboard-api'] = dash
    config['retention-worker'] = {**db, 'pulsemetry.retention.clickhouse.url': 'http://clickhouse:8123', 'pulsemetry.retention.clickhouse.database': 'default', 'pulsemetry.retention.clickhouse.timeout': '30s', 'pulsemetry.retention.drain.timeout': '10s', 'pulsemetry.retention.drain.poll-interval': '50ms', 'pulsemetry.retention.max-passes': 3, 'pulsemetry.retention.requests.lease': '10m', 'pulsemetry.retention.requests.max-runs': 3}
    services = {
        'postgres': {'image': 'postgres:16-alpine', 'environment': {'POSTGRES_DB': 'pulsemetry', 'POSTGRES_USER': 'pulsemetry', 'POSTGRES_PASSWORD': 'pulsemetry'}, 'healthcheck': {'test': ['CMD-SHELL', 'pg_isready -U pulsemetry -d pulsemetry'], 'interval': '2s', 'timeout': '3s', 'retries': 30}},
        'clickhouse': {'image': 'clickhouse/clickhouse-server:24.8-alpine', 'environment': {'CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT': '1'}, 'healthcheck': {'test': ['CMD-SHELL', 'wget -qO- http://127.0.0.1:8123/ping | grep -q Ok'], 'interval': '2s', 'timeout': '3s', 'retries': 30}, 'ulimits': {'nofile': {'soft': 262144, 'hard': 262144}}},
        'kafka': {'image': 'apache/kafka:3.9.2', 'environment': {'KAFKA_NODE_ID': '1', 'KAFKA_PROCESS_ROLES': 'broker,controller', 'KAFKA_LISTENERS': 'INTERNAL://:29092,CONTROLLER://:9093', 'KAFKA_ADVERTISED_LISTENERS': 'INTERNAL://kafka:29092', 'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP': 'INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT', 'KAFKA_INTER_BROKER_LISTENER_NAME': 'INTERNAL', 'KAFKA_CONTROLLER_LISTENER_NAMES': 'CONTROLLER', 'KAFKA_CONTROLLER_QUORUM_VOTERS': '1@localhost:9093', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR': '1', 'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR': '1', 'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR': '1', 'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS': '0', 'KAFKA_AUTO_CREATE_TOPICS_ENABLE': 'false', 'KAFKA_MESSAGE_MAX_BYTES': '13631488', 'KAFKA_REPLICA_FETCH_MAX_BYTES': '13631488'}, 'healthcheck': {'test': ['CMD-SHELL', '/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:29092 > /dev/null 2>&1'], 'interval': '3s', 'timeout': '10s', 'retries': 30}},
        'mailpit': {'image': 'axllent/mailpit:v1.27', 'environment': {'MP_SMTP_AUTH_ACCEPT_ANY': '1', 'MP_SMTP_AUTH_ALLOW_INSECURE': '1'}},
    }
    mounts = {'enrollment-api': ['user-private', 'user-public', 'telemetry-private', 'telemetry-public'], 'dashboard-api': ['user-public'], 'telemetry-ingest': ['telemetry-public'], 'telemetry-gateway': ['telemetry-public'], 'retention-worker': []}
    for app in APPS:
        services[app] = {'image': f'{IMAGE_PREFIX}/{app}:{IMAGE_TAG}', 'pull_policy': 'never', 'environment': {'SPRING_APPLICATION_JSON': json.dumps(config[app])}, 'volumes': [f'{WORK}/keys/{key}.pem:/keys/{key}.pem:ro' for key in mounts[app]], 'mem_limit': '1536m'}
    services['probe'] = {'image': 'python:3.13-slim', 'init': True, 'command': ['sleep', 'infinity']}
    content = {'services': services, 'networks': {'default': {'internal': True}}}
    (WORK / 'compose.json').write_text(json.dumps(content, indent=2))
    (WORK / 'compose.json').chmod(0o600)


def infra():
    print(compose('up', '-d', '--wait', 'postgres', 'clickhouse', 'kafka', 'mailpit', 'probe'), flush=True)
    print(compose('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'kafka:29092', '--create', '--topic', 'proj306-ingress', '--partitions', '6', '--replication-factor', '1', '--config', 'max.message.bytes=13631488', '--config', 'retention.ms=259200000'), flush=True)
    compose('up', '-d', 'enrollment-api')
    wait_http('enrollment-api', 8080)


def state():
    return json.loads((WORK / 'state.json').read_text())


def save_state(value):
    (WORK / 'state.json').write_text(json.dumps(value))


def access_token():
    s = state()
    now = int(time.time())
    claims = {'iss': 'http://localhost:8080', 'aud': ['pulsemetry-web'], 'sub': s['member'], 'tenant_id': s['tenant'], 'role': 'owner', 'sid': s['session'], 'manifest_revision': 0, 'iat': now, 'exp': now + 300, 'jti': str(uuid.uuid4())}
    def b64(value):
        return base64.urlsafe_b64encode(value).rstrip(b'=')
    material = b64(json.dumps({'alg': 'RS256', 'typ': 'JWT', 'kid': 'local'}).encode()) + b'.' + b64(json.dumps(claims).encode())
    signature = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(WORK / 'keys/user-private.pem')], input=material, capture_output=True, check=True).stdout
    return (material + b'.' + b64(signature)).decode()


def api(label, service, port, path, expected=200, method='GET', body=None, auth=True, headers=None):
    response = request(endpoint(service, port) + path, method, body, access_token() if auth else None, headers)
    if response[0] != expected:
        (WORK / 'failed-response.bin').write_bytes(response[1])
        raise RuntimeError(f'{label}: expected {expected}, got {response[0]}')
    print(f'{label}: HTTP {response[0]}', flush=True)
    return json.loads(response[1]) if response[1] else None


def seed_and_start():
    s = state()
    sql(f"""INSERT INTO enrollment.tenants(id,name,slug) VALUES ('{s['tenant']}','Container smoke','container-smoke'), ('{s['other_tenant']}','Retention guard','retention-guard');
INSERT INTO enrollment.members(id,tenant_id,email,role,status) VALUES ('{s['member']}','{s['tenant']}','owner@example.test','owner','active');
INSERT INTO enrollment.user_sessions(id,member_id,manifest_revision,created_at,expires_at) VALUES ('{s['session']}','{s['member']}',0,now(),now()+interval '1 day');
CREATE ROLE pulsemetry_dashboard_source LOGIN PASSWORD 'local-dashboard-source';
CREATE ROLE pulsemetry_dashboard_cache LOGIN PASSWORD 'local-dashboard-cache';
GRANT CREATE ON DATABASE pulsemetry TO pulsemetry_dashboard_cache;
CREATE SCHEMA dashboard_cache AUTHORIZATION pulsemetry_dashboard_cache;
GRANT USAGE ON SCHEMA enrollment,telemetry_ops TO pulsemetry_dashboard_source;
GRANT SELECT ON ALL TABLES IN SCHEMA enrollment,telemetry_ops TO pulsemetry_dashboard_source;
ALTER DEFAULT PRIVILEGES FOR ROLE pulsemetry IN SCHEMA enrollment,telemetry_ops GRANT SELECT ON TABLES TO pulsemetry_dashboard_source;
GRANT USAGE ON SCHEMA telemetry_ops TO pulsemetry_dashboard_cache;
GRANT SELECT ON telemetry_ops.tenant_retention_boundary TO pulsemetry_dashboard_cache;""")
    compose('up', '-d', 'telemetry-ingest', 'telemetry-gateway')
    wait_http('telemetry-ingest', 4316)
    wait_http('telemetry-gateway', 8082)
    for query in ["CREATE DATABASE dashboard_cache", "CREATE USER pulsemetry_dashboard_source IDENTIFIED BY 'local-dashboard-source'", "CREATE USER pulsemetry_dashboard_cache IDENTIFIED BY 'local-dashboard-cache'", "GRANT SELECT ON default.* TO pulsemetry_dashboard_source", "GRANT SELECT ON default.* TO pulsemetry_dashboard_cache", "GRANT ALL ON dashboard_cache.* TO pulsemetry_dashboard_cache"]:
        ch(query)
    compose('up', '-d', 'dashboard-api')
    wait_http('dashboard-api', 8081)


def management():
    s = state()
    base = '/api/v1/organizations/' + s['tenant']
    kwargs = {'service': 'enrollment-api', 'port': 8080, 'path': base + '/teams', 'expected': 201, 'method': 'POST', 'body': {'teamName': 'Smoke team'}, 'headers': {'Idempotency-Key': 'smoke-team-create'}}
    team = api('team create', **kwargs)
    assert api('team idempotent replay', **kwargs) == team
    kwargs.update(expected=409, body={'teamName': 'Conflicting team'})
    api('team idempotency conflict', **kwargs)
    assert sql(f"SELECT count(*) FROM enrollment.teams WHERE tenant_id='{s['tenant']}';") == '1'
    api('invalid team rollback', 'enrollment-api', 8080, base + '/teams', 400, 'POST', {'teamName': ' '}, headers={'Idempotency-Key': 'smoke-invalid-team'})
    assert sql("SELECT count(*) FROM enrollment.management_commands WHERE idempotency_key='smoke-invalid-team';") == '0'
    policy = api('policy initialize', 'enrollment-api', 8080, base + '/collection-policy', method='PUT', body={'expectedVersion': 0, 'collectRawContent': False})
    assert policy['version'] == 1 and policy['existingInstallationsUpdated'] is False
    s['policy'] = policy
    kwargs = {'service': 'enrollment-api', 'port': 8080, 'path': base + '/invitations/batch', 'method': 'POST', 'body': {'invitations': [{'email': 'member@example.test', 'role': 'member', 'teamId': team['teamId']}]}, 'headers': {'Idempotency-Key': 'smoke-invite-member'}}
    invitation = api('invitation + outbox', **kwargs)
    assert invitation['results'][0]['status'] == 'issued'
    assert api('invitation idempotent replay', **kwargs) == invitation
    enrolled = api('enroll JWT', 'enrollment-api', 8080, '/api/v1/enroll', 201, 'POST', {'code': invitation['results'][0]['code'], 'platform': 'linux', 'architecture': 'arm64', 'hostname': 'container-smoke', 'client_version': '0.1.0'}, auth=False)
    assert set(enrolled) == {'installation_id', 'installation_token', 'telemetry_token', 'manifest'}
    s['enrolled'] = enrolled
    s['team'] = team
    save_state(s)
    for _ in range(25):
        if sql("SELECT count(*) FROM enrollment.mail_outbox WHERE status='sent' AND encrypted_body IS NULL;") == '1':
            break
        time.sleep(1)
    else:
        raise RuntimeError('SMTP outbox did not reach sent')
    assert sql('SELECT count(*) FROM enrollment.mail_outbox;') == '1'
    messages = api('Mailpit local delivery', 'mailpit', 8025, '/api/v1/messages', auth=False)
    assert messages['total'] == 1
    assert api('dashboard auth required', 'dashboard-api', 8081, base + '/teams', 401, auth=False)
    teams = api('dashboard team directory', 'dashboard-api', 8081, base + '/teams')
    assert any(item['teamId'] == team['teamId'] for item in teams['teams']['items'])
    (WORK / 'management-evidence.json').write_text(json.dumps({'team_id': team['teamId'], 'idempotent_replays': 2, 'conflict_http': 409, 'invalid_http': 400, 'invalid_command_rows': 0, 'outbox_sent': 1, 'mailpit_messages': 1, 'enroll_http': 201, 'enroll_response_keys': sorted(enrolled), 'dashboard_directory_http': 200}, indent=2))


def telemetry():
    s = state()
    spec = importlib.util.spec_from_file_location('observability_smoke', ROOT / 'scripts/observability-smoke/smoke.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    token_path = WORK / 'telemetry-token'
    token_path.write_text(s['enrolled']['telemetry_token'])
    token_path.chmod(0o600)
    class Client:
        def request(self, url, data=None, headers=None):
            return request(url, 'POST' if data is not None else 'GET', data, headers=headers)[:2]
    now = dt.datetime.now(dt.timezone.utc).isoformat()
    for name, receiver, port, source in [('http', 'telemetry-ingest', 4316, now), ('kafka', 'telemetry-gateway', 8082, now), ('old', 'telemetry-ingest', 4316, '2025-01-01T00:00:00Z')]:
        args = SimpleNamespace(gateway=endpoint(receiver, port), gateway_metrics=endpoint('telemetry-gateway', 9404), ingest_metrics=endpoint('telemetry-ingest', 9404), clickhouse=endpoint('clickhouse', 8123), database='default', tenant=uuid.UUID(s['tenant']), installation=uuid.UUID(s['enrolled']['installation_id']), run_id=uuid.uuid4(), source_time=source, token_file=token_path, output=WORK / ('telemetry-' + name), timeout=45, provenance=[f'{IMAGE_PREFIX}/{receiver}:{IMAGE_TAG}', receiver])
        assert module.run(args, Client()) == 0, 'OTLP ' + name
        print('OTLP logs ' + name + ': accepted + independent observation ID/readback PASS', flush=True)
    payload = json.loads((ROOT / 'libs/telemetry-adapter/src/test/resources/otlp-v2/claude_code/synthetic/metrics.otlp.jsonl').read_text().splitlines()[0])
    metrics = payload['resourceMetrics'][0]['scopeMetrics'][0]['metrics']
    metric = metrics[0]
    point = metric['sum']['dataPoints'][0]
    metric['sum']['dataPoints'] = []
    for stamp in (int(time.time()) * 1000000000, 1735689600000000000):
        p = json.loads(json.dumps(point))
        p.update(timeUnixNano=str(stamp), startTimeUnixNano=str(stamp - 1000000000), asInt='10')
        p['attributes'] = [a for a in p['attributes'] if a['key'] != 'user.id']
        metric['sum']['dataPoints'].append(p)
    metrics[:] = [metric]
    status, _, _ = request(endpoint('telemetry-gateway', 8082) + '/v1/metrics', 'POST', payload, token=s['enrolled']['telemetry_token'])
    assert status == 200
    for _ in range(30):
        if ch(f"SELECT count() FROM telemetry_metric_points FINAL WHERE tenant_id='{s['tenant']}'") == '2':
            break
        time.sleep(1)
    else:
        raise RuntimeError('Kafka metric readback timeout')
    assert ch(f"SELECT count() FROM telemetry_metric_points FINAL WHERE tenant_id='{s['tenant']}' AND value_int=10 AND notEmpty(archive_ref)") == '2'
    print('OTLP metrics Kafka: 2 points + value/archive readback PASS', flush=True)
    s['metric_payload'] = payload
    save_state(s)
    date = dt.datetime.now(dt.timezone(dt.timedelta(hours=9))).date().isoformat()
    base = '/api/v1/organizations/' + s['tenant']
    overview = api('dashboard snapshot aggregate', 'dashboard-api', 8081, base + f'/analytics/overview?startDate={date}&endDate={date}&compare=none')
    (WORK / 'dashboard-overview.json').write_text(json.dumps(overview, indent=2))
    assert overview['summary']['totalTokens']['value'] == 2188, overview['summary']['totalTokens']
    assert overview['summary']['sessionCount']['value'] == 2
    assert overview['summary']['activeUsers']['value'] == 1
    assert not overview['errors']
    print('Dashboard: 2188 tokens / 2 sessions / 1 active member, no section errors', flush=True)
    status, _, _ = request(endpoint('telemetry-gateway', 8082) + '/v1/logs', 'POST', {}, token='invalid-smoke-token')
    assert status == 401
    check_kafka()


def check_kafka():
    for _ in range(15):
        lag = compose('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'kafka:29092', '--describe', '--group', 'proj306-ingest')
        partitions = [line.split() for line in lag.splitlines() if line.startswith('proj306-ingest')]
        if len(partitions) == 6 and all(row[5] == '0' or row[3:6] == ['-', '0', '-'] for row in partitions):
            break
        time.sleep(1)
    else:
        raise RuntimeError('Kafka offsets did not catch up')
    (WORK / 'consumer-lag.txt').write_text(lag)
    print('Kafka: 6 partitions, received records committed, remaining partitions empty', flush=True)


def retention():
    s = state()
    for table in ('telemetry_events', 'telemetry_metric_points'):
        ch(f"INSERT INTO {table} SELECT * REPLACE ('{s['other_tenant']}' AS tenant_id, repeat('b',64) AS observation_id) FROM {table} FINAL WHERE tenant_id='{s['tenant']}' AND source_time < toDateTime64('2025-02-01 00:00:00',9,'UTC') LIMIT 1")
    history = sql("SELECT 'enrollment:'||row_to_json(h)::text FROM enrollment.flyway_schema_history h UNION ALL SELECT 'telemetry_ops:'||row_to_json(h)::text FROM telemetry_ops.flyway_schema_history h ORDER BY 1;")
    base = '/api/v1/organizations/' + s['tenant']
    policy = api('retention policy request', 'enrollment-api', 8080, base + '/collection-policy', method='PUT', body={'expectedVersion': 1, 'expectedSettingsVersion': 0, 'aggregateRetentionMonths': 12})
    operation = policy['cleanupOperationId']
    assert operation
    result = subprocess.run(COMPOSE + ['run', '--no-deps', '--name', PROJECT + '-retention-run', 'retention-worker', '--requests'], capture_output=True)
    (WORK / 'retention.log').write_bytes(result.stdout + result.stderr)
    assert result.returncode == 0, 'retention-worker exit ' + str(result.returncode)
    retained = {}
    for table, count in [('telemetry_events', 2), ('telemetry_metric_points', 1)]:
        assert ch(f"SELECT count() FROM {table} FINAL WHERE tenant_id='{s['tenant']}'") == str(count), table
        assert ch(f"SELECT count() FROM {table} FINAL WHERE tenant_id='{s['other_tenant']}'") == '1', table
        retained[table] = {'target_recent': count, 'target_old': 0, 'other_tenant_old': 1}
    assert sql(f"SELECT status FROM enrollment.operations WHERE id='{operation}';") == 'succeeded'
    current_history = sql("SELECT 'enrollment:'||row_to_json(h)::text FROM enrollment.flyway_schema_history h UNION ALL SELECT 'telemetry_ops:'||row_to_json(h)::text FROM telemetry_ops.flyway_schema_history h ORDER BY 1;")
    assert history == current_history, 'retention must not migrate'
    boundary = sql(f"SELECT deleted_before,policy_epoch FROM telemetry_ops.tenant_retention_boundary WHERE tenant_id='{s['tenant']}';")
    assert boundary.endswith('|1'), boundary
    detail = api('dashboard cleanup operation', 'dashboard-api', 8081, base + '/operations/' + operation)
    assert detail['status'] == 'succeeded'
    old_body = (WORK / 'telemetry-old/payload.json').read_bytes()
    status, _, _ = request(endpoint('telemetry-ingest', 4316) + '/v1/logs', 'POST', old_body, token=s['enrolled']['telemetry_token'], headers={'Content-Type': 'application/json'})
    assert status == 200
    assert ch(f"SELECT count() FROM telemetry_events FINAL WHERE tenant_id='{s['tenant']}'") == '2'
    print('Retention: exit 0, old log/metric deleted, recent/other tenant kept, old replay fenced, Flyway history unchanged', flush=True)
    (WORK / 'retention-evidence.json').write_text(json.dumps({'exit_code': 0, 'operation_id': operation, 'status': 'succeeded', 'boundary': boundary, 'retained': retained, 'old_replay_http': 200, 'old_replay_rows': 0, 'migration_history_unchanged': True}, indent=2))


def image_evidence():
    images = []
    for app in APPS:
        tag = f'{IMAGE_PREFIX}/{app}:{IMAGE_TAG}'
        value = json.loads(call(['docker', 'image', 'inspect', '--format', '{{json .}}', tag]))
        assert value['Os'] == 'linux' and value['Config']['User'] == 'pulsemetry'
        images.append({'app': app, 'tag': tag, 'id': value['Id'], 'platform': value['Os'] + '/' + value['Architecture']})
    (WORK / 'images.json').write_text(json.dumps(images, indent=2))


def shutdown():
    servers = [app for app in APPS if app != 'retention-worker']
    for app in servers:
        assert compose('exec', '-T', app, 'id', '-u') == '10001'
    compose('stop', '--timeout', '60', *servers)
    exits = {}
    for app in servers:
        container = compose('ps', '-a', '-q', app)
        value = json.loads(call(['docker', 'inspect', '--format', '{{json .State}}', container]))
        logs = compose('logs', '--no-color', app)
        graceful = 'Graceful shutdown complete' in logs
        # JVM은 SIGTERM의 shutdown hook을 마친 뒤에도 128 + SIGTERM(15)을 반환한다.
        assert not value['OOMKilled'] and value['ExitCode'] in (0, 143) and graceful, app
        if app == 'telemetry-ingest':
            assert 'Consumer stopped' in logs
        exits[app] = {'exit_code': value['ExitCode'], 'oom_killed': value['OOMKilled'], 'graceful_shutdown': graceful}
    (WORK / 'shutdown.json').write_text(json.dumps(exits, indent=2))
    print('Four HTTP apps: UID 10001, graceful SIGTERM (exit 0/143), no OOM', flush=True)


def main():
    global WORK, PROJECT, COMPOSE, IMAGE_PREFIX, IMAGE_TAG
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', required=True, type=Path, help='아직 없는 결과 디렉터리')
    parser.add_argument('--image-tag', required=True, help='미리 빌드한 다섯 로컬 이미지의 공통 태그')
    parser.add_argument('--image-prefix', default='pulsemetry-proj306', help='<prefix>/<app>:<tag>')
    args = parser.parse_args()
    if not __debug__:
        parser.error('검증 assertion을 유지해야 하므로 python -O는 지원하지 않는다')
    WORK = args.work.resolve()
    PROJECT = 'pulsemetry-smoke-' + uuid.uuid4().hex[:12]
    IMAGE_PREFIX, IMAGE_TAG = args.image_prefix, args.image_tag
    COMPOSE = ['docker', 'compose', '-p', PROJECT, '-f', str(WORK / 'compose.json')]
    # 기존 디렉터리·Compose 프로젝트·데이터는 재사용하거나 정리하지 않는다.
    prepare()
    result = {'project': PROJECT, 'result': 'incomplete'}
    try:
        image_evidence()
        for phase in (infra, seed_and_start, management, telemetry, retention, shutdown):
            result['phase'] = phase.__name__
            phase()
        result['result'] = 'passed'
    except Exception as error:
        result.update(result='failed', error_type=type(error).__name__)
        print(f"Smoke failed at {result.get('phase', 'image_evidence')}: {type(error).__name__}", flush=True)
    finally:
        # 실패해도 증거를 먼저 남기고 이 실행이 만든 컨테이너·네트워크·익명 볼륨만 정리한다.
        try:
            logs = compose('logs', '--no-color')
            (WORK / 'containers.log').write_text(logs)
            secret_values = [state()['hash_secret'], state()['admin_secret']]
            secret_values += list(state().get('enrolled', {}).get(key, '') for key in ('installation_token', 'telemetry_token'))
            if any(value and value in logs for value in secret_values):
                result.update(result='failed', error_type='CredentialInLog')
            else:
                result['credential_log_leaks'] = False
        finally:
            compose('down', '--volumes', '--remove-orphans', '--timeout', '60')
            result['resources_removed'] = True
            (WORK / 'result.json').write_text(json.dumps(result, indent=2))
    print(f"Smoke {result['result']}; evidence: {WORK}", flush=True)
    return 0 if result['result'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
