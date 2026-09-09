package com.team376.pulsemetry.telemetry.adapter

import java.security.MessageDigest

/**
 * 원본 레코드의 추적용 해시 — `"raw-" + sha1(canonical json)[..16]`.
 *
 * ## 왜 canonical JSON 인가
 *
 * 이식 원본은 **파싱한 JSON dict 를** `json.dumps(rec, sort_keys=True, ensure_ascii=False)`
 * 로 다시 적어 해시했다. 이 모듈의 입력은 protobuf 라 그 문자열이 남아 있지 않으므로,
 * [ProtoJson] 이 트리를 복원하고 [CanonicalJson] 이 같은 표기로 적는다.
 *
 * ## 이 값이 무엇이 아닌지
 *
 * **`record_id` 의 재료가 아니다.** ClickHouse ReplacingMergeTree 의 멱등 키는
 * [RecordId] 가 따로 만들고, 그 해시에는 이 값이 들어가지 않는다. 복원이 원본과 어긋나는
 * 문서(기본값을 명시한 문서)는 `source_record_id` 가 갈린다. 다만 optional·oneof 가 아닌
 * 스칼라 필드(`count`·`is_monotonic`·`aggregation_temporality`·`timeUnixNano`·
 * `startTimeUnixNano` 등)의 명시 기본값은 protobuf 가 부재와 구별하지 못하므로 그 문서는
 * **출력값과 `record_id` 도 갈릴 수 있다**(ADR 0013).
 */
internal object SourceRecordId {

	fun of(record: Map<String, Any?>): String =
		"raw-" + sha1Hex(CanonicalJson.encode(record)).substring(0, 16)

	internal fun sha1Hex(text: String): String {
		val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
		return buildString(digest.size * 2) {
			for (byte in digest) append("%02x".format(byte))
		}
	}
}
