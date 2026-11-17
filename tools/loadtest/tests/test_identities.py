"""결정적 ID·`pit_` 파생 검사. HMAC 기대값은 k6 `selftest.js`와 같은 벡터다."""
from pathlib import Path
import re
import sys
import tempfile
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generator import identities  # noqa: E402

# 시드 비밀 bytes(range(32)), 캠페인 vector-test, 설치 00000000-0000-4000-8000-000000000001.
# openssl로 따로 계산했다:
#   printf '%s' 'pulsemetry-loadtest/v1/vector-test/00000000-0000-4000-8000-000000000001' \
#     | openssl dgst -sha256 -mac HMAC -macopt hexkey:000102…1f -binary | base64 | tr '+/' '-_' | tr -d '='
VECTOR_SECRET = bytes(range(32))
VECTOR_CAMPAIGN = "vector-test"
VECTOR_INSTALLATION = "00000000-0000-4000-8000-000000000001"
VECTOR_TOKEN = "pit_ikaYADG36CR2StNANJKZSWlkWgnSenFqzNa_tG0Wum0"


class IdentitiesTest(unittest.TestCase):

    def test_namespace_is_documented_derivation(self):
        self.assertEqual(identities.NAMESPACE, uuid.uuid5(uuid.NAMESPACE_DNS, "loadtest.pulsemetry.invalid"))

    def test_entity_id_is_deterministic_and_distinct(self):
        first = identities.entity_id("camp-a", "member", 0)
        self.assertEqual(first, identities.entity_id("camp-a", "member", 0))
        self.assertEqual(first, uuid.uuid5(identities.NAMESPACE, "pulsemetry-loadtest/v1/camp-a/member/0"))
        others = {
            identities.entity_id("camp-a", "member", 1),
            identities.entity_id("camp-a", "installation", 0),
            identities.entity_id("camp-b", "member", 0),
        }
        self.assertNotIn(first, others)
        self.assertEqual(len(others), 3)

    def test_campaign_pattern(self):
        for good in ("abc", "pipeline-2026-11-18", "a" * 40):
            self.assertEqual(identities.validate_campaign(good), good)
        for bad in ("ab", "a" * 41, "Camp", "camp_a", "camp a", "캠페인", "camp/a"):
            with self.assertRaises(ValueError, msg=bad):
                identities.validate_campaign(bad)
        self.assertEqual(identities.tenant_name("camp-a"), "loadtest-camp-a")

    def test_installation_token_vector(self):
        token = identities.installation_token(VECTOR_SECRET, VECTOR_CAMPAIGN, VECTOR_INSTALLATION)
        self.assertEqual(token, VECTOR_TOKEN)
        self.assertEqual(token, identities.installation_token(VECTOR_SECRET, VECTOR_CAMPAIGN, uuid.UUID(VECTOR_INSTALLATION)))
        self.assertRegex(token, r"^pit_[A-Za-z0-9_-]{43}$")

    def test_token_depends_on_secret_campaign_and_installation(self):
        base = identities.installation_token(VECTOR_SECRET, VECTOR_CAMPAIGN, VECTOR_INSTALLATION)
        self.assertNotEqual(base, identities.installation_token(bytes(32), VECTOR_CAMPAIGN, VECTOR_INSTALLATION))
        self.assertNotEqual(base, identities.installation_token(VECTOR_SECRET, "vector-other", VECTOR_INSTALLATION))
        self.assertNotEqual(base, identities.installation_token(VECTOR_SECRET, VECTOR_CAMPAIGN, "00000000-0000-4000-8000-000000000002"))

    def test_invitation_code_hash_is_hex64_and_not_token_derived(self):
        code = identities.invitation_code_hash(VECTOR_SECRET, VECTOR_CAMPAIGN, VECTOR_INSTALLATION)
        self.assertRegex(code, r"^[0-9a-f]{64}$")
        self.assertNotEqual(code, identities.sha256_hex(VECTOR_TOKEN))

    def test_sha256_hex_matches_credential_hash_rule(self):
        self.assertEqual(identities.sha256_hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    def test_seed_secret_must_be_exactly_32_bytes(self):
        with tempfile.TemporaryDirectory() as work:
            good = Path(work, "good")
            good.write_bytes(VECTOR_SECRET)
            self.assertEqual(identities.read_seed_secret(good), VECTOR_SECRET)
            with_newline = Path(work, "newline")
            with_newline.write_bytes(VECTOR_SECRET + b"\n")
            short = Path(work, "short")
            short.write_bytes(VECTOR_SECRET[:31])
            for bad in (with_newline, short):
                with self.assertRaisesRegex(ValueError, "32바이트"):
                    identities.read_seed_secret(bad)
        with self.assertRaises(ValueError):
            identities.installation_token(b"short", VECTOR_CAMPAIGN, VECTOR_INSTALLATION)

    def test_token_prefix_matches_leak_pattern_used_by_e2e(self):
        # 로그 누출 검사(scripts/telemetry-kafka-e2e/run.sh)의 정규식에 걸리는 형식이어야 누출을 잡는다.
        self.assertRegex(VECTOR_TOKEN, re.compile(r"pit_[A-Za-z0-9_-]{20,}"))


if __name__ == "__main__":
    unittest.main()
