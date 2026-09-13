"""AWS 호출과 실제 대기 없이 워크플로의 이미지 승격·배포 함수를 검증한다."""

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
SERVICE = "test-service"
DEPLOYMENT_ID = "ecs-svc/requested"
MISSING = object()


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


def service_response(rollout_state="COMPLETED"):
    return {
        "failures": [],
        "services": [{
            "serviceName": SERVICE,
            "status": "ACTIVE",
            "desiredCount": 2,
            "runningCount": 2,
            "pendingCount": 0,
            "deployments": [{
                "id": DEPLOYMENT_ID,
                "status": "PRIMARY",
                "rolloutState": rollout_state,
            }],
        }],
    }


def changed_response(path, value, rollout_state="COMPLETED"):
    response = service_response(rollout_state)
    target = response
    for part in path[:-1]:
        target = target[part]
    if value is MISSING:
        del target[path[-1]]
    else:
        target[path[-1]] = value
    return response


class DeployServiceTest(unittest.TestCase):
    def run_deploy(self, responses, *, waiter_exit=0, describe_error_at=-1):
        workflow = WORKFLOW.read_text()
        start = workflow.index("          deploy_service() {")
        end = workflow.index('          promote_image "$ECR_REPO_ENROLLMENT_API"', start)
        function = textwrap.dedent(workflow[start:end])
        # 명령 치환의 서브셸에서도 조회 횟수가 보존되도록 파일에 기록한다.
        stub = r'''
aws() {
  printf 'aws %s\n' "$*" >> "$CALL_LOG"
  case "$1 $2" in
    'ecs update-service') printf '%s\n' "$DEPLOYMENT_ID" ;;
    'ecs wait')
      [[ "$3" == services-stable ]] || return 99
      return "$WAITER_EXIT"
      ;;
    'ecs describe-services')
      local count index
      count=$(< "$DESCRIBE_COUNT")
      printf '%s\n' "$((count + 1))" > "$DESCRIBE_COUNT"
      if (( count == DESCRIBE_ERROR_AT )); then
        echo '모의 describe-services 오류' >&2
        return 42
      fi
      # 응답이 소진되면 마지막 값을 반복해 무한 대기 회귀도 검증한다.
      index=$((count < RESPONSE_COUNT ? count : RESPONSE_COUNT - 1))
      cat "$RESPONSE_DIRECTORY/$index.json"
      ;;
    *) echo "예상하지 않은 AWS 호출: $*" >&2; return 99 ;;
  esac
}
sleep() {
  printf 'sleep %s\n' "$*" >> "$CALL_LOG"
}
'''
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            for index, response in enumerate(responses):
                raw = response if isinstance(response, str) else json.dumps(response)
                (directory / f"{index}.json").write_text(raw)
            count_path = directory / "describe-count"
            count_path.write_text("0\n")
            call_log = directory / "calls"
            result = subprocess.run(
                ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c",
                 stub + function + '\ndeploy_service "$SERVICE"'],
                env={
                    **os.environ,
                    "SERVICE": SERVICE,
                    "DEPLOYMENT_ID": DEPLOYMENT_ID,
                    "ECS_CLUSTER": "test-cluster",
                    "CALL_LOG": str(call_log),
                    "DESCRIBE_COUNT": str(count_path),
                    "RESPONSE_DIRECTORY": str(directory),
                    "RESPONSE_COUNT": str(len(responses)),
                    "WAITER_EXIT": str(waiter_exit),
                    "DESCRIBE_ERROR_AT": str(describe_error_at),
                },
                capture_output=True, text=True, timeout=10,
            )
            calls = call_log.read_text().splitlines() if call_log.exists() else []
        return result, calls

    def assert_calls(self, calls, describes, sleeps):
        self.assertTrue(calls[0].startswith("aws ecs update-service "))
        self.assertIn("--force-new-deployment", calls[0])
        self.assertEqual(
            calls[1],
            f"aws ecs wait services-stable --cluster test-cluster --services {SERVICE}",
        )
        expected = []
        for attempt in range(describes):
            expected.append(
                f"aws ecs describe-services --cluster test-cluster --services {SERVICE} "
                "--no-cli-pager --output json"
            )
            if attempt < sleeps:
                expected.append("sleep 10")
        self.assertEqual(calls[2:], expected)

    def assert_rejected(self, response):
        result, calls = self.run_deploy([response])
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("배포 완료:", result.stdout)
        self.assert_calls(calls, describes=1, sleeps=0)
        return result

    def test_immediately_completed(self):
        result, calls = self.run_deploy([service_response()])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"배포 완료: {SERVICE} ({DEPLOYMENT_ID})", result.stdout)
        self.assert_calls(calls, describes=1, sleeps=0)

    def test_in_progress_then_completed(self):
        result, calls = self.run_deploy([
            service_response("IN_PROGRESS"), service_response(),
        ])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_calls(calls, describes=2, sleeps=1)

    def test_in_progress_can_have_unsettled_counts(self):
        progress = service_response("IN_PROGRESS")
        progress["services"][0].update(runningCount=1, pendingCount=1)
        result, calls = self.run_deploy([progress, service_response()])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_calls(calls, describes=2, sleeps=1)

    def test_completed_on_last_allowed_query(self):
        result, calls = self.run_deploy(
            [service_response("IN_PROGRESS")] * 30 + [service_response()]
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_calls(calls, describes=31, sleeps=30)

    def test_in_progress_times_out_without_final_sleep(self):
        result, calls = self.run_deploy([service_response("IN_PROGRESS")])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("시간 초과", result.stdout + result.stderr)
        self.assertIn("IN_PROGRESS", result.stdout + result.stderr)
        self.assertIn(SERVICE, result.stdout + result.stderr)
        self.assertIn(DEPLOYMENT_ID, result.stdout + result.stderr)
        self.assertNotIn("배포 완료:", result.stdout)
        self.assert_calls(calls, describes=31, sleeps=30)

    def test_failed_deployment_has_distinct_diagnostic(self):
        result = self.assert_rejected(service_response("FAILED"))
        self.assertRegex(result.stdout + result.stderr, "실패|롤백")
        self.assertNotIn("시간 초과", result.stdout + result.stderr)

    def test_replacement_or_rollback_is_not_retried(self):
        for rollout_state in ("COMPLETED", "IN_PROGRESS"):
            for other_id in ("ecs-svc/previous", "ecs-svc/replacement"):
                with self.subTest(rollout_state=rollout_state, deployment_id=other_id):
                    self.assert_rejected(changed_response(
                        ("services", 0, "deployments", 0, "id"), other_id, rollout_state,
                    ))

    def test_lost_primary_is_not_retried(self):
        for rollout_state in ("COMPLETED", "IN_PROGRESS"):
            with self.subTest(rollout_state=rollout_state):
                self.assert_rejected(changed_response(
                    ("services", 0, "deployments", 0, "status"), "ACTIVE", rollout_state,
                ))

    def test_failed_after_progress_stops_without_another_sleep(self):
        result, calls = self.run_deploy([
            service_response("IN_PROGRESS"), service_response("FAILED"), service_response(),
        ])
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("시간 초과", result.stdout + result.stderr)
        self.assert_calls(calls, describes=2, sleeps=1)

    def test_describe_error_stops_immediately_or_after_progress(self):
        for error_at in (0, 1):
            with self.subTest(error_at=error_at):
                result, calls = self.run_deploy(
                    [service_response("IN_PROGRESS")], describe_error_at=error_at,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("시간 초과", result.stdout + result.stderr)
                self.assertNotIn("배포 완료:", result.stdout)
                self.assert_calls(calls, describes=error_at + 1, sleeps=error_at)

    def test_waiter_failure_has_no_followup_query(self):
        result, calls = self.run_deploy([service_response()], waiter_exit=255)
        self.assertEqual(result.returncode, 255)
        self.assert_calls(calls, describes=0, sleeps=0)

    def test_all_completed_success_conditions_are_required(self):
        cases = [
            (("failures",), [{"reason": "MISSING"}]),
            (("services",), []),
            (("services",), service_response()["services"] * 2),
            (("services", 0, "serviceName"), "another-service"),
            (("services", 0, "status"), "DRAINING"),
            (("services", 0, "desiredCount"), 0),
            (("services", 0, "runningCount"), 1),
            (("services", 0, "pendingCount"), 1),
            (("services", 0, "deployments"), []),
            (("services", 0, "deployments"),
             service_response()["services"][0]["deployments"] * 2),
            (("services", 0, "deployments", 0, "rolloutState"), "UNKNOWN"),
        ]
        for path, value in cases:
            with self.subTest(path=path, value=value):
                self.assert_rejected(changed_response(path, value))

    def test_malformed_responses_are_not_retried(self):
        complete = json.dumps(service_response())
        for raw in (
            "", "not json", "{", "null", "[]", "{}", "false",
            complete + "\n" + complete, "null\n" + complete, complete + "\n{",
        ):
            with self.subTest(raw=raw):
                self.assert_rejected(raw)

    def test_required_fields_must_exist_and_have_correct_types(self):
        fields = [
            (("failures",), [None, {}, ""]),
            (("services",), [None, {}, ""]),
            (("services", 0), [None, [], ""]),
            (("services", 0, "serviceName"), [None, 2]),
            (("services", 0, "status"), [None, True]),
            (("services", 0, "desiredCount"), [None, "2", True, -1, 1.5]),
            (("services", 0, "runningCount"), [None, "2", True, -1, 1.5]),
            (("services", 0, "pendingCount"), [None, "0", False, -1, 1.5]),
            (("services", 0, "deployments"), [None, {}, ""]),
            (("services", 0, "deployments", 0), [None, [], ""]),
            (("services", 0, "deployments", 0, "id"), [None, 2]),
            (("services", 0, "deployments", 0, "status"), [None, True]),
            (("services", 0, "deployments", 0, "rolloutState"), [None, 2]),
        ]
        for rollout_state in ("COMPLETED", "IN_PROGRESS"):
            for path, values in fields:
                for value in [MISSING, *values]:
                    with self.subTest(rollout_state=rollout_state, path=path, value=value):
                        self.assert_rejected(changed_response(path, value, rollout_state))


if __name__ == "__main__":
    unittest.main()
