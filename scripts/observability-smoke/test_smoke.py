"""네트워크 모의 테스트: 200만으로 통과하지 않고 FINAL 내용·중복·미확정 응답을 판정한다."""
import argparse
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
import uuid
import smoke


class SmokeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        token = root / "token"
        token.write_text("SECRET-CANARY")
        self.args = argparse.Namespace(gateway="http://gateway", gateway_metrics="http://gateway-metrics",
            ingest_metrics="http://ingest-metrics", clickhouse="http://clickhouse", database="default",
            tenant=uuid.uuid4(), installation=uuid.uuid4(), run_id=uuid.uuid4(),
            source_time="2026-10-22T00:00:00+09:00", token_file=token, output=root / "evidence", timeout=0.001)
        self.body, self.request_id = smoke.payload(self.args.run_id, self.args.source_time)
        self.row = {"observation_id": smoke.expected_observation_id(self.body, self.args.tenant, self.args.installation), "tenant_id": str(self.args.tenant), "installation_id": str(self.args.installation),
                    "session_id": str(self.args.run_id), "request_id": self.request_id, "event_type": "model.response.usage",
                    "tokens_input": "1066", "tokens_output": "28", "archive_ref": "file:synthetic"}

    def client(self, rows, status=200, preexisting=False, unknown=False):
        calls = []
        test = self
        class FakeClient:
            def request(self, url, data=None, headers=None):
                calls.append((url, data))
                if url.endswith("/actuator/prometheus"):
                    return 200, b"jvm_memory_used_bytes 1\npulsemetry_pipeline_inflight 0\n"
                if url.endswith("/v1/logs"):
                    test.assertEqual(data, test.body)
                    test.assertEqual(headers["Authorization"], "Bearer SECRET-CANARY")
                    if unknown:
                        raise urllib.error.URLError("SECRET-CANARY")
                    return status, b"SECRET-CANARY"
                test.assertEqual(data.decode(), smoke.QUERY)
                test.assertIn(" FINAL", data.decode())
                first = sum(1 for u, _ in calls if u.startswith("http://clickhouse")) == 1
                selected = rows if preexisting or not first else []
                return 200, b"\n".join(json.dumps(row).encode() for row in selected)
        return FakeClient(), calls

    def evidence(self):
        values = "\n".join(path.read_text() for path in self.args.output.iterdir())
        self.assertNotIn("SECRET-CANARY", values)
        return json.loads((self.args.output / "evidence.json").read_text())

    def test_pipeline_golden_fixture_matches_generator(self):
        fixture = Path(__file__).resolve().parents[2] / "apps/telemetry-ingest/src/test/resources/observability-smoke-log.json"
        self.assertEqual(fixture.read_bytes(), smoke.payload("19100000-0000-4000-8000-000000000001", "2026-10-21T15:00:00+00:00")[0])

    def test_payload_is_deterministic_and_valid_shape(self):
        self.assertEqual(self.body, smoke.payload(self.args.run_id, self.args.source_time)[0])
        record = json.loads(self.body)["resourceLogs"][0]["scopeLogs"][0]["logRecords"][0]
        self.assertEqual(record["body"]["stringValue"], "claude_code.api_request")
        self.assertNotIn("prompt", {attr["key"] for attr in record["attributes"]})

    def test_success_requires_exact_logical_row(self):
        client, calls = self.client([self.row])
        self.assertEqual(smoke.run(self.args, client), 0)
        self.assertEqual(self.evidence()["result"], "passed")
        self.assertEqual(sum(url.endswith("/v1/logs") for url, _ in calls), 1)

    def test_http_200_without_row_is_not_success(self):
        client, _ = self.client([])
        self.assertEqual(smoke.run(self.args, client), 1)
        self.assertEqual(self.evidence()["result"], "failed_or_inconclusive")

    def test_existing_run_is_never_sent_again(self):
        client, calls = self.client([self.row], preexisting=True)
        self.assertEqual(smoke.run(self.args, client), 1)
        self.assertFalse(any(url.endswith("/v1/logs") for url, _ in calls))
        self.evidence()

    def test_unknown_http_with_row_keeps_evidence_without_retry(self):
        client, calls = self.client([self.row], unknown=True)
        self.assertEqual(smoke.run(self.args, client), 1)
        result = self.evidence()
        self.assertEqual(result["http_status"], "transport_unknown")
        self.assertEqual(len(result["logical_rows"]), 1)
        self.assertEqual(sum(url.endswith("/v1/logs") for url, _ in calls), 1)

    def test_wrong_rows_and_duplicates_are_not_success(self):
        self.assertFalse(smoke.validate_rows([dict(self.row, observation_id="a" * 64)], self.args, self.request_id))
        self.assertFalse(smoke.validate_rows([dict(self.row, tokens_input=0)], self.args, self.request_id))
        self.assertFalse(smoke.validate_rows([self.row, self.row], self.args, self.request_id))
        self.assertFalse(smoke.validate_rows([dict(self.row, installation_id=str(uuid.uuid4()))], self.args, self.request_id))

    def test_credentials_and_queries_cannot_be_in_endpoint(self):
        for value in ["http://user:SECRET-CANARY@example.com", "https://example.com?password=SECRET-CANARY", "file:///tmp/data"]:
            with self.assertRaises(argparse.ArgumentTypeError):
                smoke.endpoint(value)


if __name__ == "__main__":
    unittest.main()
