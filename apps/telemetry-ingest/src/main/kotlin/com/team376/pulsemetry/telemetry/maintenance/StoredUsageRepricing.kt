package com.team376.pulsemetry.telemetry.maintenance

import com.team376.pulsemetry.telemetry.adapter.observation.*
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CodeburnReferencePrices
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor

/** 이미 검증된 정규화 행의 가격만 다시 평가한다. 원본·토큰 의미·소속·수신 시각은 재작성하지 않는다. */
internal object StoredUsageRepricing {
	data class Row(val event: EnrichedEvent, val version: RowVersioning)
	private val pricing = PricingStage(CodeburnReferencePrices.PROFILE)
	private val models = listOf(ObservationEnvelope::class, EventObservation::class, OrgAttribution::class)
	private val fields = models.flatMap { type -> type.primaryConstructor!!.parameters.mapNotNull { it.name } }
		.filter { it != "envelope" }.map(::column).toSet() + setOf("row_version", "normalizer_rev")

	/** 빠진 열을 기본값으로 채우지 않으며, 미지의 열도 버리지 않는다. 64비트 값은 Double을 거치지 않는다. */
	fun decode(json: JsonNode): Row {
		require(json.isObject && json.properties().map { it.key }.toSet() == fields) { "저장 행의 열 집합이 현재 계약과 다르다" }
		val envelope = construct(ObservationEnvelope::class, json)
		val observation = construct(EventObservation::class, json, mapOf("envelope" to envelope))
		val org = construct(OrgAttribution::class, json)
		val version = RowVersioning(numberText(json["normalizer_rev"]).toUInt(), numberText(json["row_version"]).toULong())
		require(AnalysisHash.of(observation) == envelope.analysisHash) { "저장 행의 정규화 해시가 일치하지 않는다" }
		return Row(EnrichedEvent(observation, org), version)
	}

	fun reprice(row: Row): Row {
		val source = row.event.observation
		require(source.eventType == EventType.MODEL_RESPONSE_USAGE && source.envelope.usageRole == UsageRole.PRIMARY) { "주 사용량 관측만 재평가한다" }
		require(source.envelope.recordStatus == RecordStatus.ACTIVE) { "제외된 관측은 재평가하지 않는다" }
		require(row.version.normalizerRev in 7u..RowVersioning.NORMALIZER_REV) { "지원하지 않는 정규화 판이다" }
		val priced = AnalysisHash.seal(pricing.apply(source))
		require(priced.costEstimatedUsd != null) { "환산할 수 없는 행이 남아 있어 전체 실행을 중단한다" }
		val version = RowVersioning.of(RowVersioning.NORMALIZER_REV, row.version.rowVersion.toUInt())
		if (row.version.normalizerRev == RowVersioning.NORMALIZER_REV) {
			// 저장 Decimal의 고정 scale은 산식의 scale과 달라도 같은 값이다. 봉인 해시는 그 차이를 정규화한다.
			require(priced.envelope.analysisHash == source.envelope.analysisHash) { "같은 정규화 판의 결과를 덮어쓸 수 없다" }
			return row
		}
		return Row(row.event.copy(observation = priced), version)
	}

	private fun column(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

	private fun <T : Any> construct(type: KClass<T>, json: JsonNode, supplied: Map<String, Any> = emptyMap()): T {
		val constructor = type.primaryConstructor!!
		return constructor.callBy(constructor.parameters.associateWith { parameter ->
			supplied[parameter.name] ?: convert(json[column(parameter.name!!)], parameter.type)
		})
	}

	private fun convert(node: JsonNode, type: KType): Any? {
		if (node.isNull) {
			require(type.isMarkedNullable) { "필수 열에 null이 있다" }
			return null
		}
		val kind = type.classifier as KClass<*>
		return when (kind) {
			String::class -> { require(node.isString); node.asString() }
			Long::class -> numberText(node).toLong()
			Int::class -> numberText(node).toInt()
			Boolean::class -> { require(node.isBoolean); node.asBoolean() }
			BigDecimal::class -> BigDecimal(numberText(node))
			EpochNanos::class -> EpochNanos.of(Instant.parse(node.asString().replace(' ', 'T').let { if (it.endsWith('Z')) it else it + "Z" }))
			List::class -> { require(node.isArray); (0 until node.size()).map { convert(node[it], type.arguments.single().type!!) } }
			Map::class -> { require(node.isObject); node.properties().associate { it.key to convert(it.value, type.arguments[1].type!!) } }
			DecisionSource::class -> DecisionSource(node.asString())
			DecisionScope::class -> DecisionScope(node.asString())
			ErrorType::class -> ErrorType(node.asString())
			StopReason::class -> StopReason(node.asString())
			ReasoningEffort::class -> ReasoningEffort(node.asString())
			else -> {
				require(kind.java.isEnum && WireValue::class.java.isAssignableFrom(kind.java)) { "지원하지 않는 저장 타입이다: ${kind.qualifiedName}" }
				kind.java.enumConstants.single { (it as WireValue).wire == node.asString() }
			}
		}
	}

	private fun numberText(node: JsonNode): String {
		require(node.isString || node.isNumber) { "숫자 열의 타입이 다르다" }
		return if (node.isString) node.asString() else node.toString()
	}
}
