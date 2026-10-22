#!/usr/bin/env python3
"""Grafana/k6와 독립된 단일 OTLP logs 접수·논리 적재 확인. 토큰과 서버 오류 본문은 증거에 쓰지 않는다."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


QUERY = """SELECT observation_id, tenant_id, installation_id, session_id, request_id,
 event_type, tokens_input, tokens_output, archive_ref
FROM telemetry_events FINAL
WHERE tenant_id = {tenant:String} AND installation_id = {installation:String}
 AND session_id = {session:String} AND request_id = {request:String}
FORMAT JSONEachRow"""


def payload(run_id, source_time):
    """같은 run ID·source time에는 같은 바이트와 native ID를 만든다. 원문 프롬프트는 넣지 않는다."""
    run_id = uuid.UUID(str(run_id))
    stamp = dt.datetime.fromisoformat(source_time.replace("Z", "+00:00"))
    if stamp.utcoffset() is None:
        raise ValueError("source time requires timezone")
    stamp = stamp.astimezone(dt.timezone.utc)
    delta = stamp - dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc)
    nanos = ((delta.days * 86400 + delta.seconds) * 1_000_000 + delta.microseconds) * 1000
    request_id = "req_smoke_" + run_id.hex
    attrs = {
        "session.id": str(run_id), "event.timestamp": stamp.isoformat(),
        "prompt.id": str(uuid.uuid5(run_id, "prompt")), "event.name": "api_request", "event.sequence": "1",
        "model": "claude-haiku-4-5-20251001", "input_tokens": {"intValue": "1066"}, "output_tokens": {"intValue": "28"},
        "cache_read_tokens": {"intValue": "0"}, "cache_creation_tokens": {"intValue": "0"}, "cost_usd": {"doubleValue": 0.001206},
        "duration_ms": {"intValue": "1056"}, "ttft_ms": {"intValue": "738"}, "request_id": request_id,
        "client_request_id": str(uuid.uuid5(run_id, "client")),
    }
    record = {"timeUnixNano": str(nanos), "observedTimeUnixNano": str(nanos),
              "body": {"stringValue": "claude_code.api_request"},
              "attributes": [{"key": k, "value": v if isinstance(v, dict) else {"stringValue": v}} for k, v in attrs.items()]}
    data = {"resourceLogs": [{"resource": {"attributes": [
        {"key": "service.name", "value": {"stringValue": "claude-code"}},
        {"key": "service.version", "value": {"stringValue": "2.1.290"}}]},
        "scopeLogs": [{"scope": {"name": "com.anthropic.claude_code.events", "version": "2.1.290"}, "logRecords": [record]}]}]}
    return json.dumps(data, separators=(",", ":")).encode(), request_id


def expected_observation_id(body, tenant, installation):
    """이 고정 logs fixture만 지원하는 id-v1 fingerprint oracle(ADR0020, stamp-v1).

    Claude api_request의 request_id는 관측 고유 ID가 아니다. proto 필드명·숫자·KeyValue 정렬과
    서버 신원 stamp를 반영한 fingerprint를 구한다. 임의 OTLP canonicalizer로 사용하지 않는다.
    """
    data = json.loads(body)["resourceLogs"][0]
    resource = data["resource"]
    resource["attributes"] += [{"key": key, "value": {"stringValue": str(value)}}
                               for key, value in [("tenant.id", tenant), ("developer.installation_id", installation)]]
    scope = data["scopeLogs"][0]["scope"]
    record = data["scopeLogs"][0]["logRecords"][0]
    names = {"stringValue": "string_value", "doubleValue": "double_value", "intValue": "int_value",
             "timeUnixNano": "time_unix_nano", "observedTimeUnixNano": "observed_time_unix_nano"}
    def canonical(value):
        if not isinstance(value, dict):
            return value
        result = {}
        for name in sorted(value, key=lambda key: names.get(key, key)):
            item = value[name]
            if name == "attributes":
                item = sorted([canonical(attr) for attr in item], key=lambda attr: json.dumps(attr, separators=(",", ":")))
            elif name in ("timeUnixNano", "observedTimeUnixNano", "intValue"):
                item = int(item)
            else:
                item = canonical(item)
            result[names.get(name, name)] = item
        return result
    material = {"identity_version": "id-v1", "installation_id": str(installation), "kind": "fingerprint",
                "service_name": "claude-code", "signal": "log", "source_time": int(record["timeUnixNano"]),
                "tenant_id": str(tenant), "record": canonical(record), "resource": canonical(resource),
                "resource_schema_url": "", "scope": canonical(scope), "scope_schema_url": ""}
    return hashlib.sha256(json.dumps(material, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, url, data=None, headers=None):
        request = urllib.request.Request(url, data=data, headers=headers or {})
        try:
            with self.opener.open(request, timeout=10) as response:
                return response.status, response.read(16 * 1024 * 1024)
        except urllib.error.HTTPError as error:
            # 오류 본문에 자격·SQL·원문이 섞일 수 있으므로 읽거나 출력하지 않는다.
            error.close()
            return error.code, b""


def endpoint(value):
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise argparse.ArgumentTypeError("http(s) endpoint without credentials/query/fragment required")
    return value.rstrip("/")


def read_rows(client, args, request_id):
    params = urllib.parse.urlencode({"database": args.database, "param_tenant": str(args.tenant),
                                   "param_installation": str(args.installation), "param_session": str(args.run_id),
                                   "param_request": request_id, "max_execution_time": "5", "max_result_rows": "10", "result_overflow_mode": "throw"})
    headers = {"Content-Type": "text/plain"}
    if os.environ.get("PULSEMETRY_SMOKE_CLICKHOUSE_USER"):
        headers["X-ClickHouse-User"] = os.environ["PULSEMETRY_SMOKE_CLICKHOUSE_USER"]
        headers["X-ClickHouse-Key"] = os.environ.get("PULSEMETRY_SMOKE_CLICKHOUSE_PASSWORD", "")
    status, body = client.request(args.clickhouse + "/?" + params, QUERY.encode(), headers)
    if status != 200:
        raise RuntimeError("ClickHouse readback HTTP " + str(status))
    return [json.loads(line) for line in body.splitlines() if line]


def validate_rows(rows, args, request_id):
    if len(rows) != 1:
        return False
    row = rows[0]
    return (row.get("tenant_id") == str(args.tenant) and row.get("installation_id") == str(args.installation)
            and row.get("session_id") == str(args.run_id) and row.get("request_id") == request_id
            and row.get("event_type") == "model.response.usage"
            and int(row.get("tokens_input") or 0) == 1066 and int(row.get("tokens_output") or 0) == 28
            and row.get("observation_id") == expected_observation_id(payload(args.run_id, args.source_time)[0], args.tenant, args.installation)
            and bool(row.get("archive_ref")))


def run(args, client=None):
    client = client or Client()
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    body, request_id = payload(args.run_id, args.source_time)
    (args.output / "payload.json").write_bytes(body)
    evidence = {"run_id": str(args.run_id), "source_time": args.source_time,
                "payload_sha256": hashlib.sha256(body).hexdigest(), "request_id": request_id,
                "expected_logical_rows": 1, "expected_observation_ids": [expected_observation_id(body, args.tenant, args.installation)],
                "identity_version": "id-v1", "fixture": "claude-code-2.1.290-api-request-v1",
                "started_at_utc": utc_now(), "http_accepted_at_utc": None, "visible_at_utc": None, "ended_at_utc": None,
                "provenance": getattr(args, "provenance", []),
                "http_status": None, "logical_rows": [], "result": "incomplete"}
    try:
        evidence["phase"] = "metrics_preflight"
        for name, url in [("gateway", args.gateway_metrics), ("ingest", args.ingest_metrics)]:
            status, scrape = client.request(url + "/actuator/prometheus")
            if status != 200 or b"jvm_memory_used_bytes" not in scrape or b"pulsemetry_" not in scrape:
                raise RuntimeError(name + " metrics preflight failed")
            (args.output / (name + "-before.prom")).write_bytes(scrape)
        evidence["phase"] = "readback_preflight"
        if read_rows(client, args, request_id):
            raise RuntimeError("run ID already has logical rows; use a fresh run ID")
        evidence["phase"] = "request"
        token = args.token_file.read_text().strip()
        if not token or any(char.isspace() for char in token):
            raise RuntimeError("token file must contain one bearer token")
        # 모호한 응답을 자동 재전송하지 않는다. 접수는 정확히 한 번만 시도한다.
        try:
            status, _ = client.request(args.gateway + "/v1/logs", body,
                                       {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
            evidence["http_status"] = status
            if status == 200:
                evidence["http_accepted_at_utc"] = utc_now()
        except (OSError, urllib.error.URLError):
            evidence["http_status"] = "transport_unknown"
        finally:
            token = None
        evidence["phase"] = "readback"
        deadline = time.monotonic() + args.timeout
        while True:
            rows = read_rows(client, args, request_id)
            evidence["logical_rows"] = rows
            if validate_rows(rows, args, request_id):
                evidence["visible_at_utc"] = utc_now()
            if rows or time.monotonic() >= deadline:
                break
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        for name, url in [("gateway", args.gateway_metrics), ("ingest", args.ingest_metrics)]:
            status, scrape = client.request(url + "/actuator/prometheus")
            if status != 200:
                raise RuntimeError(name + " metrics postflight failed")
            (args.output / (name + "-after.prom")).write_bytes(scrape)
        evidence["phase"] = "complete"
        evidence["result"] = "passed" if evidence["http_status"] == 200 and validate_rows(rows, args, request_id) else "failed_or_inconclusive"
        return 0 if evidence["result"] == "passed" else 1
    except Exception as error:
        evidence["result"] = "failed_or_inconclusive"
        # 표준 오류에도 예외 메시지나 서버 본문을 복사하지 않는다.
        evidence["error_type"] = type(error).__name__
        return 1
    finally:
        evidence["ended_at_utc"] = utc_now()
        (args.output / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("gateway", "gateway-metrics", "ingest-metrics", "clickhouse"):
        parser.add_argument("--" + name, required=True, type=endpoint)
    for name in ("tenant", "installation", "run-id"):
        parser.add_argument("--" + name, required=True, type=uuid.UUID)
    parser.add_argument("--source-time", required=True, help="현재 retention 범위의 timezone 포함 ISO8601")
    parser.add_argument("--token-file", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="아직 없는 증거 디렉터리")
    parser.add_argument("--database", default="default")
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--provenance", action="append", default=[], help="비밀 없는 revision/image digest 등 실행 출처(반복 가능)")
    args = parser.parse_args()
    if not 0 < args.timeout <= 600:
        parser.error("timeout must be between 0 and 600 seconds")
    try:
        code = run(args)
    except Exception as error:
        print("smoke failed: " + type(error).__name__)
        return 1
    print("smoke " + ("passed" if code == 0 else "failed/inconclusive") + "; inspect " + str(args.output / "evidence.json"))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
