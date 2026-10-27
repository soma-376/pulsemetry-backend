#!/usr/bin/env python3
"""검증된 과거 화면 시드를 local/dev에만 넣는 명시적 운영 도구.

표준 라이브러리와 기존 psql만 사용한다. 스키마 변경·삭제·계정 생성은 하지 않는다.
데이터 계약과 사용법은 historical-seed.md를 참고한다.
"""
from __future__ import annotations

import argparse
import contextlib
import datetime as dt
from decimal import Decimal, ROUND_HALF_EVEN
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

MAX_ROWS = 2_000
MAX_BYTES = 4 * 1024 * 1024
RECONCILE_COLUMNS = 16
RECONCILE_LOG_BYTES = 4096
MONITOR_MAX_AGE = 45
PG_TABLES = {
    "enrollment.tenants", "enrollment.members", "enrollment.teams",
    "enrollment.team_memberships", "enrollment.manifests",
    "enrollment.organization_onboarding", "enrollment.organization_policy_settings",
    "enrollment.organization_alert_rules", "enrollment.managed_vendors",
    "enrollment.vendor_contract_versions", "enrollment.seat_assignments",
    "enrollment.seat_assignment_events", "enrollment.invitations",
    "enrollment.installations", "enrollment.installation_manifest_assignments",
    "enrollment.installation_heartbeats", "enrollment.installation_collection_segments",
    "enrollment.contracts", "enrollment.contract_term_commitments", "enrollment.contract_memberships",
    "telemetry_ops.tenant_ingest_summary",
}
CH_TABLES = {
    "telemetry_events": ("source_time", "observation_id"),
    "telemetry_ingest_ledger": ("received_time", "receipt_id"),
}
CH_REQUIRED_TYPES = {
    "telemetry_events": {
        "tenant_id": "LowCardinality(String)", "observation_id": "FixedString(64)",
        "source_time": "DateTime64(9, 'UTC')", "received_time": "DateTime64(9, 'UTC')",
        "row_version": "UInt64", "normalizer_rev": "UInt32", "analysis_hash": "FixedString(64)",
        "semantics_profile": "Nullable(String)", "tokens_total_derived": "Nullable(Int64)",
        "tokens_input_uncached": "Nullable(Int64)", "tokens_cache_read": "Nullable(Int64)",
        "tokens_cache_create": "Nullable(Int64)", "tokens_input": "Nullable(Int64)",
        "tokens_output": "Nullable(Int64)", "cost_estimated_usd": "Nullable(Decimal(38, 12))",
    },
    "telemetry_ingest_ledger": {
        "tenant_id": "LowCardinality(String)", "receipt_id": "String",
        "received_time": "DateTime64(9, 'UTC')", "record_count": "UInt32", "rejected_count": "UInt32",
    },
}
TIMES = {
    "source_time", "received_time", "event_time", "observed_time", "end_time",
    "source_time_min", "source_time_max",
}
ALERT_RULE_TABLE = "enrollment.organization_alert_rules"


class SeedError(RuntimeError):
    pass


class Paused(SeedError):
    pass


class PressurePaused(Paused):
    """컨테이너 압력/관측 기준 변경은 재시도로 자동 해제하지 않는다."""


def require(condition, message):
    if not condition:
        raise SeedError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def decode(data):
    def reject_duplicate(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "JSON 객체의 필드가 중복되었습니다.")
            result[key] = value
        return result
    return json.loads(data, parse_float=Decimal, object_pairs_hook=reject_duplicate,
                      parse_constant=lambda _: (_ for _ in ()).throw(SeedError("비정상 JSON 수치입니다.")))


def encode(value):
    """Decimal을 float를 거치지 않는 JSON 수치로 기록한다."""
    if isinstance(value, Decimal):
        require(value.is_finite(), "유한 수치만 허용합니다.")
        return str(value)
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(k, ensure_ascii=False) + ":" + encode(v) for k, v in value.items()) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(encode(v) for v in value) + "]"
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


def identifier(name):
    require(isinstance(name, str) and re.fullmatch(r"[a-z_][a-z0-9_]*", name), "허용되지 않은 SQL 식별자입니다.")
    return name


def pg_literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, Decimal)):
        return str(value)
    require(isinstance(value, str), "PostgreSQL 필드는 JSON 문자열 또는 스칼라여야 합니다.")
    require("\x00" not in value, "문자열에 NUL이 있습니다.")
    return "'" + value.replace("'", "''") + "'"


def ch_literal(value):
    require(isinstance(value, str) and "\x00" not in value, "ClickHouse 문자열이 유효하지 않습니다.")
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def instant(value):
    """나노초를 보존하는 UTC epoch Decimal. PG/CH의 서로 다른 시각 표기를 비교한다."""
    require(isinstance(value, str), "시각은 문자열이어야 합니다.")
    m = re.fullmatch(r"(\d{4}-\d\d-\d\d)[T ](\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d(?::?\d\d)?)?", value)
    require(m is not None, "ISO 날짜·시각이 유효하지 않습니다.")
    offset = m[4] or "+00:00"
    if offset == "Z":
        offset = "+00:00"
    elif len(offset) == 3:
        offset += ":00"
    stamp = dt.datetime.fromisoformat(m[1] + "T" + m[2] + offset)
    return Decimal(int(stamp.timestamp())) + Decimal("0." + (m[3] or "0"))


def now_iso():
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def read_regular(path, max_bytes=16 * 1024 * 1024):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), "일반 파일만 사용할 수 있습니다: " + path.name)
    require(path.stat().st_size <= max_bytes, "파일 크기 제한을 넘었습니다: " + path.name)
    return path.read_bytes()


def within(root, name):
    require(isinstance(name, str) and name and not Path(name).is_absolute(), "artifact 경로는 상대 경로여야 합니다.")
    candidate = root / name
    require(root == candidate.resolve().parent or root in candidate.resolve().parents, "artifact 경로가 디렉터리 밖입니다.")
    require(all(not p.is_symlink() for p in [candidate, *candidate.parents] if p != root.parent), "artifact 심볼릭 링크는 허용하지 않습니다.")
    return candidate


def activation_rules(manifest, tables):
    """명시된 준비 계약만 해석한다. 구 manifest의 enabled를 조용히 바꾸지 않는다."""
    rows = tables.get(ALERT_RULE_TABLE, [])
    require(all(type(row.get("enabled")) is bool for row in rows), "알림 규칙 enabled는 boolean이어야 합니다.")
    if "activationRules" not in manifest:
        return None
    entries = manifest["activationRules"]
    require(isinstance(entries, list), "activationRules는 배열이어야 합니다.")
    rules = {}
    for entry in entries:
        require(isinstance(entry, dict) and set(entry) == {"ruleId", "desiredEnabled", "expectedVersion"}, "activationRules 필드가 유효하지 않습니다.")
        rule = entry["ruleId"]
        require(isinstance(rule, str) and rule and rule not in rules, "activationRules 규칙 ID가 없거나 중복되었습니다.")
        require(type(entry["desiredEnabled"]) is bool and type(entry["expectedVersion"]) is int and entry["expectedVersion"] > 0, "activationRules의 원래 enabled/version 타입이 유효하지 않습니다.")
        rules[rule] = entry
    require(len(rows) == len(rules) and {row.get("rule_id") for row in rows} == set(rules), "activationRules는 모든 시드 알림 규칙을 정확히 한 번 포함해야 합니다.")
    for row in rows:
        require(row["enabled"] is False, "activationRules가 있는 PostgreSQL 알림 규칙은 모두 비활성이어야 합니다.")
        require(type(row.get("version")) is int and row["version"] == rules[row["rule_id"]]["expectedVersion"], "activationRules version이 PostgreSQL 원래 판과 다릅니다.")
    return rules


class Artifact:
    def __init__(self, path):
        self.path = Path(path).absolute()
        self.root = self.path.parent.resolve()
        raw = read_regular(self.path)
        self.sha = sha256(raw)
        self.manifest = m = decode(raw)
        require(m.get("schemaVersion") == 1, "지원하지 않는 manifest 버전입니다.")
        require(m.get("environment") in ("local", "dev"), "local/dev artifact만 허용합니다.")
        require(m.get("datasetId") == "soma-history-v1", "soma-history-v1 데이터셋만 허용합니다.")
        require(m.get("tenantName") == "soma" and m.get("tenantSlug") == "soma", "soma 조직만 허용합니다.")
        require(str(uuid.UUID(m["tenantId"])) == m["tenantId"], "tenant UUID가 유효하지 않습니다.")
        self.tenant = m["tenantId"]
        require(re.fullmatch(r"[0-9a-f]{64}", m.get("configHash", "")), "configHash가 유효하지 않습니다.")
        start = dt.date.fromisoformat(m["range"]["start"])
        end = dt.date.fromisoformat(m["range"]["end"])
        require(m["range"]["timezone"] == "Asia/Seoul" and (end - start).days == 179, "한국 시간의 180일 범위가 필요합니다.")
        snapshot = instant(m["snapshotAt"])
        cutoff = dt.datetime.combine(end + dt.timedelta(days=1), dt.time(), dt.timezone(dt.timedelta(hours=9)))
        self.period_start = Decimal(int(dt.datetime.combine(start, dt.time(), dt.timezone(dt.timedelta(hours=9))).timestamp()))
        self.period_until = Decimal(int(cutoff.timestamp()))
        self.snapshot = snapshot
        snapshot_day = dt.datetime.fromtimestamp(float(snapshot), dt.timezone(dt.timedelta(hours=9))).date()
        require(end == snapshot_day - dt.timedelta(days=1), "사용 기간은 고정 스냅샷 날짜의 전날까지여야 합니다.")
        require(snapshot >= Decimal(int(cutoff.timestamp())) + 600, "마지막 날 종료 후 10분의 수집 근거가 필요합니다.")
        require(snapshot <= Decimal(str(time.time())) + 1, "미래 스냅샷 시각을 사용할 수 없습니다.")
        pg = m["postgres"]
        pg_raw = read_regular(within(self.root, pg["file"]))
        require(sha256(pg_raw) == pg["sha256"], "PostgreSQL artifact 해시가 다릅니다.")
        tables = decode(pg_raw)["tables"]
        self.tables = {}
        for item in tables:
            table, rows = item["table"], item["rows"]
            require(table in PG_TABLES and table not in self.tables and isinstance(rows, list) and rows, "PostgreSQL 테이블 목록이 유효하지 않습니다.")
            require(all(isinstance(row, dict) and row for row in rows), "PostgreSQL 행이 비어 있습니다.")
            require(all(set(row) == set(rows[0]) for row in rows), "PostgreSQL 테이블의 생성 컬럼 집합이 일정하지 않습니다.")
            for row in rows:
                for key in row:
                    identifier(key)
                require(row.get("tenant_id", self.tenant) == self.tenant, "다른 tenant의 행이 들어 있습니다.")
            self.tables[table] = rows
        require({k: len(v) for k, v in self.tables.items()} == pg["tables"], "PostgreSQL 테이블별 행 수가 다릅니다.")
        self.activation_rules = activation_rules(m, self.tables)
        tenants = self.tables.get("enrollment.tenants", [])
        require(len(tenants) == 1 and tenants[0]["id"] == self.tenant and tenants[0]["name"] == "soma" and tenants[0]["slug"] == "soma", "조직 행이 manifest와 다릅니다.")
        require(tenants[0].get("sso_enabled") is False and tenants[0].get("oidc_require_verified_email") is True, "초기 SSO는 비활성, 이메일 검증은 활성이어야 합니다.")
        oidc = m["oidc"]
        for field, key in (("oidc_issuer", "issuer"), ("oidc_client_id", "clientId"), ("oidc_client_secret_ref", "clientSecretRef")):
            require(tenants[0].get(field) == oidc[key], "OIDC 설정과 조직 행이 다릅니다.")
        require(re.fullmatch(r"config:[A-Za-z0-9_-]{1,64}", oidc["clientSecretRef"]), "OIDC 비밀 참조가 유효하지 않습니다.")
        if m["environment"] == "dev":
            require(oidc == {"issuer": "https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_aTZWvnmMu", "clientId": "5p4p8mm8st8i1aimimbmdek03d", "clientSecretRef": "config:cognito-a"}, "dev의 기존 Cognito A 설정만 허용합니다.")
        members = self.tables.get("enrollment.members", [])
        require(len(members) == 400 and all(r["status"] == "active" for r in members), "활성 회원 400명이 필요합니다.")
        require([(r["email"], r["role"]) for r in members if r["role"] != "member"] == [("owner@seed-a.example.test", "owner"), ("admin@seed-a.example.test", "admin")], "관리자 두 명의 이메일·역할이 다릅니다.")
        require(all(r.get("oidc_subject") is None for r in members), "신규 OIDC subject는 비워야 합니다.")
        require(len(self.tables.get("enrollment.teams", [])) == 50 and len(self.tables.get("enrollment.installations", [])) == 398, "50팀·398개 설치가 필요합니다.")
        members_ids = {r["id"] for r in members}
        self.team_ids = {r["id"] for r in self.tables["enrollment.teams"]}
        self.installation_ids = {r["id"] for r in self.tables["enrollment.installations"]}
        self.member_ids = members_ids
        references = {
            **{field: members_ids for field in ("member_id", "target_member_id", "created_by_member_id", "policy_confirmed_by", "completed_by", "updated_by", "recorded_by", "actor_id")},
            "team_id": self.team_ids, "installation_id": self.installation_ids,
            **{field: {r["id"] for r in self.tables["enrollment.manifests"]} for field in ("manifest_id", "applied_manifest_id")},
            "invitation_id": {r["id"] for r in self.tables.get("enrollment.invitations", [])},
            "contract_id": {r["id"] for r in self.tables.get("enrollment.contracts", [])},
            "seat_assignment_id": {r["id"] for r in self.tables.get("enrollment.seat_assignments", [])},
        }
        for table, rows in self.tables.items():
            for row in rows:
                for field, allowed in references.items():
                    if field in row and row[field] is not None:
                        require(row[field] in allowed, "데이터셋 밖 PostgreSQL 참조가 있습니다: " + table + "." + field)
        self.batches = m["batches"]
        require(self.batches and len({b["id"] for b in self.batches}) == len(self.batches), "배치가 없거나 ID가 중복되었습니다.")
        self.batch_columns = {}
        for batch in self.batches:
            table = batch["table"]
            require(table in CH_TABLES and (batch["timeColumn"], batch["identityColumn"]) == CH_TABLES[table], "ClickHouse 배치 테이블·열이 잘못되었습니다.")
            require(isinstance(batch["id"], str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", batch["id"]), "배치 ID가 유효하지 않습니다.")
            require(0 < batch["rows"] <= MAX_ROWS and 0 < batch["bytes"] <= MAX_BYTES, "배치 제한을 넘었습니다.")
            require(instant(batch["timeMin"]) <= instant(batch["timeMax"]), "배치 시간 범위가 역전되었습니다.")
            month = lambda value: dt.datetime.fromtimestamp(float(instant(value)), dt.timezone.utc).strftime("%Y-%m")
            require(month(batch["timeMin"]) == month(batch["timeMax"]), "배치는 하나의 UTC 월 파티션만 포함해야 합니다.")
            rows = self.batch_rows(batch)
            cols = set(rows[0])
            require(all(set(r) == cols for r in rows), "배치의 컬럼 집합이 일정하지 않습니다.")
            require(table not in self.batch_columns or self.batch_columns[table] == cols, "테이블 배치의 컬럼 집합이 다릅니다.")
            self.batch_columns[table] = cols
        require(set(self.batch_columns) == set(CH_TABLES), "사용 이벤트와 수신 원장이 모두 필요합니다.")

    def batch_rows(self, batch):
        raw = read_regular(within(self.root, batch["file"]), MAX_BYTES)
        require(len(raw) == batch["bytes"] and sha256(raw) == batch["sha256"], "배치 해시·바이트 수가 다릅니다: " + batch["id"])
        rows = [decode(line) for line in raw.splitlines() if line.strip()]
        require(len(rows) == batch["rows"], "배치 행 수가 다릅니다: " + batch["id"])
        identities = set()
        for row in rows:
            require(row.get("tenant_id") == self.tenant, "다른 tenant의 ClickHouse 행입니다.")
            require(row.get("installation_id") in self.installation_ids, "데이터셋 밖 설치의 ClickHouse 행입니다.")
            if "member_id" in row and row["member_id"] is not None:
                require(row["member_id"] in self.member_ids, "데이터셋 밖 구성원의 ClickHouse 행입니다.")
            if "team_id_as_of" in row and row["team_id_as_of"] is not None:
                require(row["team_id_as_of"] in self.team_ids, "데이터셋 밖 팀의 ClickHouse 행입니다.")
            if "team_ids_as_of" in row:
                require(set(row["team_ids_as_of"]) <= self.team_ids, "데이터셋 밖 과거 소속이 있습니다.")
            for key in row:
                identifier(key)
            key = row[batch["identityColumn"]]
            require(isinstance(key, str) and key not in identities, "배치의 행 ID가 중복되었습니다.")
            identities.add(key)
            stamp = instant(row[batch["timeColumn"]])
            require(instant(batch["timeMin"]) <= stamp <= instant(batch["timeMax"]), "배치 시간 범위 밖 행입니다.")
            if batch["table"] == "telemetry_events":
                require(self.period_start <= instant(row["source_time"]) < self.period_until, "시드 기간 밖의 사용 이벤트입니다.")
            if "received_time" in row:
                require(instant(row["received_time"]) <= self.snapshot, "스냅샷 이후 수신 행입니다.")
        return rows


class Target:
    def __init__(self, path, artifact):
        self.path = Path(path).absolute()
        raw = read_regular(self.path, 65536)
        self.sha = sha256(raw)
        self.data = d = decode(raw)
        require(set(d) <= {"schemaVersion", "environment", "targetId", "postgres", "clickhouse", "monitorFile"}, "target에 알 수 없는 필드 또는 비밀 원문이 있습니다.")
        require(d.get("schemaVersion") == 1 and d.get("environment") in ("local", "dev"), "local/dev 대상만 허용합니다.")
        require(d["environment"] == artifact.manifest["environment"], "artifact와 대상 환경이 다릅니다.")
        require(re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", d.get("targetId", "")), "targetId가 유효하지 않습니다.")
        pg, ch = d["postgres"], d["clickhouse"]
        require(set(pg) <= {"host", "hostaddr", "port", "dbname", "user", "sslmode", "sslrootcert", "passwordEnv", "psql"}, "PostgreSQL 대상에 비밀 원문 또는 알 수 없는 필드가 있습니다.")
        require(set(ch) <= {"url", "database", "username", "passwordEnv"}, "ClickHouse 대상에 비밀 원문 또는 알 수 없는 필드가 있습니다.")
        for key in ("host", "dbname", "user", "sslmode"):
            require(isinstance(pg.get(key), str) and pg[key] and "\x00" not in pg[key], "PostgreSQL 필수 설정이 없습니다: " + key)
        require(isinstance(pg.get("port"), int) and 1 <= pg["port"] <= 65535, "PostgreSQL 포트가 유효하지 않습니다.")
        require(pg["sslmode"] in ("disable", "verify-full"), "sslmode는 local disable 또는 verify-full이어야 합니다.")
        url = urllib.parse.urlsplit(ch["url"])
        require(url.scheme in ("http", "https") and url.hostname and not url.username and not url.password and not url.query and not url.fragment and url.path in ("", "/"), "ClickHouse URL이 유효하지 않습니다.")
        identifier(ch["database"])
        require(ch["database"] == "default", "원천 default 데이터베이스만 허용합니다.")
        for settings in (pg, ch):
            if "passwordEnv" in settings:
                require(re.fullmatch(r"[A-Z_][A-Z0-9_]*", settings["passwordEnv"]), "비밀번호 환경변수 이름이 유효하지 않습니다.")
        if d["environment"] == "dev":
            require(pg["sslmode"] == "verify-full" and pg.get("sslrootcert"), "dev PostgreSQL은 CA와 verify-full이 필수입니다.")
            require(url.scheme == "https" or url.hostname in ("localhost", "127.0.0.1", "::1"),
                    "dev ClickHouse는 HTTPS 또는 loopback 터널만 허용합니다.")
            require(d.get("monitorFile"), "dev에는 신선한 부하 관측 파일이 필요합니다.")
        else:
            require(pg["host"] in ("localhost", "127.0.0.1", "::1") and url.hostname in ("localhost", "127.0.0.1", "::1"), "local 대상은 loopback만 허용합니다.")
            require("hostaddr" not in pg or pg["hostaddr"] in ("127.0.0.1", "::1"),
                    "local PostgreSQL hostaddr도 loopback만 허용합니다.")


class Postgres:
    """한 psql 세션을 유지해 전체 import 동안 advisory lock을 보유한다."""
    def __init__(self, settings):
        env = os.environ.copy()
        for key, field in (("PGHOST", "host"), ("PGHOSTADDR", "hostaddr"), ("PGPORT", "port"), ("PGDATABASE", "dbname"), ("PGUSER", "user"), ("PGSSLMODE", "sslmode"), ("PGSSLROOTCERT", "sslrootcert")):
            env.pop(key, None)
            if field in settings:
                env[key] = str(settings[field])
        env.pop("PGSERVICE", None)
        env.pop("PGSERVICEFILE", None)
        env.pop("PGOPTIONS", None)
        env["PGCONNECT_TIMEOUT"] = "10"
        env["PGAPPNAME"] = "soma-historical-seed"
        if "passwordEnv" in settings:
            require(settings["passwordEnv"] in os.environ, "지정한 PostgreSQL 비밀번호 환경변수가 없습니다.")
            env["PGPASSWORD"] = os.environ[settings["passwordEnv"]]
        self.errors = tempfile.TemporaryFile(mode="w+t")
        self.process = subprocess.Popen([settings.get("psql", "psql"), "-X", "-A", "-t", "-q", "-w", "-v", "ON_ERROR_STOP=1"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.errors, text=True, bufsize=1, env=env)
        self.lines = queue.Queue()
        def reader():
            for line in self.process.stdout:
                self.lines.put(line.rstrip("\n"))
            self.lines.put(None)
        threading.Thread(target=reader, daemon=True).start()
        try:
            self.execute("SET statement_timeout='120s'; SET lock_timeout='5s'; SET TIME ZONE 'UTC'; SET standard_conforming_strings=on;")
        except Exception:
            self.close()
            raise

    def execute(self, sql):
        marker = "SEED_DONE_" + uuid.uuid4().hex
        try:
            self.process.stdin.write(sql.rstrip().rstrip(";") + ";\n\\echo " + marker + "\n")
            self.process.stdin.flush()
        except (BrokenPipeError, OSError) as error:
            raise SeedError("PostgreSQL 연결이 종료되었습니다.") from error
        lines, size = [], 0
        until = time.monotonic() + 130
        while True:
            try:
                line = self.lines.get(timeout=max(0.01, until - time.monotonic()))
            except queue.Empty as error:
                raise SeedError("PostgreSQL 응답 시간 초과입니다.") from error
            if line is None:
                self.errors.seek(0)
                detail = self.errors.read(4096)
                error_code = re.search(r"(?:ERROR|FATAL):\s*([^\n]{0,200})", detail)
                raise SeedError("PostgreSQL 요청 실패" + (": " + error_code[1] if error_code else ""))
            if line == marker:
                return lines
            size += len(line)
            require(size <= 32 * 1024 * 1024, "PostgreSQL 응답 제한을 넘었습니다.")
            if line:
                lines.append(line)

    def query(self, sql):
        lines = self.execute("SELECT COALESCE(json_agg(row_to_json(seed_q)), '[]'::json)::text FROM (" + sql + ") seed_q")
        require(len(lines) == 1, "PostgreSQL JSON 응답 형식이 다릅니다.")
        return decode(lines[0])

    def close(self):
        if self.process.poll() is None:
            try:
                self.process.stdin.write("\\q\n")
                self.process.stdin.flush()
                self.process.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                self.process.terminate()
                self.process.wait(timeout=5)
        self.errors.close()


class ClickHouse:
    def __init__(self, settings):
        self.settings = settings
        self.database = settings["database"]
        if "passwordEnv" in settings:
            require(settings["passwordEnv"] in os.environ, "지정한 ClickHouse 비밀번호 환경변수가 없습니다.")

    def request(self, sql, body=None, query_id=None, read_only=False, log_query_cut=None):
        settings = {
            "database": self.database, "wait_end_of_query": "1", "date_time_input_format": "best_effort",
            "output_format_json_quote_decimals": "1", "max_threads": "1", "max_execution_time": "60",
            "max_result_rows": "100000", "max_result_bytes": str(32 * 1024 * 1024),
            "max_rows_to_read": "1000000", "max_bytes_to_read": str(256 * 1024 * 1024),
            "result_overflow_mode": "throw", "read_overflow_mode": "throw",
        }
        if read_only:
            settings["readonly"] = "2"
        if log_query_cut is not None:
            require(read_only and body is None and log_query_cut == RECONCILE_LOG_BYTES,
                    "로그 길이 제한은 읽기 전용 행 대조 요청에만 허용합니다.")
            settings.update({"log_queries_cut_to_length": str(log_query_cut),
                             "log_queries": "1", "log_query_settings": "1"})
        if query_id:
            settings["query_id"] = query_id
        if body is not None:
            settings["query"] = sql
        payload = body if body is not None else sql.encode()
        url = self.settings["url"].rstrip("/") + "/?" + urllib.parse.urlencode(settings)
        headers = {"Content-Type": "application/octet-stream", "X-ClickHouse-User": self.settings.get("username", "default")}
        if "passwordEnv" in self.settings:
            headers["X-ClickHouse-Key"] = os.environ[self.settings["passwordEnv"]]
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=payload, headers=headers), timeout=90) as response:
                data = response.read(32 * 1024 * 1024 + 1)
                require(len(data) <= 32 * 1024 * 1024, "ClickHouse 응답 제한을 넘었습니다.")
                return data.decode()
        except urllib.error.HTTPError as error:
            error_body = error.read(512).decode(errors="replace")
            code = re.search(r"Code:\s*(\d+)", error_body)
            raise SeedError("ClickHouse HTTP " + str(error.code) + (" / Code " + code[1] if code else "")) from error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise SeedError("ClickHouse 연결/응답 실패. 성공 여부를 확인한 뒤 resume해야 합니다.") from error

    def query(self, sql, query_id=None, log_query_cut=None):
        return [decode(line) for line in self.request(sql + " FORMAT JSONEachRow", read_only=True,
                query_id=query_id, log_query_cut=log_query_cut).splitlines() if line]


def compare_value(expected, actual, data_type="", key=""):
    if expected is None or actual is None:
        return expected is None and actual is None
    if "timestamp" in data_type:
        # PostgreSQL timestamptz는 6자리(은행가 반올림), CH DateTime64는 9자리를 유지한다.
        return instant(str(expected)).quantize(Decimal("0.000001"), rounding=ROUND_HALF_EVEN) == instant(str(actual))
    if "DateTime" in data_type or key in TIMES:
        return instant(str(expected)) == instant(str(actual))
    if data_type == "uuid":
        return uuid.UUID(str(expected)) == uuid.UUID(str(actual))
    if data_type in ("json", "jsonb"):
        return (decode(expected) if isinstance(expected, str) else expected) == actual
    if "Float" in data_type:
        return float(expected) == float(actual)
    if isinstance(expected, (int, Decimal)) and not isinstance(expected, bool):
        try:
            return Decimal(str(expected)) == Decimal(str(actual))
        except Exception:
            return False
    if isinstance(expected, list):
        return isinstance(actual, list) and len(expected) == len(actual) and all(compare_value(e, a) for e, a in zip(expected, actual))
    return expected == actual


def missing_rows(expected, actual, identity, column_types):
    indexed = {}
    for row in actual:
        key = row[identity]
        require(key not in indexed, "같은 ID의 물리적 중복 행이 있습니다: " + str(key))
        indexed[key] = row
    require(set(indexed) <= {r[identity] for r in expected}, "예상하지 않은 행이 반환됐습니다.")
    missing = []
    for row in expected:
        other = indexed.get(row[identity])
        if other is None:
            missing.append(row)
            continue
        for key, value in row.items():
            require(key in other and compare_value(value, other[key], column_types.get(key, ""), key), "저장된 행 내용이 다릅니다: " + str(row[identity]) + "/" + key)
    return missing


def pressure_check(sample, now, expected=None):
    guard = sample.get("pressureGuard")
    require(isinstance(guard, dict) and guard.get("schemaVersion") == 1,
            "컨테이너 memory.events 압력 관측 계약이 없습니다.")
    baseline, identity = guard.get("baseline"), guard.get("identity")
    require(isinstance(baseline, dict) and isinstance(identity, dict), "압력 관측 기준 또는 identity가 없습니다.")
    fixed = {"identity": identity, "baseline": baseline}
    if expected is not None and expected != fixed:
        raise PressurePaused("실행 중 컨테이너 identity 또는 고정 memory.events 기준이 변경되었습니다.")
    require(set(identity) == {"containerId", "startedAt"} and isinstance(identity["containerId"], str)
            and 1 <= len(identity["containerId"]) <= 256, "압력 관측 container identity가 유효하지 않습니다.")
    require(isinstance(identity.get("startedAt"), str) and isinstance(baseline.get("observedAt"), str),
            "압력 관측 기준 시각이 없습니다.")
    try:
        valid_times = instant(identity["startedAt"]) <= instant(baseline["observedAt"]) <= instant(sample["observedAt"]) <= now + 2
    except (ValueError, TypeError, ArithmeticError) as error:
        raise SeedError("압력 관측 기준 시각이 유효하지 않습니다.") from error
    require(valid_times, "압력 관측 기준 시각이 유효하지 않습니다.")
    require(type(guard.get("latched")) is bool, "압력 중단 latch 상태가 없습니다.")
    for counters in (baseline.get("memoryEvents"), guard.get("memoryEvents")):
        require(isinstance(counters, dict) and all(type(counters.get(k)) is int and counters[k] >= 0
                for k in ("max", "oom", "oom_kill")), "memory.events 누적 카운터가 없습니다.")
    if guard["latched"]:
        raise PressurePaused("ClickHouse 압력 관측이 latch되었습니다. 원인 확인 없이 재개할 수 없습니다.")
    for key in ("max", "oom", "oom_kill"):
        if guard["memoryEvents"][key] != baseline["memoryEvents"][key]:
            raise PressurePaused("ClickHouse memory.events." + key + " 카운터가 고정 기준에서 변경되었습니다.")
    return fixed


def monitor_check(path, now=None, require_pressure=False, expected_pressure=None):
    sample = decode(read_regular(Path(path), 65536))
    now = Decimal(str(time.time() if now is None else now))
    age = now - instant(sample["observedAt"])
    require(-2 <= age <= MONITOR_MAX_AGE, "부하 관측이 없거나 오래되었습니다.")
    if "validUntil" in sample:
        require(now <= instant(sample["validUntil"]), "부하 관측 원본 지표의 유효 시간이 지났습니다.")
    used, limit = Decimal(str(sample["memoryWorkingSetBytes"])), Decimal(str(sample["memoryLimitBytes"]))
    latency, baseline = Decimal(str(sample["readLatencyMs"])), Decimal(str(sample["baselineReadLatencyMs"]))
    require(used >= 0 and limit > 0 and latency >= 0 and baseline > 0, "부하 관측 값이 유효하지 않습니다.")
    pressure = pressure_check(sample, now, expected_pressure) if require_pressure or "pressureGuard" in sample else None
    if used / limit >= Decimal("0.75"):
        raise Paused("ClickHouse 메모리 75% 중지선에 도달했습니다.")
    if latency >= baseline * 2:
        raise Paused("기준 조회 지연의 2배 중지선에 도달했습니다.")
    return {"memoryWorkingSetBytes": int(used), "memoryLimitBytes": int(limit), "readLatencyMs": latency,
            **({"pressureBaseline": pressure} if pressure is not None else {})}


def atomic_json(path, value):
    path = Path(path)
    fd, temporary = tempfile.mkstemp(prefix=".seed-", dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as file:
            file.write(encode(value) + "\n")
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class Runner:
    def __init__(self, artifact, target, state_dir, pg, ch, sleep=time.sleep):
        self.artifact, self.target, self.pg, self.ch, self.sleep = artifact, target, pg, ch, sleep
        self.directory = Path(state_dir).absolute()
        self.state_path = self.directory / "checkpoint.json"
        self.state = None
        self.pg_columns, self.ch_columns = {}, {}
        self.importer_sha = sha256(Path(__file__).read_bytes())

    def save(self):
        self.state["updatedAt"] = now_iso()
        atomic_json(self.state_path, self.state)

    def emit(self, event, **fields):
        print(encode({"event": event, "at": now_iso(), **fields}), flush=True)

    def verification_journal(self, record):
        # 전체 SQL은 immutable manifest와 열 목록으로 복원한다. 서버에는 4KiB만 기록한다.
        path = self.directory / "verification-queries.jsonl"
        flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND | getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(path, flags, 0o600)
        with os.fdopen(fd, "a") as file:
            file.write(encode({"at": now_iso(), **record}) + "\n")
            file.flush()
            os.fsync(file.fileno())

    def reconciliation_query(self, sql, batch, projection):
        query_id = "soma-seed-verify-" + self.artifact.sha[:16] + "-" + uuid.uuid4().hex
        record = {"queryId": query_id, "manifestSha256": self.artifact.sha, "importerSha256": self.importer_sha,
                  "batchId": batch["id"], "table": batch["table"], "database": self.ch.database,
                  "projection": projection, "sqlSha256": sha256((sql + " FORMAT JSONEachRow").encode()),
                  "sqlBytes": len((sql + " FORMAT JSONEachRow").encode()), "serverLogCutBytes": RECONCILE_LOG_BYTES}
        self.verification_journal({**record, "status": "sent"})
        try:
            result = self.ch.query(sql, query_id=query_id, log_query_cut=RECONCILE_LOG_BYTES)
        except Exception as error:
            self.verification_journal({**record, "status": "failed", "errorType": type(error).__name__})
            raise
        self.verification_journal({**record, "status": "finished", "resultRows": len(result)})
        return result

    def archive_current_error(self):
        if "lastError" in self.state:
            entry = {"message": self.state["lastError"], "pausedAt": self.state.get("pausedAt")}
            history = self.state.setdefault("errorHistory", [])
            if not history or history[-1] != entry:
                history.append(entry)

    def clear_current_error(self):
        # 이전 버전의 checkpoint에 이력이 없어도 마지막 실패를 버리지 않는다.
        self.archive_current_error()
        self.state.pop("lastError", None)
        self.state.pop("pausedAt", None)

    @contextlib.contextmanager
    def locked(self):
        require(not self.directory.is_symlink(), "state 디렉터리 심볼릭 링크는 허용하지 않습니다.")
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.directory, 0o700)
        path = self.directory / "run.lock"
        require(not path.is_symlink(), "lock 심볼릭 링크는 허용하지 않습니다.")
        with open(path, "a") as lock:
            os.chmod(path, 0o600)
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise SeedError("같은 state 디렉터리의 시드 실행이 진행 중입니다.") from error
            # 서로 다른 로컬 targetId를 사용해도 같은 실제 tenant는 직렬화한다.
            key = int(sha256(("historical-seed:" + self.artifact.tenant).encode())[:15], 16)
            require(self.pg.query("SELECT pg_try_advisory_lock(" + str(key) + ") AS locked")[0]["locked"], "같은 데이터셋의 DB 시드 실행이 진행 중입니다.")
            try:
                yield
            finally:
                with contextlib.suppress(Exception):
                    self.pg.execute("SELECT pg_advisory_unlock(" + str(key) + ")")

    def identity(self):
        pg = self.pg.query("SELECT current_database() AS database, current_user AS username, inet_server_addr()::text AS address, inet_server_port() AS port, (SELECT oid::bigint FROM pg_database WHERE datname=current_database()) AS database_oid")[0]
        require(pg["database"] == self.target.data["postgres"]["dbname"], "PostgreSQL 대상 database가 다릅니다.")
        ch = self.ch.query("SELECT hostName() AS hostname, currentDatabase() AS database, version() AS version")[0]
        require(ch["database"] == self.target.data["clickhouse"]["database"], "ClickHouse 대상 database가 다릅니다.")
        return {"postgres": pg, "clickhouse": ch}

    def schema(self):
        names = ",".join(pg_literal(x) for x in self.artifact.tables)
        columns = self.pg.query("SELECT table_schema||'.'||table_name AS table_name,column_name,data_type,udt_name,datetime_precision FROM information_schema.columns WHERE table_schema||'.'||table_name IN (" + names + ")")
        for column in columns:
            if "timestamp" in column["data_type"]:
                require(column["datetime_precision"] == 6, "PostgreSQL timestamp 정밀도가 6이 아닙니다: " + column["table_name"] + "." + column["column_name"])
            self.pg_columns.setdefault(column["table_name"], {})[column["column_name"]] = column["data_type"]
        for table, rows in self.artifact.tables.items():
            require(table in self.pg_columns, "필요한 PostgreSQL 테이블이 없습니다: " + table)
            require(set().union(*(r.keys() for r in rows)) <= self.pg_columns[table].keys(), "PostgreSQL 컬럼이 부족합니다: " + table)
            for key in ("id", "tenant_id", "member_id", "team_id", "installation_id", "contract_id", "seat_assignment_id"):
                if key in self.pg_columns[table]:
                    require(self.pg_columns[table][key] == "uuid", "PostgreSQL 식별자 타입이 다릅니다: " + table + "." + key)
        require(self.pg_columns["enrollment.tenants"]["sso_enabled"] == "boolean", "PostgreSQL SSO 타입이 다릅니다.")
        if self.artifact.activation_rules:
            require(self.pg_columns[ALERT_RULE_TABLE].get("enabled") == "boolean" and self.pg_columns[ALERT_RULE_TABLE].get("version") == "bigint", "PostgreSQL 알림 준비 계약의 enabled/version 타입이 다릅니다.")
        for table, expected in self.artifact.batch_columns.items():
            engine = self.ch.query("SELECT engine FROM system.tables WHERE database=" + ch_literal(self.ch.database) + " AND name=" + ch_literal(table))
            require(len(engine) == 1 and engine[0]["engine"] == "ReplacingMergeTree", "ClickHouse 테이블 엔진이 다릅니다: " + table)
            actual = self.ch.query("DESCRIBE TABLE " + self.ch.database + "." + table)
            self.ch_columns[table] = {r["name"]: r["type"] for r in actual}
            require(expected <= self.ch_columns[table].keys(), "ClickHouse 컬럼이 부족합니다: " + table)
            for field, required_type in CH_REQUIRED_TYPES[table].items():
                require(self.ch_columns[table].get(field, "").replace(" ", "") == required_type.replace(" ", ""), "ClickHouse 핵심 컬럼 타입이 다릅니다: " + table + "." + field)
        self.pg.query("SELECT tenant_id,deleted_before FROM telemetry_ops.tenant_retention_boundary LIMIT 0")
        self.ch.query("SELECT tenant_id,deleted_before FROM " + self.ch.database + ".telemetry_retention_fence LIMIT 0")

    def retention(self, rows=None):
        tenant = self.artifact.tenant
        pg = self.pg.query("SELECT deleted_before FROM telemetry_ops.tenant_retention_boundary WHERE tenant_id=" + pg_literal(tenant))
        ch = self.ch.query("SELECT maxOrNull(deleted_before) AS boundary FROM " + self.ch.database + ".telemetry_retention_fence WHERE tenant_id=" + ch_literal(tenant))[0]["boundary"]
        boundaries = [instant(r["deleted_before"]) for r in pg if r["deleted_before"] is not None]
        if ch is not None:
            boundaries.append(instant(ch))
        if boundaries:
            earliest = min(instant(r["source_time"]) for r in rows) if rows else min(instant(b["timeMin"]) for b in self.artifact.batches if b["table"] == "telemetry_events")
            require(earliest >= max(boundaries), "보존 삭제 경계보다 오래된 과거 시드는 적재할 수 없습니다.")

    def monitor(self):
        path = self.target.data.get("monitorFile")
        if path:
            if self.state is not None and self.state.get("pressureStopLatched"):
                raise PressurePaused("이 실행의 cgroup 압력 중단이 latch되어 있습니다. 기준을 재설정해 우회할 수 없습니다.")
            try:
                sample = monitor_check(path, require_pressure=True,
                        expected_pressure=self.state.get("pressureBaseline") if self.state is not None else None)
                if self.state is not None:
                    fixed = sample["pressureBaseline"]
                    if "pressureBaseline" in self.state and self.state["pressureBaseline"] != fixed:
                        raise PressurePaused("실행 중 컨테이너 identity 또는 고정 memory.events 기준이 변경되었습니다.")
                    if "pressureBaseline" not in self.state:
                        self.state["pressureBaseline"] = fixed
                        self.save()
            except PressurePaused:
                if self.state is not None:
                    self.state["pressureStopLatched"] = True
                    self.save()
                raise
            if self.state is not None:
                self.state["peakMemoryWorkingSetBytes"] = max(self.state.get("peakMemoryWorkingSetBytes", 0), sample["memoryWorkingSetBytes"])
                self.state["latestMonitor"] = sample
            return sample
        require(self.target.data["environment"] == "local", "dev 관측 파일이 없습니다.")

    def collision(self):
        t = self.artifact.tenant
        return self.pg.query("SELECT id::text,name,slug,sso_enabled FROM enrollment.tenants WHERE id=" + pg_literal(t) + " OR slug='soma' OR name='soma'")

    def plan(self):
        self.schema()
        self.retention()
        result = {"manifestSha256": self.artifact.sha, "targetSha256": self.target.sha, "identity": self.identity(), "collisions": self.collision(), "summary": self.artifact.manifest["summary"], "batchCount": len(self.artifact.batches), "payloadBytes": sum(b["bytes"] for b in self.artifact.batches),
                  "activation": {"stagedRules": self.artifact.activation_rules is not None, "unsafeLegacyEnabledRules": self.unsafe_legacy_rules(), "applyAllowed": not self.unsafe_legacy_rules()}}
        self.emit("plan", **result)
        return result

    def unsafe_legacy_rules(self):
        if self.artifact.activation_rules is not None:
            return []
        return [row["rule_id"] for row in self.artifact.tables.get(ALERT_RULE_TABLE, []) if row["enabled"]]

    def require_safe_write(self):
        require(not self.unsafe_legacy_rules(), "activationRules 없는 구 manifest에 활성 알림 규칙이 있습니다. 부분 적재 중 평가를 막기 위해 apply/resume/activate-login을 거부합니다. --stage-alert-rules true로 새 artifact와 state를 준비하세요. 기존 적재는 verify로만 확인할 수 있습니다.")

    def load_state(self, command):
        identity = self.identity()
        if self.state_path.exists():
            state = decode(read_regular(self.state_path))
            require(state.get("manifestSha256") == self.artifact.sha and state.get("targetSha256") == self.target.sha, "실행 manifest 또는 대상 설정이 달라졌습니다.")
            require(state.get("identity") == identity, "DB identity가 기존 실행과 다릅니다. 대상 변경을 조사하세요.")
            self.state = state
            if command == "apply":
                require(state["phase"] in ("verified", "active"), "미완료 실행은 resume 명령으로 재개하세요.")
        else:
            require(command == "apply", "기존 checkpoint가 없습니다. 최초 실행은 apply입니다.")
            require(not self.collision(), "같은 soma 이름·slug·ID의 기존 조직이 있습니다.")
            for table, (time_column, _) in CH_TABLES.items():
                count = self.ch.query("SELECT count() AS n FROM " + self.ch.database + "." + table + " WHERE tenant_id=" + ch_literal(self.artifact.tenant))[0]["n"]
                require(int(count) == 0, "실행 기록 없는 ClickHouse 데이터가 있습니다.")
            self.state = {"schemaVersion": 1, "manifestSha256": self.artifact.sha, "targetSha256": self.target.sha, "identity": identity, "phase": "prepared", "pgCommitted": False, "batches": {}, "ackedRequests": 0, "pilotObserved": False, "startedAt": now_iso()}
            self.save()

    def pg_scope(self, table):
        tenant = pg_literal(self.artifact.tenant)
        columns = self.pg_columns[table]
        if table == "enrollment.tenants":
            return "id=" + tenant
        if "tenant_id" in columns:
            return "tenant_id=" + tenant
        if "installation_id" in columns:
            return "installation_id IN (SELECT id FROM enrollment.installations WHERE tenant_id=" + tenant + ")"
        if "member_id" in columns:
            return "member_id IN (SELECT id FROM enrollment.members WHERE tenant_id=" + tenant + ")"
        if "seat_assignment_id" in columns:
            return "seat_assignment_id IN (SELECT id FROM enrollment.seat_assignments WHERE tenant_id=" + tenant + ")"
        if "contract_id" in columns:
            return "contract_id IN (SELECT id FROM enrollment.contracts WHERE tenant_id=" + tenant + ")"
        raise SeedError("tenant 범위를 정할 수 없는 PostgreSQL 테이블: " + table)

    def verify_postgres(self, activation_active=None):
        if activation_active is None:
            activation_active = self.state["phase"] == "active"
            if self.state["phase"] == "activating":
                # COMMIT 응답 유실은 SSO와 규칙이 모두 준비 상태이거나 모두 활성 상태일 때만 복구한다.
                tenant = self.pg.query("SELECT sso_enabled FROM enrollment.tenants WHERE id=" + pg_literal(self.artifact.tenant))
                require(len(tenant) == 1 and type(tenant[0]["sso_enabled"]) is bool, "활성화 확인 대상 조직이 없습니다.")
                activation_active = tenant[0]["sso_enabled"]
        for table, expected in self.artifact.tables.items():
            self.monitor()
            if table == ALERT_RULE_TABLE and self.artifact.activation_rules is not None and activation_active:
                expected = [dict(row, enabled=self.artifact.activation_rules[row["rule_id"]]["desiredEnabled"]) for row in expected]
            actual = self.pg.query("SELECT * FROM " + table + " WHERE " + self.pg_scope(table))
            require(len(actual) == len(expected), "PostgreSQL 행 수 불일치: " + table)
            # 복합 키 테이블도 모든 생성 필드를 비교한다. 단 실제 SSO가 연결한 subject는 보존한다.
            fields = set(expected[0])
            if table == "enrollment.members":
                fields -= {"oidc_subject"}
            if table == "enrollment.tenants":
                fields -= {"sso_enabled"}
            def key(row):
                values = []
                for field in sorted(fields):
                    if field not in ("id", "tenant_id", "installation_id", "member_id", "team_id", "seat_assignment_id", "contract_id", "version", "rule_id", "manifest_id", "assigned_at", "joined_at", "from_at", "received_at", "vendor_id", "run_id"):
                        continue
                    value = row.get(field)
                    data_type = self.pg_columns[table].get(field, "")
                    if value is not None and data_type == "uuid":
                        value = str(uuid.UUID(str(value)))
                    elif value is not None and "timestamp" in data_type:
                        value = instant(str(value)).quantize(Decimal("0.000001"), rounding=ROUND_HALF_EVEN)
                    values.append(value)
                return tuple(values)
            indexed = {}
            for row in actual:
                indexed.setdefault(key(row), []).append(row)
            for row in expected:
                candidates = indexed.get(key(row), [])
                # timestamp 표현이 키에 포함된 경우 SQL의 정규화된 표기와 달라질 수 있다.
                if not candidates:
                    candidates = actual
                found = [other for other in candidates if all(k in other and compare_value(row[k], other[k], self.pg_columns[table].get(k, ""), k) for k in fields)]
                if len(found) != 1:
                    closest = max(candidates, key=lambda other: sum(k in other and compare_value(row[k], other[k], self.pg_columns[table].get(k, ""), k) for k in fields), default={})
                    mismatch = next((k for k in sorted(fields) if k not in closest or not compare_value(row[k], closest[k], self.pg_columns[table].get(k, ""), k)), "duplicate")
                    raise SeedError("PostgreSQL 행 내용 불일치: " + table + "." + mismatch)
            if table == "enrollment.tenants":
                require(actual[0]["sso_enabled"] is activation_active, "SSO 활성화 상태가 checkpoint와 다릅니다.")

    def apply_postgres(self):
        self.require_safe_write()
        existing = self.collision()
        if existing:
            require(len(existing) == 1 and existing[0]["id"] == self.artifact.tenant and existing[0]["slug"] == "soma" and existing[0]["name"] == "soma", "다른 soma 조직과 충돌합니다.")
            self.verify_postgres()
            self.state["pgCommitted"] = True
            self.save()
            return
        require(not self.state.get("pgCommitted"), "기존 PostgreSQL 시드가 사라졌습니다.")
        self.monitor()
        self.pg.execute("BEGIN")
        try:
            for table, rows in self.artifact.tables.items():
                for offset in range(0, len(rows), 200):
                    statements = []
                    for row in rows[offset:offset + 200]:
                        if table == "telemetry_ops.tenant_ingest_summary":
                            setters = ",".join(identifier(k) + "=" + pg_literal(v) for k, v in row.items() if k != "tenant_id")
                            statements.append("UPDATE " + table + " SET " + setters + " WHERE tenant_id=" + pg_literal(self.artifact.tenant))
                        else:
                            statements.append("INSERT INTO " + table + " (" + ",".join(identifier(k) for k in row) + ") VALUES (" + ",".join(pg_literal(v) for v in row.values()) + ")")
                    self.pg.execute(";\n".join(statements))
            # 트리거 생성 summary UPDATE가 실제 한 행에 적용됐는지도 commit 전에 검증한다.
            self.verify_postgres()
            self.pg.execute("COMMIT")
        except Exception:
            with contextlib.suppress(Exception):
                self.pg.execute("ROLLBACK")
            raise
        self.state["pgCommitted"] = True
        self.state["phase"] = "loading"
        self.save()
        self.emit("postgres_committed")

    def wait_finished(self, query_id):
        # HTTP timeout 뒤 서버 쿼리가 계속 실행 중이면 재전송하지 않는다.
        active = self.ch.query("SELECT query_id FROM system.processes WHERE query_id=" + ch_literal(query_id))
        require(not active, "이전 배치 쿼리가 서버에서 실행 중입니다. 완료 후 resume하세요.")

    def reconcile(self, batch, rows):
        identity = batch["identityColumn"]
        fields = [identifier(k) for k in rows[0] if k != identity]
        ids = ",".join(ch_literal(r[identity]) for r in rows)
        condition = "tenant_id=" + ch_literal(self.artifact.tenant) + " AND " + batch["timeColumn"] + ">=parseDateTime64BestEffort(" + ch_literal(batch["timeMin"]) + ",9,'UTC') AND " + batch["timeColumn"] + "<=parseDateTime64BestEffort(" + ch_literal(batch["timeMax"]) + ",9,'UTC') AND " + batch["identityColumn"] + " IN (" + ids + ")"
        # 100개 이상의 열 리더를 동시에 열지 않도록 identity 포함 16열씩 순차 대조한다.
        # FINAL 없이 매번 물리 중복을 검사하고, 조회 사이 행 집합이 바뀌면 재전송하지 않는다.
        absent = None
        for offset in range(0, len(fields), RECONCILE_COLUMNS - 1):
            self.monitor()
            projected = [identity, *fields[offset:offset + RECONCILE_COLUMNS - 1]]
            expected = [{key: row[key] for key in projected} for row in rows]
            sql = "SELECT " + ",".join(projected) + " FROM " + self.ch.database + "." + batch["table"] + " PREWHERE " + condition + " LIMIT " + str(len(rows) + 1) + " SETTINGS max_block_size=512, preferred_block_size_bytes=1048576"
            actual = self.reconciliation_query(sql, batch, projected)
            current_absent = {row[identity] for row in missing_rows(expected, actual, identity, self.ch_columns[batch["table"]])}
            require(absent is None or absent == current_absent, "열별 검증 중 저장 행 집합이 바뀌었습니다. 다시 확인한 뒤 재개하세요.")
            absent = current_absent
            if len(absent) == len(rows):
                break
        return [row for row in rows if row[identity] in absent]

    def insert_payloads(self, table, rows):
        types = self.ch_columns[table]
        fields = list(rows[0])
        def wire_type(name):
            original = types[name]
            if original == "Float64":
                return "String"
            if original == "Nullable(Float64)":
                return "Nullable(String)"
            if original == "Array(Float64)":
                return "Array(String)"
            return original
        structure = ",".join(k + " " + wire_type(k) for k in fields)
        selections = []
        for k in fields:
            if types[k] in ("Float64", "Nullable(Float64)"):
                selections.append("toFloat64(" + k + ")")
            elif types[k] == "Array(Float64)":
                selections.append("arrayMap(x -> toFloat64(x)," + k + ")")
            else:
                selections.append(k)
        fence = ""
        if table == "telemetry_events":
            fence = " WHERE source_time >= ifNull((SELECT maxOrNull(deleted_before) FROM " + self.ch.database + ".telemetry_retention_fence WHERE tenant_id=" + ch_literal(self.artifact.tenant) + "),toDateTime64('1900-01-01 00:00:00',6,'UTC'))"
        sql = "INSERT INTO " + self.ch.database + "." + table + " (" + ",".join(fields) + ") SELECT " + ",".join(selections) + " FROM input(" + ch_literal(structure) + ")" + fence + " SETTINGS async_insert=0, input_format_skip_unknown_fields=0, precise_float_parsing=1 FORMAT JSONEachRow"
        payload, count = [], 0
        size = 0
        for row in rows:
            wire = dict(row)
            for k in fields:
                if types[k] in ("Float64", "Nullable(Float64)") and wire[k] is not None:
                    wire[k] = str(wire[k])
                elif types[k] == "Array(Float64)":
                    wire[k] = [str(v) for v in wire[k]]
            encoded = encode(wire).encode()
            require(len(encoded) <= MAX_BYTES, "단일 행이 전송 제한을 넘었습니다.")
            additional = len(encoded) + (1 if payload else 0)
            if count == MAX_ROWS or size + additional > MAX_BYTES:
                yield sql, b"\n".join(payload), count
                payload, count, size = [], 0, 0
                additional = len(encoded)
            payload.append(encoded)
            count += 1
            size += additional
        if payload:
            yield sql, b"\n".join(payload), count

    def pilot(self):
        if self.state["ackedRequests"] >= 5 and not self.state["pilotObserved"]:
            self.emit("pilot_observation", seconds=30)
            self.sleep(30)
            self.monitor()
            self.state["pilotObserved"] = True
            self.save()

    def load_batches(self):
        for batch in self.artifact.batches:
            self.monitor()
            rows = self.artifact.batch_rows(batch)
            progress = self.state["batches"].setdefault(batch["id"], {})
            if progress.get("queryId"):
                self.wait_finished(progress["queryId"])
            missing = self.reconcile(batch, rows)
            if batch["table"] == "telemetry_events":
                self.retention(rows)
            self.pilot()
            for sql, body, count in self.insert_payloads(batch["table"], missing) if missing else []:
                self.monitor()
                query_id = "soma-seed-" + self.artifact.sha[:16] + "-" + uuid.uuid4().hex
                progress.update({"status": "sent", "queryId": query_id, "sentAt": now_iso()})
                self.save()
                # 잠금 소유 PG 세션이 끊겼으면 ledger를 포함한 다음 CH 쓰기도 멈춘다.
                require(self.pg.query("SELECT 1 AS alive")[0]["alive"] == 1, "PostgreSQL 잠금 세션을 확인하지 못했습니다.")
                before = time.monotonic()
                self.ch.request(sql, body, query_id=query_id)
                latency = Decimal(str(round((time.monotonic() - before) * 1000, 3)))
                progress.update({"status": "acked", "ackLatencyMs": latency})
                self.state["ackedRequests"] += 1
                self.save()
                self.emit("batch_ack", batch=batch["id"], rows=count, bytes=len(body), latencyMs=latency)
                self.sleep(1)
                self.pilot()
            require(not self.reconcile(batch, rows), "ACK 뒤 저장 행이 부족합니다: " + batch["id"])
            progress["status"] = "verified"
            self.save()
        self.state["phase"] = "loaded"
        self.save()

    def verify_clickhouse(self):
        for index, batch in enumerate(self.artifact.batches, 1):
            self.monitor()
            rows = self.artifact.batch_rows(batch)
            progress = self.state["batches"].get(batch["id"], {})
            if progress.get("queryId"):
                self.wait_finished(progress["queryId"])
            require(not self.reconcile(batch, rows), "검증 중 누락된 행이 있습니다: " + batch["id"])
            self.sleep(1)
            if index % 10 == 0 or index == len(self.artifact.batches):
                self.emit("verification_progress", completedBatches=index, totalBatches=len(self.artifact.batches))
        for table, (time_column, _) in CH_TABLES.items():
            groups = {}
            for batch in self.artifact.batches:
                if batch["table"] == table:
                    key = dt.datetime.fromtimestamp(float(instant(batch["timeMin"])), dt.timezone.utc).strftime("%Y-%m")
                    groups.setdefault(key, []).append(batch)
            total_expected = 0
            for month, batches in groups.items():
                self.monitor()
                start = month + "-01 00:00:00"
                year, mon = map(int, month.split("-"))
                end = dt.datetime(year + (mon == 12), mon % 12 + 1, 1).strftime("%Y-%m-%d %H:%M:%S")
                expected = sum(b["rows"] for b in batches)
                condition = "tenant_id=" + ch_literal(self.artifact.tenant) + " AND " + time_column + ">=toDateTime64(" + ch_literal(start) + ",9,'UTC') AND " + time_column + "<toDateTime64(" + ch_literal(end) + ",9,'UTC')"
                observed = int(self.ch.query("SELECT count() AS n FROM " + self.ch.database + "." + table + " WHERE " + condition)[0]["n"])
                require(observed == expected, "월별 원천 행 수 또는 물리 중복 불일치: " + table + "/" + month)
                total_expected += expected
                self.sleep(1)
            total = int(self.ch.query("SELECT count() AS n FROM " + self.ch.database + "." + table + " WHERE tenant_id=" + ch_literal(self.artifact.tenant))[0]["n"])
            require(total == total_expected, "기간 밖 또는 예상하지 않은 원천 행이 있습니다: " + table)

    def verify(self):
        self.retention()
        self.verify_postgres()
        self.verify_clickhouse()
        if self.state["phase"] not in ("active", "activating"):
            self.state["phase"] = "verified"
        self.state["verifiedAt"] = now_iso()
        self.clear_current_error()
        self.save()
        self.emit("verified", summary=self.artifact.manifest["summary"], peakMemoryWorkingSetBytes=self.state.get("peakMemoryWorkingSetBytes"))

    def activate(self):
        self.require_safe_write()
        require(self.state["phase"] in ("verified", "activating", "active"), "전체 검증 완료 후에만 SSO를 활성화할 수 있습니다.")
        self.verify()
        if self.state["phase"] == "active":
            return
        already_enabled = self.collision()
        if self.state["phase"] == "activating" and len(already_enabled) == 1 and already_enabled[0]["id"] == self.artifact.tenant and already_enabled[0]["sso_enabled"]:
            self.state["phase"] = "active"
            self.state["activatedAt"] = now_iso()
            self.save()
            self.emit("login_activation_reconciled", tenantId=self.artifact.tenant)
            return
        oidc = self.artifact.manifest["oidc"]
        self.state["phase"] = "activating"
        self.save()
        self.pg.execute("BEGIN")
        try:
            tenant = pg_literal(self.artifact.tenant)
            # 앱 전체를 멈추지 않고 이 조직의 변경만 잠근다. version/updated_at/updated_by는 보존한다.
            self.pg.query("SELECT id FROM enrollment.tenants WHERE id=" + tenant + " FOR UPDATE")
            self.pg.query("SELECT rule_id FROM " + ALERT_RULE_TABLE + " WHERE tenant_id=" + tenant + " FOR UPDATE")
            self.verify_postgres(activation_active=False)
            rules = self.artifact.activation_rules
            if rules:
                values = ",".join("(" + pg_literal(rule) + "," + pg_literal(entry["desiredEnabled"]) + "," + str(entry["expectedVersion"]) + ")" for rule, entry in rules.items())
                # DML CTE는 query()의 하위 FROM에 감싸지 않고 최상위 문장으로 실행한다.
                lines = self.pg.execute("WITH changed AS (UPDATE " + ALERT_RULE_TABLE + " AS r SET enabled=desired.enabled FROM (VALUES " + values + ") AS desired(rule_id,enabled,version) WHERE r.tenant_id=" + tenant + " AND r.rule_id=desired.rule_id AND r.version=desired.version AND NOT r.enabled RETURNING r.rule_id) SELECT COALESCE(json_agg(row_to_json(changed)),'[]'::json)::text FROM changed")
                require(len(lines) == 1, "알림 규칙 활성화 응답 형식이 다릅니다.")
                changed = decode(lines[0])
                require(len(changed) == len(rules) and {row["rule_id"] for row in changed} == set(rules), "알림 규칙의 준비 상태·판이 달라졌습니다.")
            lines = self.pg.execute("WITH changed AS (UPDATE enrollment.tenants SET sso_enabled=true WHERE id=" + tenant + " AND name='soma' AND slug='soma' AND NOT sso_enabled AND oidc_require_verified_email AND oidc_issuer=" + pg_literal(oidc["issuer"]) + " AND oidc_client_id=" + pg_literal(oidc["clientId"]) + " AND oidc_client_secret_ref=" + pg_literal(oidc["clientSecretRef"]) + " RETURNING id::text) SELECT COALESCE(json_agg(row_to_json(changed)),'[]'::json)::text FROM changed")
            require(len(lines) == 1, "SSO 활성화 응답 형식이 다릅니다.")
            result = decode(lines[0])
            require(len(result) == 1 and result[0]["id"] == self.artifact.tenant, "SSO 최초 활성화 대상이 정확히 한 조직이 아닙니다.")
            self.verify_postgres(activation_active=True)
            self.pg.execute("COMMIT")
        except Exception:
            with contextlib.suppress(Exception):
                self.pg.execute("ROLLBACK")
            raise
        self.state["phase"] = "active"
        self.state["activatedAt"] = now_iso()
        self.save()
        self.emit("login_activated", tenantId=self.artifact.tenant)

    def run(self, command):
        if command == "plan":
            return self.plan()
        if command in ("apply", "resume", "activate-login"):
            self.require_safe_write()
        with self.locked():
            self.schema()
            self.retention()
            self.load_state(command)
            try:
                self.monitor()
                if command in ("apply", "resume"):
                    if self.state["phase"] == "activating":
                        return self.activate()
                    if self.state["phase"] in ("verified", "active"):
                        return self.verify()
                    self.apply_postgres()
                    self.load_batches()
                    self.verify()
                elif command == "verify":
                    self.verify()
                elif command == "activate-login":
                    self.activate()
            except Exception as error:
                self.archive_current_error()
                self.state["lastError"] = str(error)[:500]
                self.state["pausedAt"] = now_iso()
                self.archive_current_error()
                self.save()
                raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "apply", "resume", "verify", "activate-login"))
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--state-dir", required=True)
    args = parser.parse_args(argv)
    os.umask(0o077)
    pg = None
    try:
        artifact = Artifact(args.manifest)
        target = Target(args.target, artifact)
        pg = Postgres(target.data["postgres"])
        Runner(artifact, target, args.state_dir, pg, ClickHouse(target.data["clickhouse"])).run(args.command)
        return 0
    except (SeedError, ValueError, KeyError, OSError) as error:
        print(encode({"event": "paused", "message": str(error)}), file=sys.stderr)
        return 2
    finally:
        if pg:
            pg.close()


if __name__ == "__main__":
    sys.exit(main())
