"""AWS 호출과 실제 대기 없이 이미지 게시 설정·승격·배포 순서를 검증한다."""

import json
import os
from pathlib import Path
import shlex
from itertools import combinations
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


def workflow_step(name):
    workflow = WORKFLOW.read_text()
    start = workflow.index(f"      - name: {name}\n")
    end = workflow.find("      - name: ", start + 1)
    return workflow[start:end if end != -1 else len(workflow)]


def step_script(name):
    return textwrap.dedent(workflow_step(name).split("        run: |\n", 1)[1])


class DeploymentWorkflowTest(unittest.TestCase):
    CONFIG = "Verify required GitHub Secrets"
    PREFLIGHT = "Verify all ECS services are active"
    DEPLOY = "Deploy enrollment-api, then telemetry-ingest, then dashboard-api"
    APPS = (("ENROLLMENT_API", "enrollment"), ("TELEMETRY_INGEST", "ingest"),
            ("DASHBOARD_API", "dashboard"))
    ENV = {
        "AWS_REGION": "fixture-region", "IMAGE_TAG": "dev",
        "AWS_DEPLOY_ROLE_ARN": "fixture-role", "ECS_CLUSTER": "fixture-cluster",
        "ECR_REPO_ENROLLMENT_API": "fixture/enrollment",
        "ECR_REPO_TELEMETRY_INGEST": "fixture/ingest",
        "ECR_REPO_DASHBOARD_API": "fixture/dashboard",
        "ECS_SERVICE_ENROLLMENT_API": "fixture-enrollment",
        "ECS_SERVICE_TELEMETRY_INGEST": "fixture-ingest",
        "ECS_SERVICE_DASHBOARD_API": "fixture-dashboard",
        "ENROLLMENT_IMAGE_DIGEST": DIGEST, "INGEST_IMAGE_DIGEST": DIGEST,
        "DASHBOARD_IMAGE_DIGEST": DIGEST,
    }
    # 실제 AWS 실행 파일 대신 셸 함수가 모든 호출을 처리한다. 오류·대기도 모의한다.
    AWS_STUB = r'''
aws() {
  printf 'aws %s\n' "$*" >> "$CALL_LOG"
  local service='' arg collecting=false
  local -a requested=()
  for arg in "$@"; do
    if [[ "$arg" == --service || "$arg" == --services ]]; then
      collecting=true
      continue
    fi
    [[ "$arg" != --* ]] || collecting=false
    if [[ "$collecting" == true ]]; then requested+=("$arg"); service="$arg"; fi
  done
  case "$1 $2" in
    'ecr batch-get-image')
      if [[ "$*" == *'imageTag='* ]]; then printf '%s\n' different-digest
      else printf '%s\n' "$SOURCE_RESPONSE"; fi ;;
    'ecr put-image') printf '%s\n' "$DIGEST" ;;
    'ecs update-service') printf '%s\n' "$DEPLOYMENT_ID" ;;
    'ecs wait')
      [[ "$service" != "$FAIL_SERVICE" ]] || return 42 ;;
    'ecs describe-services')
      if (( ${#requested[@]} > 1 )); then
        [[ "$DESCRIBE_EXIT" == 0 ]] || return "$DESCRIBE_EXIT"
        printf '%s\n' "$PREFLIGHT_RESPONSE"
      else
        jq --arg service "$service" --arg failed "$FAILED_DEPLOYMENT_SERVICE" '
          .services[0].serviceName = $service |
          if $service == $failed then .services[0].deployments[0].rolloutState = "FAILED"
          else . end' <<< "$SERVICE_RESPONSE"
      fi ;;
    *) echo '예상하지 않은 모의 AWS 호출' >&2; return 99 ;;
  esac
}
sleep() { printf 'sleep %s\n' "$*" >> "$CALL_LOG"; }
'''

    def ready_services(self):
        services = []
        for setting, _ in self.APPS:
            service = service_response()["services"][0]
            service["serviceName"] = self.ENV[f"ECS_SERVICE_{setting}"]
            services.append(service)
        return {"services": services, "failures": []}

    def run_steps(self, names=None, **overrides):
        names = names or (self.CONFIG, self.PREFLIGHT, self.DEPLOY)
        with tempfile.TemporaryDirectory() as directory:
            calls = Path(directory) / "calls"
            result = subprocess.run(
                ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c",
                 self.AWS_STUB + "\n".join(step_script(name) for name in names)],
                env={
                    **os.environ, **self.ENV, "CALL_LOG": str(calls),
                    "DIGEST": DIGEST,
                    "DEPLOYMENT_ID": DEPLOYMENT_ID, "FAIL_SERVICE": "",
                    "DESCRIBE_EXIT": "0", "FAILED_DEPLOYMENT_SERVICE": "",
                    "SOURCE_RESPONSE": json.dumps({"images": [image("sha-fixture")], "failures": []}),
                    "SERVICE_RESPONSE": json.dumps(service_response()),
                    "PREFLIGHT_RESPONSE": json.dumps(self.ready_services()), **overrides,
                }, capture_output=True, text=True, timeout=10,
            )
            return result, calls.read_text().splitlines() if calls.exists() else []

    def lifecycle(self, calls):
        lifecycle = []
        for call in calls:
            args = shlex.split(call)
            if args[:3] == ["aws", "ecr", "put-image"]:
                lifecycle.append("promote:" + args[args.index("--repository-name") + 1])
            elif args[:3] == ["aws", "ecs", "update-service"]:
                lifecycle.append("update:" + args[args.index("--service") + 1])
            elif args[:3] == ["aws", "ecs", "wait"]:
                lifecycle.append("wait:" + args[args.index("--services") + 1])
        return lifecycle

    def expected_lifecycle(self, apps=("enrollment", "ingest", "dashboard")):
        return [call for app in apps for call in
                (f"promote:fixture/{app}", f"update:fixture-{app}", f"wait:fixture-{app}")]

    def test_all_images_use_common_build_and_secret_wiring(self):
        workflow = WORKFLOW.read_text()
        for setting, app in self.APPS:
            for prefix in ("ECR_REPO", "ECS_SERVICE"):
                key = f"{prefix}_{setting}"
                self.assertIn(key + ": ${{ secrets." + key + "_DEV }}", workflow)
            target = setting.lower().replace("_", "-")
            build_name = f"Build and push {target}"
            build = workflow_step(build_name)
            for line in (
                f"id: build-{app}", "context: .", "file: Dockerfile", f"target: {target}",
                "platforms: linux/arm64", "provenance: false", "push: true",
                "${{ steps.ecr.outputs.registry }}/${{ env.ECR_REPO_" + setting + " }}:sha-${{ github.sha }}",
                f"cache-from: type=gha,scope={target}", f"cache-to: type=gha,mode=max,scope={target}",
            ):
                self.assertIn(line, build)
            if app != "enrollment":
                self.assertNotIn("build-contexts:", build)
            self.assertIn(app.upper() + "_IMAGE_DIGEST: ${{ steps.build-" + app + ".outputs.digest }}",
                          workflow_step(self.DEPLOY))
            self.assertLess(workflow.index(f"- name: {self.PREFLIGHT}"),
                            workflow.index(f"- name: {build_name}"))

    def test_missing_repository_or_service_stops_before_aws_without_values(self):
        for prefix in ("ECR_REPO", "ECS_SERVICE"):
            for setting, _ in self.APPS:
                key = f"{prefix}_{setting}"
                with self.subTest(key=key):
                    result, calls = self.run_steps(**{key: ""})
                    self.assertNotEqual(result.returncode, 0)
                    output = result.stdout + result.stderr
                    self.assertIn(key + "_DEV", output)
                    self.assertTrue(all(value not in output for value in self.ENV.values()))
                    self.assertEqual(calls, [])

    def test_any_duplicate_repository_or_service_stops_before_aws(self):
        for prefix in ("ECR_REPO", "ECS_SERVICE"):
            for (first, _), (second, _) in combinations(self.APPS, 2):
                with self.subTest(prefix=prefix, first=first, second=second):
                    value = self.ENV[f"{prefix}_{first}"]
                    result, calls = self.run_steps(**{f"{prefix}_{second}": value})
                    self.assertNotEqual(result.returncode, 0)
                    self.assertNotIn(value, result.stdout + result.stderr)
                    self.assertEqual(calls, [])

    def test_three_services_promote_update_and_wait_in_order(self):
        ready = self.ready_services()
        ready["services"].reverse()
        result, calls = self.run_steps(PREFLIGHT_RESPONSE=json.dumps(ready))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.lifecycle(calls), self.expected_lifecycle())
        self.assertEqual(calls[0],
                         "aws ecs describe-services --cluster fixture-cluster --services "
                         "fixture-enrollment fixture-ingest fixture-dashboard --no-cli-pager --output json")
        for _, app in self.APPS:
            describes = [call for call in calls[1:] if call.startswith("aws ecs describe-services ")
                         and f"--services fixture-{app} " in call]
            self.assertEqual(len(describes), 1)

    def test_any_wait_failure_stops_later_services(self):
        for index, (_, app) in enumerate(self.APPS):
            with self.subTest(app=app):
                result, calls = self.run_steps(FAIL_SERVICE=f"fixture-{app}")
                self.assertEqual(result.returncode, 42)
                apps = tuple(name for _, name in self.APPS[:index + 1])
                self.assertEqual(self.lifecycle(calls), self.expected_lifecycle(apps))
                self.assertNotIn(f"배포 완료: fixture-{app}", result.stdout)

    def assert_preflight_rejected(self, **overrides):
        result, calls = self.run_steps(**overrides)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.lifecycle(calls), [])
        self.assertEqual(len(calls), 1)

    def test_any_unready_or_mismatched_service_prevents_all_promotions(self):
        for index, (_, app) in enumerate(self.APPS):
            for field, value in (("serviceName", "another-service"), ("status", "DRAINING"),
                                 ("desiredCount", 0), ("desiredCount", "2"), ("desiredCount", True),
                                 ("desiredCount", -1), ("desiredCount", 1.5), ("desiredCount", MISSING)):
                with self.subTest(app=app, field=field, value=value):
                    response = self.ready_services()
                    if value is MISSING:
                        del response["services"][index][field]
                    else:
                        response["services"][index][field] = value
                    self.assert_preflight_rejected(PREFLIGHT_RESPONSE=json.dumps(response))

    def test_invalid_service_preflight_response_prevents_all_promotions(self):
        ready = self.ready_services()
        invalid = ["", "not json", "null", "[]", "{}", json.dumps(ready) + "\n" + json.dumps(ready)]
        for services in ([], ready["services"][:2], ready["services"] + ready["services"][:1]):
            invalid.append(json.dumps({**ready, "services": services}))
        for failures in (None, {}, [{"reason": "MISSING"}]):
            invalid.append(json.dumps({**ready, "failures": failures}))
        for response in invalid:
            with self.subTest(response=response):
                self.assert_preflight_rejected(PREFLIGHT_RESPONSE=response)

    def test_service_preflight_api_failure_prevents_all_promotions(self):
        self.assert_preflight_rejected(DESCRIBE_EXIT="42")

    def test_any_failed_deployment_completion_stops_later_services(self):
        for index, (_, app) in enumerate(self.APPS):
            with self.subTest(app=app):
                result, calls = self.run_steps(FAILED_DEPLOYMENT_SERVICE=f"fixture-{app}")
                self.assertNotEqual(result.returncode, 0)
                apps = tuple(name for _, name in self.APPS[:index + 1])
                self.assertEqual(self.lifecycle(calls), self.expected_lifecycle(apps))
                self.assertNotIn(f"배포 완료: fixture-{app}", result.stdout)


if __name__ == "__main__":
    unittest.main()
