#!/usr/bin/env python3
"""검토된 infra 실행기를 고정해 호출한다. 후보 artifact의 코드는 실행하지 않는다."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from contract import REPOSITORY, encode, strict_json
from prepare_promotion import Github, prepare, select_run
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
    if default != "develop" or env.get("GITHUB_REF") != "refs/heads/develop" or env.get("GITHUB_WORKFLOW_REF") != REPOSITORY + "/.github/workflows/promote_dev.yml@refs/heads/develop":
        raise ValueError("검토된 develop 기본 브랜치 게시 workflow가 필요하다")
    kind = env.get("GITHUB_EVENT_NAME")
    if kind not in ("workflow_run", "workflow_dispatch"):
        raise ValueError("허용하지 않은 승격 이벤트")
    if kind == "workflow_run":
        producer = event.get("workflow_run", {})
        if producer.get("conclusion") != "success" or producer.get("head_branch") != "develop" or producer.get("head_repository", {}).get("full_name") != REPOSITORY:
            raise ValueError("허용하지 않은 생산 이벤트")


def check_ancestry(provenance):
    # 게시 직전 현재 develop 이력을 다시 읽어 강제 변경으로 이탈한 생산 source를 거부한다.
    fetched = subprocess.run(["git", "fetch", "--no-tags", "origin", "+refs/heads/develop:refs/remotes/origin/develop"],
                             capture_output=True, timeout=60, check=False)
    if fetched.returncode:
        raise ValueError("현재 develop 이력 조회 실패")
    if subprocess.run(["git", "merge-base", "--is-ancestor", provenance["backendCommit"], "refs/remotes/origin/develop"], capture_output=True, timeout=30).returncode:
        raise ValueError("생산 source가 현재 develop 이력 밖에 있다")


def prepare_latest(github, run_id=None, attempt=None):
    selected = select_run(github)
    identity = (selected["id"], selected["run_attempt"])
    if run_id is not None and (run_id, attempt) != identity:
        raise ValueError("최신 성공 생산 실행만 게시할 수 있다")
    return prepare(github, *identity)


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
        raw, provenance = prepare_latest(github, run_id, attempt)
        check_ancestry(provenance)
        verify_candidate(strict_json(raw))
        prepared.mkdir(mode=0o700, exist_ok=False)
        (prepared / "completion.json").write_bytes(raw)
        (prepared / "producer-evidence.json").write_bytes(encode(provenance))
        return
    raw = (prepared / "completion.json").read_bytes()
    provenance = strict_json((prepared / "producer-evidence.json").read_bytes())
    # token 발급 전 검증한 정확한 최신 run/attempt와 artifact인지 다시 확인한다.
    current, evidence = prepare_latest(github, int(provenance["workflowRunId"]), int(provenance["workflowRunAttempt"]))
    if raw != current or provenance != evidence:
        raise ValueError("게시 준비 후 원본 증거 변경")
    if run_id is not None and (run_id, attempt) != (int(provenance["workflowRunId"]), int(provenance["workflowRunAttempt"])):
        raise ValueError("검토한 수동 생산 입력이 달라졌다")
    check_ancestry(provenance)
    with tempfile.TemporaryDirectory(prefix="gitops-candidate-") as temporary:
        candidate = Path(temporary) / "completion.json"
        evidence = Path(temporary) / "producer-evidence.json"
        candidate.write_bytes(raw)
        evidence.write_bytes(encode(provenance))
        # 공통 실행기가 Git 충돌과 최신성을 재검사한다. Argo 적용 완료는 기다리지 않는다.
        result = subprocess.run(["node", str(infra / "scripts/dev-gitops/publish.mjs"),
                                 "--candidate", str(candidate), "--provenance", str(evidence)], check=False)
        if result.returncode:
            raise ValueError("Git 게시 결과 확인 필요")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("GitOps 게시 중단: 최신 생산 입력·도구 고정·Git 결과를 확인한다", file=sys.stderr)
        sys.exit(1)
