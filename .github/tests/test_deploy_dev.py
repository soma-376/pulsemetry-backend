"""AWS 호출 없이 dev 이미지 공급 설정·digest 승격·실패와 재실행을 검증한다."""

import json
import os
from pathlib import Path
from itertools import combinations
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


def workflow_step(name):
    workflow = WORKFLOW.read_text()
    start = workflow.index(f"      - name: {name}\n")
    end = workflow.find("      - name: ", start + 1)
    return workflow[start:end if end != -1 else len(workflow)]


def step_script(name):
    return textwrap.dedent(workflow_step(name).split("        run: |\n", 1)[1])


class PromoteImageTest(unittest.TestCase):
    def run_promote(self, images, current_digest="sha256:" + "b" * 64, failures=None):
        script = step_script("Promote dev image tags")
        end = script.index('promote_image "$ECR_REPO_ENROLLMENT_API"')
        function = script[:end]
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


class ImagePublicationWorkflowTest(unittest.TestCase):
    CONFIG = "Verify required GitHub Secrets"
    PROMOTE = "Promote dev image tags"
    APPS = (("ENROLLMENT_API", "enrollment"), ("TELEMETRY_INGEST", "ingest"),
            ("DASHBOARD_API", "dashboard"))
    ENV = {
        "AWS_REGION": "fixture-region", "IMAGE_TAG": "dev",
        "AWS_DEPLOY_ROLE_ARN": "fixture-role",
        "ECR_REPO_ENROLLMENT_API": "fixture/enrollment",
        "ECR_REPO_TELEMETRY_INGEST": "fixture/ingest",
        "ECR_REPO_DASHBOARD_API": "fixture/dashboard",
        "ENROLLMENT_IMAGE_DIGEST": "sha256:" + "a" * 64,
        "INGEST_IMAGE_DIGEST": "sha256:" + "b" * 64,
        "DASHBOARD_IMAGE_DIGEST": "sha256:" + "c" * 64,
    }
    REQUIRED = {
        "AWS_REGION": "AWS_REGION", "IMAGE_TAG": "IMAGE_TAG_DEV",
        "AWS_DEPLOY_ROLE_ARN": "AWS_DEPLOY_ROLE_ARN_DEV",
        "ECR_REPO_ENROLLMENT_API": "ECR_REPO_ENROLLMENT_API_DEV",
        "ECR_REPO_TELEMETRY_INGEST": "ECR_REPO_TELEMETRY_INGEST_DEV",
        "ECR_REPO_DASHBOARD_API": "ECR_REPO_DASHBOARD_API_DEV",
    }
    OLD_DIGEST = "sha256:" + "d" * 64
    # AWS CLI 대신 함수가 호출을 기록하고 ECR의 dev 태그 상태를 임시 파일에서 모의한다.
    # 허용 목록 밖의 AWS 호출은 항상 실패하므로 ECS 호출을 조용히 통과시키지 않는다.
    AWS_STUB = r'''
aws() {
  python3 -c 'import json, sys
with open(sys.argv[1], "a") as log:
    log.write(json.dumps(sys.argv[2:]) + "\n")' "$CALL_LOG" "$@"
  local repository='' digest='' image_id='' option='' argument operation
  for argument in "$@"; do
    case "$option" in
      --repository-name) repository="$argument" ;;
      --image-digest) digest="$argument" ;;
      --image-ids) image_id="$argument" ;;
    esac
    option="$argument"
  done
  case "$1 $2" in
    'ecr batch-get-image')
      case "$image_id" in
        imageDigest=*) operation=source ;;
        imageTag=dev) operation=current ;;
        *) echo '예상하지 않은 모의 이미지 조회' >&2; return 99 ;;
      esac ;;
    'ecr put-image') operation=put ;;
    *) echo '예상하지 않은 모의 AWS 호출' >&2; return 99 ;;
  esac
  if [[ "$repository" == "$FAIL_REPOSITORY" && "$operation" == "$FAIL_OPERATION" ]]; then
    return 42
  fi
  case "$operation" in
    source) jq -c --arg repository "$repository" '.[$repository]' <<< "$SOURCE_RESPONSES" ;;
    current) jq -r --arg repository "$repository" '.[$repository]' "$STATE_FILE" ;;
    put)
      jq --arg repository "$repository" --arg digest "$digest" \
        '.[$repository] = $digest' "$STATE_FILE" > "$STATE_FILE.next"
      mv "$STATE_FILE.next" "$STATE_FILE"
      printf '%s\n' "$digest" ;;
  esac
}
'''

    def expected_state(self, promoted=()):
        return {
            self.ENV[f"ECR_REPO_{setting}"]:
            self.ENV[f"{app.upper()}_IMAGE_DIGEST"] if app in promoted else self.OLD_DIGEST
            for setting, app in self.APPS
        }

    def run_steps(self, *, state=None, **overrides):
        sources = {
            self.ENV[f"ECR_REPO_{setting}"]: {
                "images": [image("sha-fixture", self.ENV[f"{app.upper()}_IMAGE_DIGEST"])],
                "failures": [],
            }
            for setting, app in self.APPS
        }
        with tempfile.TemporaryDirectory() as directory:
            calls = Path(directory) / "calls"
            state_path = Path(directory) / "state.json"
            state_path.write_text(json.dumps(self.expected_state() if state is None else state))
            # 개발자 셸에 남은 ECS 설정으로 테스트가 우연히 통과하지 않게 제거한다.
            environment = {key: value for key, value in os.environ.items()
                           if not key.startswith("ECS_")}
            result = subprocess.run(
                ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c",
                 self.AWS_STUB + "\n".join(step_script(name)
                                           for name in (self.CONFIG, self.PROMOTE))],
                env={
                    **environment, **self.ENV, "CALL_LOG": str(calls),
                    "STATE_FILE": str(state_path), "SOURCE_RESPONSES": json.dumps(sources),
                    "FAIL_REPOSITORY": "", "FAIL_OPERATION": "", **overrides,
                }, capture_output=True, text=True, timeout=10,
            )
            recorded = [json.loads(line) for line in calls.read_text().splitlines()] \
                if calls.exists() else []
            return result, recorded, json.loads(state_path.read_text())

    def lifecycle(self, calls):
        lifecycle = []
        for args in calls:
            self.assertIn(args[:2], [["ecr", "batch-get-image"], ["ecr", "put-image"]])
            repository = args[args.index("--repository-name") + 1]
            if args[1] == "put-image":
                operation = "put"
            else:
                image_id = args[args.index("--image-ids") + 1]
                operation = "source" if image_id.startswith("imageDigest=") else "current"
            lifecycle.append((repository, operation))
        return lifecycle

    def expected_lifecycle(self, apps=("enrollment", "ingest", "dashboard"), skip_put=()):
        return [(f"fixture/{app}", operation) for app in apps
                for operation in (("source", "current") if app in skip_put
                                  else ("source", "current", "put"))]

    def test_all_images_use_arm64_sha_tags_and_build_digests(self):
        workflow = WORKFLOW.read_text()
        promotion = workflow_step(self.PROMOTE)
        self.assertIn("runs-on: ubuntu-24.04-arm", workflow)
        for setting, app in self.APPS:
            key = f"ECR_REPO_{setting}"
            self.assertIn(key + ": ${{ secrets." + key + "_DEV }}", workflow)
            target = setting.lower().replace("_", "-")
            build = workflow_step(f"Build and push {target}")
            for line in (
                f"id: build-{app}", "context: .", "file: Dockerfile", f"target: {target}",
                "platforms: linux/arm64", "provenance: false", "push: true",
                "${{ steps.ecr.outputs.registry }}/${{ env.ECR_REPO_" + setting + " }}:sha-${{ github.sha }}",
                f"cache-from: type=gha,scope={target}", f"cache-to: type=gha,mode=max,scope={target}",
            ):
                self.assertIn(line, build)
            if app == "enrollment":
                self.assertIn("telemetry-contracts=./telemetryctl/contracts", build)
            else:
                self.assertNotIn("build-contexts:", build)
            self.assertIn(app.upper() + "_IMAGE_DIGEST: ${{ steps.build-" + app + ".outputs.digest }}",
                          promotion)
            self.assertNotIn("env.IMAGE_TAG", build)

    def test_validation_and_every_build_gate_tag_promotion(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("needs: validate", workflow)
        promotion_position = workflow.index(f"- name: {self.PROMOTE}")
        for setting, _ in self.APPS:
            name = "Build and push " + setting.lower().replace("_", "-")
            self.assertLess(workflow.index(f"- name: {self.CONFIG}"),
                            workflow.index(f"- name: {name}"))
            self.assertLess(workflow.index(f"- name: {name}"), promotion_position)
            # GitHub Actions의 기본 success 조건을 우회하는 옵션을 허용하지 않는다.
            self.assertNotRegex(workflow_step(name), r"(?m)^        (?:if|continue-on-error):")
        self.assertNotRegex(workflow_step(self.PROMOTE), r"(?m)^        (?:if|continue-on-error):")

    def test_workflow_has_no_ecs_or_cluster_deployment(self):
        workflow = WORKFLOW.read_text()
        self.assertNotRegex(workflow, r"ECS_(?:CLUSTER|SERVICE)|\baws\s+ecs\b")
        self.assertNotRegex(workflow,
                            r"(?m)^\s*(?:run:\s*)?(?:kubectl\s|helm\s|cdk\s+deploy\b|aws\s+eks\b)")
        result, calls, state = self.run_steps()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.lifecycle(calls), self.expected_lifecycle())
        self.assertEqual(state, self.expected_state(("enrollment", "ingest", "dashboard")))

    def test_each_required_setting_stops_before_aws_without_values(self):
        for key, secret_name in self.REQUIRED.items():
            with self.subTest(key=key):
                result, calls, state = self.run_steps(**{key: ""})
                self.assertNotEqual(result.returncode, 0)
                output = result.stdout + result.stderr
                self.assertIn(secret_name, output)
                self.assertTrue(all(value not in output for value in self.ENV.values()))
                self.assertEqual(calls, [])
                self.assertEqual(state, self.expected_state())

    def test_non_dev_tag_stops_before_aws(self):
        result, calls, state = self.run_steps(IMAGE_TAG="fixture-invalid-tag")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("IMAGE_TAG_DEV", result.stdout + result.stderr)
        self.assertNotIn("fixture-invalid-tag", result.stdout + result.stderr)
        self.assertEqual(calls, [])
        self.assertEqual(state, self.expected_state())

    def test_any_duplicate_repository_stops_before_aws(self):
        for (first, _), (second, _) in combinations(self.APPS, 2):
            with self.subTest(first=first, second=second):
                value = self.ENV[f"ECR_REPO_{first}"]
                result, calls, state = self.run_steps(**{f"ECR_REPO_{second}": value})
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn(value, result.stdout + result.stderr)
                self.assertEqual(calls, [])
                self.assertEqual(state, self.expected_state())

    def test_three_distinct_digests_promote_in_order(self):
        result, calls, state = self.run_steps()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.lifecycle(calls), self.expected_lifecycle())
        for index, (_, app) in enumerate(self.APPS):
            digest = self.ENV[f"{app.upper()}_IMAGE_DIGEST"]
            source, current, put = calls[index * 3:index * 3 + 3]
            self.assertEqual(source[source.index("--image-ids") + 1], f"imageDigest={digest}")
            self.assertEqual(current[current.index("--image-ids") + 1], "imageTag=dev")
            self.assertEqual(put[put.index("--image-digest") + 1], digest)
            self.assertEqual(put[put.index("--image-tag") + 1], "dev")
        self.assertEqual(state, self.expected_state(("enrollment", "ingest", "dashboard")))

    def test_any_ecr_failure_stops_later_promotions_without_rollback(self):
        operations = ("source", "current", "put")
        for app_index, (_, app) in enumerate(self.APPS):
            for operation_index, operation in enumerate(operations):
                with self.subTest(app=app, operation=operation):
                    result, calls, state = self.run_steps(
                        FAIL_REPOSITORY=f"fixture/{app}", FAIL_OPERATION=operation,
                    )
                    self.assertEqual(result.returncode, 42, result.stdout + result.stderr)
                    expected = self.expected_lifecycle()[:app_index * 3 + operation_index + 1]
                    self.assertEqual(self.lifecycle(calls), expected)
                    prior = tuple(name for _, name in self.APPS[:app_index])
                    self.assertEqual(state, self.expected_state(prior))

    def test_same_digest_rerun_has_no_puts(self):
        all_apps = tuple(app for _, app in self.APPS)
        before = self.expected_state(all_apps)
        result, calls, state = self.run_steps(state=before)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.lifecycle(calls), self.expected_lifecycle(skip_put=all_apps))
        self.assertEqual(state, before)

    def test_partial_retry_keeps_promoted_tag_and_finishes_remaining_apps(self):
        failed, _, partial = self.run_steps(
            FAIL_REPOSITORY="fixture/ingest", FAIL_OPERATION="put",
        )
        self.assertEqual(failed.returncode, 42)
        self.assertEqual(partial, self.expected_state(("enrollment",)))
        result, calls, state = self.run_steps(state=partial)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.lifecycle(calls), self.expected_lifecycle(skip_put=("enrollment",)))
        self.assertEqual(state, self.expected_state(("enrollment", "ingest", "dashboard")))


if __name__ == "__main__":
    unittest.main()
