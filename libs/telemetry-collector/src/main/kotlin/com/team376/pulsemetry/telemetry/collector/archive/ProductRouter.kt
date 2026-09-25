package com.team376.pulsemetry.telemetry.collector.archive

import com.google.protobuf.Message
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource

/** 제품 구간 하나에 담길 export 문서와, 그 resource 들이 전체 요청에서 몇 번째였는지. */
internal class RoutedDocument(
	val product: Product,
	val document: Message,
	val resourceIndexes: List<Int>,
)

/**
 * 수신한 요청을 제품 구간별 아카이브 문서로 가른다.
 *
 * 판정은 resource 단위다 — 구간을 정하는 `service.name` 이 resource 속성이라 한 resource 아래 레코드의
 * 판정이 모두 같다. **모든 resource 가 정확히 한 문서에 들어간다.** 별칭 표에 없는 서비스와
 * `service.name` 이 없는 resource 는 [Product.UNKNOWN] 문서다([Product] KDoc — 이식 원본은 그것을 버렸다).
 *
 * 문서의 순서는 각 구간이 요청에서 처음 나타난 순서이고, 문서 안 resource 의 순서는 요청 안 순서 그대로다.
 * 그래야 [RoutedDocument.resourceIndexes] 로 요청 기준 경로를 문서 기준 selector 로 옮길 수 있다.
 */
internal object ProductRouter {

	private const val SERVICE_NAME = "service.name"

	/** 원본을 건드리지 않는다 — 아카이브와 하류가 같은 메시지를 보되 서로의 편집이 섞이면 안 된다. */
	fun split(request: Message): List<RoutedDocument> = when (request) {
		is ExportLogsServiceRequest ->
			route(request.resourceLogsList, { it.resource }) { list ->
				ExportLogsServiceRequest.newBuilder().addAllResourceLogs(list).build()
			}

		is ExportTraceServiceRequest ->
			route(request.resourceSpansList, { it.resource }) { list ->
				ExportTraceServiceRequest.newBuilder().addAllResourceSpans(list).build()
			}

		is ExportMetricsServiceRequest ->
			route(request.resourceMetricsList, { it.resource }) { list ->
				ExportMetricsServiceRequest.newBuilder().addAllResourceMetrics(list).build()
			}

		else -> error("아카이브가 다루지 않는 요청 타입이다: ${request.descriptorForType.fullName}")
	}

	private fun <T> route(
		resources: List<T>,
		resourceOf: (T) -> Resource,
		build: (List<T>) -> Message,
	): List<RoutedDocument> =
		resources.withIndex()
			.groupBy { productOf(resourceOf(it.value)) }
			.map { (product, indexed) ->
				RoutedDocument(product, build(indexed.map { it.value }), indexed.map { it.index })
			}

	private fun productOf(resource: Resource): Product =
		Product.ofServiceName(
			resource.attributesList
				.firstOrNull { it.key == SERVICE_NAME }
				?.let { stringValueOf(it) },
		)

	/** `service.name` 이 문자열이 아니면 없는 것으로 본다 — [Product.UNKNOWN] 이다. */
	private fun stringValueOf(attribute: KeyValue): String? =
		attribute.value.takeIf { it.hasStringValue() }?.stringValue
}
