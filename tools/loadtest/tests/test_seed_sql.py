"""시드·확인·폐기 SQL 생성기 검사. DB에는 접속하지 않는다 — 실제 적용은 로컬 E2E(tools/loadtest/local/run.sh)가 본다."""
import io
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generator import identities, seed_sql  # noqa: E402
from generator.kafka_partition import partition_for_key, record_key  # noqa: E402

SECRET = bytes(range(32))
CAMPAIGN = "unit-test"


def all_tokens(plan):
    return [identities.installation_token(SECRET, plan.campaign, seat.installation_id) for seat in plan.seats]


class PlanTest(unittest.TestCase):

    def test_sixty_installations_over_six_partitions_is_ten_each(self):
        plan = seed_sql.build_plan(CAMPAIGN, 60, 6)
        self.assertEqual(plan.distribution(), [10] * 6)
        self.assertEqual(len({seat.installation_id for seat in plan.seats}), 60)
        for seat in plan.seats:
            self.assertEqual(seat.partition, partition_for_key(record_key(plan.tenant_id, seat.installation_id), 6))

    def test_plan_is_deterministic(self):
        self.assertEqual(seed_sql.build_plan(CAMPAIGN, 12, 6), seed_sql.build_plan(CAMPAIGN, 12, 6))
        self.assertNotEqual(seed_sql.build_plan(CAMPAIGN, 12, 6).tenant_id, seed_sql.build_plan("unit-other", 12, 6).tenant_id)

    def test_ids_are_distinct_across_kinds(self):
        plan = seed_sql.build_plan(CAMPAIGN, 12, 6)
        ids = [plan.tenant_id, plan.team_id]
        for seat in plan.seats:
            ids += [seat.installation_id, seat.member_id, seat.membership_id, seat.invitation_id, seat.credential_id]
        self.assertEqual(len(ids), len(set(ids)))

    def test_rejects_uneven_and_out_of_range_inputs(self):
        for installations, partitions in ((61, 6), (0, 6), (10, 0), (seed_sql.MAX_INSTALLATIONS + 1, 1)):
            with self.assertRaises(ValueError, msg=(installations, partitions)):
                seed_sql.build_plan(CAMPAIGN, installations, partitions)
        with self.assertRaises(ValueError):
            seed_sql.build_plan("Bad_Campaign", 12, 6)


class SqlTest(unittest.TestCase):

    def setUp(self):
        self.plan = seed_sql.build_plan(CAMPAIGN, 60, 6)

    def test_seed_sql_has_no_token_plaintext_only_hashes(self):
        sql = seed_sql.seed_sql(self.plan, SECRET)
        self.assertNotIn("pit_", sql)
        for token in all_tokens(self.plan):
            self.assertNotIn(token, sql)
            self.assertNotIn(token[len("pit_"):], sql)
            self.assertIn(identities.sha256_hex(token), sql)

    def test_verify_and_revoke_sql_have_no_token_plaintext(self):
        for sql in (seed_sql.verify_sql(self.plan, SECRET), seed_sql.revoke_sql(self.plan)):
            self.assertNotIn("pit_", sql)
            for token in all_tokens(self.plan):
                self.assertNotIn(token[len("pit_"):], sql)

    def test_seed_sql_is_one_idempotent_transaction(self):
        sql = seed_sql.seed_sql(self.plan, SECRET)
        self.assertIn("\nBEGIN;\n", sql)
        self.assertTrue(sql.rstrip().endswith("COMMIT;"))
        self.assertEqual(sql.count("BEGIN;"), 1)
        inserts = re.findall(r"^INSERT INTO enrollment\.(\w+) ", sql, flags=re.MULTILINE)
        self.assertEqual(inserts, ["tenants", "teams", "members", "team_memberships", "invitations", "installations", "installation_credentials"])
        # 충돌 대상은 id만이다 — credential_hash 같은 다른 UNIQUE 충돌은 오류로 낸다.
        self.assertEqual(sql.count("ON CONFLICT (id) DO NOTHING;"), len(inserts))
        self.assertNotIn("ON CONFLICT DO NOTHING", sql)
        self.assertIn("<> 60 THEN", sql)
        self.assertEqual(sql, seed_sql.seed_sql(self.plan, SECRET))

    def test_seed_sql_rejects_changed_installation_set_before_inserts(self):
        sql = seed_sql.seed_sql(self.plan, SECRET)
        guard = sql.index("기존 설치 집합이 계획")
        self.assertLess(guard, sql.index("INSERT INTO enrollment.tenants"))
        self.assertIn("(SELECT count(*) FROM enrollment.installations WHERE tenant_id = ", sql)
        self.assertIn("AND id NOT IN (", sql)
        for seat in self.plan.seats:
            self.assertGreaterEqual(sql.count(f"'{seat.installation_id}'::uuid"), 2)

    def test_seed_sql_checks_each_installation_hash_before_commit(self):
        sql = seed_sql.seed_sql(self.plan, SECRET)
        check = sql.index("시드 비밀과 맞지 않는다")
        self.assertGreater(check, sql.index("INSERT INTO enrollment.installation_credentials"))
        self.assertLess(check, sql.index("COMMIT;"))
        self.assertIn("count(DISTINCT c.installation_id)", sql)
        self.assertIn("ON c.installation_id = p.installation_id AND c.credential_hash = p.credential_hash", sql)
        for seat, token in zip(self.plan.seats, all_tokens(self.plan)):
            self.assertIn(f"('{seat.installation_id}'::uuid, '{identities.sha256_hex(token)}')", sql)

    def test_seed_sql_rows(self):
        sql = seed_sql.seed_sql(self.plan, SECRET)
        self.assertIn("'loadtest-unit-test'", sql)
        self.assertEqual(sql.count("@example.invalid'"), 60)
        self.assertEqual(sql.count("'owner'"), 1)
        self.assertEqual(sql.count("now() - interval '1 day'"), 60)
        self.assertEqual(sql.count("'linux', 'arm64', 'loadtest-v1', 'active'"), 60)
        for seat in self.plan.seats:
            self.assertIn(str(seat.installation_id), sql)
        code_hashes = {identities.invitation_code_hash(SECRET, CAMPAIGN, seat.invitation_id) for seat in self.plan.seats}
        self.assertEqual(len(code_hashes), 60)

    def test_verify_sql_counts_expected_rows_and_secret_match(self):
        sql = seed_sql.verify_sql(self.plan, SECRET)
        # 쓰기 문장과 트랜잭션은 없다. DO 블록의 BEGIN…END는 PL/pgSQL 블록이지 트랜잭션이 아니다.
        self.assertNotRegex(sql, r"(?i)\b(insert|update|delete|commit)\b")
        self.assertNotIn("BEGIN;", sql)
        self.assertIn("'credential_matches_secret' AS kind, 60 AS expected", sql)
        self.assertIn("'tenant_active' AS kind, 1 AS expected", sql)
        # 표로 보여 주고(SELECT), 같은 검사를 DO 블록이 다시 해 하나라도 틀리면 오류로 끝낸다.
        self.assertEqual(sql.count("AS kind"), 16)
        self.assertIn("RAISE EXCEPTION", sql)
        self.assertIn("WHERE expected <> actual", sql)
        self.assertIn("ON c.installation_id = p.installation_id AND c.credential_hash = p.credential_hash", sql)

    def test_revoke_sql_is_scoped_to_campaign_tenant(self):
        sql = seed_sql.revoke_sql(self.plan)
        self.assertIn("배수(consumer lag 0)를 확인한 뒤 실행한다", sql)
        self.assertIn(f"name = 'loadtest-{CAMPAIGN}'", sql)
        self.assertEqual(len(re.findall(r"^UPDATE ", sql, flags=re.MULTILINE)), 2)
        self.assertIn("COALESCE(revoked_at, now())", sql)
        self.assertNotIn("DELETE", sql)

    def test_identities_document_has_no_secret_material(self):
        document = seed_sql.identities_document(self.plan)
        self.assertEqual(set(document), {"campaign", "tenantId", "installations"})
        self.assertEqual(document["tenantId"], str(self.plan.tenant_id))
        self.assertEqual(len(document["installations"]), 60)
        text = json.dumps(document)
        self.assertNotIn("pit_", text)
        self.assertNotRegex(text, r"[0-9a-f]{64}")


class CliTest(unittest.TestCase):

    def run_cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        code = seed_sql.main(list(args), stdout=out, stderr=err)
        return code, out.getvalue(), err.getvalue()

    def test_seed_writes_sql_identities_and_distribution(self):
        with tempfile.TemporaryDirectory() as work:
            secret = Path(work, "secret")
            secret.write_bytes(SECRET)
            out_path = Path(work, "nested", "identities.json")
            code, sql, err = self.run_cli("--campaign", CAMPAIGN, "--installations", "60", "--partitions", "6",
                                          "--secret-file", str(secret), "--identities-out", str(out_path))
            self.assertEqual(code, 0, err)
            self.assertTrue(sql.startswith("-- 부하 tenant 시드"))
            self.assertIn(f"tenant {identities.tenant_id(CAMPAIGN)} (loadtest-{CAMPAIGN})", err)
            self.assertIn("partition 분포: p0=10 p1=10 p2=10 p3=10 p4=10 p5=10", err)
            document = json.loads(out_path.read_text(encoding="utf-8"))
            self.assertEqual(document, seed_sql.identities_document(seed_sql.build_plan(CAMPAIGN, 60, 6)))
            self.assertEqual(out_path.stat().st_mode & 0o777, 0o644)
            self.assertEqual([p.name for p in out_path.parent.iterdir()], ["identities.json"])

    def test_verify_and_revoke_modes(self):
        with tempfile.TemporaryDirectory() as work:
            secret = Path(work, "secret")
            secret.write_bytes(SECRET)
            code, sql, _ = self.run_cli("--campaign", CAMPAIGN, "--installations", "12", "--secret-file", str(secret), "--verify")
            self.assertEqual(code, 0)
            self.assertIn("'installation_active' AS kind, 12 AS expected", sql)
            code, sql, _ = self.run_cli("--campaign", CAMPAIGN, "--revoke")
            self.assertEqual(code, 0)
            self.assertIn("SET status = 'revoked'", sql)

    def test_identities_out_must_not_be_the_secret_file(self):
        with tempfile.TemporaryDirectory() as work:
            secret = Path(work, "secret")
            secret.write_bytes(SECRET)
            link = Path(work, "link")
            link.symlink_to(secret)
            for target in (secret, link, Path(work, ".", "secret")):
                code, sql, err = self.run_cli("--campaign", CAMPAIGN, "--secret-file", str(secret), "--identities-out", str(target))
                self.assertEqual((code, sql), (2, ""))
                self.assertIn("시드 비밀을 덮어쓰지 않도록", err)
                self.assertEqual(secret.read_bytes(), SECRET)

    def test_bad_inputs_fail_without_sql(self):
        with tempfile.TemporaryDirectory() as work:
            secret = Path(work, "secret")
            secret.write_bytes(SECRET + b"\n")
            code, sql, err = self.run_cli("--campaign", CAMPAIGN, "--secret-file", str(secret), "--identities-out", str(Path(work, "i.json")))
            self.assertEqual((code, sql), (2, ""))
            self.assertIn("32바이트", err)
            self.assertFalse(Path(work, "i.json").exists())
            secret.write_bytes(SECRET)
            code, sql, err = self.run_cli("--campaign", CAMPAIGN, "--installations", "61", "--secret-file", str(secret), "--verify")
            self.assertEqual((code, sql), (2, ""))
            self.assertIn("배수가 아니다", err)
        for args in (["--campaign", CAMPAIGN, "--verify"], ["--campaign", CAMPAIGN, "--secret-file", "x"], ["--campaign", CAMPAIGN, "--revoke", "--verify"]):
            with self.assertRaises(SystemExit), mock.patch("sys.stderr", io.StringIO()):
                seed_sql.parse_args(args)


if __name__ == "__main__":
    unittest.main()
