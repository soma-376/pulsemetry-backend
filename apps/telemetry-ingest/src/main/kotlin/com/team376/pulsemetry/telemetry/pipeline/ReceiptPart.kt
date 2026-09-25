package com.team376.pulsemetry.telemetry.pipeline

import com.google.protobuf.Message
import com.team376.pulsemetry.telemetry.adapter.observation.ArchiveLocator
import com.team376.pulsemetry.telemetry.adapter.observation.ArchivePointer
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveReceipt
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveSelector
import com.team376.pulsemetry.telemetry.collector.archive.ArchivedDocument
import com.team376.pulsemetry.telemetry.collector.archive.Product
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.metrics.v1.Metric

/**
 * push 하나를 아카이브 제품 문서 단위로 나눈 조각. 정규화 실행과 수신 ledger 행의 단위다(ADR 0021 §1 — 행 하나가
 * (receipt, signal, archive product) 하나).
 *
 * [request] 는 그 문서에 담긴 resource 만 요청 안 순서대로 모은 export 요청이라 아카이브된 문서와 같은 모양이다. 그래서
 * 이 조각을 정규화할 때의 경로(resource/scope/record)가 곧 문서 기준 selector 의 경로다.
 */
class ReceiptPart(
	val request: Message,
	/** 이 조각이 담긴 아카이브 문서. 영수증이 덮지 않은 resource 의 조각이면 null 이다. */
	val archived: ArchivedDocument?,
	/** ledger 의 `product` — 아카이브 구간. */
	val product: String,
	/** 받은 레코드 수(로그 레코드·스팬·metric point). */
	val recordCount: Int,
) {
	/** 문서 안 경로를 아카이브 참조로 바꾼다. 문서가 없으면 null — 행은 `archive_receipt_missing` 이다(ADR 0020 §6). */
	val locator: ArchiveLocator?
		get() = archived?.let { document ->
			val location = document.location
			ArchiveLocator { path -> ArchivePointer(location.uri, ArchiveSelector(location.byteOffset, location.byteLength, path).format()) }
		}

	companion object {
		/**
		 * [receipt] 의 문서마다 조각 하나. 수집 단계는 모든 resource 를 정확히 한 문서에 넣으므로 보통은 그것으로 끝이다.
		 * 영수증이 덮지 않은 resource 가 있으면(영수증을 손으로 만든 경로) 그것들을 문서 없는 조각 하나로 모아 계속
		 * 적재한다 — 행은 `archive_ref = null` + `archive_receipt_missing` 이다.
		 */
		fun of(request: Message, receipt: ArchiveReceipt): List<ReceiptPart> {
			val resources = OtlpResources.of(request)
			val covered = receipt.documents.flatMap { it.resourceIndexes }.toSet()
			val parts = receipt.documents.map { document -> part(request, resources, document.resourceIndexes, document, document.product) }
			val uncovered = resources.indices.filterNot { it in covered }
			return if (uncovered.isEmpty()) parts else parts + part(request, resources, uncovered, null, Product.UNKNOWN)
		}

		private fun part(
			request: Message,
			resources: OtlpResources,
			indexes: List<Int>,
			archived: ArchivedDocument?,
			product: Product,
		): ReceiptPart {
			val sub = resources.subRequest(request, indexes)
			return ReceiptPart(sub, archived, product.archiveSegment, OtlpResources.recordCount(sub))
		}
	}
}

/** export 요청 세 종류의 resource 목록을 다루는 최소 도구. */
class OtlpResources private constructor(val indices: IntRange) {

	fun subRequest(request: Message, indexes: List<Int>): Message = when (request) {
		is ExportLogsServiceRequest ->
			ExportLogsServiceRequest.newBuilder().addAllResourceLogs(indexes.map { request.getResourceLogs(it) }).build()
		is ExportTraceServiceRequest ->
			ExportTraceServiceRequest.newBuilder().addAllResourceSpans(indexes.map { request.getResourceSpans(it) }).build()
		is ExportMetricsServiceRequest ->
			ExportMetricsServiceRequest.newBuilder().addAllResourceMetrics(indexes.map { request.getResourceMetrics(it) }).build()
		else -> error("모르는 요청 타입이다: ${request.descriptorForType.fullName}")
	}

	companion object {
		fun of(request: Message): OtlpResources = OtlpResources(
			0 until when (request) {
				is ExportLogsServiceRequest -> request.resourceLogsCount
				is ExportTraceServiceRequest -> request.resourceSpansCount
				is ExportMetricsServiceRequest -> request.resourceMetricsCount
				else -> error("모르는 요청 타입이다: ${request.descriptorForType.fullName}")
			},
		)

		/** 정규화가 세는 것과 같은 단위 — 로그 레코드·스팬·metric point. */
		fun recordCount(request: Message): Int = when (request) {
			is ExportLogsServiceRequest -> request.resourceLogsList.sumOf { rl -> rl.scopeLogsList.sumOf { it.logRecordsCount } }
			is ExportTraceServiceRequest -> request.resourceSpansList.sumOf { rs -> rs.scopeSpansList.sumOf { it.spansCount } }
			is ExportMetricsServiceRequest ->
				request.resourceMetricsList.sumOf { rm -> rm.scopeMetricsList.sumOf { sm -> sm.metricsList.sumOf { points(it) } } }
			else -> error("모르는 요청 타입이다: ${request.descriptorForType.fullName}")
		}

		private fun points(metric: Metric): Int = when (metric.dataCase) {
			Metric.DataCase.GAUGE -> metric.gauge.dataPointsCount
			Metric.DataCase.SUM -> metric.sum.dataPointsCount
			Metric.DataCase.HISTOGRAM -> metric.histogram.dataPointsCount
			Metric.DataCase.EXPONENTIAL_HISTOGRAM -> metric.exponentialHistogram.dataPointsCount
			Metric.DataCase.SUMMARY -> metric.summary.dataPointsCount
			Metric.DataCase.DATA_NOT_SET, null -> 0
		}
	}
}
