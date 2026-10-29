package com.team376.pulsemetry.telemetry.maintenance

import com.team376.pulsemetry.telemetry.adapter.observation.*
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor

/**
 * 저장된 정규화 행을 분류하고, 토큰 의미를 믿을 수 있는 행의 가격만 다시 평가한다.
 * 원본·토큰·소속·수신 시각은 재작성하지 않는다. 의미나 원본이 없는 행은 추정으로 채우지 않고 사유만 남긴다.
 */
internal object StoredUsageRepricing {
	data class Row(val event: EnrichedEvent, val version: RowVersioning)

	/** 저장 행 하나의 처리. [target] 은 [Disposition.REPRICE]·[Disposition.CURRENT] 에만 있다. */
	data class Planned(val source: Row, val disposition: Disposition, val reason: Reason, val target: Row?)

	enum class Disposition(val wire: String) {
		/** 검증된 의미의 행 중 계산 금액이 달라진 행 — 가격만 현재 규칙으로 교체한다. */
		REPRICE("reprice"),
		/** 이미 현재 계산 금액과 같다. 정규화 번호가 달라도 쓰지 않는다. */
		CURRENT("current"),
		/** 토큰 의미를 믿을 수 없지만 원본 참조가 있다. 원본이 남아 있으면 재정규화로만 고칠 수 있다. */
		RENORMALIZE("renormalize"),
		/** 토큰 의미도 원본 참조도 없다. 복구할 수 없다. */
		UNRECOVERABLE("unrecoverable"),
	}

	enum class Reason(val wire: String) {
		PRICED("priced"),
		PRICE_UNAVAILABLE("price_unavailable"),
		/** 저장 행에 토큰 의미 프로파일이 없다(예: develop 판의 Codex 행). */
		SEMANTICS_MISSING("semantics_missing"),
		/** 배포되지 않은 개발 판이라 같은 프로파일 ID 의 의미를 보증하지 않는다. */
		UNSUPPORTED_NORMALIZER_REV("unsupported_normalizer_rev"),
	}

	/**
	 * 토큰 의미 프로파일을 믿을 수 있는 원천 판. 1·7·8·9·10은 이전에 사용된 규칙이다.
	 * 이 규칙들은 프로파일 ID 와 토큰 유도 규칙이 현재 판과 같다. 2~6 은 로컬 반복 중의 판이라 제외한다.
	 */
	val SOURCE_REVISIONS: Set<UInt> = setOf(1u, 7u, 8u, 9u, 10u)

	private val pricing = PricingStage()
	private val models = listOf(ObservationEnvelope::class, EventObservation::class, OrgAttribution::class)
	private val fields = models.flatMap { type -> type.primaryConstructor!!.parameters.mapNotNull { it.name } }
		.filter { it != "envelope" }.map(::column).toSet() + setOf("row_version", "normalizer_rev")

	/** 빠진 열을 기본값으로 채우지 않으며, 미지의 열도 버리지 않는다. 64비트 값은 Double을 거치지 않는다. */
	fun decode(json: JsonNode): Row {
		stopUnless(json.isObject && json.properties().map { it.key }.toSet() in setOf(fields, fields + "pricing_version")) { "저장 행의 열 집합이 현재 계약과 다르다" }
		val envelope = construct(ObservationEnvelope::class, json)
		val observation = construct(EventObservation::class, json, mapOf("envelope" to envelope))
		val org = construct(OrgAttribution::class, json)
		val version = RowVersioning(numberText(json["normalizer_rev"]).toUInt(), numberText(json["row_version"]).toULong())
		val hash = if (version.normalizerRev < 10u) {
			stopUnless(json.has("pricing_version")) { "구 정규화 행의 호환 열이 없다" }
			val legacy = json["pricing_version"]
			stopUnless(legacy.isNull || legacy.isString) { "구 가격판 열의 타입이 다르다" }
			val name = if (legacy.isNull) null else legacy.asString()
			stopUnless((observation.costEstimatedUsd == null) == (name == null)) { "구 가격판과 금액의 유무가 다르다" }
			AnalysisHash.ofLegacyPriced(observation, name)
		} else {
			stopUnless(!json.has("pricing_version") || json["pricing_version"].isNull) { "새 관측에는 가격판을 기록하지 않는다" }
			AnalysisHash.of(observation)
		}
		stopUnless(hash == envelope.analysisHash) { "저장 행의 정규화 해시가 일치하지 않는다" }
		return Row(EnrichedEvent(observation, org), version)
	}

	fun classify(row: Row): Planned {
		val source = row.event.observation
		stopUnless(source.eventType == EventType.MODEL_RESPONSE_USAGE && source.envelope.usageRole == UsageRole.PRIMARY) { "주 사용량 관측만 재평가한다" }
		stopUnless(source.envelope.recordStatus == RecordStatus.ACTIVE) { "제외된 관측은 재평가하지 않는다" }
		stopUnless(row.version.normalizerRev <= RowVersioning.NORMALIZER_REV) { "현재보다 높은 정규화 판이다" }
		val priced = AnalysisHash.seal(pricing.apply(source))
		val pricedReason = if (priced.costEstimatedUsd == null) Reason.PRICE_UNAVAILABLE else Reason.PRICED
		val unsupported = when {
			row.version.normalizerRev != RowVersioning.NORMALIZER_REV && row.version.normalizerRev !in SOURCE_REVISIONS -> Reason.UNSUPPORTED_NORMALIZER_REV
			source.semanticsProfile == null -> Reason.SEMANTICS_MISSING
			else -> null
		}
		if (unsupported != null) {
			val disposition = if (source.envelope.archiveRef == null) Disposition.UNRECOVERABLE else Disposition.RENORMALIZE
			return Planned(row, disposition, unsupported, null)
		}
		// 가격판·정규화 번호 차이만으로 기존 금액을 다시 쓰지 않는다. Decimal scale도 금액 차이가 아니다.
		val storedCost = source.costEstimatedUsd
		val currentCost = priced.costEstimatedUsd
		val sameCost = when {
			storedCost == null -> currentCost == null
			currentCost == null -> false
			else -> storedCost.compareTo(currentCost) == 0
		}
		if (sameCost) return Planned(row, Disposition.CURRENT, pricedReason, row)
		stopUnless(row.version.normalizerRev < RowVersioning.NORMALIZER_REV) { "같은 정규화 판의 결과를 덮어쓸 수 없다" }
		val target = Row(row.event.copy(observation = priced), RowVersioning.of(RowVersioning.NORMALIZER_REV, row.version.rowVersion.toUInt()))
		return Planned(row, Disposition.REPRICE, pricedReason, target)
	}

	/** 운영자에게 그대로 보여도 되는 중단 사유. 행 원문·접속 정보를 담지 않는 고정 문장만 쓴다. */
	class Stop(message: String) : IllegalArgumentException(message)

	fun stopUnless(condition: Boolean, message: () -> String) {
		if (!condition) throw Stop(message())
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
			stopUnless(type.isMarkedNullable) { "필수 열에 null이 있다" }
			return null
		}
		val kind = type.classifier as KClass<*>
		return when (kind) {
			String::class -> { stopUnless(node.isString) { "문자열 열의 타입이 다르다" }; node.asString() }
			Long::class -> numberText(node).toLong()
			Int::class -> numberText(node).toInt()
			Boolean::class -> { stopUnless(node.isBoolean) { "불리언 열의 타입이 다르다" }; node.asBoolean() }
			BigDecimal::class -> BigDecimal(numberText(node))
			EpochNanos::class -> EpochNanos.of(Instant.parse(node.asString().replace(' ', 'T').let { if (it.endsWith('Z')) it else it + "Z" }))
			List::class -> { stopUnless(node.isArray) { "배열 열의 타입이 다르다" }; (0 until node.size()).map { convert(node[it], type.arguments.single().type!!) } }
			Map::class -> { stopUnless(node.isObject) { "맵 열의 타입이 다르다" }; node.properties().associate { it.key to convert(it.value, type.arguments[1].type!!) } }
			DecisionSource::class -> DecisionSource(node.asString())
			DecisionScope::class -> DecisionScope(node.asString())
			ErrorType::class -> ErrorType(node.asString())
			StopReason::class -> StopReason(node.asString())
			ReasoningEffort::class -> ReasoningEffort(node.asString())
			else -> {
				stopUnless(kind.java.isEnum && WireValue::class.java.isAssignableFrom(kind.java)) { "지원하지 않는 저장 타입이다: ${kind.qualifiedName}" }
				kind.java.enumConstants.single { (it as WireValue).wire == node.asString() }
			}
		}
	}

	private fun numberText(node: JsonNode): String {
		stopUnless(node.isString || node.isNumber) { "숫자 열의 타입이 다르다" }
		return if (node.isString) node.asString() else node.toString()
	}
}
