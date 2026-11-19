"""Kafka 기본 partitioner의 key 배정 — 설치를 partition마다 고르게 고르는 데 쓴다.

Gateway producer는 partitioner를 지정하지 않고 String key `"<tenantId>:<installationId>"`를 보낸다
(`GatewayConfig`, `TelemetryRecordKey`). kafka-clients의 `BuiltInPartitioner.partitionForKey`는
`Utils.toPositive(Utils.murmur2(UTF-8 key)) % partitions`다. 아래는 `Utils.murmur2`를 그대로 옮긴 것이다.
"""
from __future__ import annotations

import uuid

_SEED = 0x9747B28C
_M = 0x5BD1E995
_MASK = 0xFFFFFFFF


def _to_int32(value: int) -> int:
    value &= _MASK
    return value - 0x1_0000_0000 if value & 0x8000_0000 else value


def murmur2(data: bytes) -> int:
    """Java `Utils.murmur2`와 같은 부호 있는 32비트 값."""
    length = len(data)
    h = (_SEED ^ length) & _MASK
    for i in range(0, length - length % 4, 4):
        k = data[i] | (data[i + 1] << 8) | (data[i + 2] << 16) | (data[i + 3] << 24)
        k = (k * _M) & _MASK
        k ^= k >> 24
        k = (k * _M) & _MASK
        h = (h * _M) & _MASK
        h ^= k
    tail = length & ~3
    remainder = length % 4
    if remainder == 3:
        h ^= data[tail + 2] << 16
    if remainder >= 2:
        h ^= data[tail + 1] << 8
    if remainder >= 1:
        h ^= data[tail]
        h = (h * _M) & _MASK
    h ^= h >> 13
    h = (h * _M) & _MASK
    h ^= h >> 15
    return _to_int32(h)


def partition_for_key(key: str, partitions: int) -> int:
    if partitions < 1:
        raise ValueError(f"partition 수는 1 이상이다: {partitions}")
    return (murmur2(key.encode("utf-8")) & 0x7FFFFFFF) % partitions


def record_key(tenant_id: uuid.UUID | str, installation_id: uuid.UUID | str) -> str:
    """`TelemetryRecordKey.of` — `"<tenantId>:<installationId>"`."""
    return f"{tenant_id}:{installation_id}"
