package com.team376.pulsemetry.telemetry.collector.archive

import com.google.protobuf.Message
import com.team376.pulsemetry.telemetry.collector.OtlpHttpRequest
import com.team376.pulsemetry.telemetry.collector.OtlpIngestHandler
import com.team376.pulsemetry.telemetry.collector.OtlpJson
import com.team376.pulsemetry.telemetry.collector.Signal
import com.team376.pulsemetry.telemetry.collector.StampedIdentity
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.resource.v1.Resource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 영수증의 selector 로 아카이브에서 레코드를 다시 찾으면, 다음 단계가 받은 **마스킹·스탬프된** 레코드와
 * 같아야 한다 (ADR 0020 §6). 실제 writer 둘(파일·S3)로 본다.
 *
 * 요청에는 제품 구간 셋(codex·unknown·claude_code)이 섞여 있고 resource 순서가 구간 순서와 다르다 —
 * 요청 기준 경로가 문서 기준 경로로 옮겨지지 않으면 다른 레코드를 찾게 되는 배치다.
 */
class ArchiveSelectorRoundTripTest {

	@TempDir
	lateinit var root: Path

	enum class Store { FILE, S3 }

	@ParameterizedTest(name = "{0} / {1}")
	@EnumSource(Signal::class)
	@DisplayName("모든 레코드가 selector 로 아카이브에서 되찾아지고 하류가 받은 값과 같다 — 파일")
	fun fileArchive(signal: Signal) = roundTrip(Store.FILE, signal)

	@ParameterizedTest(name = "{0} / {1}")
	@EnumSource(Signal::class)
	@DisplayName("모든 레코드가 selector 로 아카이브에서 되찾아지고 하류가 받은 값과 같다 — S3")
	fun s3Archive(signal: Signal) = roundTrip(Store.S3, signal)

	private fun roundTrip(store: Store, signal: Signal) {
		val s3 = CapturingS3()
		val writer = when (store) {
			Store.FILE -> FileArchiveWriter(root)
			Store.S3 -> S3ArchiveWriter(s3, "raw", clock = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC))
		}
		var received: Message? = null
		var receipt: ArchiveReceipt? = null
		val handler = OtlpIngestHandler(
			archive = writer,
			next = { _, request, r ->
				received = request
				receipt = r
			},
			identity = { StampedIdentity(tenantId = "tenant-verified", installationId = "installation-verified") },
		)

		// 같은 파일에 앞선 줄이 있어도 위치가 맞아야 한다 — 두 번 보내 두 번째 영수증을 본다.
		handler.handle(post(signal))
		val response = handler.handle(post(signal))
		assertThat(response.status).isEqualTo(200)

		val request = received!!
		val paths = recordPaths(request)
		assertThat(paths).hasSize(RECORDS_PER_REQUEST.getValue(signal))
		for (path in paths) {
			val (document, selector) = receipt!!.selectorOf(path) ?: error("selector 가 없다: $path")
			val archived = parse(signal, read(document.location, selector, s3))

			assertThat(recordAt(archived, selector.path)).isEqualTo(recordAt(request, path))
			// resource(스탬프된 신원 포함)도 같다 — 재처리가 원래 신원 문맥을 본다.
			assertThat(resourceAt(archived, selector.path[0])).isEqualTo(resourceAt(request, path[0]))
			assertThat(resourceAt(archived, selector.path[0]).attributesList.map { it.value.stringValue })
				.contains("tenant-verified", "installation-verified")
			// 표기가 왕복한다 — 분석 행의 archive_selector 가 이 문자열이다.
			assertThat(ArchiveSelector.parse(selector.format())).isEqualTo(selector)
		}
	}

	/** 선택자가 가리키는 문서 하나의 바이트. 파일은 줄 하나, S3 는 객체 전체다. */
	private fun read(location: ArchivedObject, selector: ArchiveSelector, s3: CapturingS3): ByteArray =
		if (location.uri.startsWith("s3://")) {
			val key = location.uri.removePrefix("s3://raw/")
			s3.puts.single { it.first == key }.second
		} else {
			val bytes = Files.readAllBytes(Path.of(URI.create(location.uri)))
			val offset = selector.byteOffset!!.toInt()
			val length = selector.byteLength!!.toInt()
			assertThat(bytes[offset + length]).isEqualTo('\n'.code.toByte())
			bytes.copyOfRange(offset, offset + length)
		}

	private fun parse(signal: Signal, bytes: ByteArray): Message =
		signal.newRequestBuilder().also { OtlpJson.fromJson(bytes, it) }.build()

	private fun recordPaths(message: Message): List<List<Int>> = when (message) {
		is ExportLogsServiceRequest -> message.resourceLogsList.flatMapIndexed { r, rl ->
			rl.scopeLogsList.flatMapIndexed { s, sl -> sl.logRecordsList.indices.map { listOf(r, s, it) } }
		}

		is ExportTraceServiceRequest -> message.resourceSpansList.flatMapIndexed { r, rs ->
			rs.scopeSpansList.flatMapIndexed { s, ss -> ss.spansList.indices.map { listOf(r, s, it) } }
		}

		is ExportMetricsServiceRequest -> message.resourceMetricsList.flatMapIndexed { r, rm ->
			rm.scopeMetricsList.flatMapIndexed { s, sm ->
				sm.metricsList.flatMapIndexed { m, metric -> (0 until pointCount(metric)).map { listOf(r, s, m, it) } }
			}
		}

		else -> error("unexpected ${message.javaClass}")
	}

	private fun recordAt(message: Message, path: List<Int>): Message = when (message) {
		is ExportLogsServiceRequest ->
			message.getResourceLogs(path[0]).getScopeLogs(path[1]).getLogRecords(path[2])

		is ExportTraceServiceRequest ->
			message.getResourceSpans(path[0]).getScopeSpans(path[1]).getSpans(path[2])

		is ExportMetricsServiceRequest -> {
			val metric = message.getResourceMetrics(path[0]).getScopeMetrics(path[1]).getMetrics(path[2])
			when (metric.dataCase) {
				Metric.DataCase.GAUGE -> metric.gauge.getDataPoints(path[3])
				Metric.DataCase.SUM -> metric.sum.getDataPoints(path[3])
				Metric.DataCase.HISTOGRAM -> metric.histogram.getDataPoints(path[3])
				else -> error("fixture 에 없는 종류: ${metric.dataCase}")
			}
		}

		else -> error("unexpected ${message.javaClass}")
	}

	private fun resourceAt(message: Message, index: Int): Resource = when (message) {
		is ExportLogsServiceRequest -> message.getResourceLogs(index).resource
		is ExportTraceServiceRequest -> message.getResourceSpans(index).resource
		is ExportMetricsServiceRequest -> message.getResourceMetrics(index).resource
		else -> error("unexpected ${message.javaClass}")
	}

	private fun pointCount(metric: Metric): Int = when (metric.dataCase) {
		Metric.DataCase.GAUGE -> metric.gauge.dataPointsCount
		Metric.DataCase.SUM -> metric.sum.dataPointsCount
		Metric.DataCase.HISTOGRAM -> metric.histogram.dataPointsCount
		else -> 0
	}

	private fun post(signal: Signal): OtlpHttpRequest =
		OtlpHttpRequest("POST", signal.path, "application/json", null, body(signal).toByteArray())

	/** codex · unknown · claude_code · codex 순의 resource 넷. 레코드 수는 [RECORDS_PER_REQUEST] 다. */
	private fun body(signal: Signal): String {
		val services = listOf("codex-app-server", "node_repl", "claude-code", "Codex Desktop")
		val resources = services.mapIndexed { r, service ->
			val resource = """{"attributes":[{"key":"service.name","value":{"stringValue":"$service"}}]}"""
			val records = (0..r % 2).map { i -> record(signal, "r$r-$i") }.joinToString(",")
			when (signal) {
				Signal.LOGS -> """{"resource":$resource,"scopeLogs":[{"scope":{"name":"s"},"logRecords":[$records]}]}"""
				Signal.TRACES -> """{"resource":$resource,"scopeSpans":[{"scope":{"name":"s"},"spans":[$records]}]}"""
				Signal.METRICS ->
					"""{"resource":$resource,"scopeMetrics":[{"scope":{"name":"s"},"metrics":[""" +
						"""{"name":"m$r","sum":{"aggregationTemporality":1,"isMonotonic":true,"dataPoints":[$records]}},""" +
						"""{"name":"h$r","histogram":{"aggregationTemporality":1,"dataPoints":[""" +
						"""{"timeUnixNano":"1758758400000000000","count":"3","sum":12,"bucketCounts":["1","2"],"explicitBounds":[10]}]}}]}]}"""
			}
		}.joinToString(",")
		val key = when (signal) {
			Signal.LOGS -> "resourceLogs"
			Signal.TRACES -> "resourceSpans"
			Signal.METRICS -> "resourceMetrics"
		}
		return """{"$key":[$resources]}"""
	}

	private fun record(signal: Signal, tag: String): String = when (signal) {
		Signal.LOGS ->
			"""{"timeUnixNano":"1758758400000000000","body":{"stringValue":"$tag"},""" +
				""""attributes":[{"key":"tag","value":{"stringValue":"$tag"}}]}"""

		Signal.TRACES ->
			"""{"traceId":"0102030405060708090a0b0c0d0e0f10","spanId":"0102030405060708","name":"$tag",""" +
				""""startTimeUnixNano":"1758758400000000000","endTimeUnixNano":"1758758401000000000"}"""

		Signal.METRICS ->
			"""{"timeUnixNano":"1758758400000000000","asInt":"7","attributes":[{"key":"tag","value":{"stringValue":"$tag"}}]}"""
	}

	private companion object {
		/** resource 넷의 레코드 수 합. logs·traces 는 1+2+1+2, metrics 는 그 sum point 여섯 + histogram point 넷. */
		val RECORDS_PER_REQUEST: Map<Signal, Int> = mapOf(Signal.LOGS to 6, Signal.TRACES to 6, Signal.METRICS to 10)
	}
}
