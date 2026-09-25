package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import com.google.protobuf.Message
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.metrics.v1.Metric
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * `otlp-v2/codex/real/` — Codex 실캡처를 익명화한 추출본. 출처·선택 기준·치환 규칙은 그 디렉터리의 README 가 적는다.
 *
 * 기대값은 아직 없다 — Codex 프로파일이 명세에서 쓴다. 여기서는 실제 producer 의 wire 모양이 하네스를 통과하는지를 본다:
 * 모든 문서가 OTLP 로 읽히고(모르는 필드 없음), 레코드·point 가 빠짐없이 옮겨지며, 프로파일 없이 태운 결과가
 * 공통 불변식을 지킨다.
 */
class CodexRealFixtureTest {

	private val suite = FixtureSuite()

	@TestFactory
	@DisplayName("codex/real 추출본 — 모든 문서가 읽히고 레코드가 빠짐없이 옮겨지며 공통 불변식을 지킨다")
	fun everyDocumentParses(): List<DynamicTest> {
		val cases = suite.inputs("codex/real")
		assertThat(cases).isNotEmpty()
		return cases.map { case ->
			DynamicTest.dynamicTest(case.name) {
				assertThat(case.documents).isNotEmpty()
				case.documents.forEachIndexed { index, document ->
					val request = suite.request(document)
					assertThat(counts(request)).describedAs("${case.name} 문서 #$index").isEqualTo(jsonCounts(document))
					assertThat(counts(request).first).describedAs("${case.name} 문서 #$index 의 레코드 수").isPositive()
				}
				val errors = suite.checkInvariants(case)
				assertThat(errors).describedAs(errors.joinToString("\n", prefix = "\n")).isEmpty()
			}
		}
	}

	/** (레코드 수, metric point 수) — 로그 레코드·스팬·metric 을 레코드로 센다. */
	private fun counts(request: Message): Pair<Int, Int> = when (request) {
		is ExportLogsServiceRequest -> request.resourceLogsList.sumOf { r -> r.scopeLogsList.sumOf { it.logRecordsCount } } to 0
		is ExportTraceServiceRequest -> request.resourceSpansList.sumOf { r -> r.scopeSpansList.sumOf { it.spansCount } } to 0
		is ExportMetricsServiceRequest -> {
			val metrics = request.resourceMetricsList.flatMap { r -> r.scopeMetricsList.flatMap { it.metricsList } }
			metrics.size to metrics.sumOf { points(it) }
		}
		else -> error("export 요청이 아니다: ${request::class}")
	}

	private fun points(metric: Metric): Int = when {
		metric.hasGauge() -> metric.gauge.dataPointsCount
		metric.hasSum() -> metric.sum.dataPointsCount
		metric.hasHistogram() -> metric.histogram.dataPointsCount
		metric.hasExponentialHistogram() -> metric.exponentialHistogram.dataPointsCount
		metric.hasSummary() -> metric.summary.dataPointsCount
		else -> 0
	}

	private fun jsonCounts(document: Map<*, *>): Pair<Int, Int> {
		fun list(node: Any?, key: String): List<Map<*, *>> = ((node as Map<*, *>?)?.get(key) as List<*>?).orEmpty().map { it as Map<*, *> }
		val logs = list(document, "resourceLogs").sumOf { r -> list(r, "scopeLogs").sumOf { list(it, "logRecords").size } }
		val spans = list(document, "resourceSpans").sumOf { r -> list(r, "scopeSpans").sumOf { list(it, "spans").size } }
		val metrics = list(document, "resourceMetrics").flatMap { r -> list(r, "scopeMetrics").flatMap { list(it, "metrics") } }
		val points = metrics.sumOf { m ->
			listOf("gauge", "sum", "histogram", "exponentialHistogram", "summary").sumOf { list(m[it], "dataPoints").size }
		}
		return (logs + spans + metrics.size) to points
	}
}
