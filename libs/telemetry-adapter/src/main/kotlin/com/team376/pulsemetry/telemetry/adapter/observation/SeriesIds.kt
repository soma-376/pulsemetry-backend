package com.team376.pulsemetry.telemetry.adapter.observation

import com.google.protobuf.Message
import com.google.protobuf.MessageOrBuilder

/** [SeriesIds.of] 의 결과. [seriesId] 가 null 이면 [status] 는 `missing` 이다. */
public data class SeriesIdentity(
	val seriesId: String?,
	val status: SeriesIdentityStatus,
)

/**
 * metric point 의 `series_id` 와 `series_identity_status`(ADR 0020 §4).
 *
 * 재료: tenant·installation, resource(producer)와 그 schema URL, scope 와 그 schema URL, metric 의 이름·타입·단위·
 * temporality·monotonic, **원본 point 차원**(point 속성). 설명·값·point 시각·파생 family 는 재료가 아니다.
 *
 * ## 상태
 *
 * - `missing` — 데이터 종류가 없거나 이름이 비어 series 를 말할 수 없다. `series_id` 는 null.
 * - `unverified` — 기본값. 특히 **마스킹이 가린 차원**(resource·scope·point 속성의 문자열 안에 [MASK] 가 있음)이
 *   있으면 서로 다른 series 가 같은 ID 로 합쳐질 수 있으므로, 프로파일 검증과 무관하게 여기 머문다.
 * - `ambiguous` — 같은 point 차원 키가 서로 다른 값으로 두 번 이상 온다.
 * - `verified` — 보존한 차원이 정본 구분을 유지함을 검증한 프로파일이 요청했고, 위 어느 것에도 걸리지 않을 때만.
 *
 * `unverified`·`ambiguous` 도 ID 는 만든다 — 진단에 쓰고 자동 합산하지 않는다.
 */
public object SeriesIds {

	public const val SERIES_VERSION: String = "series-v1"

	/**
	 * 수집 단계가 비밀을 덮을 때 쓰는 문자열. 이 값이 차원에 있으면 원래 값이 무엇이었는지 알 수 없다.
	 * 수집 단계의 마스킹 규칙(ADR 0012 · `MaskingRules`)과 같은 값이어야 한다.
	 */
	public const val MASK: String = "****"

	public fun of(
		tenantId: String,
		installationId: String,
		resource: MessageOrBuilder?,
		resourceSchemaUrl: String,
		scope: MessageOrBuilder?,
		scopeSchemaUrl: String,
		metric: Message,
		pointIndex: Int,
		profileVerified: Boolean = false,
	): SeriesIdentity {
		val data = MetricPoints.dataField(metric)
		val name = metric.getField(metric.descriptorForType.findFieldByName("name")) as String
		if (data == null || name.isEmpty()) return SeriesIdentity(null, SeriesIdentityStatus.MISSING)

		val body = metric.getField(data) as MessageOrBuilder
		val bodyType = body.descriptorForType
		val point = body.getRepeatedField(bodyType.findFieldByName("data_points"), pointIndex) as MessageOrBuilder
		val attributes = point.getField(point.descriptorForType.findFieldByName("attributes")) as List<*>

		val out = StringBuilder("{")
		out.append("\"dimensions\":[")
		attributes.map { Canonical.encode(it as MessageOrBuilder) }.sorted().joinTo(out, ",")
		out.append("],\"installation_id\":")
		Canonical.writeString(out, installationId)
		out.append(",\"metric\":{\"monotonic\":")
		out.append(optionalScalar(body, "is_monotonic"))
		out.append(",\"name\":")
		Canonical.writeString(out, name)
		out.append(",\"temporality\":")
		out.append(optionalScalar(body, "aggregation_temporality"))
		out.append(",\"type\":")
		Canonical.writeString(out, data.name)
		out.append(",\"unit\":")
		Canonical.writeString(out, metric.getField(metric.descriptorForType.findFieldByName("unit")) as String)
		out.append("},\"resource\":")
		out.append(if (resource == null) "null" else Canonical.encode(resource))
		out.append(",\"resource_schema_url\":")
		Canonical.writeString(out, resourceSchemaUrl)
		out.append(",\"scope\":")
		out.append(if (scope == null) "null" else Canonical.encode(scope))
		out.append(",\"scope_schema_url\":")
		Canonical.writeString(out, scopeSchemaUrl)
		out.append(",\"series_version\":")
		Canonical.writeString(out, SERIES_VERSION)
		out.append(",\"tenant_id\":")
		Canonical.writeString(out, tenantId)
		out.append('}')
		val seriesId = Canonical.sha256Hex(out.toString())

		val masked = listOfNotNull(resource, scope, point).any { containsMask(it) }
		val status = when {
			hasConflictingDimensions(attributes) -> SeriesIdentityStatus.AMBIGUOUS
			masked -> SeriesIdentityStatus.UNVERIFIED
			profileVerified -> SeriesIdentityStatus.VERIFIED
			else -> SeriesIdentityStatus.UNVERIFIED
		}
		return SeriesIdentity(seriesId, status)
	}

	/** 그 종류에 필드가 없으면 `null`, 있으면 값(proto3 기본값이어도 값이다 — temporality 0 은 UNSPECIFIED). */
	private fun optionalScalar(body: MessageOrBuilder, fieldName: String): String {
		val field = body.descriptorForType.findFieldByName(fieldName) ?: return "null"
		return when (val value = body.getField(field)) {
			is com.google.protobuf.Descriptors.EnumValueDescriptor -> value.number.toString()
			else -> value.toString()
		}
	}

	private fun hasConflictingDimensions(attributes: List<*>): Boolean =
		attributes.map { it as MessageOrBuilder }
			.groupBy { it.getField(it.descriptorForType.findFieldByName("key")) as String }
			.values.any { group -> group.map { Canonical.encode(it) }.toSet().size > 1 }

	/** 메시지 안의 어떤 문자열 필드라도 [MASK] 를 담고 있는가. */
	private fun containsMask(message: MessageOrBuilder): Boolean =
		message.allFields.any { (field, value) ->
			val values = if (field.isRepeated) value as List<*> else listOf(value)
			values.any { element ->
				when (element) {
					is String -> MASK in element
					is MessageOrBuilder -> containsMask(element)
					else -> false
				}
			}
		}
}
