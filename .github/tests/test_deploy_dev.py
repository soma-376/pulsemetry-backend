"""AWS 호출 없이 실제 워크플로의 이미지 promote 함수를 검증한다."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / "workflows/deploy_dev.yml"
DIGEST = "sha256:" + "a" * 64
MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json"
MANIFEST = '{"schemaVersion":2,"mediaType":"' + MEDIA_TYPE + '"}'


def image(tag, digest=DIGEST, manifest=MANIFEST):
    return {
        "imageId": {"imageDigest": digest, "imageTag": tag},
        "imageManifest": manifest,
        "imageManifestMediaType": MEDIA_TYPE,
    }


class PromoteImageTest(unittest.TestCase):
    def run_promote(self, images, current_digest="sha256:" + "b" * 64, failures=None):
        workflow = WORKFLOW.read_text()
        start = workflow.index("          promote_image() {")
        end = workflow.index("          deploy_service() {", start)
        function = textwrap.dedent(workflow[start:end])
        # 함수가 실제 AWS CLI를 호출하지 않도록 셸 함수로 대체한다.
        stub = r'''
aws() {
  case "$1 $2" in
    'ecr batch-get-image')
      if [[ "$*" == *'imageTag='* ]]; then
        printf '%s\n' "$CURRENT_DIGEST"
      else
        printf '%s\n' "$SOURCE_RESPONSE"
      fi
      ;;
    'ecr put-image') printf '%s\0' "$@" > "$PUT_ARGS" ;;
    *) return 99 ;;
  esac
}
'''
        with tempfile.TemporaryDirectory() as directory:
            args_path = Path(directory) / "put-args"
            result = subprocess.run(
                ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c",
                 stub + function + '\npromote_image test/repository "$DIGEST"'],
                env={
                    **os.environ,
                    "SOURCE_RESPONSE": json.dumps({"images": images, "failures": failures or []}),
                    "CURRENT_DIGEST": current_digest,
                    "IMAGE_TAG": "dev",
                    "DIGEST": DIGEST,
                    "PUT_ARGS": str(args_path),
                },
                capture_output=True, text=True,
            )
            args = args_path.read_text().split("\0")[:-1] if args_path.exists() else []
        return result, args

    def assert_promoted(self, images):
        result, args = self.run_promote(images)
        self.assertEqual(result.returncode, 0, result.stderr)
        for option, expected in {
            "--image-digest": DIGEST,
            "--image-tag": "dev",
            "--image-manifest": MANIFEST,
            "--image-manifest-media-type": MEDIA_TYPE,
        }.items():
            self.assertEqual(args[args.index(option) + 1], expected)

    def test_single_tag(self):
        self.assert_promoted([image("sha-commit")])

    def test_multiple_tags_for_same_digest(self):
        self.assert_promoted([image("sha-commit"), image("dev"), image("sha-other-commit")])

    def test_rerun_after_enrollment_was_promoted(self):
        result, args = self.run_promote([image("sha-commit"), image("dev")], DIGEST)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(args, [])

    def test_selects_requested_digest_even_when_not_first(self):
        self.assert_promoted([image("other", digest="sha256:" + "c" * 64), image("sha-commit")])

    def test_invalid_responses_stop_before_promote(self):
        cases = {
            "missing": ([], []),
            "wrong_digest": ([image("other", digest="wrong")], []),
            "conflicting_manifests": ([image("sha-commit"), image("dev", manifest="different")], []),
            "empty_manifest": ([image("sha-commit", manifest="")], []),
            "missing_media_type": ([{**image("sha-commit"), "imageManifestMediaType": None}], []),
            "api_failure": ([image("sha-commit")], [{"failureCode": "ImageNotFound"}]),
        }
        for name, (images, failures) in cases.items():
            with self.subTest(name=name):
                result, args = self.run_promote(images, failures=failures)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(args, [])


if __name__ == "__main__":
    unittest.main()
