#!/usr/bin/env python3
"""게시된 CLI·GUI 릴리스를 고정 해시로 검증해 enrollment 이미지에 공급한다."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import urllib.error
import urllib.request


CLI_ASSETS = tuple(
    f"pulsemetry_cli_{system}_{arch}" + (".exe" if system == "windows" else "")
    for system in ("darwin", "linux", "windows")
    for arch in ("amd64", "arm64")
)
GUI_ASSETS = tuple(
    f"pulsemetry_gui_{system}_{arch}{extension}"
    for system, extension in (("darwin", ".dmg"), ("linux", ".AppImage"), ("windows", ".exe"))
    for arch in ("amd64", "arm64")
)
PRODUCT_ASSETS = (*CLI_ASSETS, *GUI_ASSETS)
ASSETS = ("SHA256SUMS", *PRODUCT_ASSETS)
DEFAULT_LOCK = Path(__file__).resolve().parents[2] / "config/telemetryctl-release.json"


class ReleaseError(Exception):
    pass


def load_lock(path):
    lock = json.loads(Path(path).read_text())
    if lock.get("repository") != "soma-376/telemetryctl":
        raise ReleaseError("허용하지 않은 릴리스 저장소")
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", lock.get("tag", "")):
        raise ReleaseError("명시적인 릴리스 태그가 필요하다")
    hashes = lock.get("sha256", {})
    if set(hashes) != set(ASSETS) or any(
        not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
        for value in hashes.values()
    ):
        raise ReleaseError("CLI 6종·GUI 6종과 SHA256SUMS의 고정 해시가 필요하다")
    return lock


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def checksums(path):
    result = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.-]+)", line)
        if not match or match[2] in result:
            raise ReleaseError("SHA256SUMS 형식 오류 또는 중복 파일명")
        result[match[2]] = match[1]
    if not set(PRODUCT_ASSETS).issubset(result):
        raise ReleaseError("SHA256SUMS에 필수 CLI 또는 GUI가 없다")
    return result


def verify(lock, root):
    root = Path(root)
    directory = root / lock["tag"]
    # 다른 판이나 평면 파일을 섞으면 서버가 예상 밖 릴리스를 선택할 수 있다.
    if root.is_symlink() or directory.is_symlink() or not directory.is_dir():
        raise ReleaseError("릴리스 디렉터리가 없거나 심볼릭 링크다")
    if {entry.name for entry in root.iterdir()} != {lock["tag"]}:
        raise ReleaseError("공급 디렉터리는 고정 태그 하나만 포함해야 한다")
    if {entry.name for entry in directory.iterdir()} != set(ASSETS):
        raise ReleaseError("CLI 6종·GUI 6종과 원본 SHA256SUMS만 공급해야 한다")
    for name in ASSETS:
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise ReleaseError(f"필수 자산이 일반 파일이 아니다: {name}")
        if sha256(path) != lock["sha256"][name]:
            raise ReleaseError(f"고정 해시와 일치하지 않는다: {name}")
    expected = checksums(directory / "SHA256SUMS")
    for name in PRODUCT_ASSETS:
        if expected[name] != lock["sha256"][name]:
            raise ReleaseError(f"SHA256SUMS와 자산 해시가 일치하지 않는다: {name}")
    return directory


def read_metadata(lock):
    url = f'https://api.github.com/repos/{lock["repository"]}/releases/tags/{lock["tag"]}'
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "pulsemetry-release-supply"}
    token = os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    # 인증 헤더는 고정된 API origin에만 사용하고 리다이렉트하지 않는다.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, response_headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    try:
        with opener.open(urllib.request.Request(url, headers=headers), timeout=60) as response:
            return json.load(response)
    except (OSError, ValueError) as error:
        raise ReleaseError("원격 릴리스 조회 실패") from error


def release_urls(lock, release):
    if release.get("tag_name") != lock["tag"] or release.get("draft") is not False or not release.get("published_at"):
        raise ReleaseError("선택한 태그의 게시 릴리스를 확인할 수 없다")
    urls = {}
    for name in ASSETS:
        matches = [asset for asset in release.get("assets", []) if asset.get("name") == name]
        if len(matches) != 1 or matches[0].get("state") != "uploaded":
            raise ReleaseError(f"게시된 필수 자산이 없거나 중복됐다: {name}")
        asset = matches[0]
        url = f'https://github.com/{lock["repository"]}/releases/download/{lock["tag"]}/{name}'
        if asset.get("browser_download_url") != url or asset.get("size", 0) <= 0:
            raise ReleaseError(f"게시 자산의 주소 또는 크기가 잘못됐다: {name}")
        if asset.get("digest") != "sha256:" + lock["sha256"][name]:
            raise ReleaseError(f"게시 자산 digest가 고정 해시와 다르다: {name}")
        urls[name] = url
    return urls


def download_asset(url, destination):
    # 공개 릴리스 다운로드에는 토큰을 보내지 않는다. GitHub CDN 리다이렉트에도 비밀값이 없다.
    request = urllib.request.Request(url, headers={"User-Agent": "pulsemetry-release-supply"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as target:
            shutil.copyfileobj(response, target)
    except OSError as error:
        raise ReleaseError(f"게시 자산 다운로드 실패: {destination.name}") from error


def download(lock, output):
    output = Path(output)
    if output.exists():
        raise ReleaseError("공급 경로가 이미 존재한다. 새 빈 경로를 선택한다")
    urls = release_urls(lock, read_metadata(lock))
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".telemetryctl-release-", dir=output.parent) as temporary:
        staging = Path(temporary) / "binaries"
        release_dir = staging / lock["tag"]
        release_dir.mkdir(parents=True)
        for name in ASSETS:
            download_asset(urls[name], release_dir / name)
            if sha256(release_dir / name) != lock["sha256"][name]:
                raise ReleaseError(f"다운로드 자산의 해시가 다르다: {name}")
            # 읽기 권한은 런타임 사용자에게도 주고 CLI와 Linux AppImage만 실행 가능하게 둔다.
            (release_dir / name).chmod(0o755 if name in CLI_ASSETS or name.endswith(".AppImage") else 0o644)
        verify(lock, staging)
        staging.rename(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("download", "verify"))
    parser.add_argument("directory", type=Path)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    arguments = parser.parse_args()
    try:
        lock = load_lock(arguments.lock)
        if arguments.command == "download":
            download(lock, arguments.directory)
        verify(lock, arguments.directory)
        print(json.dumps({"repository": lock["repository"], "tag": lock["tag"], "sha256": lock["sha256"]}, indent=2))
    except (ReleaseError, OSError, ValueError, TypeError) as error:
        # HTTP 응답 본문·인증 헤더는 출력하지 않는다.
        print(f"릴리스 공급 실패: {error if isinstance(error, ReleaseError) else type(error).__name__}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
