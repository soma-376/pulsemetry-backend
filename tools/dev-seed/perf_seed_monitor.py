#!/usr/bin/env python3
"""격리 perf 시드의 고정 읽기 지연·cgroup 압력을 관측한다. DB 쓰기는 수행하지 않는다."""
import argparse
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import time
import urllib.parse
import urllib.request

import historical_seed as seed

EVENTS = ('max', 'oom', 'oom_kill')
CGROUP = 'for f in memory.current memory.max memory.stat memory.events; do echo "$f"; cat "/sys/fs/cgroup/$f" || exit 1; done'
PROBE = 'SELECT count() FROM system.parts WHERE active'


class MonitorError(Exception):
    """출력에는 고정 오류 코드만 전달한다."""


def require(condition, code):
    if not condition:
        raise MonitorError(code)


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def parse_cgroup(raw):
    sections, key = {}, None
    for line in raw.splitlines():
        if line in ('memory.current', 'memory.max', 'memory.stat', 'memory.events'):
            require(line not in sections, 'cgroup_duplicate_section')
            key = line
            sections[key] = []
        else:
            require(key is not None, 'cgroup_unknown_section')
            sections[key].append(line)
    try:
        used, limit = int(sections['memory.current'][0]), int(sections['memory.max'][0])
        stat = {k: int(v) for k, v in (line.split() for line in sections['memory.stat'])}
        events = {k: int(v) for k, v in (line.split() for line in sections['memory.events'])}
        require(used >= 0 and limit > 0 and stat['inactive_file'] >= 0, 'cgroup_memory_invalid')
        require(all(events[k] >= 0 for k in EVENTS), 'cgroup_events_invalid')
    except (KeyError, ValueError, IndexError):
        raise MonitorError('cgroup_incomplete') from None
    return {'memoryWorkingSetBytes': max(0, used - stat['inactive_file']), 'memoryLimitBytes': limit,
            'memoryEvents': {k: events[k] for k in EVENTS}}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise MonitorError('clickhouse_redirect_rejected')


class Live:
    def __init__(self, target, kubectl, kubeconfig):
        self.target, self.kubectl, self.kubeconfig = target, kubectl, kubeconfig
        proof, path = seed.evidence_json(target.path.parent, target.data['targetEvidence'])
        self.pod = seed.evidence_json(path.parent, proof['clickhousePod'])[0]
        resolved = seed.evidence_json(path.parent, proof['resolved'])[0]
        self.workload = resolved['kubernetes']['workloads']['clickhouse']
        candidates = [c for c in self.pod['spec']['containers'] if c['image'] == self.workload['image']]
        require(len(candidates) == 1 and bool(self.pod['metadata'].get('uid')), 'pod_identity_incomplete')
        self.container = candidates[0]['name']
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def kubernetes(self, *args):
        result = subprocess.run([self.kubectl, '--kubeconfig', self.kubeconfig,
            '-n', self.workload['namespace'], *args], capture_output=True, timeout=10)
        require(result.returncode == 0 and len(result.stdout) <= 1024 * 1024, 'kubernetes_read_failed')
        return result.stdout.decode()

    def state(self):
        actual = json.loads(self.kubernetes('get', 'pod', self.pod['metadata']['name'], '-o', 'json'))
        require(actual['metadata']['uid'] == self.pod['metadata']['uid'], 'pod_replaced')
        require(any(o.get('kind') == 'StatefulSet' and o.get('uid') == self.workload['uid']
                    for o in actual['metadata'].get('ownerReferences', [])), 'pod_owner_changed')
        require(any(c['name'] == self.container and c['image'] == self.workload['image']
                    for c in actual['spec']['containers']), 'pod_image_changed')
        status = next(s for s in actual['status']['containerStatuses'] if s['name'] == self.container)
        require(status.get('ready') and 'running' in status.get('state', {}), 'container_not_ready')
        identity = {'containerId': status['containerID'], 'startedAt': status['state']['running']['startedAt']}
        memory = parse_cgroup(self.kubernetes('exec', self.pod['metadata']['name'], '-c', self.container,
                                             '--', 'sh', '-c', CGROUP))
        return identity, memory

    def request(self, sql):
        settings = self.target.data['clickhouse']
        query = urllib.parse.urlencode({'database': settings['database'], 'readonly': 2,
            'max_execution_time': 5, 'max_threads': 1, 'max_result_rows': 3,
            'max_result_bytes': 4096, 'result_overflow_mode': 'throw'})
        headers = {'X-ClickHouse-User': settings.get('username', 'default')}
        if 'passwordEnv' in settings:
            headers['X-ClickHouse-Key'] = os.environ[settings['passwordEnv']]
        request = urllib.request.Request(settings['url'].rstrip('/') + '/?' + query,
                                         data=sql.encode(), headers=headers, method='POST')
        started = time.monotonic()
        with self.opener.open(request, timeout=6) as response:
            require(response.status == 200, 'clickhouse_response_failed')
            body = response.read(4097)
        elapsed = (time.monotonic() - started) * 1000
        require(len(body) <= 4096 and math.isfinite(elapsed) and elapsed > 0, 'clickhouse_response_invalid')
        return body, elapsed

    def check_server(self):
        body, _ = self.request('SELECT hostName() AS hostname,currentDatabase() AS database,version() AS version FORMAT JSONEachRow')
        actual = json.loads(body)
        expected = self.target.expected_identity['clickhouse']
        require(actual == {k: expected[k] for k in ('hostname', 'database', 'version')}, 'clickhouse_identity_changed')
        body, _ = self.request("SELECT name,toString(uuid) AS uuid FROM system.tables WHERE database=currentDatabase() AND name IN ('telemetry_events','telemetry_ingest_ledger') ORDER BY name FORMAT JSONEachRow")
        rows = [json.loads(line) for line in body.splitlines()]
        require(len(rows) == 2 and {row['name']: row['uuid'] for row in rows} == expected['table_uuids'], 'table_uuid_changed')

    def probe(self):
        body, elapsed = self.request(PROBE)
        require(body.strip().isdigit(), 'read_probe_invalid')
        return elapsed

    def error_count(self, since):
        raw = self.kubernetes('logs', self.pod['metadata']['name'], '-c', self.container,
                              '--since-time=' + since, '--limit-bytes=65536')
        require(len(raw.encode()) < 65536, 'log_window_truncated')
        count = 0
        for line in raw.splitlines():
            try:
                value = json.loads(line)
                matched = str(value.get('level', '')).lower() in ('error', 'fatal', 'critical')
            except (ValueError, AttributeError):
                matched = any(s in line for s in ('<Error>', '<Fatal>', '<Critical>'))
            count += int(matched)
        return count


def initial_baseline(live, sleep=time.sleep):
    observed = utc()
    live.check_server()
    identity, before = live.state()
    require(before['memoryWorkingSetBytes'] < before['memoryLimitBytes'] * .75, 'baseline_memory_pressure')
    warmup, samples = [], []
    for output, count in ((warmup, 10), (samples, 30)):
        for _ in range(count):
            output.append(live.probe())
            sleep(.2)
    after_identity, after = live.state()
    require(identity == after_identity and before['memoryEvents'] == after['memoryEvents']
            and before['memoryLimitBytes'] == after['memoryLimitBytes'], 'baseline_container_or_pressure_changed')
    require(after['memoryWorkingSetBytes'] < after['memoryLimitBytes'] * .75, 'baseline_memory_pressure')
    require(live.error_count(observed) == 0, 'baseline_clickhouse_error')
    return {'observedAt': observed, 'identity': identity, 'memoryEvents': before['memoryEvents'],
            'memoryLimitBytes': before['memoryLimitBytes'], 'baselineReadLatencyMs': sorted(samples)[28],
            'warmupSamplesMs': warmup, 'samplesMs': samples,
            'method': '10 discarded warmups; fixed nearest-rank p95 of 30 client elapsed samples',
            'probeSha256': hashlib.sha256(PROBE.encode()).hexdigest()}


def collect(live, baseline, sleep=time.sleep, since=None):
    observed = utc()
    started = time.monotonic()
    identity, before = live.state()
    samples = []
    for _ in range(5):
        samples.append(live.probe())
        sleep(.2)
    after_identity, after = live.state()
    # 직전 구간 시작부터 겹쳐 읽어 두 표본 사이의 sleep 구간 오류를 놓치지 않는다.
    count = live.error_count(since or baseline['observedAt'])
    require(time.monotonic() - started < 40, 'sample_expired')
    reasons = []
    if identity != baseline['identity'] or after_identity != identity:
        reasons.append('container_identity_changed')
    if any(v['memoryEvents'] != baseline['memoryEvents'] for v in (before, after)):
        reasons.append('memory_events_changed')
    if any(v['memoryLimitBytes'] != baseline['memoryLimitBytes'] for v in (before, after)):
        reasons.append('memory_limit_changed')
    used = max(before['memoryWorkingSetBytes'], after['memoryWorkingSetBytes'])
    if used >= baseline['memoryLimitBytes'] * .75:
        reasons.append('memory_at_least_75_percent')
    latency = statistics.median(samples)
    if latency >= baseline['baselineReadLatencyMs'] * 2:
        reasons.append('read_latency_at_least_twice_baseline')
    if count:
        reasons.append('clickhouse_error_observed')
    sample = {'observedAt': observed, 'validUntil': (dt.datetime.fromisoformat(observed) + dt.timedelta(seconds=40)).isoformat(),
              'memoryWorkingSetBytes': used, 'memoryLimitBytes': baseline['memoryLimitBytes'],
              'readLatencyMs': latency, 'baselineReadLatencyMs': baseline['baselineReadLatencyMs'],
              'readLatencyAggregation': 'median_of_5', 'readLatencySamplesMs': samples,
              'clickhouseErrorCount': count, 'stopReasons': reasons,
              'pressureGuard': {'schemaVersion': 1, 'identity': after_identity,
                'baseline': {k: baseline[k] for k in ('observedAt', 'memoryEvents')},
                'memoryEvents': after['memoryEvents'], 'latched': bool(reasons)}}
    return sample


def run(args):
    require(60 <= args.duration <= 10800, 'duration_out_of_range')
    artifact = seed.Artifact(args.manifest)
    target = seed.Target(args.target, artifact)
    require(target.data['environment'] == 'perf' and 'monitorLatency' not in target.data, 'perf_client_latency_target_required')
    output = args.output.absolute()
    require(Path(target.data['monitorFile']).absolute() == output / 'monitor.json', 'monitor_path_mismatch')
    output.mkdir(mode=0o700, parents=False, exist_ok=False)
    monitor = output / 'monitor.json'
    live = Live(target, args.kubectl, args.kubeconfig)
    latched = False
    try:
        baseline = initial_baseline(live)
        baseline.update(targetSha256=target.sha, manifestSha256=artifact.sha,
                        monitorSourceSha256=seed.sha256(Path(__file__).read_bytes()))
        seed.atomic_json(output / 'baseline.json', baseline)
        print(json.dumps({'event': 'baseline_ready', 'environmentId': target.data['perfEnvironmentId']}), flush=True)
        started, since = time.monotonic(), baseline['observedAt']
        with (output / 'samples.jsonl').open('x') as series:
            os.chmod(output / 'samples.jsonl', 0o600)
            while time.monotonic() - started < args.duration:
                require(seed.sha256(seed.read_regular(target.path, 65536)) == target.sha, 'target_changed')
                sample = collect(live, baseline, since=since)
                since = sample['observedAt']
                series.write(json.dumps(sample) + '\n')
                series.flush()
                seed.atomic_json(monitor, sample)
                if sample['pressureGuard']['latched']:
                    latched = True
                    seed.atomic_json(output / 'stop-latch.json', sample)
                    print(json.dumps({'event': 'monitor_stopped', 'reasons': sample['stopReasons']}), flush=True)
                    return 2
                time.sleep(5)
        return 0
    except Exception as error:
        monitor.unlink(missing_ok=True)
        # HTTP 본문·kubectl stderr·예외 원문에는 비밀이 있을 수 있으므로 보존하지 않는다.
        reason = str(error) if isinstance(error, MonitorError) else type(error).__name__
        seed.atomic_json(output / 'stop-latch.json', {'observedAt': utc(), 'reason': reason})
        print(json.dumps({'event': 'monitor_capture_failed'}), flush=True)
        return 2
    finally:
        if not latched:
            monitor.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--target', type=Path, required=True)
    parser.add_argument('--kubeconfig', required=True)
    parser.add_argument('--kubectl', default='kubectl')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--duration', type=int, default=3600)
    args = parser.parse_args()
    os.umask(0o077)
    try:
        return run(args)
    except Exception:
        print(json.dumps({'event': 'monitor_initialization_failed'}), flush=True)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
