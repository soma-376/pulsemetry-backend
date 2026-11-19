"""부하 캠페인의 결정적 ID와 설치 토큰 파생(ADR 0082 Decision 2·3).

같은 캠페인·순번은 언제나 같은 UUID다. `pit_`은 시드 비밀의 HMAC으로 만들고 저장하지 않는다.
k6 `lib/auth.js`가 같은 계산을 JS로 한다 — 문자열 형식을 바꾸면 두 쪽과 테스트 벡터를 함께 고친다.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
from pathlib import Path
import re
import uuid

# uuid5(NAMESPACE_DNS, "loadtest.pulsemetry.invalid"). 바꾸면 모든 캠페인의 ID가 바뀐다.
NAMESPACE = uuid.UUID("836cb53f-6700-58a6-8ed2-be356c860698")
PREFIX = "pulsemetry-loadtest/v1"
CAMPAIGN_PATTERN = re.compile(r"^[a-z0-9-]{3,40}$")
SECRET_BYTES = 32
TOKEN_PREFIX = "pit_"


def validate_campaign(campaign: str) -> str:
    if not CAMPAIGN_PATTERN.fullmatch(campaign):
        raise ValueError(f"캠페인 ID는 ^[a-z0-9-]{{3,40}}$ 이어야 한다: {campaign!r}")
    return campaign


def tenant_name(campaign: str) -> str:
    return f"loadtest-{validate_campaign(campaign)}"


def entity_id(campaign: str, kind: str, index: int) -> uuid.UUID:
    """`uuid5(NAMESPACE, "pulsemetry-loadtest/v1/<campaign>/<kind>/<index>")`."""
    validate_campaign(campaign)
    if index < 0:
        raise ValueError(f"순번은 0 이상이다: {index}")
    return uuid.uuid5(NAMESPACE, f"{PREFIX}/{campaign}/{kind}/{index}")


def tenant_id(campaign: str) -> uuid.UUID:
    return entity_id(campaign, "tenant", 0)


def team_id(campaign: str) -> uuid.UUID:
    return entity_id(campaign, "team", 0)


def read_seed_secret(path: str | Path) -> bytes:
    """시드 비밀 파일의 원시 바이트. 정확히 32바이트여야 한다(`openssl rand 32 > <파일>`). 줄바꿈도 바이트로 센다."""
    data = Path(path).read_bytes()
    if len(data) != SECRET_BYTES:
        raise ValueError(f"시드 비밀은 정확히 {SECRET_BYTES}바이트여야 한다: {len(data)}바이트")
    return data


def _hmac(secret: bytes, message: str) -> bytes:
    if len(secret) != SECRET_BYTES:
        raise ValueError(f"시드 비밀은 정확히 {SECRET_BYTES}바이트여야 한다")
    return hmac.new(secret, message.encode("utf-8"), hashlib.sha256).digest()


def installation_token(secret: bytes, campaign: str, installation_id: uuid.UUID | str) -> str:
    """`"pit_" + base64url_nopad(HMAC_SHA256(secret, "pulsemetry-loadtest/v1/<campaign>/<installationId>"))`."""
    validate_campaign(campaign)
    digest = _hmac(secret, f"{PREFIX}/{campaign}/{installation_id}")
    return TOKEN_PREFIX + base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def invitation_code_hash(secret: bytes, campaign: str, invitation_id: uuid.UUID | str) -> str:
    """소비된 초대의 `code_hash`(SHA-256 hex 64자 자리). 원문 코드가 없어 쓸 수 없는 값이고, 비밀로 파생해 추측할 수도 없다."""
    validate_campaign(campaign)
    return _hmac(secret, f"{PREFIX}/{campaign}/invitation-code/{invitation_id}").hex()


def sha256_hex(value: str) -> str:
    """`installation_credentials.credential_hash`의 계산 — UTF-8 문자열 전체의 SHA-256 hex(`InstallationCredentialVerifier`)."""
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
