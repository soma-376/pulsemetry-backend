#!/usr/bin/env python3
"""허용된 생산 run/attempt의 원본 완료 artifact만 승격 입력으로 만든다."""
import argparse
import io
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from contract import APPS, DIGEST, REPOSITORY, SHA, WORKFLOW, encode, sha256, strict_json

PREFIX = "/repos/" + REPOSITORY + "/actions"
MAX_ARCHIVE = 4 * 1024 * 1024
FIELDS = {"schemaVersion", "repository", "backendCommit", "workflowRef", "workflowRunId", "workflowRunAttempt",
          "status", "images", "deploymentContracts", "enrollmentAssets"}
HASH = re.compile(r"^[a-f0-9]{64}$")
ASSETS = {"SHA256SUMS"} | {
    f"pulsemetry_{product}_{platform}_{arch}{suffix}"
    for product in ("cli", "gui") for arch in ("amd64", "arm64")
    for platform, suffix in (("darwin", ".dmg" if product == "gui" else ""),
                             ("linux", ".AppImage" if product == "gui" else ""), ("windows", ".exe"))
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def timestamp(value):
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ValueError("UTC 시각 오류")
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


class Github:
    def __init__(self, token):
        if not token or "\n" in token or "\r" in token:
            raise ValueError("GitHub 조회 자격 누락")
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def get(self, path, binary=False):
        if not path.startswith(PREFIX + "/") or ".." in path:
            raise ValueError("허용하지 않은 API 경로")
        request = urllib.request.Request("https://api.github.com" + path, headers={
            "Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "pulsemetry-gitops"})
        try:
            response = self.opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            if not binary or error.code != 302:
                error.close()
                raise ValueError("GitHub API 조회 실패") from None
            destination = urllib.parse.urlsplit(error.headers.get("Location", ""))
            error.close()
            if destination.scheme != "https" or destination.username or destination.password or not (
                destination.hostname and (destination.hostname.endswith(".blob.core.windows.net") or
                destination.hostname.endswith(".githubusercontent.com"))):
                raise ValueError("artifact 다운로드 host 오류")
            # 서명된 blob URL에는 GitHub token을 전달하지 않는다.
            response = self.opener.open(urllib.request.Request(destination.geturl()), timeout=60)
        with response:
            body = response.read(MAX_ARCHIVE + 1)
        if len(body) > MAX_ARCHIVE:
            raise ValueError("GitHub 응답 크기 초과")
        return body if binary else strict_json(body)


def validate_run(run):
    if run.get("repository", {}).get("full_name") != REPOSITORY or run.get("head_repository", {}).get("full_name") != REPOSITORY:
        raise ValueError("생산 repository 오류")
    if run.get("head_branch") != "develop" or run.get("path", "").split("@")[0] != ".github/workflows/deploy_dev.yml":
        raise ValueError("생산 workflow/ref 오류")
    if run.get("event") not in ("push", "workflow_dispatch") or run.get("status") != "completed" or run.get("conclusion") != "success":
        raise ValueError("생산 workflow 전체 성공이 필요하다")
    if not SHA.fullmatch(run.get("head_sha", "")):
        raise ValueError("생산 SHA 오류")
    for key in ("id", "run_attempt", "workflow_id"):
        if not isinstance(run.get(key), int) or isinstance(run[key], bool) or run[key] < 1:
            raise ValueError("생산 run 식별자 오류")
    timestamp(run.get("created_at"))
    return run


def pages(github, path, field):
    values = []
    ids = set()
    total = None
    for page in range(1, 101):
        response = github.get(path + ("&" if "?" in path else "?") + f"per_page=100&page={page}")
        count = response.get("total_count")
        if not isinstance(count, int) or count < 0 or count > 10000 or not isinstance(response.get(field), list):
            raise ValueError("GitHub 목록 형식 오류")
        if total is None:
            total = count
        if total != count:
            raise ValueError("조회 중 목록 변경")
        for item in response[field]:
            identity = item.get("id")
            if not isinstance(identity, int) or identity < 1 or identity in ids:
                raise ValueError("GitHub 목록 중복")
            ids.add(identity)
            values.append(item)
        if len(values) == total:
            return values
        if len(values) > total or len(response[field]) < 100:
            raise ValueError("GitHub 목록 불완전")
    raise ValueError("GitHub 목록 한도 초과")


def select_run(github):
    runs = pages(github, PREFIX + "/workflows/deploy_dev.yml/runs?branch=develop&status=success", "workflow_runs")
    if not runs:
        raise ValueError("성공한 생산 실행 없음")
    for run in runs:
        validate_run(run)
    return max(runs, key=lambda run: (timestamp(run["created_at"]), run["id"], run["run_attempt"]))


def validate_completion(raw, run):
    manifest = strict_json(raw)
    if not isinstance(manifest, dict) or set(manifest) != FIELDS:
        raise ValueError("완료 manifest schema 오류")
    expected = {"schemaVersion": 1, "repository": REPOSITORY, "backendCommit": run["head_sha"],
                "workflowRef": WORKFLOW, "workflowRunId": str(run["id"]), "workflowRunAttempt": str(run["run_attempt"]),
                "status": "complete"}
    if type(manifest["schemaVersion"]) is not int or any(manifest.get(key) != value for key, value in expected.items()):
        raise ValueError("완료 manifest 출처 불일치")
    if set(manifest["images"]) != set(APPS) or set(manifest["deploymentContracts"]) != set(APPS):
        raise ValueError("네 앱 완료 묶음 필요")
    for app in APPS:
        image = manifest["images"][app]
        contract = manifest["deploymentContracts"][app]
        if set(image) != {"repository", "digest", "arm64ManifestDigest", "platform", "deploymentContractSha256", "buildEvidence"}:
            raise ValueError("이미지 필드 오류")
        if image["repository"] != "soma-376/" + app or image["platform"] != "linux/arm64" or not DIGEST.fullmatch(image["digest"]) or not DIGEST.fullmatch(image["arm64ManifestDigest"]):
            raise ValueError("이미지 repository/platform/digest 오류")
        if set(contract) != {"content", "sha256"} or not isinstance(contract["content"], str):
            raise ValueError("이미지 계약 형식 오류")
        if sha256(contract["content"].encode("utf-8")) != contract["sha256"] or image["deploymentContractSha256"] != contract["sha256"]:
            raise ValueError("이미지 계약 hash 불일치")
        parsed = strict_json(contract["content"])
        if set(parsed) != {"schemaVersion", "app", "backendCommit", "compatibility", "compatibilitySha256"} or parsed.get("app") != app or parsed.get("backendCommit") != run["head_sha"] or type(parsed.get("schemaVersion")) is not int or parsed["schemaVersion"] != 1:
            raise ValueError("이미지 계약 출처 불일치")
        validate_compatibility(parsed["compatibility"])
        if sha256(encode(parsed["compatibility"])) != parsed.get("compatibilitySha256"):
            raise ValueError("호환 계약 hash 불일치")
        evidence = image["buildEvidence"]
        if set(evidence) != {"platform", "imageConfigSha256"} or evidence["platform"] != "linux/arm64" or not re.fullmatch(r"[a-f0-9]{64}", evidence["imageConfigSha256"]):
            raise ValueError("이미지 검사 증거 오류")
    assets = manifest["enrollmentAssets"]
    if set(assets) != {"lock", "lockSha256", "verified"} or assets["verified"] is not True or not HASH.fullmatch(assets["lockSha256"]):
        raise ValueError("CLI/GUI 출처 오류")
    lock = assets["lock"]
    if set(lock) != {"repository", "tag", "sha256"} or lock["repository"] != "soma-376/telemetryctl" or not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", lock["tag"]) or set(lock["sha256"]) != ASSETS or any(not HASH.fullmatch(value) for value in lock["sha256"].values()):
        raise ValueError("CLI 6종·GUI 6종과 SHA256SUMS 출처 오류")
    for contract in manifest["deploymentContracts"].values():
        if strict_json(contract["content"])["compatibility"]["assetsLockSha256"] != assets["lockSha256"]:
            raise ValueError("자산 lock과 이미지 계약 불일치")
    # 공개 artifact에 registry/계정/endpoint를 실어 전달하지 않는다.
    if re.search(rb"(?:[0-9]{12}\.dkr\.ecr\.|arn:aws:|https?://|BEGIN .*PRIVATE KEY)", raw):
        raise ValueError("공개 artifact 허용 경계 오류")
    return manifest


def validate_compatibility(value):
    if set(value) != {"postgres", "clickhouse", "envelope", "assetsLockSha256"} or not HASH.fullmatch(value["assetsLockSha256"]):
        raise ValueError("호환 계약 필드 오류")
    if value["envelope"].keys() != {"version", "schemaSha256"} or type(value["envelope"]["version"]) is not int or value["envelope"]["version"] != 1 or not HASH.fullmatch(value["envelope"]["schemaSha256"]):
        raise ValueError("envelope 계약 오류")
    for engine, schemas in (("postgres", {"enrollment", "telemetry_ops", "dashboard_cache"}),
                            ("clickhouse", {"telemetry", "dashboard_cache"})):
        if set(value[engine]) != schemas:
            raise ValueError("schema 목록 오류")
        for records in value[engine].values():
            if not isinstance(records, list) or not records:
                raise ValueError("migration 목록 누락")
            versions = set()
            for record in records:
                expected = {"version", "script", "sha256"} | ({"checksum"} if engine == "postgres" else set())
                if set(record) != expected or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", record["version"]) or not re.fullmatch(r"V[0-9._]+__[A-Za-z0-9_.-]+\.sql", record["script"]) or not HASH.fullmatch(record["sha256"]) or record["version"] in versions:
                    raise ValueError("migration 계약 오류")
                versions.add(record["version"])
                if engine == "postgres" and (type(record["checksum"]) is not int or not -(2**31) <= record["checksum"] < 2**31):
                    raise ValueError("Flyway checksum 오류")


def unpack_archive(archive):
    if len(archive) > MAX_ARCHIVE:
        raise ValueError("artifact 크기 초과")
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        entries = bundle.infolist()
        if len(entries) != 2 or {entry.filename for entry in entries} != {"completion.json", "completion.sha256"}:
            raise ValueError("artifact 파일 목록 오류")
        for entry in entries:
            mode = entry.external_attr >> 16
            if entry.is_dir() or mode & 0o170000 == 0o120000 or entry.file_size > MAX_ARCHIVE or entry.flag_bits & 1:
                raise ValueError("artifact 파일 형식 오류")
        raw = bundle.read("completion.json")
        expected = bundle.read("completion.sha256").decode("ascii")
        if expected != sha256(raw) + "\n":
            raise ValueError("완료 묶음 원본 hash 불일치")
        return raw


def prepare(github, run_id=None, attempt=None, now=None):
    if run_id is None:
        selected = select_run(github)
        run_id, attempt = selected["id"], selected["run_attempt"]
    if not isinstance(run_id, int) or run_id < 1 or not isinstance(attempt, int) or attempt < 1:
        raise ValueError("run/attempt 입력 오류")
    run = validate_run(github.get(f"{PREFIX}/runs/{run_id}/attempts/{attempt}"))
    if run["id"] != run_id or run["run_attempt"] != attempt:
        raise ValueError("원본 attempt 불일치")
    workflow = github.get(f"{PREFIX}/workflows/{run['workflow_id']}")
    if workflow.get("path") != ".github/workflows/deploy_dev.yml" or workflow.get("id") != run["workflow_id"]:
        raise ValueError("허용 workflow ID 불일치")
    jobs = pages(github, f"{PREFIX}/runs/{run_id}/attempts/{attempt}/jobs", "jobs")
    for name in ("Gradle build (Testcontainers)", "Build and publish images to ECR"):
        matches = [job for job in jobs if job.get("name") == name]
        if len(matches) != 1 or matches[0].get("conclusion") != "success" or matches[0].get("status") != "completed" or matches[0].get("head_sha") != run["head_sha"] or matches[0].get("run_attempt") != attempt or matches[0].get("run_id") != run_id:
            raise ValueError("필수 생산 job 성공 증거 오류")
    artifacts = pages(github, f"{PREFIX}/runs/{run_id}/artifacts", "artifacts")
    expected_name = f"dev-image-bundle-{run_id}-{attempt}"
    matching = [item for item in artifacts if item.get("name") == expected_name]
    if len(matching) != 1:
        raise ValueError("원본 완료 artifact 누락 또는 중복")
    artifact = matching[0]
    now = now or datetime.now(timezone.utc)
    if artifact.get("expired") is not False or timestamp(artifact.get("expires_at")) <= now or artifact.get("workflow_run", {}).get("id") != run_id or artifact["workflow_run"].get("head_sha") != run["head_sha"]:
        raise ValueError("원본 artifact 만료/출처 오류")
    archive = github.get(f"{PREFIX}/artifacts/{artifact['id']}/zip", binary=True)
    archive_sha = sha256(archive)
    if artifact.get("digest") != "sha256:" + archive_sha:
        raise ValueError("GitHub artifact digest 불일치")
    raw = unpack_archive(archive)
    validate_completion(raw, run)
    provenance = {"repository": REPOSITORY, "workflowRef": WORKFLOW, "workflowRunId": str(run_id),
                  "workflowRunAttempt": str(attempt), "backendCommit": run["head_sha"], "createdAt": run["created_at"],
                  "conclusion": "success", "event": run["event"], "headBranch": "develop",
                  "artifactId": str(artifact["id"]), "artifactSha256": archive_sha}
    return raw, provenance


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--attempt", type=int)
    args = parser.parse_args()
    # event는 후보 발견에만 쓴다. API에서 원본 전체 성공을 다시 확인한다.
    raw, provenance = prepare(Github(os.environ.get("GH_TOKEN")), args.run_id, args.attempt)
    result = subprocess.run(["git", "merge-base", "--is-ancestor", provenance["backendCommit"], "refs/remotes/origin/develop"],
                            capture_output=True, timeout=30, check=False)
    if result.returncode:
        raise ValueError("생산 source가 현재 develop 이력에 없다")
    output = Path(args.output)
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    (output / "completion.json").write_bytes(raw)
    (output / "producer-evidence.json").write_bytes(encode(provenance))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("GitOps 생산 실행·artifact 검증 실패", file=sys.stderr)
        sys.exit(1)
