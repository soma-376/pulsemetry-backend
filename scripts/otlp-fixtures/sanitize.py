#!/usr/bin/env python3
"""extract.py 가 고른 OTLP/JSON 문서를 익명화한다 — allowlist 방식.

    python3 sanitize.py --out-dir FIXTURE_DIR RAW.otlp.jsonl [RAW.otlp.jsonl ...]

한 번의 실행에 넘긴 파일 전체가 **같은 대응표·같은 시각 오프셋**을 쓴다(파일을 가로지르는 ID·시각 관계가 보존된다).
대응표를 만드는 salt 는 실행마다 무작위이고 어디에도 저장하지 않는다. 시각 오프셋도 저장하지 않는다.

남기는 것: 구조 키, 속성 키, 숫자·불리언 값과 그 wire 타입(stringValue 로 온 숫자는 stringValue 그대로),
enum 꼴 한 토큰 값(버전·이름·상태), 로그 최상위 eventName 의 소스 위치 꼴, 스팬·메트릭·scope 이름, 메트릭 설명·단위.

바꾸는 것(규칙 표는 README.md):
- ID → 같은 모양의 가짜(uuid → uuid, hex → 같은 길이 hex, 숫자 → 같은 자릿수 숫자, `접두사_` 는 유지하고 나머지를
  문자 종류별로). 값 안에 박힌 uuid·hex 조각도 바꾼다.
- 이메일 → `userN@example.test`, 경로 → `/redacted/pathN`, host.name → `host-redacted`
- MCP 서버·커넥터·도구·플러그인·스킬·에이전트 이름 → `serverN`·`mcp__serverN`·`connectorN`·`mcp_toolN`·`pluginN`·`skillN`·`/root/agentN`
- 프롬프트·본문·arguments·output·content·tool_parameters 등 내용 키 → 빈 문자열(키는 남는다)
- 그 밖의 문자열 → `<redacted len=N>`
- 시각: 모든 레코드에 같은 오프셋(밀리초 배수)을 빼 가장 이른 시각이 2026-01-01T00:00:00Z 부근이 되게 한다.
  `event.timestamp` 같은 ISO 문자열도 같은 오프셋으로 옮긴다. 0 은 0 으로 둔다.
"""
import argparse
import base64
import calendar
import hashlib
import hmac
import json
import os
import re
import sys
import time
from collections import defaultdict

BASE_NANOS = 1767225600 * 10**9  # 2026-01-01T00:00:00Z
TIME_FIELDS = {"timeUnixNano", "observedTimeUnixNano", "startTimeUnixNano", "endTimeUnixNano"}
HEX_ID_FIELDS = {"traceId", "spanId", "parentSpanId"}

# 내용 키 — 값을 비운다(키는 남겨 존재 여부를 보존한다).
CONTENT_KEYS = {
    "prompt", "content", "arguments", "output", "tool_parameters", "tool_input", "tool_output", "input", "response",
    "body", "mcp_servers", "query", "query_text", "command", "stdout", "stderr", "diff", "patch",
    "user_prompt", "full_command",
}
# 경로 키 — 값은 언제나 /redacted/pathN.
PATH_KEYS = {"cwd", "code.file.path", "file_path", "file.path", "workspace", "workspace.path"}
# 경로일 수도 enum 일 수도 있는 키 — 구분자가 있으면 경로로 본다.
MAYBE_PATH_KEYS = {"path", "directory", "bash_argv0"}
# 저장소·브랜치 이름 — 언제나 가린다.
SCM_KEYS = {"repo", "repository", "branch", "git.branch", "git.repository", "vcs.repository.name", "vcs.ref.head.name"}
# 이메일·호스트
EMAIL_KEYS = {"user.email", "email"}
HOST_KEYS = {"host.name", "host.hostname"}
# 사람 이름 — 한 단어여도 가린다(모양 allowlist 가 한 토큰 이름을 남기지 않게).
PERSON_KEYS = {"user.name", "user.full_name", "username", "enduser.id", "os.user", "author", "committer"}
# 이름 범주 — 값마다 번호를 붙인 자리표시자.
SERVER_KEYS = {"mcp_server", "mcp.server.name", "mcp_server.name", "server_name", "hook.server", "server", "mcp.server"}
CONNECTOR_KEYS = {"connector_name", "mcp.connector.name"}
PLUGIN_KEYS = {"plugin_id", "plugin", "plugin.name"}
SKILL_KEYS = {"skill", "skill.name", "skill_name"}
TOOL_NAME_KEYS = {"tool_name", "tool.name", "hook.tool", "mcp_tool.name"}
MARKETPLACE_KEYS = {"marketplace.name"}
# 에이전트 종류 — 제품에 내장된 이름만 남기고 사용자 정의 이름은 가린다.
AGENT_NAME_KEYS = {"agent.name", "agent_type", "subagent_type"}
BUILTIN_AGENTS = {"Explore", "general-purpose", "Plan", "statusline-setup", "output-style-setup", "claude-code-guide"}
AGENT_QUERY = re.compile(r"agent([:.])(builtin|custom)\1(.+)")
ORIGIN_KEYS = {"mcp_server_origin", "mcp.server.origin"}
KEEP_SENTINELS = {"", "none", "unknown", "unattributed", "stdio", "local", "builtin"}
# ID 키 — 이름이 이것이거나 접미사가 ID 꼴이다.
ID_KEYS = {
    "conversation.id", "call_id", "communication_id", "sender_thread_id", "receiver_thread_id", "auth.cf_ray",
    "auth.request_id", "user.account_id", "user.account_uuid", "user.id", "organization.id", "session.id", "session_id",
    "turn.id", "turn_id", "thread_id", "thread.id", "submission.id", "rpc.request_id", "app_server.connection_id",
    "approval_id", "cell.id", "environment_id", "runtime_tool_call_id", "tool.call_id", "tool_use_id", "prompt.id",
    "request_id", "request.id", "response_id", "response.id", "installation.id", "connector_id", "mcp.connector.id",
    "plugin_id_hash",
}
ID_SUFFIXES = (".id", "_id", ".uuid", "_uuid")
NOT_ID_KEYS = PLUGIN_KEYS | {"model_id", "service.instance.id"}
# 한 단어 enum 꼴 — 이 모양이면 값을 남긴다.
SAFE_TOKEN = re.compile(r"[A-Za-z0-9_.:+\-]{1,80}")
# 공백을 허용하는 이름 키(제품이 정한 표시 이름).
SPACED_KEYS = {"service.name", "app_server.client_name"}
SPACED_TOKEN = re.compile(r"[A-Za-z0-9_.:+\-]+( [A-Za-z0-9_.:+\-]+){0,3}")
# 키별 특수 모양
KEY_PATTERNS = {
    "endpoint": re.compile(r"/?[A-Za-z0-9_\-]+(/[A-Za-z0-9_\-]+)*"),
    "api.path": re.compile(r"/?[A-Za-z0-9_\-]+(/[A-Za-z0-9_\-]+)*"),
    "rpc.method": re.compile(r"[A-Za-z0-9_\-]+(/[A-Za-z0-9_\-]+)*"),
    "url.path": re.compile(r"/?[A-Za-z0-9_\-]+(/[A-Za-z0-9_\-]+)*"),
}
PUBLIC_HOST = re.compile(r"(?:[a-z0-9-]+\.)*(?:openai\.com|chatgpt\.com|anthropic\.com|claude\.ai)")
EVENT_LOCATION = re.compile(r"event [A-Za-z0-9_\-]+(/[A-Za-z0-9_.\-]+)*\.rs:\d+")
SECRET = re.compile(r"(?<![A-Za-z0-9])(sk-|ghp_|gho_|ptt_|pit_)|Bearer |eyJ[A-Za-z0-9_\-]+\.")
EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")
UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}(?:-[0-9a-fA-F]{1,12})?")
HEX_RUN = re.compile(r"(?<![A-Za-z0-9])[0-9a-fA-F]{12,}(?![A-Za-z0-9])")
ISO_TIME = re.compile(r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z")


class Sanitizer:
    def __init__(self, offset):
        self.salt = os.urandom(32)
        self.offset = offset
        self.numbers = defaultdict(dict)  # 범주 → 원문 → 번호
        self.mcp_tools = set()
        self.mcp_namespaces = set()
        self.record = {}  # 지금 처리하는 레코드의 문자열 속성(레코드 단위 규칙용)

    # ── 결정적 가짜 ─────────────────────────────────────────────────────

    def stream(self, kind, value, length):
        out = b""
        counter = 0
        while len(out) < length:
            out += hmac.new(self.salt, f"{kind}\0{value}\0{counter}".encode(), hashlib.sha256).digest()
            counter += 1
        return out[:length]

    def number(self, category, value):
        table = self.numbers[category]
        if value not in table:
            table[value] = len(table) + 1
        return table[value]

    def fake_chars(self, kind, value):
        """문자 종류별 치환 — 숫자는 숫자, 소문자는 소문자, 대문자는 대문자, 구두점은 그대로."""
        noise = self.stream(kind, value, len(value))
        out = []
        for ch, b in zip(value, noise):
            if ch.isdigit():
                out.append("0123456789"[b % 10])
            elif "a" <= ch <= "z":
                out.append("abcdefghijklmnopqrstuvwxyz"[b % 26])
            elif "A" <= ch <= "Z":
                out.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ"[b % 26])
            else:
                out.append(ch)
        return "".join(out)

    def fake_hex(self, value):
        noise = self.stream("hex", value.lower(), len(value)).hex()[: len(value)]
        return noise.upper() if value.isupper() else noise

    def fake_uuid(self, value):
        groups = value.split("-")
        fake = self.fake_hex(value.replace("-", ""))
        out, i = [], 0
        for g in groups:
            out.append(fake[i : i + len(g)])
            i += len(g)
        if len(out) >= 3 and len(out[2]) == 4:
            out[2] = groups[2][0] + out[2][1:]  # 버전 니블 유지
        if len(out) >= 4 and len(out[3]) == 4:
            out[3] = groups[3][0] + out[3][1:]  # variant 유지
        return "-".join(out)

    def fake_id(self, value):
        if UUID.fullmatch(value):
            return self.fake_uuid(value)
        if re.fullmatch(r"[0-9a-fA-F]{8,}", value) and not value.isdigit():
            return self.fake_hex(value)
        if value.isdigit():
            fake = self.fake_chars("digits", value)
            return ("1" + fake[1:]) if len(fake) > 1 and fake[0] == "0" else fake
        prefix = re.match(r"([a-z]+[_\-])+", value)
        if prefix:
            rest = value[prefix.end() :]
            return prefix.group(0) + self.fake_embedded(rest, whole=True)
        return self.fake_embedded(value, whole=True)

    def fake_embedded(self, value, whole=False):
        """값 안의 uuid·긴 hex 를 바꾼다. whole 이면 나머지도 문자 종류별로 바꾼다."""
        replaced = UUID.sub(lambda m: self.fake_uuid(m.group(0)), value)
        replaced = HEX_RUN.sub(lambda m: self.fake_hex(m.group(0)) if not m.group(0).isdigit() else m.group(0), replaced)
        if whole and replaced == value:
            return self.fake_chars("id", value)
        return replaced

    # ── 문자열 규칙 ─────────────────────────────────────────────────────

    def redact(self, value):
        return f"<redacted len={len(value)}>"

    def path(self, value):
        return f"/redacted/path{self.number('path', value)}"

    def server(self, value):
        return f"server{self.number('server', value)}"

    def is_id_key(self, key):
        return key not in NOT_ID_KEYS and (key in ID_KEYS or key.endswith(ID_SUFFIXES))

    def string(self, key, value):
        if value == "":
            return ""
        if key in CONTENT_KEYS:
            return ""
        if SECRET.search(value):
            return self.redact(value)
        if key in EMAIL_KEYS or EMAIL.fullmatch(value):
            return f"user{self.number('email', value.lower())}@example.test"
        if key in HOST_KEYS:
            return "host-redacted"
        if key in PERSON_KEYS:
            return self.redact(value)
        if key in PATH_KEYS or (key in MAYBE_PATH_KEYS and ("/" in value or "\\" in value)):
            return self.path(value)
        if key in SCM_KEYS:
            return self.redact(value)
        if self.is_id_key(key):
            return value if value in KEEP_SENTINELS else self.fake_id(value)
        if key in SERVER_KEYS:
            return value if value in KEEP_SENTINELS else self.server(value)
        if key in CONNECTOR_KEYS:
            return value if value in KEEP_SENTINELS else f"connector{self.number('connector', value)}"
        if key in PLUGIN_KEYS:
            return value if value in KEEP_SENTINELS else f"plugin{self.number('plugin', value)}"
        if key in MARKETPLACE_KEYS:
            return value if value in KEEP_SENTINELS else f"marketplace{self.number('marketplace', value)}"
        if key in AGENT_NAME_KEYS:
            return value if value in BUILTIN_AGENTS else f"agent{self.number('agent', value)}"
        if key in ("query_source", "query_source_safe") and AGENT_QUERY.fullmatch(value):
            sep, kind, name = AGENT_QUERY.fullmatch(value).groups()
            if kind == "builtin" and name in BUILTIN_AGENTS:
                return value
            return f"agent{sep}{kind}{sep}agent{self.number('agent', name)}"
        if key == "command_name":
            return value if self.record.get("command_source") == "builtin" else f"command{self.number('command', value)}"
        if key in SKILL_KEYS:
            return value if value in KEEP_SENTINELS else f"skill{self.number('skill', value)}"
        if key in ORIGIN_KEYS:
            return value if value in KEEP_SENTINELS else f"https://origin{self.number('origin', value)}.example.test"
        if key == "agent_name":
            if value == "/root":
                return value
            return f"/root/agent{self.number('agent', value)}"
        if key == "tool_namespace" and value.startswith("mcp__"):
            return "mcp__" + self.server(value[len("mcp__") :])
        if value.startswith("mcp__") and key != "tool":
            # `mcp__<서버>__<도구>`(Claude Code 등) — 서버·도구 이름을 자리표시자로.
            server, _, tool = value[len("mcp__") :].partition("__")
            fake = "mcp__" + self.server(server)
            return fake + (f"__mcp_tool{self.number('mcp_tool', tool)}" if tool else "")
        if key == "mcp_tool.name":
            return value if value in KEEP_SENTINELS else f"mcp_tool{self.number('mcp_tool', value)}"
        if key in TOOL_NAME_KEYS and value in self.mcp_tools:
            return f"mcp_tool{self.number('mcp_tool', value)}"
        if key == "tool" and value.startswith("mcp__"):
            return self.concatenated_tool(value)
        if key == "tool" and value in self.mcp_tools:
            return f"mcp_tool{self.number('mcp_tool', value)}"
        if key in ("server.address", "http.host", "net.peer.name"):
            return value if PUBLIC_HOST.fullmatch(value) else "host-redacted"
        if ISO_TIME.fullmatch(value):
            return self.shift_iso(value)
        if key in KEY_PATTERNS and KEY_PATTERNS[key].fullmatch(value):
            return value
        if SAFE_TOKEN.fullmatch(value) or (key in SPACED_KEYS and SPACED_TOKEN.fullmatch(value)):
            return self.fake_embedded(value)
        return self.redact(value)

    def concatenated_tool(self, value):
        """`mcp__<서버><도구>` 처럼 붙어 온 값 — 알려진 이름 쌍으로 풀리면 자리표시자 쌍으로, 아니면 가린다."""
        rest = value[len("mcp__") :]
        for namespace in sorted(self.mcp_namespaces, key=len, reverse=True):
            if rest.startswith(namespace) and rest[len(namespace) :] in self.mcp_tools:
                tool = rest[len(namespace) :]
                return f"mcp__{self.server(namespace)}mcp_tool{self.number('mcp_tool', tool)}"
        return self.redact(value)

    def shift_iso(self, value):
        m = ISO_TIME.fullmatch(value)
        year, month, day, hour, minute, second, fraction = m.groups()
        seconds = calendar.timegm((int(year), int(month), int(day), int(hour), int(minute), int(second)))
        digits = fraction or ""
        nanos = seconds * 10**9 + (int(digits.ljust(9, "0")) if digits else 0) - self.offset
        whole, rem = divmod(nanos, 10**9)
        text = "%04d-%02d-%02dT%02d:%02d:%02d" % tuple(time.gmtime(whole)[:6])
        if digits:
            text += "." + str(rem).rjust(9, "0")[: len(digits)]
        return text + "Z"

    # ── OTLP 트리 ──────────────────────────────────────────────────────

    def any_value(self, key, value):
        if not isinstance(value, dict):
            return value
        out = {}
        for wire, inner in value.items():
            if wire == "stringValue":
                out[wire] = self.string(key, inner)
            elif wire == "arrayValue":
                out[wire] = {"values": [self.any_value(key, v) for v in (inner or {}).get("values", [])]}
            elif wire == "kvlistValue":
                out[wire] = {"values": self.attributes((inner or {}).get("values", []))}
            elif wire == "bytesValue":
                raw = base64.b64decode(inner)
                out[wire] = base64.b64encode(self.stream("bytes", inner, len(raw))).decode()
            else:
                out[wire] = inner  # intValue·doubleValue·boolValue — 값과 wire 타입을 그대로 둔다
        return out

    def attributes(self, attributes):
        return [dict(kv, value=self.any_value(kv["key"], kv.get("value"))) for kv in attributes or []]

    def time(self, value):
        number = int(value)
        if number == 0:
            return value
        shifted = number - self.offset
        return str(shifted) if isinstance(value, str) else shifted

    def node(self, value, parent=None):
        """OTLP 문서의 구조를 따라 내려간다. 속성 목록·시각·ID·이름 필드만 바꾸고 나머지 구조는 그대로 둔다."""
        if isinstance(value, list):
            return [self.node(v, parent) for v in value]
        if not isinstance(value, dict):
            return value
        if isinstance(value.get("attributes"), list):
            self.record = {kv.get("key"): (kv.get("value") or {}).get("stringValue") for kv in value["attributes"]}
        out = {}
        for key, inner in value.items():
            if key in ("attributes", "filteredAttributes"):
                out[key] = self.attributes(inner)
            elif key in TIME_FIELDS:
                out[key] = self.time(inner)
            elif key in HEX_ID_FIELDS:
                out[key] = self.fake_hex(inner) if inner else inner
            elif key == "body":
                out[key] = self.body(inner)
            elif key == "eventName":
                out[key] = inner if (EVENT_LOCATION.fullmatch(inner) or SAFE_TOKEN.fullmatch(inner) or inner == "") else self.redact(inner)
            elif key == "name" and parent in ("scope", "record", "event", "metric"):
                out[key] = inner if (SAFE_TOKEN.fullmatch(inner) or SPACED_TOKEN.fullmatch(inner) or EVENT_LOCATION.fullmatch(inner)) else self.redact(inner)
            elif key == "message" and parent == "status":
                out[key] = self.redact(inner) if inner else inner
            elif key == "traceState":
                out[key] = ""
            elif key == "scope":
                out[key] = self.node(inner, "scope")
            elif key == "status":
                out[key] = self.node(inner, "status")
            elif key in ("logRecords", "spans"):
                out[key] = [self.node(v, "record") for v in inner]
            elif key == "metrics":
                out[key] = [self.node(v, "metric") for v in inner]
            elif key == "events":
                out[key] = [self.node(v, "event") for v in inner]
            else:
                out[key] = self.node(inner, key)
        return out

    def body(self, body):
        """본문은 비운다. 단 본문이 정확히 `<접두사>.<같은 레코드의 event.name>` 이면(제품이 이벤트 이름을 두 곳에 싣는
        경우) 그대로 둔다 — 사용자 내용이 아니다."""
        name = self.record.get("event.name") or ""
        text = body.get("stringValue") if isinstance(body, dict) else None
        if text and re.fullmatch(r"[a-z_]+\.[a-z_.]+", text) and name and text.endswith("." + name) and re.fullmatch(r"[a-z_]+", text[: -len(name) - 1]):
            return dict(body)
        return self.empty_body(body)

    def empty_body(self, body):
        if not isinstance(body, dict):
            return body
        out = {}
        for wire, inner in body.items():
            if wire == "stringValue":
                out[wire] = ""
            elif wire == "arrayValue":
                out[wire] = {"values": [self.empty_body(v) for v in (inner or {}).get("values", [])]}
            elif wire == "kvlistValue":
                out[wire] = {"values": [dict(kv, value=self.empty_body(kv.get("value"))) for kv in (inner or {}).get("values", [])]}
            elif wire == "bytesValue":
                out[wire] = ""
            else:
                out[wire] = inner
        return out


def walk_times(value, found):
    if isinstance(value, list):
        for v in value:
            walk_times(v, found)
    elif isinstance(value, dict):
        for key, inner in value.items():
            if key in TIME_FIELDS and int(inner) > 0:
                found.append(int(inner))
            else:
                walk_times(inner, found)


def walk_mcp(value, tools, namespaces):
    """MCP 도구 이름을 모은다 — 같은 속성 목록에 MCP 네임스페이스나 서버 이름이 있는 레코드의 도구 이름."""
    if isinstance(value, list):
        for v in value:
            walk_mcp(v, tools, namespaces)
        return
    if not isinstance(value, dict):
        return
    attrs = value.get("attributes")
    if isinstance(attrs, list):
        flat = {kv.get("key"): (kv.get("value") or {}).get("stringValue") for kv in attrs}
        namespace = flat.get("tool_namespace") or ""
        mcp = namespace.startswith("mcp__") or any(flat.get(k) for k in ("mcp_server", "mcp.server.name", "mcp_server.name"))
        if namespace.startswith("mcp__"):
            namespaces.add(namespace[len("mcp__") :])
        if mcp:
            for key in TOOL_NAME_KEYS:
                if flat.get(key):
                    tools.add(flat[key])
    for inner in value.values():
        walk_mcp(inner, tools, namespaces)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("inputs", nargs="+")
    args = parser.parse_args()

    documents = {}
    times = []
    tools, namespaces = set(), set()
    for path in args.inputs:
        with open(path, encoding="utf-8") as handle:
            documents[path] = [json.loads(line) for line in handle if line.strip()]
        for document in documents[path]:
            walk_times(document, times)
            walk_mcp(document, tools, namespaces)
    earliest = min(times) if times else BASE_NANOS
    offset = ((earliest - BASE_NANOS) // 10**6) * 10**6  # 밀리초 배수 — ISO 밀리초 표기가 정확히 옮겨진다

    sanitizer = Sanitizer(offset)
    sanitizer.mcp_tools = tools
    sanitizer.mcp_namespaces = namespaces
    os.makedirs(args.out_dir, exist_ok=True)
    total = 0
    for path, docs in documents.items():
        target = os.path.join(args.out_dir, os.path.basename(path))
        with open(target, "w", encoding="utf-8") as out:
            for document in docs:
                out.write(json.dumps(sanitizer.node(document), ensure_ascii=False, separators=(",", ":")) + "\n")
        total += len(docs)
    print(f"익명화 {len(documents)}개 파일, 문서 {total}개 → {args.out_dir}", file=sys.stderr)


if __name__ == "__main__":
    main()
