#!/usr/bin/env python3
"""검토된 infra 실행기를 고정해 호출한다. 후보 artifact의 코드는 실행하지 않는다."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from contract import REPOSITORY, encode, strict_json
from prepare_promotion import Github, prepare
from verify_image_contracts import verify_candidate


def tooling_revision(path):
    lock = strict_json(Path(path).read_bytes())
    if set(lock) != {"schemaVersion", "repository", "revision"} or lock["schemaVersion"] != 1 or lock["repository"] != "soma-376/infra":
        raise ValueError("infra tooling lock 형식 오류")
    if not isinstance(lock["revision"], str) or not re.fullmatch(r"[a-f0-9]{40}", lock["revision"]) or lock["revision"] == "0" * 40:
        raise ValueError("검토된 infra tooling commit을 먼저 고정해야 한다")
    return lock["revision"]


def trusted_context(env, event):
    default = event.get("repository", {}).get("default_branch")
    if env.get("DELIVERY_ENABLED") != "true" or env.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise ValueError("GitOps 승격 비활성 또는 repository 불일치")
    if not default or env.get("GITHUB_REF") != "refs/heads/" + default or env.get("GITHUB_WORKFLOW_REF") != REPOSITORY + "/.github/workflows/promote_dev.yml@refs/heads/" + default:
        raise ValueError("검토된 default branch 승격 workflow가 필요하다")
    kind = env.get("GITHUB_EVENT_NAME")
    if kind not in ("workflow_run", "workflow_dispatch", "schedule"):
        raise ValueError("허용하지 않은 승격 이벤트")
    if kind == "workflow_run":
        producer = event.get("workflow_run", {})
        if producer.get("conclusion") != "success" or producer.get("head_branch") != "develop" or producer.get("head_repository", {}).get("full_name") != REPOSITORY:
            raise ValueError("허용하지 않은 생산 이벤트")


def check_ancestry(provenance):
    # 한 배포의 잠금 해제 뒤 새 commit의 후보를 고를 수 있으므로 checkout 시점 이력을 재사용하지 않는다.
    fetched = subprocess.run(["git", "fetch", "--no-tags", "origin", "+refs/heads/develop:refs/remotes/origin/develop"],
                             capture_output=True, timeout=60, check=False)
    if fetched.returncode:
        raise ValueError("현재 develop 이력 조회 실패")
    if subprocess.run(["git", "merge-base", "--is-ancestor", provenance["backendCommit"], "refs/remotes/origin/develop"], capture_output=True, timeout=30).returncode:
        raise ValueError("생산 source가 현재 develop 이력 밖에 있다")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preflight", action="store_true")
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--infra", default=".gitops-infra")
    args = parser.parse_args()
    event = strict_json(Path(os.environ["GITHUB_EVENT_PATH"]).read_bytes())
    trusted_context(os.environ, event)
    revision = tooling_revision("config/gitops-tooling.json")
    if args.preflight:
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write("revision=" + revision + "\n")
        return
    infra = Path(args.infra).resolve()
    actual = subprocess.run(["git", "-C", str(infra), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    if actual != revision:
        raise ValueError("infra tooling checkout 불일치")
    run_id = attempt = None
    if os.environ["GITHUB_EVENT_NAME"] == "workflow_dispatch":
        inputs = event.get("inputs", {})
        if bool(inputs.get("run_id")) != bool(inputs.get("run_attempt")):
            raise ValueError("수동 재평가는 원본 run/attempt를 함께 지정해야 한다")
        if inputs.get("run_id"):
            run_id, attempt = int(inputs["run_id"]), int(inputs["run_attempt"])
    github = Github(os.environ.get("GH_TOKEN"))
    prepared = Path(os.environ["RUNNER_TEMP"]) / "verified-gitops-candidate"
    if args.prepare:
        raw, provenance = prepare(github, run_id, attempt)
        check_ancestry(provenance)
        verify_candidate(strict_json(raw))
        prepared.mkdir(mode=0o700, exist_ok=False)
        (prepared / "completion.json").write_bytes(raw)
        (prepared / "producer-evidence.json").write_bytes(encode(provenance))
        return
    seen = set()
    started = time.monotonic()
    # 매번 최신 적격성을 재평가한다. 나머지 후보는 schedule 또는 다음 완료 이벤트가 이어받는다.
    for index in range(3):
        if index and time.monotonic() - started > 300:
            # token 수명·job 제한 안에서 한 번의 복구 시간을 남긴다. 다음 schedule이 이어받는다.
            return
        if index == 0:
            raw = (prepared / "completion.json").read_bytes()
            provenance = strict_json((prepared / "producer-evidence.json").read_bytes())
            # token 생성 전 검사와 같은 원본 run/attempt·artifact인지 다시 확인한다.
            current, evidence = prepare(github, int(provenance["workflowRunId"]), int(provenance["workflowRunAttempt"]))
            if raw != current or provenance != evidence:
                raise ValueError("승격 준비 후 원본 증거 변경")
        else:
            raw, provenance = prepare(github, run_id, attempt)
        identity = (provenance["workflowRunId"], provenance["workflowRunAttempt"])
        if identity in seen:
            return
        seen.add(identity)
        if index != 0:
            verify_candidate(strict_json(raw))
            if time.monotonic() - started > 300:
                return
        check_ancestry(provenance)
        if index and time.monotonic() - started > 300:
            return
        with tempfile.TemporaryDirectory(prefix="gitops-candidate-") as temporary:
            candidate = Path(temporary) / "completion.json"
            evidence = Path(temporary) / "producer-evidence.json"
            candidate.write_bytes(raw)
            evidence.write_bytes(encode(provenance))
            # 고정 실행기만 호출하고 입력은 파일 인자로 전달한다. shell을 사용하지 않는다.
            result = subprocess.run(["node", str(infra / "scripts/dev-gitops/promote.mjs"),
                                     "--candidate", str(candidate), "--provenance", str(evidence)], check=False)
            if result.returncode:
                raise ValueError("승격 결과 확인 필요")
        if run_id is not None:
            return


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("GitOps 승격 중단: 입력·도구 고정·공통 상태를 확인한다", file=sys.stderr)
        sys.exit(1)
