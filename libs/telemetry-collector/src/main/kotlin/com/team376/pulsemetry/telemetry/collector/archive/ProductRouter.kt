package com.team376.pulsemetry.telemetry.collector.archive

import com.google.protobuf.Message
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource

/**
 * 수신한 요청을 제품별 아카이브 문서로 가른다. 현행 `filter/codex` · `filter/claude_code` 이식.
 *
 * ## drop-on-true 를 뒤집어 적었다
 *
 * 원본은 조건이 참인 레코드를 버리는 processor 이고 조건이 `service.name != "<제품>"` 이므로,
 * 남는 것은 `service.name == "<제품>"` 인 것뿐이다. 그래서 여기서는 **남길 것을 고르는** 형태로
 * 적는다 — 같은 결과이고 읽기 쉽다. 아는 제품이 아니면 어느 쪽에도 담기지 않는다([Product] KDoc).
 *
 * ## 빈 컨테이너는 남기지 않는다
 *
 * 상위 `processSkipExpression` 은 레코드를 지운 뒤 **빈 scope 를 지우고 다시 빈 resource 를**
 * 지운다. 여기서는 resource 단위로 고르므로 그 계단이 한 번에 끝난다.
 * 남는 resource 가 하나도 없으면 상위는 `ErrSkipProcessingData` 로 하류를 부르지 않고 끝내는데
 * (`processorhelper` 가 그 오류만 삼킨다) 수신기는 그대로 **200** 을 돌려준다.
 * 이식본에서는 그 자리가 "쓸 문서가 없다" 즉 [split] 이 빈 맵을 주는 것이다.
 */
internal object ProductRouter {

	private const val SERVICE_NAME = "service.name"

	/**
	 * 제품별로 갈라 각각 완결된 export 문서를 만든다. 담길 것이 없는 제품은 결과에 없다.
	 *
	 * 원본을 건드리지 않는다 — 아카이브와 하류가 같은 메시지를 보되 서로의 편집이 섞이면 안 된다.
	 */
	fun split(request: Message): Map<Product, Message> = when (request) {
		is ExportLogsServiceRequest ->
			request.resourceLogsList
				.groupBy { productOf(it.resource) }
				.filterNotNullKeys()
				.mapValues { (_, list) -> ExportLogsServiceRequest.newBuilder().addAllResourceLogs(list).build() }

		is ExportTraceServiceRequest ->
			request.resourceSpansList
				.groupBy { productOf(it.resource) }
				.filterNotNullKeys()
				.mapValues { (_, list) -> ExportTraceServiceRequest.newBuilder().addAllResourceSpans(list).build() }

		is ExportMetricsServiceRequest ->
			request.resourceMetricsList
				.groupBy { productOf(it.resource) }
				.filterNotNullKeys()
				.mapValues { (_, list) -> ExportMetricsServiceRequest.newBuilder().addAllResourceMetrics(list).build() }

		else -> error("아카이브가 다루지 않는 요청 타입이다: ${request.descriptorForType.fullName}")
	}

	private fun productOf(resource: Resource): Product? =
		Product.ofServiceName(
			resource.attributesList
				.firstOrNull { it.key == SERVICE_NAME }
				?.let { stringValueOf(it) },
		)

	/**
	 * `service.name` 이 문자열이 아니면 없는 것으로 본다. OTTL 도 문자열 아닌 값을 문자열과
	 * 비교하면 `invalidComparison` 으로 떨어져 같은 결론에 이른다.
	 */
	private fun stringValueOf(attribute: KeyValue): String? =
		attribute.value.takeIf { it.hasStringValue() }?.stringValue

	private fun <V> Map<Product?, V>.filterNotNullKeys(): Map<Product, V> =
		buildMap { this@filterNotNullKeys.forEach { (k, v) -> if (k != null) put(k, v) } }
}
