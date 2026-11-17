"""payload 템플릿 생성기 검사. 실제 fixture 트리로 만든 결과와 커밋본(`k6/data/templates.json`)을 함께 본다."""
import json
from pathlib import Path
import random
import re
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generator import build_templates as bt  # noqa: E402

BASE = 1_767_225_600_000_000_000  # 2026-01-01T00:00:00Z
SMALL = {
    "resourceLogs": [{
        "resource": {"attributes": [
            {"key": "service.name", "value": {"stringValue": "claude-code"}},
            {"key": "user.id", "value": {"stringValue": "0123456789abcdef0123456789abcdef"}},
        ]},
        "scopeLogs": [{"logRecords": [
            {"timeUnixNano": str(BASE + 5_000_000), "observedTimeUnixNano": str(BASE + 7_000_000),
             "traceId": "0af7651916cd43dd8448eb211c80319c", "spanId": "b7ad6b7169203331",
             "attributes": [
                 {"key": "event.timestamp", "value": {"stringValue": "2026-01-01T00:00:00.005Z"}},
                 {"key": "event.sequence", "value": {"intValue": "41"}},
                 {"key": "session.id", "value": {"stringValue": "5f1d2c3b-aaaa-4bbb-8ccc-0123456789ab"}},
                 {"key": "request_id", "value": {"stringValue": "req_011CVa2bQ9xyZ"}},
                 {"key": "thread.id", "value": {"intValue": "12"}},
                 {"key": "cell.id", "value": {"stringValue": "17"}},
                 {"key": "model", "value": {"stringValue": "claude-haiku-4-5-20251001"}},
             ]},
            {"timeUnixNano": "0", "observedTimeUnixNano": str(BASE),
             "attributes": [{"key": "session.id", "value": {"stringValue": "5f1d2c3b-aaaa-4bbb-8ccc-0123456789ab"}}]},
        ]}],
    }],
}


class TemplatizerTest(unittest.TestCase):

    def setUp(self):
        self.converted = bt.Templatizer(SMALL)
        self.text = json.dumps(self.converted.body)

    def record(self, index):
        return self.converted.body["resourceLogs"][0]["scopeLogs"][0]["logRecords"][index]

    def attribute(self, index, key):
        return next(a["value"] for a in self.record(index)["attributes"] if a["key"] == key)

    def test_times_become_offsets_from_latest_and_zero_is_kept(self):
        self.assertEqual(self.converted.base, BASE + 7_000_000)
        self.assertEqual(self.record(0)["timeUnixNano"], "@@T-2000000@@")
        self.assertEqual(self.record(0)["observedTimeUnixNano"], "@@T0@@")
        self.assertEqual(self.record(1)["timeUnixNano"], "0")
        self.assertEqual(self.record(1)["observedTimeUnixNano"], "@@T-7000000@@")
        self.assertEqual(self.attribute(0, "event.timestamp"), {"stringValue": "@@D-2000000@@"})
        self.assertNotIn(str(BASE), self.text)
        self.assertNotIn("2026-01-01", self.text)

    def test_ids_are_numbered_per_value_and_short_or_int_ids_kept(self):
        session_first = self.attribute(0, "session.id")["stringValue"]
        self.assertRegex(session_first, r"^@@I\d+@@$")
        self.assertEqual(self.attribute(1, "session.id")["stringValue"], session_first)
        self.assertEqual(self.attribute(0, "thread.id"), {"intValue": "12"})
        self.assertEqual(self.attribute(0, "cell.id"), {"stringValue": "17"})
        self.assertEqual(self.attribute(0, "model"), {"stringValue": "claude-haiku-4-5-20251001"})
        self.assertEqual(self.attribute(0, "event.sequence"), {"intValue": "@@S41@@"})
        self.assertEqual(len(self.converted.ids), 5)  # user.id, traceId, spanId, session.id, request_id
        for original in ("0123456789abcdef0123456789abcdef", "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                         "5f1d2c3b-aaaa-4bbb-8ccc-0123456789ab", "req_011CVa2bQ9xyZ"):
            self.assertNotIn(original, self.text)

    def test_id_shapes(self):
        self.assertEqual(bt.id_shape("5f1d2c3b-aaaa-4bbb-8ccc-0123456789ab"), {"prefix": "", "mask": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"})
        self.assertEqual(bt.id_shape("b7ad6b7169203331"), {"prefix": "", "mask": "x" * 16})
        self.assertEqual(bt.id_shape("req_011CVa2bQ9xyZ"), {"prefix": "req_", "mask": "ddduuldludllu"})
        self.assertEqual(bt.id_shape("call-5f1d2c3b-aaaa-4bbb-8ccc-0123456789ab"), {"prefix": "call-", "mask": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"})
        self.assertEqual(bt.id_shape("0a1b2c3d4e5f6a7b-ICN"), {"prefix": "", "mask": "dldldldldldldldl-uuu"})

    def test_render_fills_every_placeholder(self):
        template = {"ids": self.converted.ids, "body": self.converted.body}
        now = 1_800_000_000_000_123_456
        first = bt.render(template, now, 1000, random.Random(1))
        self.assertNotIn("@@", first)
        record = json.loads(first)["resourceLogs"][0]["scopeLogs"][0]["logRecords"][0]
        self.assertEqual(record["timeUnixNano"], str(now - 2_000_000))
        self.assertEqual(record["observedTimeUnixNano"], str(now))
        attrs = {a["key"]: a["value"] for a in record["attributes"]}
        self.assertEqual(attrs["event.timestamp"]["stringValue"], bt.nanos_to_iso(now - 2_000_000))
        self.assertEqual(attrs["event.sequence"]["intValue"], "1041")
        self.assertRegex(attrs["session.id"]["stringValue"], bt.UUID)
        self.assertRegex(attrs["request_id"]["stringValue"], r"^req_\d{3}[A-Z]{2}[a-z]\d[a-z][A-Z]\d[a-z]{2}[A-Z]$")
        self.assertRegex(record["traceId"], r"^[0-9a-f]{32}$")
        second = bt.render(template, now, 1000, random.Random(2))
        self.assertNotEqual(json.loads(first)["resourceLogs"][0]["resource"], json.loads(second)["resourceLogs"][0]["resource"])

    def test_iso_round_trip(self):
        self.assertEqual(bt.iso_to_nanos("2026-01-01T00:00:00.005Z"), BASE + 5_000_000)
        self.assertEqual(bt.nanos_to_iso(BASE + 5_999_999), "2026-01-01T00:00:00.005Z")


class FixtureBuildTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.document = bt.build()
        cls.text = bt.dumps(cls.document)
        cls.templates = cls.document["templates"]

    def test_committed_file_matches_build(self):
        self.assertEqual(bt.OUTPUT.read_text(encoding="utf-8"), self.text, "build_templates.py 로 다시 만든다")
        self.assertEqual(bt.dumps(bt.build()), self.text)

    def test_size_and_coverage(self):
        self.assertLess(len(self.text.encode("utf-8")), bt.MAX_BYTES)
        buckets = {(t["product"], t["signal"]) for t in self.templates}
        self.assertEqual(buckets, {(p, s) for p in ("claude-code", "codex") for s in ("logs", "metrics", "traces")})
        self.assertEqual(len({t["id"] for t in self.templates}), len(self.templates))

    def test_only_real_captures_with_expectations(self):
        for template in self.templates:
            source = template["source"].rsplit(":", 1)[0]
            self.assertNotIn("synthetic", source)
            self.assertTrue(source.startswith(("claude_code/real/", "claude_code/api-2.1.290/logs-api-", "codex/real/", "codex/completed/logs-")), source)
            self.assertTrue((bt.FIXTURES / source.replace(".otlp.jsonl", ".expected.jsonl")).exists())
            self.assertEqual(template["path"], f"/v1/{template['signal']}")
        self.assertFalse(any("node-repl" in t["id"] for t in self.templates))

    def test_expected_counts_come_from_expected_files(self):
        for template in self.templates:
            source, line = template["source"].rsplit(":", 1)
            expected_path = bt.FIXTURES / source.replace(".otlp.jsonl", ".expected.jsonl")
            expected = json.loads(expected_path.read_text(encoding="utf-8").splitlines()[int(line) - 1])
            self.assertEqual(template["expected"], {"events": len(expected.get("events", [])), "metricPoints": len(expected.get("metric_points", []))})
        self.assertGreater(sum(t["expected"]["metricPoints"] for t in self.templates if t["signal"] == "metrics"), 0)

    def test_original_times_and_ids_do_not_survive(self):
        leaks = []

        def walk(node):
            if isinstance(node, dict):
                if isinstance(node.get("key"), str) and isinstance(node.get("value"), dict):
                    value = node["value"].get("stringValue")
                    if bt.is_id_key(node["key"]) and isinstance(value, str) and len(value) >= bt.MIN_ID_LENGTH \
                            and value not in bt.KEEP_SENTINELS and value in self.text:
                        leaks.append(value)
                for key, value in node.items():
                    if key in bt.TIME_FIELDS and value != "0" and value in self.text:
                        leaks.append(value)
                    if key in bt.HEX_ID_FIELDS and value and value in self.text:
                        leaks.append(value)
                    walk(value)
            elif isinstance(node, list):
                for item in node:
                    walk(item)
            elif isinstance(node, str) and bt.ISO_TIME.fullmatch(node) and node in self.text:
                leaks.append(node)

        for template in self.templates:
            source, line = template["source"].rsplit(":", 1)
            walk(json.loads((bt.FIXTURES / source).read_text(encoding="utf-8").splitlines()[int(line) - 1]))
        self.assertEqual(leaks, [])
        self.assertIsNone(re.search(r'"(?:timeUnixNano|observedTimeUnixNano|startTimeUnixNano|endTimeUnixNano)":"[1-9]', self.text))

    def test_placeholders_are_well_formed_and_render_to_valid_json(self):
        rng = random.Random(7)
        for template in self.templates:
            body = json.dumps(template["body"])
            for kind, number in re.findall(r"@@([A-Z])(-?\d+)@@", body):
                self.assertIn(kind, "TDSI")
                if kind == "I":
                    self.assertLess(int(number), len(template["ids"]))
                if kind in "TD":
                    self.assertLessEqual(int(number), 0)
            self.assertEqual(body.count("@@") % 2, 0)
            rendered = json.loads(bt.render(template, 1_800_000_000_000_000_000, 0, rng))
            self.assertNotIn("@@", json.dumps(rendered))
            self.assertIn(next(iter(rendered)), ("resourceLogs", "resourceMetrics", "resourceSpans"))


if __name__ == "__main__":
    unittest.main()
