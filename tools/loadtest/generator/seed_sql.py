#!/usr/bin/env python3
"""부하 캠페인 전용 tenant의 시드·확인·폐기 SQL 생성기(ADR 0082 Decision 2·3·5).

    python3 tools/loadtest/generator/seed_sql.py --campaign <id> --installations 60 --partitions 6 \\
        --secret-file <path> --identities-out <json> [--revoke | --verify]

stdout은 SQL, stderr는 tenant ID와 partition 분포다. DB에 접속하지 않는다 — 사람이 `psql -v ON_ERROR_STOP=1`로 적용한다.
SQL에는 `pit_` 원문이 없고 `sha256_hex(pit_)`만 있다. `--identities-out`에는 비밀이 없다 — k6가 이 파일과 시드 비밀로 `pit_`을 다시 만든다.

넣는 행의 컬럼·제약은 Flyway V1(+V8·V28·V29·V30)과 `TelemetryCredentialAuthorizer`의 판정을 따른다.
설치·회원·조직은 active여야 ingest가 받는다. 팀 소속은 하루 전부터라 보강의 as-of 판정이 팀을 찾는다.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import sys
import tempfile
import uuid

if __package__ in (None, ""):  # 스크립트로 실행할 때 — tools/loadtest를 경로에 넣어 패키지로 가져온다.
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generator import identities  # noqa: E402
from generator.kafka_partition import partition_for_key, record_key  # noqa: E402

MAX_INSTALLATIONS = 10_000
MAX_PARTITIONS = 1_000
# partition마다 같은 수를 채울 때까지 후보를 늘린다. 이 배수 안에서 못 채우면 실패로 본다(실제로는 수 배면 찬다).
CANDIDATE_FACTOR = 100


@dataclass(frozen=True)
class Seat:
    """설치 하나와 그 주인 구성원. `ordinal`은 0부터의 순번, `candidate`는 설치 ID를 만든 후보 순번이다."""

    ordinal: int
    candidate: int
    installation_id: uuid.UUID
    partition: int
    member_id: uuid.UUID
    membership_id: uuid.UUID
    invitation_id: uuid.UUID
    credential_id: uuid.UUID


@dataclass(frozen=True)
class Plan:
    campaign: str
    tenant_id: uuid.UUID
    team_id: uuid.UUID
    partitions: int
    seats: tuple

    @property
    def tenant_name(self) -> str:
        return identities.tenant_name(self.campaign)

    @property
    def owner_id(self) -> uuid.UUID:
        return self.seats[0].member_id

    def distribution(self) -> list:
        counts = [0] * self.partitions
        for seat in self.seats:
            counts[seat.partition] += 1
        return counts


def build_plan(campaign: str, installations: int, partitions: int) -> Plan:
    """partition마다 정확히 N/P개의 설치를 고른다. 후보 순번 0, 1, 2, …의 설치 ID를 차례로 배정해 본다."""
    identities.validate_campaign(campaign)
    if not 1 <= installations <= MAX_INSTALLATIONS:
        raise ValueError(f"설치 수는 1~{MAX_INSTALLATIONS}이다: {installations}")
    if not 1 <= partitions <= MAX_PARTITIONS:
        raise ValueError(f"partition 수는 1~{MAX_PARTITIONS}이다: {partitions}")
    if installations % partitions:
        raise ValueError(f"설치 수({installations})가 partition 수({partitions})의 배수가 아니다 — 고르게 나눌 수 없다")

    tenant = identities.tenant_id(campaign)
    per_partition = installations // partitions
    counts = [0] * partitions
    chosen = []
    candidate = 0
    limit = installations * CANDIDATE_FACTOR
    while len(chosen) < installations:
        if candidate >= limit:
            raise RuntimeError(f"후보 {limit}개 안에서 partition을 고르게 채우지 못했다: {counts}")
        installation = identities.entity_id(campaign, "installation", candidate)
        partition = partition_for_key(record_key(tenant, installation), partitions)
        if counts[partition] < per_partition:
            counts[partition] += 1
            chosen.append((candidate, installation, partition))
        candidate += 1

    seats = tuple(
        Seat(
            ordinal=ordinal,
            candidate=cand,
            installation_id=installation,
            partition=partition,
            member_id=identities.entity_id(campaign, "member", ordinal),
            membership_id=identities.entity_id(campaign, "team-membership", ordinal),
            invitation_id=identities.entity_id(campaign, "invitation", ordinal),
            credential_id=identities.entity_id(campaign, "installation-credential", ordinal),
        )
        for ordinal, (cand, installation, partition) in enumerate(chosen)
    )
    return Plan(campaign=campaign, tenant_id=tenant, team_id=identities.team_id(campaign), partitions=partitions, seats=seats)


def _q(value: object) -> str:
    """SQL 문자열 리터럴. 값은 UUID·hex·검증된 캠페인 ID뿐이지만 그래도 따옴표를 이스케이프한다."""
    return "'" + str(value).replace("'", "''") + "'"


def _label(plan: Plan, seat: Seat) -> str:
    width = max(3, len(str(len(plan.seats))))
    return str(seat.ordinal + 1).zfill(width)


def _values(rows: list) -> str:
    return ",\n".join("    (" + ", ".join(row) + ")" for row in rows)


def _guard_tenant(plan: Plan) -> str:
    message = f"부하 tenant {plan.tenant_id} ({plan.tenant_name})가 없다 — 캠페인 ID를 확인한다"
    return (
        "DO $$ BEGIN\n"
        f"    IF NOT EXISTS (SELECT 1 FROM enrollment.tenants WHERE id = {_q(plan.tenant_id)} AND name = {_q(plan.tenant_name)}) THEN\n"
        f"        RAISE EXCEPTION {_q(message)};\n"
        "    END IF;\n"
        "END $$;\n"
    )


def _header(plan: Plan, purpose: str) -> str:
    return (
        f"-- {purpose} — 캠페인 {plan.campaign}, tenant {plan.tenant_id} ({plan.tenant_name}), "
        f"설치 {len(plan.seats)}개, partition {plan.partitions}개.\n"
        "-- tools/loadtest/generator/seed_sql.py가 만든 파일이다(ADR 0082). 손으로 고치지 않는다.\n"
    )


def _planned_pairs(plan: Plan, secret: bytes) -> str:
    """계획한 (installation_id, credential_hash) 쌍 — 확인·커밋 전 검사가 설치별로 대조한다."""
    rows = []
    for seat in plan.seats:
        token = identities.installation_token(secret, plan.campaign, seat.installation_id)
        rows.append(f"({_q(seat.installation_id)}::uuid, {_q(identities.sha256_hex(token))})")
    return "(VALUES " + ", ".join(rows) + ") AS p(installation_id, credential_hash)"


def _matched_credentials(plan: Plan, secret: bytes) -> str:
    """활성 자격증명 중 계획한 쌍과 설치·해시가 모두 맞는 설치 수. 해시가 바뀌거나 빠진 설치는 세지 않는다."""
    return (
        "SELECT count(DISTINCT c.installation_id) FROM enrollment.installation_credentials c JOIN "
        + _planned_pairs(plan, secret)
        + " ON c.installation_id = p.installation_id AND c.credential_hash = p.credential_hash WHERE c.revoked_at IS NULL"
    )


def _guard_existing_campaign(plan: Plan) -> str:
    """이미 있는 캠페인이면 설치 집합이 계획과 같을 때만 계속한다(설치 수 변경 거부). 처음 시드는 통과한다."""
    tenant = _q(plan.tenant_id)
    planned = ", ".join(f"{_q(seat.installation_id)}::uuid" for seat in plan.seats)
    message = (
        f"부하 tenant {plan.tenant_id}의 기존 설치 집합이 계획(설치 {len(plan.seats)}개)과 다르다 — "
        "다른 설치 수로 시드했다. 새 캠페인 ID를 쓴다"
    )
    return (
        "DO $$ BEGIN\n"
        f"    IF EXISTS (SELECT 1 FROM enrollment.tenants WHERE id = {tenant}) AND (\n"
        f"        (SELECT count(*) FROM enrollment.installations WHERE tenant_id = {tenant}) <> {len(plan.seats)}\n"
        f"        OR EXISTS (SELECT 1 FROM enrollment.installations WHERE tenant_id = {tenant} AND id NOT IN ({planned}))\n"
        "    ) THEN\n"
        f"        RAISE EXCEPTION {_q(message)};\n"
        "    END IF;\n"
        "END $$;\n\n"
    )


def seed_sql(plan: Plan, secret: bytes) -> str:
    """한 트랜잭션의 시드. 같은 입력은 다시 적용해도 안전하다(`ON CONFLICT (id) DO NOTHING`).
    설치 수가 다르거나(INSERT 전 검사), 시드 비밀이 다르거나(커밋 전 설치별 해시 대조), 폐기한 캠페인이면 롤백한다.
    충돌 대상을 id로 한정해 credential_hash 같은 다른 UNIQUE 충돌은 조용히 넘기지 않고 오류로 낸다."""
    tenant, team, owner = _q(plan.tenant_id), _q(plan.team_id), _q(plan.owner_id)
    members, memberships, invitations, installations, credentials = [], [], [], [], []
    for seat in plan.seats:
        label = _label(plan, seat)
        member = _q(seat.member_id)
        role = "'owner'" if seat.ordinal == 0 else "'member'"
        email = _q(f"{plan.tenant_name}-{label}@example.invalid")
        members.append([member, tenant, email, _q(f"loadtest {label}"), role, "'active'"])
        memberships.append([_q(seat.membership_id), team, member, "now() - interval '1 day'"])
        code_hash = identities.invitation_code_hash(secret, plan.campaign, seat.invitation_id)
        invitations.append([_q(seat.invitation_id), tenant, member, owner, _q(code_hash), "now()", "now() + interval '72 hours'"])
        installations.append([
            _q(seat.installation_id), tenant, member, _q(seat.invitation_id), _q(f"loadtest-{label}"),
            "'linux'", "'arm64'", "'loadtest-v1'", "'active'",
        ])
        token = identities.installation_token(secret, plan.campaign, seat.installation_id)
        credentials.append([_q(seat.credential_id), _q(seat.installation_id), _q(identities.sha256_hex(token))])

    expected = len(plan.seats)
    mismatch = (
        f"부하 tenant {plan.tenant_id}의 활성 설치가 {expected}개가 아니다 — 이미 폐기한 캠페인이다. 새 캠페인 ID를 쓴다"
    )
    wrong_secret = (
        f"부하 tenant {plan.tenant_id}의 설치별 자격증명 해시가 이 시드 비밀과 맞지 않는다 — 이 캠페인을 처음 시드한 비밀 파일을 쓴다"
    )
    return (
        _header(plan, "부하 tenant 시드")
        + "-- 재적용에 안전하다(ON CONFLICT (id) DO NOTHING). 설치 토큰 원문은 없다 — credential_hash(SHA-256)만 있다.\n"
        "BEGIN;\n"
        "SET LOCAL lock_timeout = '5s';\n"
        "SET LOCAL statement_timeout = '60s';\n\n"
        + _guard_existing_campaign(plan)
        + "INSERT INTO enrollment.tenants (id, name, status) VALUES\n"
        + _values([[tenant, _q(plan.tenant_name), "'active'"]]) + "\nON CONFLICT (id) DO NOTHING;\n\n"
        "INSERT INTO enrollment.teams (id, tenant_id, name, status) VALUES\n"
        + _values([[team, tenant, "'loadtest'", "'active'"]]) + "\nON CONFLICT (id) DO NOTHING;\n\n"
        "INSERT INTO enrollment.members (id, tenant_id, email, display_name, role, status) VALUES\n"
        + _values(members) + "\nON CONFLICT (id) DO NOTHING;\n\n"
        "-- 하루 전부터 소속 — 보강이 관측 시각 기준(joined_at <= t < left_at)으로 팀을 찾는다.\n"
        "INSERT INTO enrollment.team_memberships (id, team_id, member_id, joined_at) VALUES\n"
        + _values(memberships) + "\nON CONFLICT (id) DO NOTHING;\n\n"
        "-- 이미 소비한 초대다. code_hash는 시드 비밀에서 파생한 값이고 원문 코드는 없다.\n"
        "INSERT INTO enrollment.invitations (id, tenant_id, target_member_id, created_by_member_id, code_hash, used_at, expires_at) VALUES\n"
        + _values(invitations) + "\nON CONFLICT (id) DO NOTHING;\n\n"
        "INSERT INTO enrollment.installations (id, tenant_id, member_id, invitation_id, hostname, platform, architecture, client_version, status) VALUES\n"
        + _values(installations) + "\nON CONFLICT (id) DO NOTHING;\n\n"
        "INSERT INTO enrollment.installation_credentials (id, installation_id, credential_hash) VALUES\n"
        + _values(credentials) + "\nON CONFLICT (id) DO NOTHING;\n\n"
        + _guard_tenant(plan)
        + "DO $$ BEGIN\n"
        f"    IF (SELECT count(*) FROM enrollment.installations WHERE tenant_id = {tenant} AND status = 'active' AND revoked_at IS NULL) <> {expected} THEN\n"
        f"        RAISE EXCEPTION {_q(mismatch)};\n"
        "    END IF;\n"
        f"    IF ({_matched_credentials(plan, secret)}) <> {expected}\n"
        "       OR (SELECT count(*) FROM enrollment.installation_credentials c JOIN enrollment.installations i ON i.id = c.installation_id\n"
        f"           WHERE i.tenant_id = {tenant} AND c.revoked_at IS NULL) <> {expected} THEN\n"
        f"        RAISE EXCEPTION {_q(wrong_secret)};\n"
        "    END IF;\n"
        "END $$;\n"
        "COMMIT;\n"
    )


def verify_sql(plan: Plan, secret: bytes) -> str:
    """읽기만 하는 확인. 모든 행의 `ok`가 true여야 하고, 하나라도 아니면 마지막 DO 블록이 오류로 끝낸다
    (`psql -v ON_ERROR_STOP=1`이면 종료 코드 3). 마지막 행은 설치별로 시드 비밀의 해시가 맞는지 본다."""
    tenant = _q(plan.tenant_id)
    expected = len(plan.seats)
    tenant_rows = f"(SELECT id FROM enrollment.tenants WHERE id = {tenant} AND name = {_q(plan.tenant_name)})"
    installs = f"(SELECT id FROM enrollment.installations WHERE tenant_id IN {tenant_rows})"
    checks = [
        ("tenant_active", 1,
         f"SELECT count(*) FROM enrollment.tenants WHERE id IN {tenant_rows} AND status = 'active' AND deleted_at IS NULL"),
        ("team_active", 1, f"SELECT count(*) FROM enrollment.teams WHERE tenant_id IN {tenant_rows} AND status = 'active'"),
        ("member_active", expected, f"SELECT count(*) FROM enrollment.members WHERE tenant_id IN {tenant_rows} AND status = 'active'"),
        ("membership_open", expected,
         "SELECT count(*) FROM enrollment.team_memberships tm JOIN enrollment.members m ON m.id = tm.member_id "
         f"WHERE m.tenant_id IN {tenant_rows} AND tm.left_at IS NULL AND tm.joined_at <= now()"),
        ("invitation_used", expected, f"SELECT count(*) FROM enrollment.invitations WHERE tenant_id IN {tenant_rows} AND used_at IS NOT NULL"),
        ("installation_active", expected,
         f"SELECT count(*) FROM enrollment.installations WHERE tenant_id IN {tenant_rows} AND status = 'active' AND revoked_at IS NULL"),
        ("credential_active", expected,
         f"SELECT count(*) FROM enrollment.installation_credentials WHERE installation_id IN {installs} AND revoked_at IS NULL"),
        ("credential_matches_secret", expected, _matched_credentials(plan, secret) + f" AND c.installation_id IN {installs}"),
    ]
    union = "\nUNION ALL\n".join(
        f"SELECT {order} AS ord, {_q(kind)} AS kind, {want} AS expected, ({query}) AS actual"
        for order, (kind, want, query) in enumerate(checks, start=1)
    )
    failed = f"부하 tenant {plan.tenant_id} 확인 실패 — 위 표에서 ok가 f인 행을 본다"
    return (
        _header(plan, "부하 tenant 확인(읽기 전용)")
        + "SELECT kind, expected, actual, expected = actual AS ok FROM (\n"
        + union
        + "\n) checks ORDER BY ord;\n"
        + "DO $$ BEGIN\n"
        + "    IF EXISTS (SELECT 1 FROM (\n" + union + "\n    ) checks WHERE expected <> actual) THEN\n"
        + f"        RAISE EXCEPTION {_q(failed)};\n"
        + "    END IF;\n"
        + "END $$;\n"
    )


def revoke_sql(plan: Plan) -> str:
    """캠페인의 설치·자격 폐기. 재실행에 안전하다. 데이터는 지우지 않는다(retention-worker, ADR 0082 Decision 5)."""
    tenant = _q(plan.tenant_id)
    return (
        _header(plan, "부하 tenant 폐기")
        + "-- 배수(consumer lag 0)를 확인한 뒤 실행한다. 그 전에 실행하면 Kafka에 남은 record가\n"
        "-- installation_revoked 영구 실패가 된다(TelemetryCredentialAuthorizer). 폐기 뒤에는 토큰 교환도 403이다.\n"
        "BEGIN;\n"
        "SET LOCAL lock_timeout = '5s';\n"
        "SET LOCAL statement_timeout = '60s';\n"
        + _guard_tenant(plan)
        + "UPDATE enrollment.installation_credentials SET revoked_at = COALESCE(revoked_at, now())\n"
        f" WHERE installation_id IN (SELECT id FROM enrollment.installations WHERE tenant_id = {tenant});\n"
        "UPDATE enrollment.installations SET status = 'revoked', revoked_at = COALESCE(revoked_at, now()), updated_at = now()\n"
        f" WHERE tenant_id = {tenant} AND status <> 'revoked';\n"
        "COMMIT;\n"
    )


def identities_document(plan: Plan) -> dict:
    """k6 입력. 비밀·해시는 없다."""
    return {
        "campaign": plan.campaign,
        "tenantId": str(plan.tenant_id),
        "installations": [{"installationId": str(seat.installation_id), "partition": seat.partition} for seat in plan.seats],
    }


def write_identities(path: Path, plan: Plan) -> None:
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix=".identities-", suffix=".json", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(identities_document(plan), handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.chmod(temp, 0o644)
        os.replace(temp, path)
    except BaseException:
        Path(temp).unlink(missing_ok=True)
        raise


def _same_file(a: Path, b: Path) -> bool:
    """두 경로가 같은 파일인지(심볼릭 링크·상대 경로 포함). 출력 파일이 아직 없으면 경로만 비교한다."""
    try:
        return os.path.samefile(a, b)
    except FileNotFoundError:
        return a.resolve() == b.resolve()


def parse_args(argv: list) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="부하 캠페인 전용 tenant의 시드·확인·폐기 SQL을 stdout에 쓴다(ADR 0082).")
    parser.add_argument("--campaign", required=True, help="캠페인 ID, ^[a-z0-9-]{3,40}$")
    parser.add_argument("--installations", type=int, default=60, help="설치 수 N (기본 60)")
    parser.add_argument("--partitions", type=int, default=6, help="Kafka topic partition 수 P (기본 6). N은 P의 배수")
    parser.add_argument("--secret-file", type=Path, help="시드 비밀 32바이트 파일(저장소 밖). 시드·확인에 필요")
    parser.add_argument("--identities-out", type=Path, help="k6 입력 JSON 경로. 시드에 필요")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--revoke", action="store_true", help="설치·자격 폐기 SQL — 배수 후 실행")
    mode.add_argument("--verify", action="store_true", help="행 수·활성 상태를 세는 SELECT")
    args = parser.parse_args(argv)
    if not args.revoke and args.secret_file is None:
        parser.error("시드·확인에는 --secret-file이 필요하다")
    if not args.revoke and not args.verify and args.identities_out is None:
        parser.error("시드에는 --identities-out이 필요하다")
    return args


def main(argv: list | None = None, stdout=None, stderr=None) -> int:
    stdout = stdout or sys.stdout
    stderr = stderr or sys.stderr
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        plan = build_plan(args.campaign, args.installations, args.partitions)
        if args.revoke:
            sql = revoke_sql(plan)
        else:
            secret = identities.read_seed_secret(args.secret_file)
            if args.verify:
                sql = verify_sql(plan, secret)
            else:
                if _same_file(args.secret_file, args.identities_out):
                    raise ValueError("--identities-out이 --secret-file과 같은 파일이다 — 시드 비밀을 덮어쓰지 않도록 다른 경로를 준다")
                sql = seed_sql(plan, secret)
                write_identities(args.identities_out, plan)
    except (ValueError, RuntimeError, OSError) as error:
        print(f"실패: {error}", file=stderr)
        return 2
    stdout.write(sql)
    print(f"tenant {plan.tenant_id} ({plan.tenant_name})", file=stderr)
    print("partition 분포: " + " ".join(f"p{index}={count}" for index, count in enumerate(plan.distribution())), file=stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
