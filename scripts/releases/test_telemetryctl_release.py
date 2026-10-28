"""릴리스 공급 실패가 미완성·변조된 이미지 입력을 남기지 않는지 검증한다."""

import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import telemetryctl_release as supply


def digest(data):
    return hashlib.sha256(data).hexdigest()


class ReleaseSupplyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / "binaries"
        self.files = {name: (name + "-published-fixture").encode() for name in supply.CLI_ASSETS}
        sums = "".join(f"{digest(data)}  {name}\n" for name, data in self.files.items())
        # GUI 항목이 있어도 필수 CLI 6종 검증은 빠지지 않는다.
        sums += "0" * 64 + "  pulsemetry_gui_linux_amd64.AppImage\n"
        self.files["SHA256SUMS"] = sums.encode()
        self.lock = {"repository": "soma-376/telemetryctl", "tag": "v0.1.0-rc.2",
                     "sha256": {name: digest(data) for name, data in self.files.items()}}
        self.release = {"tag_name": self.lock["tag"], "draft": False,
                        "prerelease": True, "published_at": "2026-10-23T13:22:51Z", "assets": [
                            {"name": name, "state": "uploaded", "size": len(data),
                             "digest": "sha256:" + digest(data),
                             "browser_download_url": f'https://github.com/soma-376/telemetryctl/releases/download/{self.lock["tag"]}/{name}'}
                            for name, data in self.files.items()]}

    def download(self, release=None, writer=None):
        def write(url, destination):
            destination.write_bytes(self.files[destination.name])
        with patch.object(supply, "read_metadata", return_value=release or self.release), \
             patch.object(supply, "download_asset", side_effect=writer or write):
            supply.download(self.lock, self.output)

    def test_complete_published_release_keeps_all_original_bytes_and_names(self):
        self.download()
        directory = supply.verify(self.lock, self.output)
        self.assertEqual(directory.name, "v0.1.0-rc.2")
        self.assertEqual({file.name for file in directory.iterdir()}, set(supply.ASSETS))
        for name, data in self.files.items():
            self.assertEqual((directory / name).read_bytes(), data)
            self.assertTrue((directory / name).stat().st_mode & 0o004)
        self.assertTrue(os.access(directory / supply.CLI_ASSETS[0], os.X_OK))

    def test_query_failure_leaves_no_image_input(self):
        with patch.object(supply, "read_metadata", side_effect=supply.ReleaseError("원격 릴리스 조회 실패")), \
             patch.object(supply, "download_asset") as download:
            with self.assertRaises(supply.ReleaseError):
                supply.download(self.lock, self.output)
        download.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_missing_any_of_seven_remote_assets_prevents_download(self):
        for name in supply.ASSETS:
            with self.subTest(name=name):
                release = copy.deepcopy(self.release)
                release["assets"] = [asset for asset in release["assets"] if asset["name"] != name]
                with self.assertRaises(supply.ReleaseError), patch.object(supply, "download_asset") as download:
                    self.download(release)
                download.assert_not_called()
                self.assertFalse(self.output.exists())

    def test_wrong_tag_draft_unpublished_duplicate_or_redirected_metadata_fails(self):
        bad_releases = []
        for field, value in (("tag_name", "v9.9.9"), ("draft", True), ("published_at", None)):
            release = copy.deepcopy(self.release)
            release[field] = value
            bad_releases.append(release)
        release = copy.deepcopy(self.release)
        release["assets"].append(release["assets"][0])
        bad_releases.append(release)
        for field, value in (("browser_download_url", "https://example.invalid/cli"),
                             ("digest", "sha256:" + "0" * 64), ("state", "new")):
            release = copy.deepcopy(self.release)
            release["assets"][0][field] = value
            bad_releases.append(release)
        for release in bad_releases:
            with self.subTest(release=release), self.assertRaises(supply.ReleaseError):
                supply.release_urls(self.lock, release)

    def test_download_failure_or_tampering_never_publishes_partial_directory(self):
        for failure in ("network", "hash"):
            def write(url, destination):
                if destination.name == supply.CLI_ASSETS[-1]:
                    if failure == "network":
                        raise supply.ReleaseError("다운로드 실패")
                    destination.write_bytes(b"tampered")
                else:
                    destination.write_bytes(self.files[destination.name])
            with self.subTest(failure=failure), self.assertRaises(supply.ReleaseError):
                self.download(writer=write)
            self.assertFalse(self.output.exists())
            self.assertEqual(list(self.root.iterdir()), [])

    def test_existing_input_is_never_overwritten(self):
        self.output.mkdir()
        marker = self.output / "keep"
        marker.write_text("existing")
        with self.assertRaises(supply.ReleaseError):
            self.download()
        self.assertEqual(marker.read_text(), "existing")

    def test_image_verifier_rejects_missing_tampered_symlink_and_extra_release(self):
        self.download()
        binary = self.output / self.lock["tag"] / supply.CLI_ASSETS[-1]
        original = binary.read_bytes()
        binary.unlink()
        with self.assertRaises(supply.ReleaseError):
            supply.verify(self.lock, self.output)
        binary.write_bytes(b"tampered")
        with self.assertRaises(supply.ReleaseError):
            supply.verify(self.lock, self.output)
        binary.unlink()
        target = self.root / "real-binary"
        target.write_bytes(original)
        binary.symlink_to(target)
        with self.assertRaises(supply.ReleaseError):
            supply.verify(self.lock, self.output)
        binary.unlink()
        binary.write_bytes(original)
        (self.output / "v9.9.9").mkdir()
        with self.assertRaises(supply.ReleaseError):
            supply.verify(self.lock, self.output)

    def test_checksum_missing_cli_duplicate_malformed_and_wrong_hash_fail(self):
        self.download()
        sums = self.output / self.lock["tag"] / "SHA256SUMS"
        lines = sums.read_text().splitlines()
        for body in ("\n".join(lines[1:]), "\n".join(lines + [lines[0]]),
                     "\n".join(lines + ["broken"]), "\n".join(["0" * 64 + lines[0][64:]] + lines[1:])):
            sums.write_text(body)
            lock = copy.deepcopy(self.lock)
            lock["sha256"]["SHA256SUMS"] = supply.sha256(sums)
            with self.subTest(body=body), self.assertRaises(supply.ReleaseError):
                supply.verify(lock, self.output)

    def test_repository_lock_is_explicit_and_complete(self):
        lock = supply.load_lock(supply.DEFAULT_LOCK)
        self.assertEqual(lock["tag"], "v0.1.0-rc.2")
        path = self.root / "lock.json"
        for invalid in ({**lock, "tag": "latest"}, {**lock, "sha256": {}},
                        {**lock, "repository": "other/repo"}):
            path.write_text(json.dumps(invalid))
            with self.assertRaises(supply.ReleaseError):
                supply.load_lock(path)

    def test_deploy_download_precedes_build_and_supplies_only_enrollment(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/deploy_dev.yml").read_text()
        self.assertLess(workflow.index("- name: Download and verify published telemetryctl CLI"),
                        workflow.index("- name: Build and push enrollment-api"))
        self.assertEqual(workflow.count("telemetry-binaries=./binaries"), 1)
        self.assertIn("GH_TOKEN: ${{ github.token }}", workflow)
        dockerfile = (root / "Dockerfile").read_text()
        self.assertIn("COPY --from=telemetry-binaries / /binaries/", dockerfile)
        self.assertIn("telemetryctl_release.py verify /binaries", dockerfile)
        self.assertIn("COPY --from=telemetry-binaries-verified --chown=pulsemetry:pulsemetry /binaries/ /app/binaries/", dockerfile)


if __name__ == "__main__":
    unittest.main()
