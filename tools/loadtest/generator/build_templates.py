#!/usr/bin/env python3
"""실제 OTLP 캡처로 부하 payload 템플릿을 만든다(ADR 0082 Decision 4). 산출물은 `tools/loadtest/k6/data/templates.json`.

    python3 tools/loadtest/generator/build_templates.py            # 다시 만든다
    python3 tools/loadtest/generator/build_templates.py --check    # 커밋본과 같은지만 본다(다르면 종료 1)

## 원천

`libs/telemetry-adapter/src/test/resources/otlp-v2/` 중 **기대 결과 파일이 함께 있는 실제 캡처**만 쓴다.

| 디렉터리 | 쓰는 파일 | 이유 |
|---|---|---|
| `claude_code/real` | 기대값이 있는 전부 | Claude Code 로그·스팬·메트릭 실캡처 익명화본 |
| `claude_code/api-2.1.290` | `logs-api-*` | `api_request` 실캡처 8건. `synthetic-*`는 합성 경계 사례라 뺀다 |
| `codex/real` | 기대값이 있는 전부 | Codex 로그·스팬·메트릭 실캡처 익명화본 |
| `codex/completed` | `logs-*` | 완료 로그 실캡처 1건. `synthetic-*`는 뺀다 |

빼는 것: `*/synthetic`·`common/synthetic`·`codex/semantics`(합성 명세 사례), `claude_code/api-2.1.311`(합성, 기대값 없음),
`codex/app-server-current`·`codex/desktop-0.165.0`(합성 회귀 사례). Gemini는 제품 프로필이 없어 원천이 없다.
제품의 `service.name`이 아닌 문서(`node_repl`)도 뺀다. ConfigMap 1MiB 안에 들도록 파일마다 앞에서 `--max-per-file`개 문서만 쓴다.

## 변환

템플릿 하나는 원본 문서(push) 하나다. `{id, product, signal, path, body, ids, expected:{events, metricPoints}, source}`.
`body`는 JSON 객체이고, 요청마다 바꿀 값만 문자열 자리표시자다. k6 `lib/payload.js`가 [render]와 같은 규칙으로 채운다.

| 자리표시자 | 원래 값 | 채우는 값 |
|---|---|---|
| `@@T<오프셋>@@` | 0이 아닌 `timeUnixNano`·`observedTimeUnixNano`·`startTimeUnixNano`·`endTimeUnixNano` | 요청 기준 시각(ns) + 오프셋, 10진 문자열 |
| `@@D<오프셋>@@` | ISO-8601 시각 문자열(`event.timestamp`) | 같은 시각의 ISO 문자열(밀리초, `Z`) |
| `@@S<원래 값>@@` | `event.sequence`(intValue) | 요청마다 정한 기준 + 원래 값 |
| `@@I<번호>@@` | ID 속성 값(8자 이상 문자열)·trace/span ID | `ids[번호]` 모양의 새 무작위 값. 문서 안에서 같은 원래 값은 같은 번호 |

오프셋은 문서에서 가장 늦은 시각을 0으로 둔 ns 차이(0 이하)다. 원본의 절대 시각과 ID는 산출물에 남지 않는다.
0인 시각은 "없음"이라는 뜻(ADR 0020 §2 fallback)이라 그대로 둔다. ID 판정은 익명화 도구 `scripts/otlp-fixtures/sanitize.py`의
`is_id_key`와 같은 키 규칙이다. 8자 미만·intValue ID(`thread.id`·`cell.id` 등)는 식별력이 없어 바꾸지 않는다.

`ids[n]`은 `{prefix, mask}`다. 새 값 = `prefix` + `mask`의 글자마다 `x`(16진 소문자)·`d`(숫자)·`l`(영소문자)·`u`(영대문자),
그 밖의 글자(`-`·`_` 등)는 그대로.

`expected`는 같은 캡처의 `*.expected.jsonl` 같은 줄에서 센 `events`·`metric_points` 행 수다. 제외 스팬·거부 레코드는 이미 빠져 있다.
"""
from __future__ import annotations

import argparse
import datetime as dt
from dataclasses import dataclass
import json
from pathlib import Path
import random
import re
import sys

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / "libs/telemetry-adapter/src/test/resources/otlp-v2"
OUTPUT = ROOT / "tools/loadtest/k6/data/templates.json"
MAX_BYTES = 900 * 1024
DEFAULT_MAX_PER_FILE = 3
SCHEMA_VERSION = 1


@dataclass(frozen=True)
class Source:
    product: str
    directory: str
    pattern: str
    note: str


SOURCES = (
    Source("claude-code", "claude_code/real", "*.otlp.jsonl", "Claude Code 로그·스팬·메트릭 실캡처 익명화본"),
    Source("claude-code", "claude_code/api-2.1.290", "logs-api-*.otlp.jsonl", "api_request 실캡처 8건"),
    Source("codex", "codex/real", "*.otlp.jsonl", "Codex 로그·스팬·메트릭 실캡처 익명화본"),
    Source("codex", "codex/completed", "logs-*.otlp.jsonl", "완료 로그 실캡처 1건"),
)
EXCLUDED = (
    "claude_code/synthetic", "codex/synthetic", "common/synthetic", "codex/semantics",
    "claude_code/api-2.1.311", "codex/app-server-current", "codex/desktop-0.165.0", "synthetic-*",
)
PRODUCT_SERVICES = {
    "claude-code": {"claude-code"},
    "codex": {"codex-app-server", "Codex Desktop", "codex_cli_rs"},
}
SIGNALS = {"resourceLogs": "logs", "resourceMetrics": "metrics", "resourceSpans": "traces"}

TIME_FIELDS = {"timeUnixNano", "observedTimeUnixNano", "startTimeUnixNano", "endTimeUnixNano"}
HEX_ID_FIELDS = {"traceId", "spanId", "parentSpanId"}
# scripts/otlp-fixtures/sanitize.py 의 ID 키 규칙과 같다.
ID_KEYS = {
    "conversation.id", "call_id", "communication_id", "sender_thread_id", "receiver_thread_id", "auth.cf_ray",
    "auth.request_id", "user.account_id", "user.account_uuid", "user.id", "organization.id", "session.id", "session_id",
    "turn.id", "turn_id", "thread_id", "thread.id", "submission.id", "rpc.request_id", "app_server.connection_id",
    "approval_id", "cell.id", "environment_id", "runtime_tool_call_id", "tool.call_id", "tool_use_id", "prompt.id",
    "request_id", "request.id", "response_id", "response.id", "installation.id", "connector_id", "mcp.connector.id",
    "plugin_id_hash",
}
ID_SUFFIXES = (".id", "_id", ".uuid", "_uuid")
NOT_ID_KEYS = {"plugin_id", "plugin", "plugin.name", "model_id", "service.instance.id"}
KEEP_SENTINELS = {"", "none", "unknown", "unattributed", "stdio", "local", "builtin"}
MIN_ID_LENGTH = 8
SEQUENCE_KEY = "event.sequence"

ISO_TIME = re.compile(r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
HEX = re.compile(r"[0-9a-f]+")
ID_PREFIX = re.compile(r"([a-z]{2,10}[_-])(.+)")
PLACEHOLDER = re.compile(r"@@([TDSI])(-?\d+)@@")
MASK_CLASSES = {"x": "0123456789abcdef", "d": "0123456789", "l": "abcdefghijklmnopqrstuvwxyz", "u": "ABCDEFGHIJKLMNOPQRSTUVWXYZ"}


def is_id_key(key: str) -> bool:
    return key not in NOT_ID_KEYS and (key in ID_KEYS or key.endswith(ID_SUFFIXES))


def iso_to_nanos(value: str) -> int:
    match = ISO_TIME.fullmatch(value)
    if not match:
        raise ValueError(f"ISO 시각이 아니다: {value}")
    year, month, day, hour, minute, second, fraction = match.groups()
    seconds = int(dt.datetime(int(year), int(month), int(day), int(hour), int(minute), int(second), tzinfo=dt.timezone.utc).timestamp())
    return seconds * 10**9 + int((fraction or "0").ljust(9, "0"))


def nanos_to_iso(nanos: int) -> str:
    """밀리초까지 자른 UTC ISO — JS `Date.toISOString()`과 같은 모양."""
    seconds, rest = divmod(nanos, 10**9)
    stamp = dt.datetime.fromtimestamp(seconds, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    return f"{stamp}.{rest // 10**6:03d}Z"


def id_shape(value: str) -> dict:
    """원래 ID와 같은 모양의 생성 규칙. uuid·hex는 16진, `접두사_`는 유지, 나머지는 글자 종류별."""
    def hex_mask(text: str) -> str:
        return "".join("x" if char != "-" else "-" for char in text)

    def class_mask(text: str) -> str:
        out = []
        for char in text:
            if char.isdigit():
                out.append("d")
            elif "a" <= char <= "z":
                out.append("l")
            elif "A" <= char <= "Z":
                out.append("u")
            elif char.isalnum():
                raise ValueError(f"ASCII가 아닌 ID 글자: {value!r}")
            else:
                out.append(char)
        return "".join(out)

    def body_mask(text: str) -> str:
        if UUID.fullmatch(text) or HEX.fullmatch(text):
            return hex_mask(text)
        return class_mask(text)

    if UUID.fullmatch(value) or HEX.fullmatch(value):
        return {"prefix": "", "mask": hex_mask(value)}
    match = ID_PREFIX.fullmatch(value)
    if match and len(match.group(2)) >= MIN_ID_LENGTH:
        return {"prefix": match.group(1), "mask": body_mask(match.group(2))}
    return {"prefix": "", "mask": class_mask(value)}


def _times(node, found: list) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            if key in TIME_FIELDS and isinstance(value, str) and value != "0":
                found.append(int(value))
            elif isinstance(value, str) and ISO_TIME.fullmatch(value):
                found.append(iso_to_nanos(value))
            else:
                _times(value, found)
    elif isinstance(node, list):
        for item in node:
            _times(item, found)


class Templatizer:
    """문서 하나의 시각·ID를 자리표시자로 바꾼다. 같은 원래 ID는 같은 번호다."""

    def __init__(self, document: dict):
        found: list = []
        _times(document, found)
        if not found:
            raise ValueError("시각이 하나도 없는 문서다")
        self.base = max(found)
        self.ids: list = []
        self._numbers: dict = {}
        self.body = self._walk(document)

    def _id(self, value: str) -> str:
        if value not in self._numbers:
            self._numbers[value] = len(self.ids)
            self.ids.append(id_shape(value))
        return f"@@I{self._numbers[value]}@@"

    def _time(self, value: str) -> str:
        return f"@@T{int(value) - self.base}@@"

    def _iso(self, value: str) -> str:
        return f"@@D{iso_to_nanos(value) - self.base}@@"

    def _attribute(self, node: dict) -> dict:
        key, value = node["key"], node["value"]
        if is_id_key(key) and isinstance(value.get("stringValue"), str):
            text = value["stringValue"]
            if len(text) >= MIN_ID_LENGTH and text not in KEEP_SENTINELS:
                return {**node, "value": {**value, "stringValue": self._id(text)}}
        if key == SEQUENCE_KEY and isinstance(value.get("intValue"), str):
            return {**node, "value": {**value, "intValue": f"@@S{int(value['intValue'])}@@"}}
        return {key_: self._walk(item) for key_, item in node.items()}

    def _walk(self, node):
        if isinstance(node, dict):
            if isinstance(node.get("key"), str) and isinstance(node.get("value"), dict):
                return self._attribute(node)
            out = {}
            for key, value in node.items():
                if key in TIME_FIELDS and isinstance(value, str) and value != "0":
                    out[key] = self._time(value)
                elif key in HEX_ID_FIELDS and isinstance(value, str) and value:
                    out[key] = self._id(value)
                else:
                    out[key] = self._walk(value)
            return out
        if isinstance(node, list):
            return [self._walk(item) for item in node]
        if isinstance(node, str) and ISO_TIME.fullmatch(node):
            return self._iso(node)
        return node


def _service_names(document: dict, signal_key: str) -> set:
    names = set()
    for resource in document[signal_key]:
        for attribute in resource.get("resource", {}).get("attributes", []):
            if attribute.get("key") == "service.name":
                names.add(attribute["value"].get("stringValue"))
    return names


def _read_jsonl(path: Path) -> list:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def source_files(fixtures: Path = FIXTURES) -> list:
    """(Source, 입력 경로, 기대값 경로). 기대값이 없는 파일·synthetic-* 는 뺀다."""
    files = []
    for source in SOURCES:
        for path in sorted((fixtures / source.directory).glob(source.pattern)):
            expected = path.with_name(path.name.replace(".otlp.jsonl", ".expected.jsonl"))
            if path.name.startswith("synthetic") or not expected.exists():
                continue
            files.append((source, path, expected))
    return files


def build(fixtures: Path = FIXTURES, max_per_file: int = DEFAULT_MAX_PER_FILE) -> dict:
    if max_per_file < 1:
        raise ValueError("--max-per-file 는 1 이상이다")
    templates, summary = [], []
    for source, path, expected_path in source_files(fixtures):
        documents, expectations = _read_jsonl(path), _read_jsonl(expected_path)
        if len(documents) != len(expectations):
            raise ValueError(f"입력과 기대값의 줄 수가 다르다: {path}")
        used = 0
        for line, (document, expected) in enumerate(zip(documents, expectations), start=1):
            if used >= max_per_file:
                break
            signal_key = next(key for key in document if key in SIGNALS)
            if not _service_names(document, signal_key) <= PRODUCT_SERVICES[source.product]:
                continue
            signal = SIGNALS[signal_key]
            converted = Templatizer(document)
            relative = path.relative_to(fixtures).as_posix()
            templates.append({
                "id": f"{source.product}/{signal}/{path.parent.name}/{path.name[: -len('.otlp.jsonl')]}#{line}",
                "product": source.product,
                "signal": signal,
                "path": f"/v1/{signal}",
                "expected": {"events": len(expected.get("events", [])), "metricPoints": len(expected.get("metric_points", []))},
                "source": f"{relative}:{line}",
                "ids": converted.ids,
                "body": converted.body,
            })
            used += 1
        summary.append((source, path, used))

    buckets = {(t["product"], t["signal"]) for t in templates}
    missing = sorted({(p, s) for p in PRODUCT_SERVICES for s in SIGNALS.values()} - buckets)
    if missing:
        raise ValueError(f"제품·신호 조합에 템플릿이 없다: {missing}")
    sources = []
    for source in SOURCES:
        used_files = [(path, count) for src, path, count in summary if src is source and count]
        sources.append({
            "product": source.product, "directory": source.directory, "pattern": source.pattern, "note": source.note,
            "files": len(used_files), "documents": sum(count for _, count in used_files),
        })
    return {
        "schemaVersion": SCHEMA_VERSION,
        "generator": "tools/loadtest/generator/build_templates.py",
        "maxPerFile": max_per_file,
        "placeholders": {
            "T": "요청 기준 시각(ns) + 오프셋, 10진 문자열",
            "D": "요청 기준 시각(ns) + 오프셋의 ISO-8601(밀리초, Z)",
            "S": "요청마다 정한 순번 기준 + 원래 값",
            "I": "ids[번호]의 prefix + mask(x 16진·d 숫자·l 영소문자·u 영대문자, 그 밖은 그대로)",
        },
        "sources": sources,
        "excluded": list(EXCLUDED),
        "templates": templates,
    }


def dumps(document: dict) -> str:
    """결정적 직렬화. 머리 항목은 한 줄씩, 템플릿도 한 줄에 하나 — 바뀐 템플릿만 diff 에 보인다."""
    def compact(value) -> str:
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    lines = ["{"]
    for key, value in document.items():
        if key != "templates":
            lines.append(f"{compact(key)}:{compact(value)},")
    lines.append('"templates":[')
    templates = document["templates"]
    for index, template in enumerate(templates):
        lines.append(compact(template) + ("," if index < len(templates) - 1 else ""))
    lines.append("]")
    lines.append("}")
    return "\n".join(lines) + "\n"


def render(template: dict, base_nanos: int, sequence_base: int, rng: random.Random) -> str:
    """요청 본문 하나 — k6 `lib/payload.js`의 기준 구현이자 테스트용이다. JSON 문자열을 돌려준다."""
    values = [shape["prefix"] + "".join(rng.choice(MASK_CLASSES[c]) if c in MASK_CLASSES else c for c in shape["mask"])
              for shape in template["ids"]]

    def substitute(match: re.Match) -> str:
        kind, number = match.group(1), int(match.group(2))
        if kind == "T":
            return str(base_nanos + number)
        if kind == "D":
            return nanos_to_iso(base_nanos + number)
        if kind == "S":
            return str(sequence_base + number)
        return values[number]

    text = json.dumps(template["body"], ensure_ascii=False, separators=(",", ":"))
    return PLACEHOLDER.sub(substitute, text)


def main(argv: list | None = None) -> int:
    parser = argparse.ArgumentParser(description="실제 OTLP 캡처로 부하 payload 템플릿을 만든다(ADR 0082).")
    parser.add_argument("--out", type=Path, default=OUTPUT, help=f"산출물 경로(기본 {OUTPUT.relative_to(ROOT)})")
    parser.add_argument("--max-per-file", type=int, default=DEFAULT_MAX_PER_FILE, help="캡처 파일마다 쓰는 최대 문서 수")
    parser.add_argument("--check", action="store_true", help="다시 만든 결과가 --out 파일과 같은지만 본다")
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)

    document = build(max_per_file=args.max_per_file)
    text = dumps(document)
    size = len(text.encode("utf-8"))
    if size >= MAX_BYTES:
        print(f"실패: {size}바이트 — ConfigMap 여유를 위해 {MAX_BYTES}바이트 미만이어야 한다", file=sys.stderr)
        return 1
    counts = {}
    for template in document["templates"]:
        counts[(template["product"], template["signal"])] = counts.get((template["product"], template["signal"]), 0) + 1
    summary = ", ".join(f"{p}/{s}={n}" for (p, s), n in sorted(counts.items()))
    if args.check:
        current = args.out.read_text(encoding="utf-8") if args.out.exists() else None
        if current != text:
            print(f"다르다: {args.out} 를 다시 만든다(python3 tools/loadtest/generator/build_templates.py)", file=sys.stderr)
            return 1
        print(f"같다: 템플릿 {len(document['templates'])}개, {size}바이트 ({summary})", file=sys.stderr)
        return 0
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(text, encoding="utf-8")
    print(f"썼다: {args.out} — 템플릿 {len(document['templates'])}개, {size}바이트 ({summary})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
