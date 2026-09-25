package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import com.google.protobuf.Message
import com.team376.pulsemetry.telemetry.adapter.observation.AnalysisHash
import com.team376.pulsemetry.telemetry.adapter.observation.ArchiveLocator
import com.team376.pulsemetry.telemetry.adapter.observation.ArchivePointer
import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.Hex64
import com.team376.pulsemetry.telemetry.adapter.observation.MetadataAllowlist
import com.team376.pulsemetry.telemetry.adapter.observation.MetricPointObservation
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationBatch
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationContext
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationEnvelope
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationIds
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationNormalizer
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationPipeline
import com.team376.pulsemetry.telemetry.adapter.observation.WireValue
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * otlp-v2 fixture 한 쌍. 입력 한 줄 = OTLP/JSON 문서(push) 하나, 기대 한 줄 = 그 문서의 기대값.
 */
class FixtureCase(val name: String, val documents: List<Map<*, *>>, val expectations: List<Map<*, *>>)

/**
 * otlp-v2 fixture 하네스(테스트 전용) — `src/test/resources/otlp-v2/README.md` 가 형식과 원칙을 적는다.
 *
 * 한 사례를 파이프라인에 태우고 (1) 기대 파일이 적은 필드만 부분 일치로 대조하고 (2) 모든 관측에 공통 불변식을
 * 자동으로 검사한다. 실패는 모아서 한 번에 보고한다 — 어느 사례·문서·관측·필드가 어떻게 다른지.
 */
class FixtureSuite(
	private val registry: ProfileRegistry = ProfileRegistry.EMPTY,
	private val pricing: PricingStage = PricingStage.NONE,
) {

	fun load(directory: String): List<FixtureCase> =
		inputFiles(directory).map { input ->
			val stem = input.name.removeSuffix(".otlp.jsonl")
			val expected = input.resolveSibling("$stem.expected.jsonl")
			require(Files.exists(expected)) { "$stem.expected.jsonl 가 없다" }
			FixtureCase(
				name = "$directory/$stem",
				documents = lines(input).map { it as Map<*, *> },
				expectations = lines(expected).map { it as Map<*, *> },
			)
		}

	/** 기대값 없이 입력만 읽는다 — 기대값을 아직 쓰지 않은 실캡처 추출본용. [checkInvariants] 와 짝이다. */
	fun inputs(directory: String): List<FixtureCase> =
		inputFiles(directory).map { input ->
			FixtureCase("$directory/${input.name.removeSuffix(".otlp.jsonl")}", lines(input).map { it as Map<*, *> }, emptyList())
		}

	private fun inputFiles(directory: String): List<Path> {
		val root = Path.of(requireNotNull(FixtureSuite::class.java.getResource("/otlp-v2/$directory")) { "otlp-v2/$directory 가 없다" }.toURI())
		return Files.list(root).use { files -> files.map { it }.toList() }
			.filter { it.name.endsWith(".otlp.jsonl") }
			.sortedBy { it.name }
	}

	private fun lines(path: Path): List<Any?> = path.readLines().filter { it.isNotBlank() }.map { JsonTree.parse(it) }

	/** 사례 하나를 검사하고 실패 목록을 돌려준다. 비어 있으면 통과다. */
	fun check(case: FixtureCase): List<String> {
		val errors = mutableListOf<String>()
		if (case.documents.size != case.expectations.size) {
			return listOf("[${case.name}] 입력 ${case.documents.size}줄 · 기대 ${case.expectations.size}줄 — 줄 수가 같아야 한다")
		}
		val labels = Labels()
		case.documents.forEachIndexed { index, document ->
			val where = "[${case.name}] 문서 #$index"
			val expectation = case.expectations[index]
			requireSpec(expectation, where, errors)
			val context = context(case, index, expectation["context"] as Map<*, *>?)
			val request = request(document)
			val batch = pipeline().run(request, context)

			invariants(where, request, context, batch, errors)
			expectBatch(where, expectation, batch, labels, errors)
		}
		labels.verify(case.name, errors)
		return errors
	}

	/** 기대값 대조 없이 문서마다 파이프라인을 태워 공통 불변식만 검사한다. 비어 있으면 통과다. */
	fun checkInvariants(case: FixtureCase): List<String> {
		val errors = mutableListOf<String>()
		case.documents.forEachIndexed { index, document ->
			val where = "[${case.name}] 문서 #$index"
			val context = context(case, index, null)
			val request = request(document)
			invariants(where, request, context, pipeline().run(request, context), errors)
		}
		return errors
	}

	private fun pipeline() = ObservationPipeline(ObservationNormalizer(registry), pricing)

	private fun requireSpec(expectation: Map<*, *>, where: String, errors: MutableList<String>) {
		if ((expectation["spec"] as? String).isNullOrBlank()) errors += "$where: 기대값에 근거 절(spec)이 없다"
	}

	private fun context(case: FixtureCase, document: Int, overrides: Map<*, *>?): ObservationContext {
		val archive = if (overrides?.get("archive") == false) {
			null
		} else {
			ArchiveLocator { path -> ArchivePointer("s3://fixture/${case.name}/$document", "path=" + path.joinToString("/")) }
		}
		return ObservationContext(TENANT, INSTALLATION, RECEIVED, "masking-v2", "stamp-v1", archive)
	}

	/** OTLP/JSON 문서 하나를 export 요청으로 읽는다. 모르는 필드는 실패한다([OtlpJsonV2]). */
	fun request(document: Map<*, *>): Message {
		val builder = when {
			"resourceLogs" in document -> ExportLogsServiceRequest.newBuilder()
			"resourceSpans" in document -> ExportTraceServiceRequest.newBuilder()
			"resourceMetrics" in document -> ExportMetricsServiceRequest.newBuilder()
			else -> throw IllegalArgumentException("OTLP export 문서가 아니다: ${document.keys}")
		}
		OtlpJsonV2.merge(document, builder)
		return builder.build()
	}

	// ── 공통 불변식 ──────────────────────────────────────────────────────────

	private fun invariants(where: String, request: Message, context: ObservationContext, batch: ObservationBatch, errors: MutableList<String>) {
		// 결정성 — 같은 입력을 다시 태워도, protobuf 바이트로 왕복해도 같은 결과다.
		if (pipeline().run(request, context).let { it.events != batch.events || it.metricPoints != batch.metricPoints }) {
			errors += "$where: 같은 입력의 두 번째 실행 결과가 다르다"
		}
		val reparsed = request.parserForType.parseFrom(request.toByteArray())
		if (pipeline().run(reparsed, context).let { it.events != batch.events || it.metricPoints != batch.metricPoints }) {
			errors += "$where: protobuf 바이트로 왕복한 입력의 결과가 다르다"
		}
		// 받은 레코드는 행이 되거나 거부·제외 수로 세어진다.
		val rows = batch.events.size + batch.metricPoints.size
		if (batch.stats.received != rows + batch.stats.rejectedCount) {
			errors += "$where: 받은 ${batch.stats.received} ≠ 행 $rows + 거부·제외 ${batch.stats.rejectedCount}"
		}
		val observations: List<Pair<String, Any>> =
			batch.events.mapIndexed { i, o -> "events[$i]" to o } + batch.metricPoints.mapIndexed { i, o -> "metric_points[$i]" to o }
		for ((name, observation) in observations) {
			val at = "$where $name"
			val envelope = if (observation is EventObservation) observation.envelope else (observation as MetricPointObservation).envelope
			if (!Hex64.isValid(envelope.observationId)) errors += "$at: observation_id 가 64자리 hex 가 아니다"
			if (envelope.analysisHash == AnalysisHash.PLACEHOLDER) errors += "$at: analysis_hash 가 봉인되지 않았다"
			val recomputed = if (observation is EventObservation) AnalysisHash.of(observation) else AnalysisHash.of(observation as MetricPointObservation)
			if (recomputed != envelope.analysisHash) errors += "$at: analysis_hash 가 재계산 값과 다르다"
			(observation as? MetricPointObservation)?.seriesId?.let { if (!Hex64.isValid(it)) errors += "$at: series_id 가 64자리 hex 가 아니다" }
			if (envelope.tenantId != TENANT || envelope.installationId != INSTALLATION) errors += "$at: 신원이 문맥 값이 아니다"
			if (envelope.receivedTime != RECEIVED) errors += "$at: received_time 이 문맥 값이 아니다"
			if (envelope.identityVersion != ObservationIds.IDENTITY_VERSION) errors += "$at: identity_version 이 ${ObservationIds.IDENTITY_VERSION} 이 아니다"
			forbiddenKeys(envelope).forEach { errors += "$at: metadata 에 금지 키 $it" }
			val rendered = JsonTree.render(render(observation))
			if (SENSITIVE_MARKER in rendered) errors += "$at: '$SENSITIVE_MARKER' 로 표시한 원문이 분석 관측에 남았다"
		}
	}

	private fun forbiddenKeys(envelope: ObservationEnvelope): List<String> {
		val tree = JsonTree.parse(envelope.metadataJson) as Map<*, *>
		val sections = listOf("resource", "scope", "record").flatMap { (tree[it] as List<*>?).orEmpty() } +
			(tree["exemplars"] as List<*>?).orEmpty().flatMap { it as List<*> }
		return sections.map { (it as Map<*, *>)["key"] as String }.filter { MetadataAllowlist.isForbidden(it) }
	}

	// ── 기대값 대조 ──────────────────────────────────────────────────────────

	private fun expectBatch(where: String, expectation: Map<*, *>, batch: ObservationBatch, labels: Labels, errors: MutableList<String>) {
		val known = setOf("spec", "note", "context", "events", "metric_points", "stats")
		(expectation.keys - known).forEach { errors += "$where: 기대값의 모르는 항목 $it" }
		expectList(where, "events", expectation["events"] as List<*>?, batch.events.map { render(it) }, labels, errors)
		expectList(where, "metric_points", expectation["metric_points"] as List<*>?, batch.metricPoints.map { render(it) }, labels, errors)
		(expectation["stats"] as Map<*, *>?)?.let { expected ->
			partial("$where stats", expected, stats(batch), labels, errors)
		}
	}

	/** 목록을 적었다면 개수와 순서까지 단언한다. 적지 않았다면 비어 있어야 한다. */
	private fun expectList(where: String, name: String, expected: List<*>?, actual: List<Map<String, Any?>>, labels: Labels, errors: MutableList<String>) {
		val wanted = expected.orEmpty()
		if (wanted.size != actual.size) {
			errors += "$where $name: 관측 ${wanted.size}개를 기대했는데 ${actual.size}개다"
			return
		}
		wanted.forEachIndexed { i, fields -> partial("$where $name[$i]", fields as Map<*, *>, actual[i], labels, errors) }
	}

	/** 기대가 적은 필드만 본다. 없는 필드 이름은 오타로 보고 실패한다. */
	private fun partial(where: String, expected: Map<*, *>, actual: Map<String, Any?>, labels: Labels, errors: MutableList<String>) {
		for ((key, value) in expected) {
			if (key !in actual) {
				errors += "$where: 모르는 필드 $key"
				continue
			}
			compare("$where.$key", value, actual[key], labels, errors)
		}
	}

	private fun compare(where: String, expected: Any?, actual: Any?, labels: Labels, errors: MutableList<String>) {
		if (expected is Map<*, *> && expected.size == 1 && (expected.keys.single() as String).startsWith("$")) {
			matcher(where, expected.keys.single() as String, expected.values.single(), actual, labels, errors)
			return
		}
		val ok = when (expected) {
			null -> actual == null
			is JsonNumber -> actual is BigDecimal && actual.compareTo(expected.toBigDecimal()) == 0
			is List<*> -> {
				if (actual !is List<*> || actual.size != expected.size) {
					false
				} else {
					expected.indices.forEach { compare("$where[$it]", expected[it], actual[it], labels, errors) }
					true
				}
			}
			is Map<*, *> -> {
				if (actual !is Map<*, *> || actual.keys != expected.keys) {
					false
				} else {
					expected.forEach { (k, v) -> compare("$where.$k", v, actual[k], labels, errors) }
					true
				}
			}
			else -> expected == actual
		}
		if (!ok) errors += "$where: 기대 ${JsonTree.render(expected)} · 실제 ${JsonTree.render(actual)}"
	}

	private fun matcher(where: String, name: String, argument: Any?, actual: Any?, labels: Labels, errors: MutableList<String>) {
		when (name) {
			"\$hex64" -> if (actual !is String || !Hex64.isValid(actual)) errors += "$where: 64자리 hex 가 아니다 — ${JsonTree.render(actual)}"
			"\$notNull" -> if (actual == null) errors += "$where: null 이다"
			"\$all" -> (argument as List<*>).forEach { compare(where, it, actual, labels, errors) }
			"\$same" -> labels.same.getOrPut(argument as String) { mutableListOf() } += where to actual
			"\$distinct" -> labels.distinct.getOrPut(argument as String) { mutableListOf() } += where to actual
			"\$contains", "\$excludes" -> {
				val wanted = argument as List<*>
				val present = wanted.map { item ->
					when (actual) {
						is String -> actual.contains(item as String)
						is List<*> -> actual.contains(item)
						else -> false
					}
				}
				val failed = if (name == "\$contains") wanted.filterIndexed { i, _ -> !present[i] } else wanted.filterIndexed { i, _ -> present[i] }
				if (failed.isNotEmpty()) errors += "$where: $name 위반 ${JsonTree.render(failed)} — 실제 ${JsonTree.render(actual)}"
			}
			else -> errors += "$where: 모르는 matcher $name"
		}
	}

	/** 사례 안의 `$same`·`$distinct` 묶음. 문서를 가로질러 모은 뒤 끝에 판정한다. */
	private class Labels {
		val same = linkedMapOf<String, MutableList<Pair<String, Any?>>>()
		val distinct = linkedMapOf<String, MutableList<Pair<String, Any?>>>()

		fun verify(case: String, errors: MutableList<String>) {
			same.forEach { (label, values) ->
				if (values.size < 2) errors += "[$case] \$same:$label 이 한 번만 쓰였다"
				if (values.map { it.second }.toSet().size != 1) errors += "[$case] \$same:$label 값이 다르다 — ${values.joinToString { "${it.first}=${JsonTree.render(it.second)}" }}"
			}
			distinct.forEach { (label, values) ->
				if (values.map { it.second }.toSet().size != values.size) {
					errors += "[$case] \$distinct:$label 값이 겹친다 — ${values.joinToString { "${it.first}=${JsonTree.render(it.second)}" }}"
				}
			}
		}
	}

	// ── 관측을 컬럼 이름의 맵으로 ─────────────────────────────────────────────

	companion object {
		const val TENANT = "tenant-fixture"
		const val INSTALLATION = "installation-fixture"
		val RECEIVED = EpochNanos(1_758_844_800_000_000_000)

		/** 분석 관측에 절대 남으면 안 되는 원문을 fixture 에서 이 접두사로 표시한다. */
		const val SENSITIVE_MARKER = "SENSITIVE:"

		/** 봉투를 펼친 컬럼 맵. `metadata` 는 `metadata_json` 을 트리로 읽은 가상 필드다. */
		fun render(observation: Any): Map<String, Any?> {
			val fields = fields(observation)
			val envelope = fields.remove("envelope") as Map<String, Any?>
			val out = LinkedHashMap<String, Any?>(envelope)
			out.putAll(fields)
			out["metadata"] = normalize(JsonTree.parse(envelope["metadata_json"] as String))
			return out
		}

		fun stats(batch: ObservationBatch): Map<String, Any?> = linkedMapOf(
			"received" to BigDecimal(batch.stats.received),
			"rejected_count" to BigDecimal(batch.stats.rejectedCount),
			"excluded_spans" to BigDecimal(batch.stats.excludedSpans),
			"source_time_rejections" to batch.stats.sourceTimeRejections.entries.associate { (k, v) -> k.wire to BigDecimal(v) },
			"excluded_span_names" to batch.stats.excludedSpanNames.mapValues { BigDecimal(it.value) },
			"source_time_min" to batch.stats.sourceTimeMin?.value?.toString(),
			"source_time_max" to batch.stats.sourceTimeMax?.value?.toString(),
		)

		@Suppress("UNCHECKED_CAST")
		private fun fields(value: Any): LinkedHashMap<String, Any?> {
			val type = value::class as KClass<Any>
			val properties = type.memberProperties.associateBy { it.name }
			val out = LinkedHashMap<String, Any?>()
			for (parameter in type.primaryConstructor!!.parameters) {
				val v = properties.getValue(parameter.name!!).get(value)
				out[snake(parameter.name!!)] = if (v is ObservationEnvelope) fields(v) else normalize(v)
			}
			return out
		}

		private fun snake(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

		private fun normalize(value: Any?): Any? = when (value) {
			null -> null
			is WireValue -> value.wire
			is EpochNanos -> value.value.toString()
			is String, is Boolean -> value
			is JsonNumber -> value.toBigDecimal()
			is Int -> BigDecimal(value)
			is Long -> BigDecimal(value)
			is UInt, is ULong -> BigDecimal(value.toString())
			is Double -> if (value.isFinite()) BigDecimal(value.toString()) else value.toString()
			is BigDecimal -> value
			is List<*> -> value.map { normalize(it) }
			is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to normalize(v) }
			else -> throw IllegalArgumentException("표기할 수 없는 값: ${value::class}")
		}
	}
}
