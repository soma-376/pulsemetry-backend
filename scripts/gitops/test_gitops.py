"""AWS·Docker·GitHub를 호출하지 않고 원본 출처·패키지·실패 경계를 검증한다."""
import copy
from datetime import datetime, timezone
import io
import os
from pathlib import Path
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

import contract as c
import prepare_promotion as p
import promote
from verify_image_contracts import arm64_digest, contract_from_tar, docker_environment, verify_packaged

ROOT = Path(__file__).resolve().parents[2]
COMMIT = "a" * 40
DIGEST = "sha256:" + "b" * 64
RUN = {"id": 123, "run_attempt": 2, "workflow_id": 456, "repository": {"full_name": c.REPOSITORY},
       "head_repository": {"full_name": c.REPOSITORY}, "head_branch": "develop", "head_sha": COMMIT,
       "event": "push", "path": ".github/workflows/deploy_dev.yml", "status": "completed",
       "conclusion": "success", "created_at": "2026-11-01T00:00:00Z"}
ENV = {"GITHUB_REPOSITORY": c.REPOSITORY, "GITHUB_REF": "refs/heads/develop", "GITHUB_SHA": COMMIT,
       "GITHUB_WORKFLOW_REF": c.WORKFLOW, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2"}


def fixture():
    with tempfile.TemporaryDirectory() as directory:
        contracts = Path(directory) / "contracts"
        c.write_contracts(ROOT, contracts, COMMIT)
        images = {app: {"repository": "soma-376/" + app, "digest": DIGEST,
                        "registry": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com"} for app in c.APPS}
        def inspect(reference):
            app = reference.split("/")[-1].split("@")[0]
            return {"platform": "linux/arm64", "imageConfigSha256": "c" * 64, "backendCommit": COMMIT,
                    "arm64ManifestDigest": DIGEST,
                    "deploymentContractSha256": c.sha256((contracts / (app + ".json")).read_bytes())}
        return c.completion(ROOT, contracts, images, ENV, inspect, lambda *_: None)


def archive(raw, files=None):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as bundle:
        for name, data in (files or {"completion.json": raw, "completion.sha256": c.sha256(raw) + "\n"}).items():
            bundle.writestr(name, data)
    return output.getvalue()


class GithubFixture:
    def __init__(self, raw):
        self.run = copy.deepcopy(RUN)
        self.original_run = copy.deepcopy(RUN)
        self.archive = archive(raw)
        self.jobs = [{"id": 1 + index, "name": name, "run_id": 123, "run_attempt": 2,
                      "head_sha": COMMIT, "status": "completed", "conclusion": "success"}
                     for index, name in enumerate(("Gradle build (Testcontainers)", "Build and publish images to ECR"))]
        self.artifact = {"id": 789, "name": "dev-image-bundle-123-2", "expired": False,
                         "expires_at": "2099-01-01T00:00:00Z", "digest": "sha256:" + c.sha256(self.archive),
                         "workflow_run": {"id": 123, "head_sha": COMMIT}}

    def get(self, path, binary=False):
        if path.endswith("/zip"):
            assert binary
            return self.archive
        if "/jobs?" in path:
            return {"total_count": len(self.jobs), "jobs": self.jobs}
        if "/artifacts?" in path:
            return {"total_count": 1, "artifacts": [self.artifact]}
        if "/workflows/456" in path:
            return {"id": 456, "path": ".github/workflows/deploy_dev.yml"}
        if "/runs?" in path:
            return {"total_count": 1, "workflow_runs": [self.original_run]}
        if path.endswith("/runs/123"):
            return self.original_run
        if path.endswith("/runs/123/attempts/2"):
            return self.run
        raise AssertionError(path)


class ContractTest(unittest.TestCase):
    def test_all_schemas_envelope_assets_are_fingerprinted(self):
        contracts = c.contracts(ROOT, COMMIT)
        self.assertEqual(set(contracts), set(c.APPS))
        fingerprints = set()
        for app, value in contracts.items():
            self.assertEqual(value["app"], app)
            self.assertEqual(value["backendCommit"], COMMIT)
            p.validate_compatibility(value["compatibility"])
            fingerprints.add(value["compatibilitySha256"])
            self.assertGreater(len(value["compatibility"]["postgres"]["dashboard_cache"]), 0)
        self.assertEqual(len(fingerprints), 1)

    def test_flyway_crc32_ignores_bom_and_crlf_but_not_sql_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "V1__test.sql"
            path.write_bytes(b"\xef\xbb\xbfSELECT 1;\r\nSELECT 2;\r\n")
            original = c.file_records(Path(directory), ".", True)[0]
            path.write_bytes(b"SELECT 1;\nSELECT 2;\n")
            normalized = c.file_records(Path(directory), ".", True)[0]
            self.assertEqual(original["checksum"], normalized["checksum"])
            self.assertEqual(original["checksum"], -1665099012)
            self.assertNotEqual(original["sha256"], normalized["sha256"])
            path.write_bytes(b"SELECT 3;\n")
            self.assertNotEqual(original["checksum"], c.file_records(Path(directory), ".", True)[0]["checksum"])

    def test_duplicate_json_and_non_json_constants_rejected(self):
        for raw in ('{"status":"complete","status":"failed"}', '{"x":NaN}', '{"x":Infinity}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                c.strict_json(raw)

    def test_wrong_architecture_or_config_not_accepted(self):
        for architecture in ("amd64", None):
            def runner(*args, **kwargs):
                if "--raw" in args[0]:
                    return SimpleNamespace(returncode=0, stdout=c.encode({"schemaVersion": 2, "mediaType": "application/vnd.oci.image.manifest.v1+json"}))
                return SimpleNamespace(returncode=0, stdout=c.encode({"os": "linux", "architecture": architecture}))
            with self.subTest(architecture=architecture), self.assertRaises(ValueError):
                c.inspect_image("fixture@" + DIGEST, runner)

    def test_complete_four_image_bundle_has_no_private_registry(self):
        value = fixture()
        raw = c.encode(value)
        self.assertEqual(p.validate_completion(raw, RUN), value)
        self.assertNotIn(b"123456789012", raw)
        self.assertNotIn(b"amazonaws", raw)

    def test_missing_or_unreadable_packaged_contract_cannot_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            contracts = Path(directory) / "contracts"
            c.write_contracts(ROOT, contracts, COMMIT)
            images = {app: {"repository": "soma-376/" + app, "digest": DIGEST,
                            "registry": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com"} for app in c.APPS}
            inspector = lambda _: {"platform": "linux/arm64", "imageConfigSha256": "c" * 64, "backendCommit": COMMIT,
                                    "arm64ManifestDigest": DIGEST,
                                    "deploymentContractSha256": c.sha256((contracts / "enrollment-api.json").read_bytes())}
            def unreadable(*_):
                raise ValueError("package missing")
            with self.assertRaises(ValueError):
                c.completion(ROOT, contracts, images, ENV, inspector, unreadable)

    def test_manifest_tampering_including_mixed_attempt_and_missing_gateway(self):
        base = fixture()
        cases = []
        for field, value in (("workflowRunAttempt", "1"), ("backendCommit", "d" * 40), ("status", "partial"), ("repository", "fork/backend")):
            changed = copy.deepcopy(base); changed[field] = value; cases.append(changed)
        changed = copy.deepcopy(base); del changed["images"]["telemetry-gateway"]; cases.append(changed)
        changed = copy.deepcopy(base); changed["images"]["enrollment-api"]["platform"] = "linux/amd64"; cases.append(changed)
        changed = copy.deepcopy(base); changed["deploymentContracts"]["dashboard-api"]["content"] += " "; cases.append(changed)
        changed = copy.deepcopy(base); del changed["enrollmentAssets"]["lock"]["sha256"]["SHA256SUMS"]; cases.append(changed)
        for index, value in enumerate(cases):
            with self.subTest(index=index), self.assertRaises(ValueError):
                p.validate_completion(c.encode(value), RUN)


class ProducerEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.raw = c.encode(fixture())
        self.github = GithubFixture(self.raw)

    def test_api_evidence_keeps_original_manifest_bytes(self):
        raw, evidence = p.prepare(self.github)
        self.assertEqual(raw, self.raw)
        self.assertEqual(evidence["workflowRunAttempt"], "2")
        self.assertEqual(evidence["artifactSha256"], c.sha256(self.github.archive))
        self.assertEqual(evidence["createdAt"], RUN["created_at"])

    def test_retry_keeps_original_run_creation_time(self):
        self.github.run["created_at"] = "2026-11-02T03:00:00Z"
        for run_id, attempt in ((None, None), (123, 2)):
            with self.subTest(explicit=run_id is not None):
                raw, evidence = p.prepare(self.github, run_id, attempt)
                self.assertEqual(raw, self.raw)
                self.assertEqual(evidence["createdAt"], self.github.original_run["created_at"])
                self.assertNotEqual(evidence["createdAt"], self.github.run["created_at"])
                self.assertEqual(evidence["workflowRunAttempt"], "2")

    def test_original_run_and_latest_successful_attempt_must_agree(self):
        for key, value in (("id", 124), ("run_attempt", 3), ("head_sha", "f" * 40),
                           ("workflow_id", 457), ("event", "workflow_dispatch"),
                           ("conclusion", "failure"), ("status", "in_progress"),
                           ("head_branch", "feature/unsafe"),
                           ("repository", {"full_name": "fork/backend"}),
                           ("head_repository", {"full_name": "fork/backend"}),
                           ("path", ".github/workflows/other.yml")):
            changed = copy.deepcopy(self.github)
            changed.original_run[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                p.prepare(changed, 123, 2)

    def test_original_creation_time_must_be_valid_utc(self):
        for value in (None, "invalidZ", "2026-11-01T00:00:00+00:00"):
            changed = copy.deepcopy(self.github)
            changed.original_run["created_at"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                p.prepare(changed, 123, 2)

    def test_entire_producer_and_each_required_job_must_succeed(self):
        for conclusion in ("failure", "cancelled", "skipped", None):
            changed = copy.deepcopy(self.github); changed.run["conclusion"] = conclusion
            with self.subTest(conclusion=conclusion), self.assertRaises(ValueError):
                p.prepare(changed, 123, 2)
        for field, value in (("run_id", 122), ("run_attempt", 1), ("head_sha", "f" * 40), ("conclusion", "skipped")):
            changed = copy.deepcopy(self.github); changed.jobs[1][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                p.prepare(changed, 123, 2)

    def test_fork_other_branch_or_event_is_rejected(self):
        for key, value in (("head_branch", "feature/unsafe"), ("event", "pull_request"), ("path", ".github/workflows/other.yml"), ("head_repository", {"full_name": "fork/backend"})):
            changed = copy.deepcopy(self.github); changed.run[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                p.prepare(changed, 123, 2)

    def test_expired_wrong_attempt_or_mutated_archive_is_rejected(self):
        for key, value in (("expired", True), ("name", "dev-image-bundle-123-1"), ("digest", "sha256:" + "f" * 64), ("expires_at", "2020-01-01T00:00:00Z"), ("workflow_run", {"id": 122, "head_sha": COMMIT})):
            changed = copy.deepcopy(self.github); changed.artifact[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                p.prepare(changed, 123, 2)

    def test_zip_only_accepts_two_original_files(self):
        self.assertEqual(p.unpack_archive(self.github.archive), self.raw)
        cases = [archive(self.raw, {"../completion.json": self.raw, "completion.sha256": c.sha256(self.raw) + "\n"}),
                 archive(self.raw, {"completion.json": self.raw, "completion.sha256": "bad"})]
        for raw in cases:
            with self.assertRaises(ValueError):
                p.unpack_archive(raw)


class PromotionTrustTest(unittest.TestCase):
    def test_isolated_docker_keeps_only_local_endpoint_and_plugin_location(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); plugin = root / "plugins/docker-buildx"; plugin.parent.mkdir(); plugin.touch()
            config = root / "isolated"; config.mkdir()
            def run(arguments, env):
                if arguments[1] == "context":
                    return b"unix:///fixture/docker.sock\n"
                return c.encode([{"Name": "buildx", "Path": str(plugin)}])
            env = docker_environment({"DOCKER_CONTEXT": "trusted", "DOCKER_HOST": "tcp://ignored.invalid", "PATH": "/fixture/bin"}, config, run)
            self.assertEqual(env["DOCKER_HOST"], "unix:///fixture/docker.sock")
            self.assertNotIn("DOCKER_CONTEXT", env)
            self.assertEqual(c.strict_json((config / "config.json").read_bytes()), {"cliPluginsExtraDirs": [str(plugin.parent)]})
            with self.assertRaises(ValueError):
                docker_environment({"DOCKER_HOST": "tcp://remote.invalid:2375"}, config, run)

    def test_disabled_default_and_unpinned_infra_do_not_run(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "tooling.json"
            lock.write_bytes(c.encode({"schemaVersion": 1, "repository": "soma-376/infra", "revision": None}))
            with self.assertRaises(ValueError):
                promote.tooling_revision(lock)
        with self.assertRaises(ValueError):
            promote.trusted_context({}, {})

    def test_only_reviewed_default_branch_workflow_is_privileged(self):
        env = {"DELIVERY_ENABLED": "true", "GITHUB_REPOSITORY": c.REPOSITORY, "GITHUB_EVENT_NAME": "schedule",
               "GITHUB_REF": "refs/heads/main", "GITHUB_WORKFLOW_REF": c.REPOSITORY + "/.github/workflows/promote_dev.yml@refs/heads/main"}
        event = {"repository": {"default_branch": "main"}}
        promote.trusted_context(env, event)
        for field, value in (("GITHUB_REF", "refs/heads/develop"), ("GITHUB_EVENT_NAME", "pull_request"), ("DELIVERY_ENABLED", "false"), ("GITHUB_WORKFLOW_REF", c.WORKFLOW)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                promote.trusted_context({**env, field: value}, event)

    def test_shared_engine_blocked_exit_stops_without_retry(self):
        raw = c.encode(fixture()); _, evidence = p.prepare(GithubFixture(raw))
        commands = []
        with tempfile.TemporaryDirectory() as directory:
            prepared = Path(directory) / "verified-gitops-candidate"; prepared.mkdir()
            (prepared / "completion.json").write_bytes(raw)
            (prepared / "producer-evidence.json").write_bytes(c.encode(evidence))
            event = Path(directory) / "event.json"; event.write_bytes(c.encode({"repository": {"default_branch": "main"}}))
            env = {"DELIVERY_ENABLED": "true", "GITHUB_REPOSITORY": c.REPOSITORY, "GITHUB_EVENT_NAME": "schedule",
                   "GITHUB_REF": "refs/heads/main", "GITHUB_WORKFLOW_REF": c.REPOSITORY + "/.github/workflows/promote_dev.yml@refs/heads/main",
                   "GITHUB_EVENT_PATH": str(event), "RUNNER_TEMP": directory, "GH_TOKEN": "fixture-token"}
            def run(arguments, **kwargs):
                commands.append(arguments)
                return SimpleNamespace(returncode=2 if arguments[0] == "node" else 0, stdout=COMMIT + "\n")
            infra = ROOT / ".fixture-infra"
            with patch.dict(os.environ, env, clear=True), patch("sys.argv", ["promote.py", "--infra", str(infra)]), \
                 patch.object(promote, "tooling_revision", return_value=COMMIT), \
                 patch.object(promote, "prepare", return_value=(raw, evidence)) as prepare, \
                 patch.object(promote.subprocess, "run", side_effect=run):
                with self.assertRaisesRegex(ValueError, "승격 결과"):
                    promote.main()
            self.assertEqual(prepare.call_count, 1)
            node = [cmd for cmd in commands if cmd[0] == "node"]
            self.assertEqual(len(node), 1)
            self.assertEqual(node[0][1:3], [str(infra / "scripts/dev-gitops/promote.mjs"), "--candidate"])
            self.assertEqual(node[0][4], "--provenance")

    def test_arm64_child_resolves_separately_from_index_digest(self):
        child = "sha256:" + "e" * 64
        entry = {"mediaType": "application/vnd.oci.image.manifest.v1+json", "digest": child,
                 "platform": {"os": "linux", "architecture": "arm64"}}
        index = {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json", "manifests": [entry,
                 {"mediaType": entry["mediaType"], "digest": "sha256:" + "f" * 64, "platform": {"os": "unknown", "architecture": "unknown"}}]}
        self.assertEqual(arm64_digest(index, DIGEST), child)
        self.assertEqual(arm64_digest({"schemaVersion": 2, "mediaType": entry["mediaType"]}, DIGEST), DIGEST)
        for choices in ([], [entry, entry], [{**entry, "digest": "invalid"}]):
            with self.assertRaises(ValueError):
                arm64_digest({**index, "manifests": choices}, DIGEST)

    def test_long_success_leaves_next_candidate_to_schedule(self):
        raw = c.encode(fixture()); _, evidence = p.prepare(GithubFixture(raw))
        commands = []
        with tempfile.TemporaryDirectory() as directory:
            prepared = Path(directory) / "verified-gitops-candidate"; prepared.mkdir()
            (prepared / "completion.json").write_bytes(raw)
            (prepared / "producer-evidence.json").write_bytes(c.encode(evidence))
            event = Path(directory) / "event.json"; event.write_bytes(c.encode({"repository": {"default_branch": "main"}}))
            env = {"DELIVERY_ENABLED": "true", "GITHUB_REPOSITORY": c.REPOSITORY, "GITHUB_EVENT_NAME": "schedule",
                   "GITHUB_REF": "refs/heads/main", "GITHUB_WORKFLOW_REF": c.REPOSITORY + "/.github/workflows/promote_dev.yml@refs/heads/main",
                   "GITHUB_EVENT_PATH": str(event), "RUNNER_TEMP": directory, "GH_TOKEN": "fixture-token"}
            def run(arguments, **kwargs):
                commands.append(arguments)
                return SimpleNamespace(returncode=0, stdout=COMMIT + "\n")
            with patch.dict(os.environ, env, clear=True), patch("sys.argv", ["promote.py"]), \
                 patch.object(promote, "tooling_revision", return_value=COMMIT), \
                 patch.object(promote, "prepare", return_value=(raw, evidence)) as prepare, \
                 patch.object(promote.time, "monotonic", side_effect=[0, 301]), \
                 patch.object(promote.subprocess, "run", side_effect=run):
                promote.main()
            self.assertEqual(prepare.call_count, 1)
            self.assertEqual(len([command for command in commands if command[0] == "node"]), 1)

    def test_package_tar_rejects_links_traversal_extra_and_oversized_files(self):
        def tar(name="deployment-contract.json", kind=tarfile.REGTYPE, extra=False, size=None):
            output = io.BytesIO()
            with tarfile.open(fileobj=output, mode="w") as bundle:
                entry = tarfile.TarInfo(name); entry.type = kind; entry.size = size if size is not None else (2 if kind == tarfile.REGTYPE else 0)
                entry.linkname = "/etc/passwd" if kind != tarfile.REGTYPE else ""
                bundle.addfile(entry, io.BytesIO(b"{}" + b"x" * max(0, entry.size - 2)))
                if extra:
                    bundle.addfile(tarfile.TarInfo("extra"))
            return output.getvalue()
        self.assertEqual(contract_from_tar(tar()), b"{}")
        for raw in (tar("../deployment-contract.json"), tar(kind=tarfile.SYMTYPE), tar(kind=tarfile.LNKTYPE), tar(extra=True), tar(size=256 * 1024 + 1)):
            with self.assertRaises(ValueError):
                contract_from_tar(raw)

    def test_image_is_never_started_and_container_is_removed_on_mismatch(self):
        calls = []
        container = "d" * 64
        def run(arguments, _):
            calls.append(arguments)
            return container.encode() if arguments[1] == "create" else b""
        def copy_file(arguments, **kwargs):
            calls.append(arguments)
            with tarfile.open(fileobj=kwargs["stdout"], mode="w") as archive:
                entry = tarfile.TarInfo("deployment-contract.json"); entry.size = 2
                archive.addfile(entry, io.BytesIO(b"{}"))
            return SimpleNamespace(returncode=0)
        with patch("verify_image_contracts.subprocess.run", side_effect=copy_file):
            with self.assertRaises(ValueError):
                verify_packaged("fixture@" + DIGEST, b"different", {}, run)
        self.assertEqual([arguments[1] for arguments in calls], ["pull", "create", "cp", "rm"])
        self.assertEqual(calls[-1], ["docker", "rm", "--volumes", container])


if __name__ == "__main__":
    unittest.main()
