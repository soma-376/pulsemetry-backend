package com.team376.pulsemetry.telemetry.adapter.observation

import com.google.protobuf.MessageOrBuilder
import java.math.BigDecimal
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * `analysis_hash` — 정규화 단계 출력의 canonical 결과를 SHA-256 한 64자리 소문자 hex(ADR 0020 §3). **진단
 * 컬럼이지 키가 아니다.** 같은 원본·같은 `normalizer_rev` 에서 값이 다르면 정규화가 결정적이지 않다는 뜻이다.
 *
 * 재료는 관측 타입의 모든 필드다 — 분류·품질·측정값·metadata·계산 근거. 다음은 **뺀다**:
 * `analysis_hash` 자신, 서버 수신 시각(`received_time`), 영수증에서 온 값(`archive_ref`·`archive_selector`·
 * `masking_version`, 그리고 영수증이 없다는 사실인 `archive_receipt_missing` 플래그), 조직 보강이 붙이는 플래그
 * (`member_unresolved`·`multi_team_membership`). 조직 보강의 다섯 컬럼과 `row_version` 은 관측 타입에 없으므로 애초에
 * 재료가 아니다. 보강 플래그를 빼는 이유도 같다 — 소속 편집 뒤 재처리한 두 행의 hash 가 같아야 한다.
 *
 * `metadata_json` 은 문자열 그대로가 아니라 경계마다 속성을 canonical 순서로 정렬한 뒤 쓴다 — 속성 순서만 다른
 * 두 결과는 같은 hash 다. 중복 개수는 남는다. 돈은 값이 같으면 같은 표기다(`0.020` 과 `0.02`).
 *
 * 필드를 이름순으로 읽으므로 관측 타입에 필드를 더하면 자동으로 재료가 된다.
 */
public object AnalysisHash {

	/** 계산 전 자리를 채우는 값. 재료에서 빠지는 필드라 hash 에 영향이 없다. */
	public val PLACEHOLDER: String = "0".repeat(64)

	private val EXCLUDED = setOf("analysisHash", "receivedTime", "archiveRef", "archiveSelector", "maskingVersion")

	/** 정규화 뒤 단계가 붙이는 플래그 — 영수증 부재와 조직 보강의 결과다. */
	private val EXCLUDED_FLAGS = setOf(
		QualityFlag.ARCHIVE_RECEIPT_MISSING,
		QualityFlag.MEMBER_UNRESOLVED,
		QualityFlag.MULTI_TEAM_MEMBERSHIP,
	)

	public fun of(observation: EventObservation): String = Canonical.sha256Hex(encode(observation))

	public fun of(observation: MetricPointObservation): String = Canonical.sha256Hex(encode(observation))

	/** hash 를 계산해 봉투에 넣은 사본. */
	public fun seal(observation: EventObservation): EventObservation =
		observation.copy(envelope = observation.envelope.copy(analysisHash = of(observation)))

	public fun seal(observation: MetricPointObservation): MetricPointObservation =
		observation.copy(envelope = observation.envelope.copy(analysisHash = of(observation)))

	internal fun encode(value: Any): String = StringBuilder().also { write(it, value) }.toString()

	private fun write(out: StringBuilder, value: Any?) {
		when (value) {
			null -> out.append("null")
			is WireValue -> Canonical.writeString(out, value.wire)
			is EpochNanos -> out.append(value.value)
			is String -> Canonical.writeString(out, value)
			is Boolean -> out.append(value)
			is Int, is Long -> out.append(value.toString())
			is UInt -> out.append(value.toString())
			is ULong -> out.append(value.toString())
			is Double -> Canonical.writeNumber(out, value)
			is BigDecimal -> Canonical.writeString(out, value.stripTrailingZeros().toPlainString())
			is List<*> -> value.joinTo(out, ",", "[", "]") { element -> StringBuilder().also { write(it, element) } }
			is Map<*, *> -> {
				out.append('{')
				value.entries.sortedBy { it.key as String }.forEachIndexed { index, (key, v) ->
					if (index > 0) out.append(',')
					Canonical.writeString(out, key as String)
					out.append(':')
					write(out, v)
				}
				out.append('}')
			}
			is MessageOrBuilder -> out.append(Canonical.encode(value))
			is ObservationEnvelope, is EventObservation, is MetricPointObservation -> writeObject(out, value)
			else -> throw IllegalArgumentException("analysis_hash 재료로 옮길 수 없는 값: ${value::class}")
		}
	}

	private fun writeObject(out: StringBuilder, value: Any) {
		@Suppress("UNCHECKED_CAST")
		val type = value::class as KClass<Any>
		val names = type.primaryConstructor!!.parameters.map { it.name!! }.toSet()
		val properties = type.memberProperties.filter { it.name in names && it.name !in EXCLUDED }.sortedBy { it.name }
		out.append('{')
		properties.forEachIndexed { index, property ->
			if (index > 0) out.append(',')
			Canonical.writeString(out, property.name)
			out.append(':')
			val v = property.get(value)
			when (property.name) {
				"metadataJson" -> writeMetadata(out, v as String)
				"qualityFlags" -> write(out, (v as List<*>).filter { it !in EXCLUDED_FLAGS })
				else -> write(out, v)
			}
		}
		out.append('}')
	}

	/** 경계마다 속성을 canonical 텍스트 순으로 정렬한다. exemplar 는 point 순서가 의미라 목록 순서를 둔다. */
	private fun writeMetadata(out: StringBuilder, json: String) {
		val metadata = TypedMetadata.fromJson(json)
		fun section(attributes: List<TypedAttribute>): String =
			attributes.map { TypedMetadata(record = listOf(it)).toJson() }.sorted().joinToString(",", "[", "]")
		out.append("{\"exemplars\":")
		metadata.exemplars.joinTo(out, ",", "[", "]") { section(it) }
		out.append(",\"record\":").append(section(metadata.record))
		out.append(",\"resource\":").append(section(metadata.resource))
		out.append(",\"scope\":").append(section(metadata.scope))
		out.append('}')
	}
}
