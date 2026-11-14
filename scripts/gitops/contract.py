#!/usr/bin/env python3
"""소스에서 배포 호환성 계약을 만들고 네 이미지의 완료 묶음을 보존한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zlib

APPS = ("enrollment-api", "telemetry-ingest", "telemetry-gateway", "dashboard-api")
REPOSITORY = "soma-376/pulsemetry-backend"
WORKFLOW = REPOSITORY + "/.github/workflows/deploy_dev.yml@refs/heads/develop"
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
MIGRATIONS = {
    "enrollment": "libs/enrollment-persistence/src/main/resources/db/migration",
    "telemetry_ops": "libs/telemetry-ops-persistence/src/main/resources/db/telemetry-ops",
    "dashboard_cache": "apps/dashboard-api/src/main/resources/db/dashboard-cache",
}
CLICKHOUSE = {
    "telemetry": "libs/telemetry-persistence/src/main/resources/clickhouse",
    "dashboard_cache": "apps/dashboard-api/src/main/resources/clickhouse/dashboard-cache",
}
ENVELOPE = "libs/telemetry-ingress-contract/src/main/proto/pulsemetry/telemetry/ingress/v1/envelope.proto"


def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def strict_json(data):
    def unique(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("중복 JSON key")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError("JSON 상수 오류")))


def file_records(root, relative, flyway=False):
    result = []
    for path in sorted((root / relative).glob("V*__*.sql")):
        if path.is_symlink() or not path.is_file():
            raise ValueError("migration 파일 형식 오류")
        match = re.fullmatch(r"V([0-9]+(?:[._][0-9]+)*)__.+\.sql", path.name)
        if not match:
            raise ValueError("migration 파일명 오류")
        data = path.read_bytes()
        entry = {"version": match[1].replace("_", "."), "script": path.name, "sha256": sha256(data)}
        if flyway:
            # Flyway ChecksumCalculator와 같은 UTF-8 BOM·줄바꿈 정규화 후 CRC32다.
            lines = re.split(r"\r\n|\r|\n", data.decode("utf-8-sig"))
            checksum = zlib.crc32("".join(lines).encode("utf-8"))
            entry["checksum"] = checksum if checksum < 2**31 else checksum - 2**32
        result.append(entry)
    if not result or len({item["version"] for item in result}) != len(result):
        raise ValueError("migration 목록 누락 또는 중복")
    return sorted(result, key=lambda item: tuple(int(part) for part in item["version"].split(".")))


def contracts(root, commit=None):
    root = Path(root)
    if commit is not None and not SHA.fullmatch(commit):
        raise ValueError("backend commit 형식 오류")
    pg = {schema: file_records(root, path, True) for schema, path in MIGRATIONS.items()}
    ch = {schema: file_records(root, path) for schema, path in CLICKHOUSE.items()}
    assets = (root / "config/telemetryctl-release.json").read_bytes()
    envelope = (root / ENVELOPE).read_bytes()
    compatibility = {
        "postgres": pg, "clickhouse": ch,
        "envelope": {"version": 1, "schemaSha256": sha256(envelope)},
        "assetsLockSha256": sha256(assets),
    }
    return {app: {"schemaVersion": 1, "app": app, "backendCommit": commit,
                  "compatibility": compatibility,
                  "compatibilitySha256": sha256(encode(compatibility))} for app in APPS}


def write_contracts(root, output, commit):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    for app, value in contracts(root, commit).items():
        (output / (app + ".json")).write_bytes(encode(value))


def inspect_image(reference, runner=subprocess.run):
    """태그를 재조회하지 않고 정확한 build digest의 manifest/config를 검사한다."""
    from verify_image_contracts import arm64_digest
    result = runner(["docker", "buildx", "imagetools", "inspect", reference, "--raw"],
                    capture_output=True, text=True, timeout=120, check=False)
    if result.returncode:
        raise ValueError("게시 manifest 검사 실패")
    resolved = arm64_digest(strict_json(result.stdout), reference.rsplit("@", 1)[-1])
    child = reference.rsplit("@", 1)[0] + "@" + resolved
    result = runner(["docker", "buildx", "imagetools", "inspect", child, "--format", "{{json .Image}}"],
                    capture_output=True, text=True, timeout=120, check=False)
    if result.returncode:
        raise ValueError("게시 이미지 검사 실패")
    image = strict_json(result.stdout)
    if image.get("os") != "linux" or image.get("architecture") != "arm64":
        raise ValueError("게시 이미지 ARM64 불일치")
    labels = image.get("config", {}).get("Labels", {}) or {}
    return {"platform": "linux/arm64", "imageConfigSha256": sha256(encode(image)),
            "arm64ManifestDigest": resolved,
            "backendCommit": labels.get("io.pulsemetry.backend-commit"),
            "deploymentContractSha256": labels.get("io.pulsemetry.deployment-contract-sha256")}


def completion(root, directory, images, env, inspector=inspect_image, package_verifier=None):
    if package_verifier is None:
        from verify_image_contracts import verify_packaged
        package_verifier = verify_packaged
    if env.get("GITHUB_REPOSITORY") != REPOSITORY or env.get("GITHUB_REF") != "refs/heads/develop":
        raise ValueError("허용하지 않은 생산 실행")
    commit = env.get("GITHUB_SHA", "")
    if not SHA.fullmatch(commit) or env.get("GITHUB_WORKFLOW_REF") != WORKFLOW:
        raise ValueError("생산 source/workflow 오류")
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        if not re.fullmatch(r"[1-9][0-9]*", env.get(key, "")):
            raise ValueError("생산 run/attempt 오류")
    if set(images) != set(APPS):
        raise ValueError("네 앱 이미지가 모두 필요하다")
    expected = contracts(root, commit)
    deployment = {}
    manifest_images = {}
    for app in APPS:
        image = images[app]
        if set(image) != {"repository", "digest", "registry"} or image["repository"] != "soma-376/" + app:
            raise ValueError("이미지 repository 불일치")
        if not DIGEST.fullmatch(image["digest"]) or not re.fullmatch(r"[0-9]{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com", image["registry"]):
            raise ValueError("이미지 registry/digest 오류")
        raw = (Path(directory) / (app + ".json")).read_bytes()
        if raw != encode(expected[app]):
            raise ValueError("이미지 배포 계약이 source와 다르다")
        inspected = inspector(image["registry"] + "/" + image["repository"] + "@" + image["digest"])
        if inspected.get("platform") != "linux/arm64" or inspected.get("backendCommit") != commit or inspected.get("deploymentContractSha256") != sha256(raw):
            raise ValueError("ARM64 또는 이미지 내 계약 증거 불일치")
        package_verifier(image["registry"] + "/" + image["repository"] + "@" + image["digest"], raw)
        deployment[app] = {"content": raw.decode("utf-8"), "sha256": sha256(raw)}
        manifest_images[app] = {"repository": image["repository"], "digest": image["digest"],
                                "arm64ManifestDigest": inspected["arm64ManifestDigest"],
                                "platform": "linux/arm64", "deploymentContractSha256": sha256(raw),
                                "buildEvidence": {key: inspected[key] for key in ("platform", "imageConfigSha256")}}
    lock = strict_json((Path(root) / "config/telemetryctl-release.json").read_bytes())
    # Docker의 telemetry-binaries-verified 단계와 CI 다운로드가 동일 lock을 검증한다.
    return {"schemaVersion": 1, "repository": REPOSITORY, "backendCommit": commit,
            "workflowRef": WORKFLOW, "workflowRunId": env["GITHUB_RUN_ID"],
            "workflowRunAttempt": env["GITHUB_RUN_ATTEMPT"], "status": "complete",
            "images": manifest_images, "deploymentContracts": deployment,
            "enrollmentAssets": {"lock": lock, "lockSha256": sha256((Path(root) / "config/telemetryctl-release.json").read_bytes()),
                                 "verified": True}}


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    build = sub.add_parser("contracts")
    build.add_argument("--root", default=".")
    build.add_argument("--output", required=True)
    build.add_argument("--backend-commit", default=None)
    bundle = sub.add_parser("completion")
    bundle.add_argument("--root", default=".")
    bundle.add_argument("--contracts", required=True)
    bundle.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.command == "contracts":
        write_contracts(args.root, args.output, args.backend_commit or None)
    else:
        prefixes = {"enrollment-api": "ENROLLMENT", "telemetry-ingest": "INGEST", "telemetry-gateway": "GATEWAY", "dashboard-api": "DASHBOARD"}
        images = {app: {"repository": os.environ.get(prefix + "_REPOSITORY", ""),
                        "digest": os.environ.get(prefix + "_IMAGE_DIGEST", ""),
                        "registry": os.environ.get("IMAGE_REGISTRY", "")} for app, prefix in prefixes.items()}
        value = completion(args.root, args.contracts, images, os.environ)
        directory = Path(args.output)
        directory.mkdir(parents=True, exist_ok=False, mode=0o700)
        raw = encode(value)
        (directory / "completion.json").write_bytes(raw)
        (directory / "completion.sha256").write_text(sha256(raw) + "\n")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # registry·임시 URL·AWS 응답을 진단에 넣지 않는다.
        print("배포 계약 또는 완료 묶음 검증 실패", file=sys.stderr)
        sys.exit(1)
