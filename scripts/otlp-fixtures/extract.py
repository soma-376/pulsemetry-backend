#!/usr/bin/env python3
"""OTLP/JSON 캡처에서 fixture 후보 레코드를 고른다(익명화 전 단계).

입력 한 줄 = OTLP/JSON export 문서 하나. 출력 한 줄도 문서 하나이며, 고른 레코드만 남기되 **원래 문서의
resource·scope 아래에 그대로** 둔다 — 한 push 안의 묶음 구조가 보존된다. 출력은 원문을 담으므로 저장소 밖에
두고 반드시 sanitize.py 를 거친다.

    python3 extract.py --plan PLAN.json --out-dir RAW_DIR CAPTURE.jsonl [CAPTURE.jsonl ...]

계획 파일(JSON):

    {
      "services": ["codex-app-server", "Codex Desktop"],      # 이 service.name 만 본다
      "groups": [
        {
          "kind": "sse",                                        # 출력 파일 이름의 종류 조각
          "signal": "logs",                                     # logs | traces | metrics
          "select": [
            {
              "name": "codex.sse_event",                        # 로그: event.name 속성, 스팬·메트릭: name
              "match": {"event.kind": "response.completed"},    # 속성 값이 정확히 같아야 한다
              "has": ["cache_write_token_count"],               # 이 속성이 있어야 한다
              "missing": ["error.message"],                     # 이 속성이 없어야 한다
              "eventName": "models_endpoint",                   # 로그 최상위 eventName 부분 문자열
              "has_events": true,                               # 스팬: 이벤트가 있어야 한다
              "distinct_by": "event.kind", "per_value": 1,      # 이 속성의 값마다 per_value 개까지
              "group_by": "call_id", "group_min": 2,            # 같은 값을 가진 레코드가 group_min 개 이상인 묶음만,
              "groups": 2, "per_group": 3,                      #   묶음 groups 개 · 묶음당 per_group 개
              "max_points": 3,                                  # 메트릭: 한 metric 에서 남길 point 수
              "cap": 2                                          # 이 선택자가 (서비스, 버전) 조합마다 고를 최대 레코드 수
            }
          ]
        }
      ]
    }

레코드 하나는 선택자 중 **처음으로 맞는 것** 하나에만 배정된다. 출력 파일은
`<signal>-<service>-<version>-<kind>.otlp.jsonl`(서비스 이름은 소문자·하이픈)이며 (서비스, 버전) 조합마다 따로 낸다.
고른 레코드가 없는 조합은 파일을 만들지 않는다.
"""
import argparse
import json
import os
import re
import sys
from collections import Counter, OrderedDict, defaultdict

SIGNALS = {
    "logs": ("resourceLogs", "scopeLogs", "logRecords"),
    "traces": ("resourceSpans", "scopeSpans", "spans"),
    "metrics": ("resourceMetrics", "scopeMetrics", "metrics"),
}
METRIC_DATA = ("gauge", "sum", "histogram", "exponentialHistogram", "summary")


def attr_map(attributes):
    out = {}
    for kv in attributes or []:
        value = kv.get("value") or {}
        for wire in ("stringValue", "intValue", "doubleValue", "boolValue"):
            if wire in value:
                out[kv["key"]] = str(value[wire])
                break
        else:
            out[kv["key"]] = None
    return out


def record_name(signal, record):
    if signal == "logs":
        return attr_map(record.get("attributes")).get("event.name")
    return record.get("name")


def record_attrs(signal, record):
    """선택 조건에 쓰는 속성. 메트릭은 첫 data point 의 속성을 본다."""
    if signal != "metrics":
        return attr_map(record.get("attributes"))
    for data in METRIC_DATA:
        points = (record.get(data) or {}).get("dataPoints") or []
        if points:
            return attr_map(points[0].get("attributes"))
    return {}


def slug(text):
    return re.sub(r"[^a-z0-9.]+", "-", text.lower()).strip("-")


def iterate(paths, signal, services):
    """(원본 줄 번호, 문서, resource 순번, scope 순번, 레코드 순번, service, version, 레코드)."""
    rkey, skey, reckey = SIGNALS[signal]
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line_no, line in enumerate(handle):
                try:
                    document = json.loads(line)
                except ValueError:
                    continue
                for ri, resource in enumerate(document.get(rkey) or []):
                    rattrs = attr_map((resource.get("resource") or {}).get("attributes"))
                    service = rattrs.get("service.name")
                    if service not in services:
                        continue
                    version = rattrs.get("service.version") or "none"
                    for si, scope in enumerate(resource.get(skey) or []):
                        for xi, record in enumerate(scope.get(reckey) or []):
                            yield (path, line_no), document, ri, si, xi, service, version, record


class Selector:
    def __init__(self, spec, signal):
        self.spec = spec
        self.signal = signal
        self.cap = spec.get("cap", 1)
        self.taken = Counter()  # (service, version) → 수
        self.by_value = defaultdict(Counter)  # (service, version) → 값별 수
        self.group_keys = {}  # (service, version) → 고른 묶음 키 목록
        self.group_taken = defaultdict(Counter)

    def matches(self, record):
        spec = self.spec
        if "name" in spec and record_name(self.signal, record) != spec["name"]:
            return False
        attrs = record_attrs(self.signal, record)
        for key, value in (spec.get("match") or {}).items():
            if attrs.get(key) != value:
                return False
        if any(key not in attrs for key in spec.get("has") or []):
            return False
        if any(key in attrs for key in spec.get("missing") or []):
            return False
        if "eventName" in spec and spec["eventName"] not in (record.get("eventName") or ""):
            return False
        if spec.get("has_events") and not record.get("events"):
            return False
        return True

    def prepare(self, paths, services):
        """group_by 선택자는 한 번 훑어 묶음 크기를 센다."""
        key = self.spec.get("group_by")
        if not key:
            return
        sizes = defaultdict(Counter)
        order = defaultdict(list)
        for _, _, _, _, _, service, version, record in iterate(paths, self.signal, services):
            if not self.matches(record):
                continue
            value = record_attrs(self.signal, record).get(key)
            if value is None:
                continue
            combo = (service, version)
            if sizes[combo][value] == 0:
                order[combo].append(value)
            sizes[combo][value] += 1
        minimum = self.spec.get("group_min", 2)
        wanted = self.spec.get("groups", 1)
        for combo, values in order.items():
            self.group_keys[combo] = [v for v in values if sizes[combo][v] >= minimum][:wanted]

    def take(self, service, version, record):
        combo = (service, version)
        if not self.matches(record):
            return False
        spec = self.spec
        if "group_by" in spec:
            value = record_attrs(self.signal, record).get(spec["group_by"])
            if value not in self.group_keys.get(combo, []):
                return False
            if self.group_taken[combo][value] >= spec.get("per_group", 2):
                return False
            self.group_taken[combo][value] += 1
            return True
        if self.taken[combo] >= self.cap:
            return False
        if "distinct_by" in spec:
            value = record_attrs(self.signal, record).get(spec["distinct_by"])
            if self.by_value[combo][value] >= spec.get("per_value", 1):
                return False
            self.by_value[combo][value] += 1
        self.taken[combo] += 1
        return True


def trim_points(record, limit):
    if limit is None:
        return record
    record = dict(record)
    for data in METRIC_DATA:
        if data in record:
            body = dict(record[data])
            body["dataPoints"] = (body.get("dataPoints") or [])[:limit]
            record[data] = body
    return record


def reduce_document(document, signal, picks):
    """picks: {(ri, si): [record, ...]} — 원래 순서대로 resource·scope 를 남긴다."""
    rkey, skey, reckey = SIGNALS[signal]
    resources = []
    for ri, resource in enumerate(document.get(rkey) or []):
        scopes = []
        for si, scope in enumerate(resource.get(skey) or []):
            records = picks.get((ri, si))
            if records:
                kept = {k: v for k, v in scope.items() if k != reckey}
                kept[reckey] = records
                scopes.append(kept)
        if scopes:
            kept = {k: v for k, v in resource.items() if k != skey}
            kept[skey] = scopes
            resources.append(kept)
    return {rkey: resources}


def run(plan, paths, out_dir):
    services = set(plan["services"])
    written = Counter()
    for group in plan["groups"]:
        signal = group["signal"]
        selectors = [Selector(spec, signal) for spec in group["select"]]
        for selector in selectors:
            selector.prepare(paths, services)
        # (service, version) → 원본 줄 → {(ri, si): [record]}
        picked = defaultdict(OrderedDict)
        documents = {}
        for origin, document, ri, si, _, service, version, record in iterate(paths, signal, services):
            for selector in selectors:
                if selector.take(service, version, record):
                    kept = trim_points(record, selector.spec.get("max_points")) if signal == "metrics" else record
                    picked[(service, version)].setdefault(origin, defaultdict(list))[(ri, si)].append(kept)
                    documents[origin] = document
                    break
        for (service, version), origins in sorted(picked.items()):
            name = f"{signal}-{slug(service)}-{slug(version)}-{group['kind']}.otlp.jsonl"
            with open(os.path.join(out_dir, name), "w", encoding="utf-8") as out:
                for origin, picks in origins.items():
                    reduced = reduce_document(documents[origin], signal, picks)
                    out.write(json.dumps(reduced, ensure_ascii=False, separators=(",", ":")) + "\n")
                    written[name] += sum(len(v) for v in picks.values())
    for name, count in sorted(written.items()):
        print(f"{count:5d}  {name}")
    print(f"합계 {sum(written.values())} 레코드, 파일 {len(written)}개", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--plan", required=True)
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("captures", nargs="+")
    args = parser.parse_args()
    with open(args.plan, encoding="utf-8") as handle:
        plan = json.load(handle)
    os.makedirs(args.out_dir, exist_ok=True)
    run(plan, args.captures, args.out_dir)


if __name__ == "__main__":
    main()
