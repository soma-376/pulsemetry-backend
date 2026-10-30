"""외부 DB를 변경하지 않는 historical importer 회귀 검사."""
import copy
import contextlib
import datetime as dt
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import re
import tempfile
from types import SimpleNamespace
from unittest import mock
import urllib.parse
import unittest

SPEC = importlib.util.spec_from_file_location("historical_seed", Path(__file__).with_name("historical_seed.py"))
seed = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(seed)


class FakePg:
    alive = True

    def query(self, sql):
        if not self.alive:
            raise seed.SeedError("PG lock connection lost")
        return [{"alive": 1}]


class FakeClickHouse:
    database = "default"

    def __init__(self):
        self.rows = []
        self.requests = []
        self.failure = None
        self.running = False

    def request(self, sql, body, query_id):
        rows = [seed.decode(line) for line in body.splitlines()]
        self.requests.append((sql, rows, query_id))
        if self.failure == "partial":
            self.rows.extend(copy.deepcopy(rows[:1]))
            self.failure = None
            raise seed.SeedError("timeout after partial write")
        self.rows.extend(copy.deepcopy(rows))
        if self.failure == "accepted":
            self.failure = None
            raise seed.SeedError("timeout after server accepted")
        return ""


def event(key, cost="1.000000000001"):
    return {
        "tenant_id": "ed3919dc-9fba-4cb5-868c-1d7bc73fda5f",
        "installation_id": "seed-installation",
        "observation_id": key,
        "source_time": "2026-05-01T01:00:00.123456789Z",
        "received_time": "2026-05-01T01:00:01.123456789Z",
        "cost_estimated_usd": Decimal(cost),
        "tokens_total_derived": 9007199254740993,
    }


class FakeArtifact:
    sha = "0" * 64
    tenant = "ed3919dc-9fba-4cb5-868c-1d7bc73fda5f"
    manifest = {"summary": {}, "oidc": {"issuer": "https://issuer.example.test", "clientId": "client", "clientSecretRef": "config:local"}}
    activation_rules = None
    tables = {}

    def __init__(self, rows):
        self.rows = rows
        self.batches = [{"id": "events-0001", "table": "telemetry_events", "identityColumn": "observation_id", "timeColumn": "source_time", "timeMin": rows[0]["source_time"], "timeMax": rows[-1]["source_time"], "rows": len(rows)}]

    def batch_rows(self, _):
        return copy.deepcopy(self.rows)


class MemoryRunner(seed.Runner):
    def __init__(self, rows, ch=None):
        self.saved = []
        self.sleeps = []
        self.monitor_error = None
        self.journal_records = []
        super().__init__(FakeArtifact(rows), SimpleNamespace(data={"environment": "local"}), "/tmp/unused-seed-state", FakePg(), ch or FakeClickHouse(), self.sleeps.append)
        self.ch_columns = {"telemetry_events": {
            "tenant_id": "LowCardinality(String)", "observation_id": "FixedString(64)",
            "installation_id": "String",
            "source_time": "DateTime64(9, 'UTC')", "received_time": "DateTime64(9, 'UTC')",
            "cost_estimated_usd": "Nullable(Decimal(38, 12))", "tokens_total_derived": "Nullable(Int64)",
        }}
        self.state = {"phase": "loading", "batches": {}, "ackedRequests": 0, "pilotObserved": False}

    def save(self):
        self.saved.append(copy.deepcopy(self.state))

    def emit(self, *_args, **_kwargs):
        pass

    def verification_journal(self, record):
        self.journal_records.append(copy.deepcopy(record))

    def monitor(self):
        if self.monitor_error:
            raise self.monitor_error

    def retention(self, rows=None):
        pass

    def wait_finished(self, _):
        seed.require(not self.ch.running, "previous query still running")

    def reconcile(self, batch, rows):
        ids = {r[batch["identityColumn"]] for r in rows}
        actual = [r for r in self.ch.rows if r[batch["identityColumn"]] in ids]
        return seed.missing_rows(rows, actual, batch["identityColumn"], self.ch_columns[batch["table"]])


class RecoveryTests(unittest.TestCase):
    def test_ack_lost_after_full_server_write_does_not_insert_again(self):
        runner = MemoryRunner([event("a"), event("b")])
        runner.ch.failure = "accepted"
        with self.assertRaises(seed.SeedError):
            runner.load_batches()
        self.assertEqual(runner.state["batches"]["events-0001"]["status"], "sent")
        runner.load_batches()
        self.assertEqual(len(runner.ch.requests), 1)
        self.assertEqual(len(runner.ch.rows), 2)
        self.assertEqual(runner.state["phase"], "loaded")

    def test_partial_write_resume_only_sends_missing_rows(self):
        runner = MemoryRunner([event("a"), event("b"), event("c")])
        runner.ch.failure = "partial"
        with self.assertRaises(seed.SeedError):
            runner.load_batches()
        runner.load_batches()
        self.assertEqual([r["observation_id"] for r in runner.ch.requests[1][1]], ["b", "c"])
        self.assertEqual(len(runner.ch.rows), 3)

    def test_running_uncertain_query_blocks_retransmission(self):
        runner = MemoryRunner([event("a")])
        runner.state["batches"]["events-0001"] = {"queryId": "uncertain"}
        runner.ch.running = True
        with self.assertRaisesRegex(seed.SeedError, "still running"):
            runner.load_batches()
        self.assertEqual(runner.ch.requests, [])

    def test_conflicting_stored_content_stops_before_writing(self):
        runner = MemoryRunner([event("a")])
        runner.ch.rows = [event("a", "2.0")]
        with self.assertRaisesRegex(seed.SeedError, "내용이 다릅니다"):
            runner.load_batches()
        self.assertEqual(runner.ch.requests, [])

    def test_physical_duplicate_is_not_hidden_by_final(self):
        runner = MemoryRunner([event("a")])
        runner.ch.rows = [event("a"), event("a")]
        with self.assertRaisesRegex(seed.SeedError, "물리적 중복"):
            runner.load_batches()

    def test_bounded_reconciliation_filters_before_wide_columns_and_keeps_duplicates(self):
        runner = MemoryRunner([event("a"), event("b")])
        statements = []
        def query(sql, **_kwargs):
            statements.append(sql)
            return [event("a"), event("b")]
        runner.ch.query = query
        batch = runner.artifact.batches[0]
        self.assertEqual(seed.Runner.reconcile(runner, batch, runner.artifact.rows), [])
        sql = statements[0]
        self.assertIn(" PREWHERE tenant_id=", sql)
        self.assertIn(" AND source_time>=", sql)
        self.assertIn(" AND source_time<=", sql)
        self.assertIn(" AND observation_id IN ('a','b')", sql)
        self.assertIn(" LIMIT 3 SETTINGS max_block_size=512, preferred_block_size_bytes=1048576", sql)
        self.assertNotIn(" FINAL", sql)
        runner.ch.query = lambda _, **_kwargs: [event("a"), event("a"), event("b")]
        with self.assertRaisesRegex(seed.SeedError, "물리적 중복"):
            seed.Runner.reconcile(runner, batch, runner.artifact.rows)

    def test_pg_lock_connection_loss_stops_clickhouse_write(self):
        runner = MemoryRunner([event("a")])
        runner.pg.alive = False
        with self.assertRaisesRegex(seed.SeedError, "connection lost"):
            runner.load_batches()
        self.assertEqual(runner.ch.requests, [])

    def test_stripes_compare_every_column_and_return_full_missing_rows(self):
        rows = [dict(event(key), **{"field_" + str(i): str(i) for i in range(34)}) for key in ("a", "b")]
        runner = MemoryRunner(rows)
        seen = []
        def query(sql, **_kwargs):
            columns = sql.split(" FROM ", 1)[0][len("SELECT "):].split(",")
            self.assertLessEqual(len(columns), 16)
            self.assertEqual(columns[0], "observation_id")
            seen.extend(columns[1:])
            return [{key: rows[0][key] for key in columns}]
        runner.ch.query = query
        self.assertEqual(seed.Runner.reconcile(runner, runner.artifact.batches[0], rows), [rows[1]])
        self.assertEqual(seen, [key for key in rows[0] if key != "observation_id"])

    def test_late_stripe_difference_and_changing_row_set_stop_reconciliation(self):
        rows = [dict(event(key), **{"field_" + str(i): str(i) for i in range(34)}) for key in ("a", "b")]
        for failure in ("content", "missing", "duplicate"):
            with self.subTest(failure=failure):
                runner = MemoryRunner(rows)
                calls = []
                def query(sql, **_kwargs):
                    columns = sql.split(" FROM ", 1)[0][len("SELECT "):].split(",")
                    calls.append(columns)
                    result = [{key: row[key] for key in columns} for row in rows]
                    if len(calls) == 2:
                        if failure == "content":
                            result[0][columns[-1]] = "changed"
                        elif failure == "missing":
                            result.pop()
                        else:
                            result.append(result[0])
                    return result
                runner.ch.query = query
                with self.assertRaises(seed.SeedError):
                    seed.Runner.reconcile(runner, runner.artifact.batches[0], rows)
                self.assertEqual(len(calls), 2)

    def test_monitor_failure_prevents_write(self):
        runner = MemoryRunner([event("a")])
        runner.monitor_error = seed.Paused("memory")
        with self.assertRaises(seed.Paused):
            runner.load_batches()
        self.assertEqual(runner.ch.requests, [])

    def test_noop_resume_does_not_insert(self):
        runner = MemoryRunner([event("a"), event("b")])
        runner.ch.rows = [event("a"), event("b")]
        runner.load_batches()
        self.assertEqual(runner.ch.requests, [])

    def test_fifth_ack_waits_and_checks_monitor_once(self):
        runner = MemoryRunner([event("a")])
        runner.state["ackedRequests"] = 4
        runner.load_batches()
        self.assertEqual(runner.sleeps, [1, 30])
        self.assertTrue(runner.state["pilotObserved"])

    def test_events_insert_contains_server_fence_and_sync_setting(self):
        runner = MemoryRunner([event("a")])
        runner.load_batches()
        sql = runner.ch.requests[0][0]
        self.assertIn("telemetry_retention_fence", sql)
        self.assertIn("async_insert=0", sql)
        self.assertIn("input_format_skip_unknown_fields=0", sql)

    def test_activation_uses_top_level_dml_cte_in_transaction_and_checkpoints_intent(self):
        runner = MemoryRunner([event("a")])
        runner.state["phase"] = "verified"
        runner.verify = lambda: None
        runner.collision = lambda: [{"id": runner.artifact.tenant, "sso_enabled": False}]
        statements = []
        def execute(sql):
            self.assertEqual(runner.saved[-1]["phase"], "activating")
            statements.append(sql)
            return ['[{"id":"' + runner.artifact.tenant + '"}]']
        runner.pg.execute = execute
        runner.activate()
        self.assertEqual(statements[0], "BEGIN")
        self.assertTrue(statements[1].startswith("WITH changed AS (UPDATE"))
        self.assertEqual(statements[-1], "COMMIT")
        self.assertEqual(runner.state["phase"], "active")

    def test_lost_activation_ack_reconciles_without_another_update(self):
        runner = MemoryRunner([event("a")])
        runner.state["phase"] = "activating"
        runner.verify = lambda: None
        runner.collision = lambda: [{"id": runner.artifact.tenant, "sso_enabled": True}]
        runner.pg.execute = lambda _: self.fail("activation must not update again")
        runner.activate()
        self.assertEqual(runner.state["phase"], "active")

    def test_activation_failure_keeps_recoverable_intent(self):
        runner = MemoryRunner([event("a")])
        runner.state["phase"] = "verified"
        runner.verify = lambda: None
        runner.collision = lambda: [{"id": runner.artifact.tenant, "sso_enabled": False}]
        def fail(_):
            raise seed.SeedError("connection lost")
        runner.pg.execute = fail
        with self.assertRaises(seed.SeedError):
            runner.activate()
        self.assertEqual(runner.saved[-1]["phase"], "activating")


class CheckpointStatusTests(unittest.TestCase):
    def runner(self, phase="loaded"):
        runner = MemoryRunner([event("a")])
        runner.state.update({"phase": phase, "lastError": "old failure", "pausedAt": "2026-05-01T00:00:00Z"})
        runner.verify_postgres = lambda: None
        runner.verify_clickhouse = lambda: None
        return runner

    def test_success_preserves_legacy_failure_and_clears_current_error(self):
        runner = self.runner()
        runner.verify()
        self.assertEqual(runner.state["phase"], "verified")
        self.assertNotIn("lastError", runner.saved[-1])
        self.assertNotIn("pausedAt", runner.saved[-1])
        self.assertEqual(runner.state["errorHistory"], [{"message": "old failure", "pausedAt": "2026-05-01T00:00:00Z"}])

    def test_active_reverification_does_not_duplicate_archived_failure(self):
        runner = self.runner("active")
        runner.state["errorHistory"] = [{"message": "old failure", "pausedAt": "2026-05-01T00:00:00Z"}]
        runner.verify()
        runner.verify()
        self.assertEqual(runner.state["phase"], "active")
        self.assertEqual(len(runner.state["errorHistory"]), 1)
        self.assertNotIn("lastError", runner.saved[-1])
        self.assertNotIn("pausedAt", runner.saved[-1])

    def test_new_failure_preserves_both_errors_until_successful_reverification(self):
        runner = self.runner()
        runner.locked = contextlib.nullcontext
        runner.schema = lambda: None
        runner.load_state = lambda _: None
        def fail():
            raise seed.SeedError("new failure")
        runner.verify_clickhouse = fail
        with self.assertRaisesRegex(seed.SeedError, "new failure"):
            runner.run("verify")
        self.assertEqual([entry["message"] for entry in runner.state["errorHistory"]], ["old failure", "new failure"])
        self.assertEqual(runner.saved[-1]["lastError"], "new failure")
        self.assertEqual(runner.state["errorHistory"][-1]["pausedAt"], runner.state["pausedAt"])
        runner.verify_clickhouse = lambda: None
        runner.run("verify")
        self.assertEqual(len(runner.state["errorHistory"]), 2)
        self.assertNotIn("lastError", runner.saved[-1])
        self.assertNotIn("pausedAt", runner.saved[-1])


class ActivationPg:
    """PG 트랜잭션 밖의 관찰자는 COMMIT 전 규칙 변경을 보지 못하는 모형."""
    def __init__(self, tables):
        self.tables = copy.deepcopy(tables)
        self.pending = None
        self.statements, self.observed = [], []
        self.lose_commit_ack = False
        self.fail_tenant_update = False

    def query(self, sql):
        table = re.search(r"FROM ([a-z_.]+)", sql)[1]
        tenant = re.search(r"(?:tenant_id|id)='([^']+)'", sql)[1]
        rows = (self.pending if self.pending is not None else self.tables)[table]
        return copy.deepcopy([row for row in rows if row.get("tenant_id", row.get("id")) == tenant])

    def execute(self, sql):
        self.statements.append(sql)
        self.observed.append(copy.deepcopy(self.tables))
        if sql == "BEGIN":
            self.pending = copy.deepcopy(self.tables)
        elif sql == "ROLLBACK":
            self.pending = None
        elif sql == "COMMIT":
            self.tables, self.pending = self.pending, None
            if self.lose_commit_ack:
                self.lose_commit_ack = False
                raise seed.SeedError("commit accepted but ACK lost")
        elif sql.startswith("WITH changed AS (UPDATE " + seed.ALERT_RULE_TABLE):
            tenant = re.search(r"r.tenant_id='([^']+)'", sql)[1]
            values = re.findall(r"\('([^']+)',(TRUE|FALSE),(\d+)\)", sql)
            changed = []
            for rule, enabled, version in values:
                for row in self.pending[seed.ALERT_RULE_TABLE]:
                    if row["tenant_id"] == tenant and row["rule_id"] == rule and row["version"] == int(version) and not row["enabled"]:
                        row["enabled"] = enabled == "TRUE"
                        changed.append({"rule_id": rule})
            return [seed.encode(changed)]
        elif sql.startswith("WITH changed AS (UPDATE enrollment.tenants"):
            tenant = re.search(r"WHERE id='([^']+)'", sql)[1]
            changed = []
            for row in self.pending["enrollment.tenants"]:
                if row["id"] == tenant and not row["sso_enabled"] and not self.fail_tenant_update:
                    row["sso_enabled"] = True
                    changed.append({"id": tenant})
            return [seed.encode(changed)]
        else:
            raise AssertionError("unexpected SQL: " + sql)
        return []


class AlertActivationTests(unittest.TestCase):
    def runner(self):
        runner = MemoryRunner([event("a")])
        tenant = runner.artifact.tenant
        runner.artifact.manifest = copy.deepcopy(runner.artifact.manifest)
        runner.artifact.tables = {
            "enrollment.tenants": [{"id": tenant, "name": "soma", "slug": "soma", "sso_enabled": False,
                "oidc_require_verified_email": True, "oidc_issuer": "https://issuer.example.test",
                "oidc_client_id": "client", "oidc_client_secret_ref": "config:local"}],
            seed.ALERT_RULE_TABLE: [{"tenant_id": tenant, "rule_id": rule, "enabled": False,
                "version": 1, "updated_at": "2026-05-01T00:00:00Z", "updated_by": tenant}
                for rule in ("spend_spike", "unused_model")],
        }
        runner.artifact.manifest["activationRules"] = [
            {"ruleId": rule, "desiredEnabled": rule == "spend_spike", "expectedVersion": 1}
            for rule in ("spend_spike", "unused_model")]
        runner.artifact.activation_rules = seed.activation_rules(runner.artifact.manifest, runner.artifact.tables)
        runner.pg = ActivationPg(runner.artifact.tables)
        runner.pg.tables[seed.ALERT_RULE_TABLE].append({
            "tenant_id": "other-tenant", "rule_id": "spend_spike", "enabled": True, "version": 7})
        runner.pg_columns = {table: {key: "uuid" if key in ("id", "tenant_id", "updated_by") else
            "timestamp with time zone" if key == "updated_at" else "boolean" if key in ("enabled", "sso_enabled") else "text"
            for key in rows[0]} for table, rows in runner.artifact.tables.items()}
        runner.state["phase"] = "verified"
        runner.verify_clickhouse = lambda: None
        return runner

    def test_manifest_preserves_original_disabled_rules_and_rejects_incomplete_or_unstaged_contract(self):
        runner = self.runner()
        self.assertFalse(runner.artifact.activation_rules["unused_model"]["desiredEnabled"])
        for problem in ("missing", "extra", "duplicate", "version", "enabled_type", "version_type", "unstaged", "null", "extra_field"):
            with self.subTest(problem=problem):
                manifest, tables = copy.deepcopy(runner.artifact.manifest), copy.deepcopy(runner.artifact.tables)
                entries = manifest["activationRules"]
                if problem == "missing": entries.pop()
                elif problem == "extra": entries.append({"ruleId": "unknown", "desiredEnabled": True, "expectedVersion": 1})
                elif problem == "duplicate": entries.append(entries[0])
                elif problem == "version": entries[0]["expectedVersion"] = 2
                elif problem == "enabled_type": entries[0]["desiredEnabled"] = "true"
                elif problem == "version_type": entries[0]["expectedVersion"] = True
                elif problem == "unstaged": tables[seed.ALERT_RULE_TABLE][0]["enabled"] = True
                elif problem == "null": manifest["activationRules"] = None
                else: entries[0]["extra"] = True
                with self.assertRaises(seed.SeedError):
                    seed.activation_rules(manifest, tables)

    def test_staged_then_active_verification_preserves_artifact_and_rule_metadata(self):
        runner = self.runner()
        original = copy.deepcopy(runner.artifact.tables)
        runner.verify_postgres()
        runner.activate()
        self.assertEqual(runner.state["phase"], "active")
        runner.verify_postgres()
        self.assertEqual(runner.artifact.tables, original)
        actual = runner.pg.tables[seed.ALERT_RULE_TABLE]
        self.assertEqual([row["enabled"] for row in actual], [True, False, True])
        for before, after in zip(original[seed.ALERT_RULE_TABLE], actual):
            self.assertEqual({k: v for k, v in before.items() if k != "enabled"}, {k: v for k, v in after.items() if k != "enabled"})
        self.assertEqual(actual[-1], {"tenant_id": "other-tenant", "rule_id": "spend_spike", "enabled": True, "version": 7})
        for visible in runner.pg.observed:
            self.assertFalse(visible["enrollment.tenants"][0]["sso_enabled"])
            self.assertFalse(visible[seed.ALERT_RULE_TABLE][0]["enabled"])

    def test_failure_between_rule_and_login_updates_rolls_back_and_can_retry(self):
        runner = self.runner()
        before = copy.deepcopy(runner.pg.tables)
        runner.pg.fail_tenant_update = True
        with self.assertRaises(seed.SeedError):
            runner.activate()
        self.assertEqual(runner.pg.tables, before)
        self.assertEqual(runner.state["phase"], "activating")
        self.assertEqual(runner.pg.statements[-1], "ROLLBACK")
        runner.pg.fail_tenant_update = False
        runner.activate()
        self.assertEqual(runner.state["phase"], "active")

    def test_commit_ack_loss_reconciles_both_states_without_another_write(self):
        runner = self.runner()
        runner.pg.lose_commit_ack = True
        with self.assertRaisesRegex(seed.SeedError, "ACK lost"):
            runner.activate()
        self.assertEqual(runner.state["phase"], "activating")
        self.assertTrue(runner.pg.tables["enrollment.tenants"][0]["sso_enabled"])
        self.assertTrue(runner.pg.tables[seed.ALERT_RULE_TABLE][0]["enabled"])
        count = len(runner.pg.statements)
        runner.activate()
        runner.activate()
        self.assertEqual(runner.state["phase"], "active")
        self.assertEqual(len(runner.pg.statements), count)

    def test_mixed_activation_states_and_changed_versions_are_rejected_without_overwrite(self):
        for phase, sso, enabled, version in (("activating", True, False, 1), ("activating", False, True, 1),
                ("active", True, True, 2), ("verified", False, False, 2)):
            with self.subTest(phase=phase, sso=sso, enabled=enabled, version=version):
                runner = self.runner()
                runner.state["phase"] = phase
                runner.pg.tables["enrollment.tenants"][0]["sso_enabled"] = sso
                runner.pg.tables[seed.ALERT_RULE_TABLE][0].update(enabled=enabled, version=version)
                before = copy.deepcopy(runner.pg.tables)
                with self.assertRaises(seed.SeedError):
                    runner.activate()
                self.assertEqual(runner.pg.tables, before)
                self.assertEqual(runner.pg.statements, [])

    def test_active_apply_and_resume_are_verification_only(self):
        runner = self.runner()
        runner.activate()
        before = copy.deepcopy(runner.pg.tables)
        count = len(runner.pg.statements)
        runner.locked = contextlib.nullcontext
        runner.schema = lambda: None
        runner.load_state = lambda _: None
        runner.run("apply")
        runner.run("resume")
        self.assertEqual(runner.pg.tables, before)
        self.assertEqual(len(runner.pg.statements), count)

    def test_legacy_enabled_rules_allow_existing_verification_but_reject_write_commands(self):
        runner = self.runner()
        del runner.artifact.manifest["activationRules"]
        runner.artifact.tables[seed.ALERT_RULE_TABLE][0]["enabled"] = True
        runner.artifact.activation_rules = seed.activation_rules(runner.artifact.manifest, runner.artifact.tables)
        self.assertIsNone(runner.artifact.activation_rules)
        runner.state["phase"] = "active"
        runner.pg.tables["enrollment.tenants"][0]["sso_enabled"] = True
        runner.pg.tables[seed.ALERT_RULE_TABLE][0]["enabled"] = True
        runner.verify()
        for command in ("apply", "resume", "activate-login"):
            with self.subTest(command=command), self.assertRaisesRegex(seed.SeedError, "구 manifest"):
                runner.run(command)
        self.assertEqual(runner.pg.statements, [])


class PrecisionAndInputTests(unittest.TestCase):
    def test_decimal_and_large_integer_are_not_converted_to_float(self):
        value = {"cost": Decimal("12345678901234567890.123456789012"), "tokens": 9007199254740993}
        self.assertEqual(seed.decode(seed.encode(value)), value)

    def test_nanoseconds_are_compared_exactly(self):
        a = "2026-05-01T01:00:00.123456789Z"
        b = "2026-05-01 10:00:00.123456789+09:00"
        self.assertEqual(seed.instant(a), seed.instant(b))
        self.assertNotEqual(seed.instant(a), seed.instant(a.replace("789", "788")))

    def test_postgres_uuid_normalizes_32_hex_daemon_run_id(self):
        self.assertTrue(seed.compare_value("3fc481db551038b294f120a015fe61ea", "3fc481db-5510-38b2-94f1-20a015fe61ea", "uuid"))
        self.assertFalse(seed.compare_value("3fc481db551038b294f120a015fe61ea", "3fc481db-5510-38b2-94f1-20a015fe61eb", "uuid"))

    def test_postgres_rounds_microseconds_but_clickhouse_preserves_nanoseconds(self):
        expected = "2026-05-01T01:00:00.000000001Z"
        actual = "2026-05-01T01:00:00+00:00"
        self.assertTrue(seed.compare_value(expected, actual, "timestamp with time zone"))
        self.assertFalse(seed.compare_value(expected, actual, "DateTime64(9, 'UTC')"))
        self.assertTrue(seed.compare_value(expected.replace("001Z", "500Z"), actual, "timestamp with time zone"))
        self.assertTrue(seed.compare_value("2026-05-01T01:00:00.000001500Z", "2026-05-01T01:00:00.000002+00:00", "timestamp with time zone"))

    def test_duplicate_json_fields_rejected(self):
        with self.assertRaises(seed.SeedError):
            seed.decode('{"tenant":"one","tenant":"two"}')

    def test_sql_values_escape_quotes_and_backslashes(self):
        self.assertEqual(seed.pg_literal("a'b\\c"), "'a''b\\c'")
        self.assertEqual(seed.ch_literal("a'b\\c"), "'a\\'b\\\\c'")
        with self.assertRaises(seed.SeedError):
            seed.identifier("tenant; DELETE")

    def test_transport_limit_counts_utf8_bytes(self):
        runner = MemoryRunner([event("a")])
        runner.ch_columns["telemetry_events"]["extra"] = "String"
        rows = [dict(event(str(i)), extra="한" * 500_000) for i in range(3)]
        parts = list(runner.insert_payloads("telemetry_events", rows))
        self.assertEqual(len(parts), 2)
        self.assertTrue(all(len(body) <= seed.MAX_BYTES for _, body, _ in parts))
        self.assertEqual(sum(count for _, _, count in parts), 3)

    def test_float64_uses_string_wire_to_precise_server_conversion(self):
        runner = MemoryRunner([event("a")])
        runner.ch_columns["telemetry_events"]["example_float"] = "Nullable(Float64)"
        sql, body, _ = next(runner.insert_payloads("telemetry_events", [dict(event("a"), example_float=Decimal("0.12345678901234567"))]))
        self.assertIn("toFloat64(example_float)", sql)
        self.assertEqual(seed.decode(body)["example_float"], "0.12345678901234567")

    def test_changed_batch_body_rejected_before_db_connection(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = object.__new__(seed.Artifact)
            artifact.root = Path(directory).resolve()
            artifact.tenant = FakeArtifact.tenant
            artifact.period_start = seed.instant("2026-04-30T00:00:00Z")
            artifact.period_until = seed.instant("2026-05-02T00:00:00Z")
            artifact.snapshot = artifact.period_until
            artifact.installation_ids = {"seed-installation"}
            artifact.member_ids = set()
            artifact.team_ids = set()
            body = seed.encode(event("a")).encode()
            path = artifact.root / "batch.jsonl"
            path.write_bytes(body)
            batch = {"file": path.name, "bytes": len(body), "sha256": seed.sha256(body), "id": "b", "rows": 1, "table": "telemetry_events", "identityColumn": "observation_id", "timeColumn": "source_time", "timeMin": event("a")["source_time"], "timeMax": event("a")["source_time"]}
            self.assertEqual(len(artifact.batch_rows(batch)), 1)
            path.write_bytes(body.replace(b'"a"', b'"b"'))
            with self.assertRaisesRegex(seed.SeedError, "해시"):
                artifact.batch_rows(batch)

    def test_artifact_path_cannot_escape_or_follow_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            with self.assertRaises(seed.SeedError):
                seed.within(root, "../outside")
            (root / "link").symlink_to("/tmp")
            with self.assertRaises(seed.SeedError):
                seed.within(root, "link/file")


class MonitorAndTargetTests(unittest.TestCase):
    def sample(self, **changes):
        result = {"observedAt": "2026-05-01T00:00:00Z", "memoryWorkingSetBytes": 500, "memoryLimitBytes": 2000, "readLatencyMs": 10, "baselineReadLatencyMs": 10}
        result.update(changes)
        return result

    def check(self, value, after=5):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "monitor.json"
            path.write_text(seed.encode(value))
            return seed.monitor_check(path, seed.instant("2026-05-01T00:00:00Z") + after)

    def test_fresh_low_memory_sample_passes(self):
        self.assertEqual(self.check(self.sample())["memoryWorkingSetBytes"], 500)

    def test_memory_threshold_is_inclusive(self):
        with self.assertRaises(seed.Paused):
            self.check(self.sample(memoryWorkingSetBytes=1500))

    def test_latency_threshold_is_inclusive(self):
        with self.assertRaises(seed.Paused):
            self.check(self.sample(readLatencyMs=20))

    def test_stale_or_future_metric_cannot_pass_as_zero_load(self):
        for after in (46, -3):
            with self.subTest(after=after), self.assertRaises(seed.SeedError):
                self.check(self.sample(), after)

    def test_original_metric_expiry_overrides_fresh_file_timestamp(self):
        with self.assertRaisesRegex(seed.SeedError, "원본 지표"):
            self.check(self.sample(validUntil="2026-05-01T00:00:04Z"), after=5)
        self.assertEqual(self.check(self.sample(validUntil="2026-05-01T00:00:10Z"), after=5)["memoryWorkingSetBytes"], 500)

    def test_invalid_baseline_rejected(self):
        with self.assertRaises(seed.SeedError):
            self.check(self.sample(baselineReadLatencyMs=0))

    def test_prod_and_plaintext_password_are_rejected(self):
        data = {"schemaVersion": 1, "environment": "prod", "targetId": "x", "postgres": {}, "clickhouse": {}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "target.json"
            path.write_text(seed.encode(data))
            with self.assertRaises(seed.SeedError):
                seed.Target(path, SimpleNamespace(manifest={"environment": "prod"}))
            data["environment"] = "dev"
            data["postgres"]["password"] = "not-allowed"
            path.write_text(seed.encode(data))
            with self.assertRaisesRegex(seed.SeedError, "비밀 원문"):
                seed.Target(path, SimpleNamespace(manifest={"environment": "dev"}))

    def test_local_hostaddr_cannot_override_loopback_boundary(self):
        data = {"schemaVersion": 1, "environment": "local", "targetId": "local-seed",
                "postgres": {"host": "localhost", "port": 5432, "dbname": "seed", "user": "seed", "sslmode": "disable"},
                "clickhouse": {"url": "http://127.0.0.1:8123", "database": "default"}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "target.json"
            for address in (None, "127.0.0.1", "::1", "192.0.2.1", "2001:db8::1", "db.example.test", ""):
                with self.subTest(hostaddr=address):
                    candidate = copy.deepcopy(data)
                    if address is not None:
                        candidate["postgres"]["hostaddr"] = address
                    path.write_text(seed.encode(candidate))
                    if address in (None, "127.0.0.1", "::1"):
                        seed.Target(path, SimpleNamespace(manifest={"environment": "local"}))
                    else:
                        with self.assertRaisesRegex(seed.SeedError, "hostaddr"):
                            seed.Target(path, SimpleNamespace(manifest={"environment": "local"}))

    def test_dev_clickhouse_requires_tls_or_loopback_tunnel(self):
        data = {"schemaVersion": 1, "environment": "dev", "targetId": "dev-seed", "monitorFile": "/tmp/monitor.json",
                "postgres": {"host": "db.example.test", "hostaddr": "127.0.0.1", "port": 15432,
                             "dbname": "seed", "user": "seed", "sslmode": "verify-full", "sslrootcert": "/tmp/ca.pem"},
                "clickhouse": {"url": "", "database": "default"}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "target.json"
            for url in ("https://ch.example.test", "http://localhost:8123", "http://127.0.0.1:18123",
                        "http://[::1]:8123", "http://ch.example.test:8123", "http://192.0.2.1:8123"):
                with self.subTest(url=url):
                    candidate = copy.deepcopy(data)
                    candidate["clickhouse"]["url"] = url
                    path.write_text(seed.encode(candidate))
                    if url in ("http://ch.example.test:8123", "http://192.0.2.1:8123"):
                        with self.assertRaisesRegex(seed.SeedError, "HTTPS 또는 loopback"):
                            seed.Target(path, SimpleNamespace(manifest={"environment": "dev"}))
                    else:
                        seed.Target(path, SimpleNamespace(manifest={"environment": "dev"}))


def pressure_sample():
    sample = MonitorAndTargetTests().sample(observedAt=seed.now_iso())
    sample["pressureGuard"] = {
        "schemaVersion": 1, "identity": {"containerId": "container-a", "startedAt": "2026-01-01T00:00:00Z"},
        "baseline": {"observedAt": "2026-04-01T00:00:00Z", "memoryEvents": {"max": 17, "oom": 0, "oom_kill": 0}},
        "memoryEvents": {"max": 17, "oom": 0, "oom_kill": 0}, "latched": False,
    }
    return sample


class PerfTargetTests(unittest.TestCase):
    def bundle(self, directory):
        root = Path(directory)
        environment = "perf-20261030-a"
        pg = {"database": "controlplane", "username": "seed", "address": "192.0.2.10", "port": 5432, "database_oid": 16384}
        ch = {"hostname": "clickhouse-0", "database": "default", "version": "24.8.14.39",
              "table_uuids": {"telemetry_events": "11111111-1111-4111-8111-111111111111", "telemetry_ingest_ledger": "22222222-2222-4222-8222-222222222222"}}
        rds_arn = "arn:aws:rds:ap-northeast-2:111122223333:db:perf-postgres"
        cluster_arn = "arn:aws:eks:ap-northeast-2:111122223333:cluster/perf-eks"
        image = "clickhouse/clickhouse-server@sha256:" + "a" * 64
        sources = {
            "profile": {"schema_version": 3, "name": "fixture-only"},
            "rds": {"DBInstances": [{"DBInstanceArn": rds_arn, "Engine": "postgres", "DBInstanceStatus": "available", "DBName": "controlplane",
                    "Endpoint": {"Address": "perf.example.test", "Port": 5432}, "TagList": [{"Key": "pulsemetry:perf-environment", "Value": environment}]}]},
            "cluster": {"cluster": {"arn": cluster_arn, "name": "perf-eks", "status": "ACTIVE", "tags": {"pulsemetry:perf-environment": environment}}},
            "clickhousePod": {"kind": "Pod", "metadata": {"namespace": "pulsemetry-perf", "name": "clickhouse-0", "ownerReferences": [{"kind": "StatefulSet", "name": "clickhouse", "uid": "statefulset-uid"}]}, "spec": {"containers": [{"image": image}]}},
            "identity": {"postgres": pg, "clickhouse": ch},
        }
        sources["resolved"] = {"schema_version": 3, "kind": "environment-resolved", "status": "ready", "environment_id": environment,
            "aws": {"region": "ap-northeast-2"}, "infra": {"profile_sha256": seed.sha256(seed.encode(sources["profile"]).encode())},
            "kafka": {"provider": "strimzi"}, "kubernetes": {"cluster_arn": cluster_arn, "cluster_name": "perf-eks", "namespaces": {"app": "pulsemetry-perf"},
                "workloads": {"clickhouse": {"namespace": "pulsemetry-perf", "name": "clickhouse", "uid": "statefulset-uid", "image": image}}},
            "datastores": {"rds": {"arn": rds_arn, "endpoint": {"host": "perf.example.test", "port": 5432, "database": "controlplane"}}},
            "endpoints": {"clickhouse_http": "http://clickhouse.pulsemetry-perf.svc:8123"}}
        proof = {"schemaVersion": 1, "kind": "perf-seed-target-evidence", "environmentId": environment,
                 "targetId": "history-a", "observedAt": "2026-10-29T00:00:00Z"}
        target = {"schemaVersion": 1, "environment": "perf", "perfEnvironmentId": environment, "targetId": "history-a", "monitorFile": "/tmp/perf-monitor.json",
                  "postgres": {"host": "perf.example.test", "hostaddr": "127.0.0.1", "port": 15432, "dbname": "controlplane", "user": "seed", "sslmode": "verify-full", "sslrootcert": "/tmp/ca.pem"},
                  "clickhouse": {"url": "http://127.0.0.1:18123", "database": "default"}}
        artifact = SimpleNamespace(manifest={"environment": "perf", "perfEnvironmentId": environment})
        def write():
            for key, value in sources.items():
                raw = seed.encode(value).encode()
                (root / (key + ".json")).write_bytes(raw)
                proof[key] = {"file": key + ".json", "sha256": seed.sha256(raw)}
            raw = seed.encode(proof).encode()
            (root / "proof.json").write_bytes(raw)
            target["targetEvidence"] = {"file": "proof.json", "sha256": seed.sha256(raw)}
            (root / "target.json").write_text(seed.encode(target))
            return root / "target.json"
        return target, artifact, sources, proof, write

    def test_perf_target_binds_sources_and_requires_real_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            _, artifact, sources, _, write = self.bundle(directory)
            target = seed.Target(write(), artifact)
            self.assertEqual(sources["identity"], target.expected_identity)
            runner = MemoryRunner([event("a")])
            runner.target = target
            runner.pg = mock.Mock()
            runner.pg.query.return_value = [sources["identity"]["postgres"]]
            actual_ch = {k: v for k, v in sources["identity"]["clickhouse"].items() if k != "table_uuids"}
            tables = [{"name": k, "uuid": v} for k, v in sources["identity"]["clickhouse"]["table_uuids"].items()]
            runner.ch.query = mock.Mock(side_effect=[[copy.deepcopy(actual_ch)], tables])
            self.assertEqual(target.expected_identity, runner.identity())
            tables[0]["uuid"] = "33333333-3333-4333-8333-333333333333"
            runner.ch.query = mock.Mock(side_effect=[[copy.deepcopy(actual_ch)], tables])
            runner.locked = mock.Mock()
            with self.assertRaisesRegex(seed.SeedError, "실제 DB identity"):
                runner.run("apply")
            runner.locked.assert_not_called()
            runner.pg.execute.assert_not_called()

    def test_perf_rejects_missing_or_tampered_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            target, artifact, _, _, write = self.bundle(directory)
            path = write()
            del target["targetEvidence"]
            path.write_text(seed.encode(target))
            with self.assertRaisesRegex(seed.SeedError, "원천 증거"):
                seed.Target(path, artifact)
            path = write()
            (Path(directory) / "identity.json").write_text("{}")
            with self.assertRaisesRegex(seed.SeedError, "SHA-256"):
                seed.Target(path, artifact)

    def test_perf_rejects_rehashed_wrong_environment_targets_and_sources(self):
        mutations = [
            lambda t, s, p: t.update(perfEnvironmentId="perf-different-a"),
            lambda t, s, p: s["resolved"].update(schema_version=2),
            lambda t, s, p: s["resolved"].update(status="not-deployed"),
            lambda t, s, p: s["resolved"].update(environment_id="perf-different-a"),
            lambda t, s, p: s["resolved"]["infra"].update(profile_sha256="0" * 64),
            lambda t, s, p: s["resolved"]["aws"].update(region="us-east-1"),
            lambda t, s, p: s["resolved"]["kafka"].update(provider="msk"),
            lambda t, s, p: s["rds"]["DBInstances"][0].update(TagList=[]),
            lambda t, s, p: s["cluster"]["cluster"].update(tags={"pulsemetry:perf-environment": "dev"}),
            lambda t, s, p: t["postgres"].update(host="dev.example.test"),
            lambda t, s, p: t["postgres"].update(hostaddr="192.0.2.20"),
            lambda t, s, p: t["postgres"].update(sslmode="disable"),
            lambda t, s, p: s["clickhousePod"]["metadata"].update(namespace="pulsemetry-dev"),
            lambda t, s, p: s["clickhousePod"]["metadata"]["ownerReferences"][0].update(uid="dev-uid"),
            lambda t, s, p: s["identity"]["clickhouse"].update(table_uuids={}),
            lambda t, s, p: t["clickhouse"].update(url="https://dev.example.test"),
            lambda t, s, p: t.pop("monitorFile"),
        ]
        for index, change in enumerate(mutations):
            with self.subTest(case=index), tempfile.TemporaryDirectory() as directory:
                target, artifact, sources, proof, write = self.bundle(directory)
                change(target, sources, proof)
                with self.assertRaises(seed.SeedError):
                    seed.Target(write(), artifact)

    def test_perf_cannot_activate_even_before_target_or_db_connection(self):
        artifact = SimpleNamespace(manifest={"environment": "perf"})
        with mock.patch.object(seed, "Artifact", return_value=artifact), mock.patch.object(seed, "Target") as target, mock.patch.object(seed, "Postgres") as pg:
            with contextlib.redirect_stderr(__import__("io").StringIO()):
                self.assertEqual(2, seed.main(["activate-login", "--manifest", "m", "--target", "t", "--state-dir", "s"]))
            target.assert_not_called()
            pg.assert_not_called()
        runner = MemoryRunner([event("a")])
        runner.target.data["environment"] = "perf"
        runner.verify = mock.Mock()
        with self.assertRaisesRegex(seed.SeedError, "활성화"):
            runner.activate()
        runner.verify.assert_not_called()

    def test_perf_verification_rejects_active_checkpoint_without_querying(self):
        runner = MemoryRunner([event("a")])
        runner.target.data["environment"] = "perf"
        runner.pg = mock.Mock()
        for phase in ("active", "activating"):
            runner.state["phase"] = phase
            with self.subTest(phase=phase), self.assertRaisesRegex(seed.SeedError, "비활성"):
                runner.verify_postgres()
        runner.pg.query.assert_not_called()


class PressureGuardTests(unittest.TestCase):
    def test_old_immutable_baseline_is_valid_with_fresh_sample(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "monitor.json"
            path.write_text(seed.encode(pressure_sample()))
            result = seed.monitor_check(path, require_pressure=True)
            self.assertEqual(result["pressureBaseline"]["baseline"]["memoryEvents"]["max"], 17)

    def test_missing_counters_boolean_values_and_stale_sample_fail_closed(self):
        for failure in ("missing", "boolean", "stale", "future_baseline"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                value = pressure_sample()
                if failure == "missing": del value["pressureGuard"]
                elif failure == "boolean": value["pressureGuard"]["memoryEvents"]["max"] = False
                elif failure == "stale": value["observedAt"] = "2026-01-01T00:00:00Z"
                else: value["pressureGuard"]["baseline"]["observedAt"] = "2099-01-01T00:00:00Z"
                path = Path(directory) / "monitor.json"; path.write_text(seed.encode(value))
                with self.assertRaises(seed.SeedError): seed.monitor_check(path, require_pressure=True)

    def test_max_oom_kill_increase_and_counter_decrease_pause_below_working_limit(self):
        for key, delta in (("max", 1), ("oom", 1), ("oom_kill", 1), ("max", -1)):
            with self.subTest(key=key, delta=delta), tempfile.TemporaryDirectory() as directory:
                value = pressure_sample(); value["pressureGuard"]["memoryEvents"][key] += delta
                path = Path(directory) / "monitor.json"; path.write_text(seed.encode(value))
                with self.assertRaises(seed.PressurePaused): seed.monitor_check(path, require_pressure=True)

    def test_checkpoint_pins_identity_baseline_and_keeps_pressure_latched(self):
        for changed in ("container", "restarted", "baseline", "pressure"):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "monitor.json"; original = pressure_sample(); path.write_text(seed.encode(original))
                runner = MemoryRunner([event("a")]); runner.target.data = {"environment": "dev", "monitorFile": str(path)}
                seed.Runner.monitor(runner)
                self.assertIn("pressureBaseline", runner.saved[-1])
                changed_value = copy.deepcopy(original)
                if changed == "container": changed_value["pressureGuard"]["identity"]["containerId"] = "container-b"
                elif changed == "restarted": changed_value["pressureGuard"]["identity"]["startedAt"] = seed.now_iso()
                elif changed == "baseline": changed_value["pressureGuard"]["baseline"]["observedAt"] = "2026-04-02T00:00:00Z"
                else: changed_value["pressureGuard"]["memoryEvents"]["max"] += 1
                path.write_text(seed.encode(changed_value))
                with self.assertRaises(seed.PressurePaused): seed.Runner.monitor(runner)
                self.assertTrue(runner.saved[-1]["pressureStopLatched"])
                path.write_text(seed.encode(original))
                with self.assertRaises(seed.PressurePaused): seed.Runner.monitor(runner)

    def test_dev_runner_refuses_legacy_monitor(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "monitor.json"; path.write_text(seed.encode(MonitorAndTargetTests().sample(observedAt=seed.now_iso())))
            runner = MemoryRunner([event("a")]); runner.target.data = {"environment": "dev", "monitorFile": str(path)}
            with self.assertRaisesRegex(seed.SeedError, "압력 관측 계약"): seed.Runner.monitor(runner)


class SummaryLatencyTests(unittest.TestCase):
    policy = {"mode": seed.SUMMARY_LATENCY_MODE, "staticLimitMs": Decimal("58.816084000000046")}
    now = seed.instant("2026-05-01T00:00:05Z")

    def sample(self, servers=(1, 2, 3, 4, 5), clients=(10, 20, 30, 40, 50)):
        value = pressure_sample()
        value.pop("readLatencyMs"); value.pop("baselineReadLatencyMs")
        value.update(observedAt="2026-05-01T00:00:00Z", validUntil="2026-05-01T00:00:40Z",
                     latencyMode=seed.SUMMARY_LATENCY_MODE, measurement="http_response_summary_elapsed_ns",
                     aggregation="median_of_5", waitEndOfQuery=True, clickhouseErrorCount=0, stopReasons=[],
                     baselineRecalibrated=False, clientBaselineUsedAsServerBaseline=False,
                     proposedServerElapsedLimitMs=self.policy["staticLimitMs"],
                     serverElapsedMedianMs=sorted(servers)[2], clientElapsedMedianMs=sorted(clients)[2])
        probes = []
        for index, (server, client) in enumerate(zip(servers, clients)):
            guard = {"observedAt": "2026-05-01T00:00:0" + str(index) + "Z", "identity": value["pressureGuard"]["identity"],
                     "memoryEvents": value["pressureGuard"]["memoryEvents"], "memoryWorkingSetBytes": 500,
                     "memoryCurrentBytes": 600, "memoryLimitBytes": 2000,
                     "observedClickhouseErrorCount": 0, "acceptedPriorReadonlyErrorCount": 0}
            ns = str(int(Decimal(str(server)) * 1_000_000))
            summary = {"elapsed_ns": ns}
            probes.append({"index": index, "startedAt": "2026-05-01T00:00:0" + str(index) + "Z",
                           "completedAt": "2026-05-01T00:00:0" + str(index) + "Z",
                           "startIntervalSeconds": None if index == 0 else 1, "ok": True,
                           "before": copy.deepcopy(guard), "after": copy.deepcopy(guard),
                           "httpStatus": 200, "httpWaitEndOfQuery": True, "summaryHeaderCount": 1,
                           "requestSettings": {"wait_end_of_query": 1, "send_progress_in_http_headers": 0},
                           "probeSqlSha256": seed.SUMMARY_PROBE_SQL_SHA,
                           "responseBytes": 4, "queryId": "probe-" + str(index),
                           "summaryRaw": seed.encode(summary), "summary": summary,
                           "serverElapsedNs": ns, "serverElapsedMs": server, "clientElapsedMs": client})
        value["latencyEvidence"] = {"windowStartedAt": value["observedAt"],
                "windowCompletedAt": "2026-05-01T00:00:04Z", "windowElapsedSeconds": 4,
                "measurement": value["measurement"], "aggregation": "median_of_5", "httpWaitEndOfQuery": True,
                "proposedServerElapsedLimitMs": self.policy["staticLimitMs"], "rawProbes": probes,
                "rawServerElapsedMs": list(servers), "rawClientElapsedMs": list(clients),
                "serverElapsedMedianMs": sorted(servers)[2], "clientElapsedMedianMs": sorted(clients)[2]}
        return value

    def check(self, value, policy=None, now=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "monitor.json"; path.write_text(seed.encode(value))
            return seed.monitor_check(path, self.now if now is None else now, require_pressure=True,
                                      latency_policy=self.policy if policy is None else policy)

    def test_server_median_uses_raw_five_and_client_delay_is_only_diagnostic(self):
        result = self.check(self.sample((1, 2, 3, 4, 200), (1000, 2000, 3000, 4000, 5000)))
        self.assertEqual(result["serverElapsedMedianMs"], 3)
        self.assertEqual(result["clientElapsedMedianMs"], 3000)
        self.assertNotIn("readLatencyMs", result)
        self.assertNotIn("baselineReadLatencyMs", result)
        self.assertEqual(result["pressureBaseline"]["baseline"]["memoryEvents"]["max"], 17)

    def test_three_slow_values_and_exact_cap_stop(self):
        for servers, cap in (((1, 2, 60, 61, 62), self.policy["staticLimitMs"]), ((1, 2, 3, 4, 5), 3)):
            with self.subTest(servers=servers), self.assertRaises(seed.Paused):
                sample = self.sample(servers)
                sample["proposedServerElapsedLimitMs"] = cap
                sample["latencyEvidence"]["proposedServerElapsedLimitMs"] = cap
                self.check(sample, {"mode": seed.SUMMARY_LATENCY_MODE, "staticLimitMs": cap})

    def test_legacy_target_cannot_silently_accept_new_or_mixed_sample(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "monitor.json"
            for mixed in (False, True):
                sample = self.sample()
                if mixed: sample.update(readLatencyMs=0, baselineReadLatencyMs=1)
                path.write_text(seed.encode(sample))
                with self.subTest(mixed=mixed), self.assertRaisesRegex(seed.SeedError, "자동 전환"):
                    seed.monitor_check(path, self.now)
                if mixed:
                    with self.assertRaisesRegex(seed.SeedError, "혼합"): self.check(sample)

    def test_mode_threshold_and_legacy_sample_must_match_target(self):
        for changed in ("mode", "cap", "legacy", "target_mode", "target_cap", "target_extra"):
            with self.subTest(changed=changed), self.assertRaises(seed.SeedError):
                sample, policy = self.sample(), copy.deepcopy(self.policy)
                if changed == "mode": sample["latencyMode"] = "unknown"
                elif changed == "cap": sample["proposedServerElapsedLimitMs"] = 100
                elif changed == "legacy": sample = pressure_sample(); sample["observedAt"] = "2026-05-01T00:00:00Z"
                elif changed == "target_mode": policy["mode"] = "unknown"
                elif changed == "target_cap": policy["staticLimitMs"] = 0
                else: policy["baselineReadLatencyMs"] = 1
                self.check(sample, policy)

    def test_raw_missing_failure_duplicate_identity_and_median_tampering_stop(self):
        for changed in ("missing", "failed", "query_id", "median", "raw_list", "ns", "ms", "zero", "negative",
                        "summary_missing", "summary_duplicate", "summary_json", "summary_parse", "nan", "infinite"):
            with self.subTest(changed=changed), self.assertRaises(seed.SeedError):
                sample = self.sample(); evidence = sample["latencyEvidence"]; probe = evidence["rawProbes"][0]
                if changed == "missing": evidence["rawProbes"].pop()
                elif changed == "failed": probe["ok"] = False
                elif changed == "query_id": probe["queryId"] = "probe-1"
                elif changed == "median": sample["serverElapsedMedianMs"] = 1
                elif changed == "raw_list": evidence["rawClientElapsedMs"][0] = 0
                elif changed == "ns": probe["serverElapsedNs"] = "2"
                elif changed == "ms": probe["serverElapsedMs"] = 2
                elif changed == "zero": probe["summaryRaw"] = '{"elapsed_ns":"0"}'; probe["summary"] = {"elapsed_ns": "0"}
                elif changed == "negative": probe["summaryRaw"] = '{"elapsed_ns":"-1"}'; probe["summary"] = {"elapsed_ns": "-1"}
                elif changed == "summary_missing": del probe["summaryRaw"]
                elif changed == "summary_duplicate": probe["summaryRaw"] = '{"elapsed_ns":"1","elapsed_ns":"2"}'
                elif changed == "summary_json": probe["summaryRaw"] = 'not-json'
                elif changed == "summary_parse": probe["summary"] = {"elapsed_ns": "2"}
                elif changed == "nan": probe["summaryRaw"] = '{"elapsed_ns":NaN}'
                else: probe["clientElapsedMs"] = "Infinity"
                self.check(sample)

    def test_request_and_response_contract_is_mandatory(self):
        for key, bad in (("summaryHeaderCount", 0), ("summaryHeaderCount", 2), ("httpStatus", 500),
                         ("httpWaitEndOfQuery", False), ("requestSettings", {"wait_end_of_query": 1}),
                         ("requestSettings", {"wait_end_of_query": True, "send_progress_in_http_headers": False}),
                         ("probeSqlSha256", "0" * 64), ("responseBytes", 1025), ("startIntervalSeconds", Decimal("0.19"))):
            with self.subTest(key=key, bad=bad), self.assertRaises(seed.SeedError):
                sample = self.sample(); sample["latencyEvidence"]["rawProbes"][1][key] = bad
                self.check(sample)
        sample = self.sample(); sample["waitEndOfQuery"] = False
        with self.assertRaises(seed.SeedError): self.check(sample)

    def test_freshness_is_original_window_40_seconds_not_publication(self):
        for changed in ("expired", "extended", "future", "old_guard", "duration", "error", "before_order", "after_order"):
            with self.subTest(changed=changed), self.assertRaises(seed.SeedError):
                sample = self.sample(); now = self.now
                if changed == "expired": now += 35
                elif changed == "extended": sample["validUntil"] = "2026-05-01T00:00:45Z"
                elif changed == "future": sample["latencyEvidence"]["windowCompletedAt"] = "2026-05-01T00:00:30Z"
                elif changed == "old_guard": sample["latencyEvidence"]["rawProbes"][0]["before"]["observedAt"] = "2026-04-30T23:59:59Z"
                elif changed == "duration": sample["latencyEvidence"]["windowElapsedSeconds"] = 40
                elif changed == "error": sample["clickhouseErrorCount"] = 1
                elif changed == "before_order": sample["latencyEvidence"]["rawProbes"][0]["before"]["observedAt"] = "2026-05-01T00:00:01Z"
                else: sample["latencyEvidence"]["rawProbes"][1]["after"]["observedAt"] = "2026-05-01T00:00:00Z"
                self.check(sample, now=now)

    def test_pressure_and_memory_guards_remain_active_for_every_raw_probe(self):
        for changed in ("max", "oom", "oom_kill", "decreased", "memory", "identity", "new_error"):
            with self.subTest(changed=changed), self.assertRaises(seed.SeedError):
                sample = self.sample(); guard = sample["latencyEvidence"]["rawProbes"][2]["after"]
                if changed in ("max", "oom", "oom_kill"): guard["memoryEvents"][changed] += 1
                elif changed == "decreased": guard["memoryEvents"]["max"] -= 1
                elif changed == "memory": guard["memoryWorkingSetBytes"] = 1500
                elif changed == "identity": guard["identity"]["containerId"] = "new-container"
                else: guard["observedClickhouseErrorCount"] = 1
                self.check(sample)

    def test_target_explicit_opt_in_is_hashed_and_requires_monitor(self):
        data = {"schemaVersion": 1, "environment": "local", "targetId": "local-seed", "monitorFile": "/tmp/monitor.json",
                "postgres": {"host": "localhost", "port": 5432, "dbname": "seed", "user": "seed", "sslmode": "disable"},
                "clickhouse": {"url": "http://127.0.0.1:8123", "database": "default"}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "target.json"; path.write_text(seed.encode(data))
            artifact = SimpleNamespace(manifest={"environment": "local"})
            original = seed.Target(path, artifact).sha
            data["monitorLatency"] = self.policy; path.write_text(seed.encode(data))
            self.assertNotEqual(seed.Target(path, artifact).sha, original)
            for invalid in (None, {}, {"mode": "unknown", "staticLimitMs": 1},
                            {"mode": seed.SUMMARY_LATENCY_MODE, "staticLimitMs": True}):
                data["monitorLatency"] = invalid; path.write_text(seed.encode(data))
                with self.subTest(invalid=invalid), self.assertRaises(seed.SeedError): seed.Target(path, artifact)
            data["monitorLatency"] = self.policy; del data["monitorFile"]; path.write_text(seed.encode(data))
            with self.assertRaises(seed.SeedError): seed.Target(path, artifact)

    def test_runner_passes_target_policy_and_preserves_pressure_latch(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "monitor.json"; sample = self.sample(); path.write_text(seed.encode(sample))
            runner = MemoryRunner([event("a")]); runner.target.data = {"environment": "local", "monitorFile": str(path), "monitorLatency": self.policy}
            with mock.patch.object(seed.time, "time", return_value=float(self.now)):
                result = seed.Runner.monitor(runner)
                self.assertEqual(result["serverElapsedMedianMs"], 3)
                sample["pressureGuard"]["memoryEvents"]["max"] += 1; path.write_text(seed.encode(sample))
                with self.assertRaises(seed.PressurePaused): seed.Runner.monitor(runner)
                self.assertTrue(runner.state["pressureStopLatched"])
                path.write_text(seed.encode(self.sample()))
                with self.assertRaises(seed.PressurePaused): seed.Runner.monitor(runner)


class VerificationLoggingTests(unittest.TestCase):
    def test_request_only_cut_preserves_logging_and_normal_queries_are_unchanged(self):
        ch = seed.ClickHouse({"url": "http://127.0.0.1:8123", "database": "default"})
        responses = mock.MagicMock(); responses.__enter__.return_value.read.return_value = b'{"n":1}\n'
        with mock.patch.object(seed.urllib.request, "urlopen", return_value=responses) as send:
            ch.query("SELECT 1", query_id="verify-one", log_query_cut=4096)
            params = urllib.parse.parse_qs(urllib.parse.urlsplit(send.call_args.args[0].full_url).query)
            self.assertEqual(params["log_queries_cut_to_length"], ["4096"])
            self.assertEqual(params["log_queries"], ["1"])
            self.assertEqual(params["query_id"], ["verify-one"])
            self.assertEqual(params["readonly"], ["2"])
            ch.query("SELECT 1")
            self.assertNotIn("log_queries_cut_to_length", urllib.parse.parse_qs(urllib.parse.urlsplit(send.call_args.args[0].full_url).query))
        with self.assertRaises(seed.SeedError): ch.request("INSERT INTO t", b"x", log_query_cut=4096)

    def test_reconciliation_journal_retains_full_sql_hash_and_manifest_projection(self):
        runner = MemoryRunner([event("a"), event("b")]); seen = []
        def query(sql, **kwargs):
            seen.append((sql, kwargs)); return [event("a"), event("b")]
        runner.ch.query = query
        seed.Runner.reconcile(runner, runner.artifact.batches[0], runner.artifact.rows)
        sent, finished = runner.journal_records
        self.assertEqual([sent["status"], finished["status"]], ["sent", "finished"])
        self.assertEqual(sent["queryId"], seen[0][1]["query_id"])
        self.assertEqual(seen[0][1]["log_query_cut"], 4096)
        self.assertEqual(sent["sqlSha256"], seed.sha256((seen[0][0] + " FORMAT JSONEachRow").encode()))
        self.assertEqual(sent["manifestSha256"], runner.artifact.sha)
        self.assertEqual(sent["projection"][0], "observation_id")
        self.assertEqual(finished["resultRows"], 2)

    def test_query_failure_leaves_a_durable_attempt_record(self):
        runner = MemoryRunner([event("a")])
        runner.ch.query = mock.Mock(side_effect=seed.SeedError("connection failed"))
        with self.assertRaises(seed.SeedError): seed.Runner.reconcile(runner, runner.artifact.batches[0], runner.artifact.rows)
        self.assertEqual([r["status"] for r in runner.journal_records], ["sent", "failed"])
        self.assertEqual(runner.journal_records[-1]["errorType"], "SeedError")


if __name__ == "__main__":
    unittest.main()
