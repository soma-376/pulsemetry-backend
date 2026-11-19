"""Kafka 기본 partitioner 이식 검사.

기대값은 kafka-clients 4.2.1(Boot BOM이 고른 판)의 `Utils.murmur2`·`BuiltInPartitioner.partitionForKey`를 JDK 25로
직접 돌린 값이다. 앞의 여섯 개는 Kafka `UtilsTest.testMurmur2`의 공개 벡터와도 같다.
"""
from pathlib import Path
import sys
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generator.kafka_partition import murmur2, partition_for_key, record_key  # noqa: E402

# key → (murmur2, partitions=6, partitions=7)
VECTORS = {
    "21": (-973932308, 0, 3),
    "foobar": (-790332482, 0, 0),
    "a-little-bit-long-string": (-985981536, 2, 1),
    "a-little-bit-longer-string": (-1486304829, 5, 0),
    "lkjh234lh9fiuh90y23oiuhsafujhadof229phr9h19h89h8": (-58897971, 5, 3),
    "abc": (479470107, 3, 4),
    "": (275646681, 3, 2),
    "x": (2121525046, 4, 4),
    "한글": (-1593753763, 1, 2),
    "0e2e0000-0000-4000-8000-000000000001:0e2e0000-0000-4000-8000-000000000003": (-404710932, 2, 6),
}


class KafkaPartitionTest(unittest.TestCase):

    def test_murmur2_matches_kafka_clients(self):
        for key, (expected, _, _) in VECTORS.items():
            self.assertEqual(murmur2(key.encode("utf-8")), expected, key)

    def test_partition_for_key_matches_built_in_partitioner(self):
        for key, (_, six, seven) in VECTORS.items():
            self.assertEqual(partition_for_key(key, 6), six, key)
            self.assertEqual(partition_for_key(key, 7), seven, key)

    def test_partition_count_must_be_positive(self):
        with self.assertRaises(ValueError):
            partition_for_key("abc", 0)

    def test_record_key_matches_telemetry_record_key(self):
        tenant = uuid.UUID("0e2e0000-0000-4000-8000-000000000001")
        installation = uuid.UUID("0e2e0000-0000-4000-8000-000000000003")
        self.assertEqual(record_key(tenant, installation), "0e2e0000-0000-4000-8000-000000000001:0e2e0000-0000-4000-8000-000000000003")


if __name__ == "__main__":
    unittest.main()
