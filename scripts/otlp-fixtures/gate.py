#!/usr/bin/env python3
"""익명화한 fixture 가 저장소에 들어가도 되는지 검사한다. 하나라도 걸리면 비0 으로 끝나고 위반 목록을 낸다.

    python3 gate.py --source CAPTURE.jsonl [--source ...] [--forbid TEXT ...] OUTPUT [OUTPUT ...]

OUTPUT 은 파일이나 디렉터리(아래 모든 파일)다. `.jsonl` 은 줄마다 JSON 으로 읽고 문자열 값을 모두 본다.
그 밖의 파일(README 등)은 본문 전체를 문자열 하나로 본다. 원본(--source)에서 ID·호스트·내용 값의 집합을 모아
출력과 교차 검사한다.

검사 항목:
1. `@example.test` 가 아닌 이메일꼴
2. `/Users/`·`/home/`·`C:\\`, 현재 사용자 이름과 홈 디렉터리 이름, --forbid 로 준 문자열
3. 원본 `host.name` 값
4. 원본의 세션·대화·계정·조직·installation·request·call 등 ID 값(8자 이상)과 그 안의 숫자 섞인 8자 이상 조각의 교차
5. `sk-`·`ghp_`·`gho_`·`ptt_`·`pit_`·`Bearer `·JWT꼴
6. 내용·경로·저장소·MCP 서버·커넥터·플러그인·스킬·에이전트 키의 값이 비었거나 자리표시자가 아니면 위반.
   그 원본 값(4자 이상)이 출력의 어느 문자열로든 남아 있어도 위반
"""
import argparse
import getpass
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sanitize import (  # noqa: E402 — 같은 디렉터리의 규칙 표를 공유한다
    CONNECTOR_KEYS, CONTENT_KEYS, HEX_ID_FIELDS, HOST_KEYS, ID_KEYS, ID_SUFFIXES, KEEP_SENTINELS, MAYBE_PATH_KEYS,
    NOT_ID_KEYS, ORIGIN_KEYS, PATH_KEYS, PLUGIN_KEYS, SCM_KEYS, SERVER_KEYS, SKILL_KEYS, TOOL_NAME_KEYS, walk_mcp,
)

EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")
SECRETS = [
    ("sk-", re.compile(r"(?<![A-Za-z0-9])sk-[A-Za-z0-9]")),
    ("ghp_", re.compile(r"ghp_")),
    ("gho_", re.compile(r"gho_")),
    ("ptt_", re.compile(r"ptt_")),
    ("pit_", re.compile(r"(?<![A-Za-z0-9])pit_")),
    ("Bearer", re.compile(r"Bearer ")),
    ("JWT", re.compile(r"eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+")),
]
FIXED_PATHS = ["/Users/", "/home/", "C:\\"]
RUN = re.compile(r"[A-Za-z0-9]{8,}")

PLACEHOLDER = {
    "path": re.compile(r"/redacted/path\d+"),
    "redacted": re.compile(r"<redacted len=\d+>"),
    "server": re.compile(r"server\d+"),
    "connector": re.compile(r"connector\d+"),
    "plugin": re.compile(r"plugin\d+"),
    "skill": re.compile(r"skill\d+"),
    "agent": re.compile(r"/root(/agent\d+)?"),
    "namespace": re.compile(r"mcp__server\d+"),
    "origin": re.compile(r"https://origin\d+\.example\.test"),
}
# 키 → 허용하는 자리표시자(빈 값·KEEP_SENTINELS 는 늘 허용).
GUARDED = {}
for _k in CONTENT_KEYS:
    GUARDED[_k] = ()
for _k in PATH_KEYS:
    GUARDED[_k] = ("path",)
for _k in SCM_KEYS:
    GUARDED[_k] = ("redacted",)
for _k in SERVER_KEYS:
    GUARDED[_k] = ("server",)
for _k in CONNECTOR_KEYS:
    GUARDED[_k] = ("connector",)
for _k in PLUGIN_KEYS:
    GUARDED[_k] = ("plugin",)
for _k in SKILL_KEYS:
    GUARDED[_k] = ("skill",)
for _k in ORIGIN_KEYS:
    GUARDED[_k] = ("origin",)
GUARDED["agent_name"] = ("agent",)


def is_id_key(key):
    return key not in NOT_ID_KEYS and (key in ID_KEYS or key.endswith(ID_SUFFIXES))


def walk(value, visit, key=None, field=None):
    """(속성 키 또는 필드 이름, 문자열 값)마다 visit 을 부른다."""
    if isinstance(value, list):
        for v in value:
            walk(v, visit, key, field)
    elif isinstance(value, dict):
        if "key" in value and "value" in value and isinstance(value["key"], str):
            walk(value["value"], visit, value["key"], None)
            return
        for name, inner in value.items():
            if name in ("stringValue", "values", "arrayValue", "kvlistValue"):
                walk(inner, visit, key, field)
            elif name == "body":
                walk(inner, visit, "body", None)
            else:
                walk(inner, visit, None, name)
    elif isinstance(value, str):
        visit(key, field, value)


class Source:
    def __init__(self):
        self.ids = set()
        self.runs = set()
        self.hosts = set()
        self.guarded = set()
        self.mcp_tools = set()

    def load(self, path):
        namespaces = set()
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                try:
                    document = json.loads(line)
                except ValueError:
                    continue
                walk(document, self.visit)
                walk_mcp(document, self.mcp_tools, namespaces)

    def visit(self, key, field, value):
        if not value:
            return
        if (key is not None and is_id_key(key)) or field in HEX_ID_FIELDS:
            if len(value) >= 8 and value not in KEEP_SENTINELS:
                self.ids.add(value)
                self.runs.update(r for r in RUN.findall(value) if any(c.isdigit() for c in r))
        if key in HOST_KEYS:
            self.hosts.add(value)
        if (key in GUARDED or key == "tool_namespace") and len(value) >= 4 and value not in KEEP_SENTINELS:
            if key in GUARDED and any(PLACEHOLDER[p].fullmatch(value) for p in GUARDED[key]):
                return  # 제품 상수(예: 루트 에이전트 `/root`)가 자리표시자 모양과 같다
            if key != "tool_namespace" or value.startswith("mcp__"):
                self.guarded.add(value)


def check(source, output, forbidden, violations):
    records = [0, 0]

    def report(where, what, value):
        preview = value if len(value) <= 40 else value[:37] + "..."
        violations.append(f"{where}: {what} — {preview!r}")

    def visit_for(where):
        def visit(key, field, value):
            label = f"{where} [{key or field}]"
            for fixed in FIXED_PATHS + forbidden:
                if fixed and fixed in value:
                    report(label, f"금지 문자열 {fixed!r}", value)
            for email in EMAIL.findall(value):
                if not email.lower().endswith("@example.test"):
                    report(label, "example.test 가 아닌 이메일", email)
            for name, pattern in SECRETS:
                if pattern.search(value):
                    report(label, f"비밀꼴 {name}", value)
            for host in source.hosts:
                if host in value:
                    report(label, "원본 host.name", value)
            if value in source.ids:
                report(label, "원본 ID", value)
            else:
                for run in RUN.findall(value):
                    if run in source.runs:
                        report(label, "원본 ID 조각", run)
            if value in source.guarded:
                report(label, "원본 내용·이름 값", value)
            if key in GUARDED and value and value not in KEEP_SENTINELS:
                allowed = GUARDED[key]
                if not any(PLACEHOLDER[p].fullmatch(value) for p in allowed):
                    report(label, "내용·이름 키의 원문 값", value)
            if key == "body" and value:
                report(label, "본문이 비어 있지 않다", value)
            if key in MAYBE_PATH_KEYS and ("/" in value or "\\" in value) and not PLACEHOLDER["path"].fullmatch(value):
                report(label, "경로꼴 원문", value)
            if key == "tool_namespace" and value.startswith("mcp__") and not PLACEHOLDER["namespace"].fullmatch(value):
                report(label, "MCP 네임스페이스 원문", value)
            if (key in TOOL_NAME_KEYS or key == "tool") and value in source.mcp_tools:
                report(label, "MCP 도구 이름 원문", value)
        return visit

    if output.endswith(".jsonl"):
        with open(output, encoding="utf-8") as handle:
            for number, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                try:
                    document = json.loads(line)
                except ValueError as error:
                    violations.append(f"{output}:{number}: JSON 이 아니다 — {error}")
                    continue
                walk(document, visit_for(f"{output}:{number}"))
                counts = count(document)
                records[0] += counts[0]
                records[1] += counts[1]
    else:
        with open(output, encoding="utf-8", errors="replace") as handle:
            visit_for(output)(None, "text", handle.read())
    return records


def count(document):
    """(레코드 수, metric point 수) — 로그 레코드·스팬·metric 을 레코드로 센다."""
    records = points = 0
    for resource in document.get("resourceLogs", []):
        for scope in resource.get("scopeLogs", []):
            records += len(scope.get("logRecords", []))
    for resource in document.get("resourceSpans", []):
        for scope in resource.get("scopeSpans", []):
            records += len(scope.get("spans", []))
    for resource in document.get("resourceMetrics", []):
        for scope in resource.get("scopeMetrics", []):
            for metric in scope.get("metrics", []):
                records += 1
                for data in ("gauge", "sum", "histogram", "exponentialHistogram", "summary"):
                    points += len((metric.get(data) or {}).get("dataPoints", []))
    return records, points


def files(paths):
    for path in paths:
        if os.path.isdir(path):
            for root, _, names in os.walk(path):
                for name in sorted(names):
                    yield os.path.join(root, name)
        else:
            yield path


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--source", action="append", required=True, help="원본 캡처(사본) — ID·호스트·내용 값 집합의 출처")
    parser.add_argument("--forbid", action="append", default=[], help="출력 어디에도 있으면 안 되는 문자열")
    parser.add_argument("--list-kept", action="store_true", help="통과해도 키별로 남은 원문 값(자리표시자·숫자 제외)을 출력한다 — 사람 검토용")
    parser.add_argument("outputs", nargs="+")
    args = parser.parse_args()

    forbidden = [f for f in args.forbid if f]
    user = getpass.getuser()
    home = os.path.basename(os.path.expanduser("~"))
    forbidden += [x for x in {user, home} if x and len(x) >= 3]

    source = Source()
    for path in args.source:
        source.load(path)
    violations = []
    total = [0, 0]
    checked = 0
    for path in files(args.outputs):
        records = check(source, path, forbidden, violations)
        total[0] += records[0]
        total[1] += records[1]
        checked += 1
    if violations:
        for v in violations:
            print(v)
        print(f"관문 실패 — 위반 {len(violations)}건 (파일 {checked}개)", file=sys.stderr)
        sys.exit(1)
    print(f"관문 통과, 레코드 {total[0]}개(metric point {total[1]}개), 파일 {checked}개 · 원본 ID {len(source.ids)}개와 교차 0건")
    if args.list_kept:
        list_kept(files(args.outputs))


KEPT_EXCLUDE = re.compile(
    r"|<redacted len=\d+>|/redacted/path\d+|(mcp__)?server\d+|mcp_tool\d+|connector\d+|plugin\d+|skill\d+|/root(/agent\d+)?"
    r"|user\d+@example\.test|host-redacted|https://origin\d+\.example\.test|-?\d+(\.\d+)?|true|false"
)


def list_kept(paths):
    """키별로 남은 문자열 값을 모아 보여준다. ID 는 이미 가짜지만 모양 확인을 위해 함께 나온다."""
    kept = {}
    for path in paths:
        if not path.endswith(".jsonl"):
            continue
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if line.strip():
                    walk(json.loads(line), lambda key, field, value: None if KEPT_EXCLUDE.fullmatch(value) else kept.setdefault(key or "#" + field, set()).add(value))
    for key in sorted(kept):
        values = sorted(kept[key])
        shown = " | ".join(v if len(v) <= 60 else v[:57] + "..." for v in values[:12])
        print(f"  {key} ({len(values)}): {shown}")


if __name__ == "__main__":
    main()
