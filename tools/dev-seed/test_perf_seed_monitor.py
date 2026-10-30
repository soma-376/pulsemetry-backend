import copy
import datetime as dt
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import historical_seed as seed
import perf_seed_monitor as monitor


class PerfSeedMonitorTest(unittest.TestCase):
    def setUp(self):
        now = dt.datetime.now(dt.timezone.utc)
        self.identity = {'containerId': 'containerd://fixture', 'startedAt': (now-dt.timedelta(minutes=2)).isoformat()}
        self.memory = {'memoryWorkingSetBytes': 100, 'memoryLimitBytes': 1000, 'memoryEvents': {'max': 0, 'oom': 0, 'oom_kill': 0}}
        self.baseline = {'observedAt': (now-dt.timedelta(minutes=1)).isoformat(), 'identity': self.identity,
                         'memoryLimitBytes': 1000, 'memoryEvents': self.memory['memoryEvents'], 'baselineReadLatencyMs': 10}
        self.live = Mock()
        self.live.state.return_value = (self.identity, self.memory)
        self.live.probe.return_value = 5
        self.live.error_count.return_value = 0

    def test_sample_is_consumed_by_existing_importer(self):
        sample = monitor.collect(self.live, self.baseline, sleep=lambda _: None)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'monitor.json'
            seed.atomic_json(path, sample)
            result = seed.monitor_check(path, require_pressure=True)
        self.assertEqual(100, result['memoryWorkingSetBytes'])
        self.assertEqual(self.identity, result['pressureBaseline']['identity'])

    def test_pressure_identity_limit_and_log_failures_latch(self):
        for reason in ('memory_events_changed', 'container_identity_changed', 'memory_limit_changed',
                       'memory_at_least_75_percent', 'clickhouse_error_observed'):
            with self.subTest(reason=reason):
                identity, memory = copy.deepcopy(self.identity), copy.deepcopy(self.memory)
                self.live.error_count.return_value = int(reason == 'clickhouse_error_observed')
                if reason == 'memory_events_changed': memory['memoryEvents']['max'] = 1
                if reason == 'container_identity_changed': identity['containerId'] = 'new-container'
                if reason == 'memory_limit_changed': memory['memoryLimitBytes'] = 2000
                if reason == 'memory_at_least_75_percent': memory['memoryWorkingSetBytes'] = 750
                self.live.state.return_value = (identity, memory)
                sample = monitor.collect(self.live, self.baseline, sleep=lambda _: None)
                self.assertIn(reason, sample['stopReasons'])
                self.assertTrue(sample['pressureGuard']['latched'])

    def test_median_reduces_one_outlier_but_persistent_latency_stops(self):
        self.live.probe.side_effect = [5, 5, 500, 5, 5]
        self.assertEqual(5, monitor.collect(self.live, self.baseline, sleep=lambda _: None)['readLatencyMs'])
        self.live.probe.side_effect = [20] * 5
        self.assertIn('read_latency_at_least_twice_baseline', monitor.collect(self.live, self.baseline, sleep=lambda _: None)['stopReasons'])

    def test_log_window_includes_interval_between_samples(self):
        since = self.baseline['observedAt']
        monitor.collect(self.live, self.baseline, sleep=lambda _: None, since=since)
        self.live.error_count.assert_called_once_with(since)

    def test_http_probe_is_bounded_readonly_and_does_not_use_url_credentials(self):
        live = object.__new__(monitor.Live)
        live.target = SimpleNamespace(data={'clickhouse': {'url': 'http://127.0.0.1:18123', 'database': 'default', 'username': 'observer'}})
        response = Mock(status=200)
        response.read.return_value = b'1\n'
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        live.opener = Mock()
        live.opener.open.return_value = response
        self.assertGreater(live.probe(), 0)
        request = live.opener.open.call_args.args[0]
        self.assertIn('readonly=2', request.full_url)
        self.assertIn('max_execution_time=5', request.full_url)
        self.assertIn('result_overflow_mode=throw', request.full_url)
        self.assertEqual(monitor.PROBE.encode(), request.data)
        self.assertEqual(6, live.opener.open.call_args.kwargs['timeout'])
        with self.assertRaises(monitor.MonitorError): monitor.NoRedirect().redirect_request(None)

    def test_cgroup_requires_limit_and_all_pressure_counters(self):
        raw = 'memory.current\n900\nmemory.max\n1000\nmemory.stat\ninactive_file 300\nmemory.events\nmax 0\noom 0\noom_kill 0\n'
        self.assertEqual(600, monitor.parse_cgroup(raw)['memoryWorkingSetBytes'])
        for bad in (raw.replace('1000', 'max'), raw.replace('oom_kill 0\n', ''), raw.replace('max 0', 'max -1')):
            with self.assertRaises(monitor.MonitorError): monitor.parse_cgroup(bad)

    def test_baseline_fixed_p95_and_pressure_change_rejected(self):
        self.live.probe.side_effect = [1] * 10 + list(range(1, 31))
        baseline = monitor.initial_baseline(self.live, sleep=lambda _: None)
        self.assertEqual(29, baseline['baselineReadLatencyMs'])
        self.assertEqual(30, len(baseline['samplesMs']))
        self.live.probe.side_effect = None
        changed = copy.deepcopy(self.memory)
        changed['memoryEvents']['oom'] = 1
        self.live.state.side_effect = [(self.identity, self.memory), (self.identity, changed)]
        with self.assertRaises(monitor.MonitorError): monitor.initial_baseline(self.live, sleep=lambda _: None)

    def test_failure_removes_fresh_sample_and_redacts_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'execution'
            target_path = Path(directory) / 'target.json'
            target_path.write_text('{}')
            target = SimpleNamespace(path=target_path, sha=seed.sha256(b'{}'), data={
                'environment': 'perf', 'perfEnvironmentId': 'perf-test', 'monitorFile': str(output/'monitor.json')})
            args = SimpleNamespace(duration=60, manifest='fixture', target=target_path, output=output, kubectl='kubectl', kubeconfig='fixture')
            with patch.object(seed, 'Artifact', return_value=SimpleNamespace(sha='fixture')), \
                 patch.object(seed, 'Target', return_value=target), patch.object(monitor, 'Live', return_value=self.live), \
                 patch.object(monitor, 'initial_baseline', return_value=copy.deepcopy(self.baseline)), \
                 patch.object(monitor, 'collect', side_effect=RuntimeError('password=secret')):
                self.assertEqual(2, monitor.run(args))
            self.assertFalse((output/'monitor.json').exists())
            self.assertNotIn('secret', (output/'stop-latch.json').read_text())
            self.assertEqual(0o600, (output/'baseline.json').stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError): output.mkdir()


if __name__ == '__main__':
    unittest.main()
