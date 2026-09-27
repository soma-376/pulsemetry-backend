package com.team376.pulsemetry.telemetry.collector.masking

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.metrics.v1.Exemplar
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.NumberDataPoint

/**
 * 마스킹이 닿는 자리를 훑는다. 상위 `processResourceLog` · `processResourceSpan` ·
 * `processResourceMetric`(v0.157.0) 이식.
 *
 * 값을 어떻게 바꿀지는 [SecretMasker] 가 정하고, 이 클래스는 **어디를 바꾸는지**만 정한다.
 *
 * ## 상위가 훑는 자리 (그대로 옮겼다)
 *
 * | 시그널 | 자리 |
 * |---|---|
 * | logs | resource 속성 · scope 속성 · 레코드 속성 · **레코드 body(재귀)** |
 * | traces | resource 속성 · scope 속성 · span 속성 · **span event 속성** |
 * | metrics | resource 속성 · scope 속성 · **다섯 종류(gauge·sum·histogram·exponential histogram·summary) 전부의 data point 속성** |
 *
 * ## 상위보다 넓힌 자리 — metrics 의 exemplar
 *
 * 상위 `processResourceMetric` 은 data point 속성만 보고 **exemplar 의 `filteredAttributes` 는 지나친다.**
 * exemplar 는 측정 순간의 속성(요청·사용자 식별 값이 흔히 실린다)을 point 밖에 따로 싣는 자리라, 여기를
 * 비우면 point 속성만 마스킹해도 같은 비밀이 원본 아카이브에 남는다. 분석 저장소의 allowlist 가 exemplar 를
 * 예외로 두지 않는 것과 같은 이유로(ADR 0020 §1) 수집 단계도 덮는다 — 규칙과 값 처리는 속성과 같다.
 * exemplar 가 있는 종류는 gauge·sum·histogram·exponential histogram 이다(summary 에는 없다).
 *
 * metrics 를 마스킹하지 않던 현행 설정(허브 계약 §5 M6)은 이 경로에서 해소했다 — ADR 0012 Follow-up.
 *
 * ## 상위가 훑지 않는 자리 — 일부러 비워 뒀다
 *
 * - **span link 의 속성.** `processResourceSpan` 은 `processSpanEvents` 만 부르고 링크는 지나친다.
 *   상위의 공백이고, 이식은 동작 동일성이 기준이라 메우지 않는다. 메우려면 별도 티켓이다.
 * - **metric 이름·설명·단위.** 상위도 속성만 본다.
 * - **span 이름 · 로그 severity text.** 상위는 `sanitizeSpanName` 을 부르지만 `url_sanitizer` ·
 *   `db_sanitizer` 가 둘 다 꺼져 있어 아무 일도 하지 않는다.
 */
internal class AttributeWalker(private val masker: SecretMasker) {

	fun maskLogs(request: ExportLogsServiceRequest.Builder) {
		for (resourceLogs in request.resourceLogsBuilderList) {
			masker.maskAttributes(resourceLogs.resourceBuilder.attributesBuilderList)
			for (scopeLogs in resourceLogs.scopeLogsBuilderList) {
				masker.maskAttributes(scopeLogs.scopeBuilder.attributesBuilderList)
				for (record in scopeLogs.logRecordsBuilderList) {
					masker.maskAttributes(record.attributesBuilderList)
					masker.maskLogBody(record.bodyBuilder)
				}
			}
		}
	}

	/**
	 * resource·scope 는 **있을 때만** 연다. protobuf 빌더는 `resourceBuilder` 를 읽기만 해도 빈 메시지를
	 * 만들어 존재 여부를 바꾸는데, 마스킹이 원본에 없던 구조를 더하면 안 된다.
	 */
	fun maskMetrics(request: ExportMetricsServiceRequest.Builder) {
		for (resourceMetrics in request.resourceMetricsBuilderList) {
			if (resourceMetrics.hasResource()) masker.maskAttributes(resourceMetrics.resourceBuilder.attributesBuilderList)
			for (scopeMetrics in resourceMetrics.scopeMetricsBuilderList) {
				if (scopeMetrics.hasScope()) masker.maskAttributes(scopeMetrics.scopeBuilder.attributesBuilderList)
				for (metric in scopeMetrics.metricsBuilderList) {
					maskMetric(metric)
				}
			}
		}
	}

	private fun maskMetric(metric: Metric.Builder) {
		when (metric.dataCase) {
			Metric.DataCase.GAUGE -> metric.gaugeBuilder.dataPointsBuilderList.forEach { maskNumberPoint(it) }
			Metric.DataCase.SUM -> metric.sumBuilder.dataPointsBuilderList.forEach { maskNumberPoint(it) }
			Metric.DataCase.HISTOGRAM -> metric.histogramBuilder.dataPointsBuilderList.forEach { point ->
				masker.maskAttributes(point.attributesBuilderList)
				maskExemplars(point.exemplarsBuilderList)
			}
			Metric.DataCase.EXPONENTIAL_HISTOGRAM -> metric.exponentialHistogramBuilder.dataPointsBuilderList.forEach { point ->
				masker.maskAttributes(point.attributesBuilderList)
				maskExemplars(point.exemplarsBuilderList)
			}
			Metric.DataCase.SUMMARY -> metric.summaryBuilder.dataPointsBuilderList.forEach { point ->
				masker.maskAttributes(point.attributesBuilderList)
			}
			// 데이터가 없는 metric 이다 — 상위 MetricTypeEmpty 와 같이 아무것도 하지 않는다.
			Metric.DataCase.DATA_NOT_SET, null -> Unit
		}
	}

	private fun maskNumberPoint(point: NumberDataPoint.Builder) {
		masker.maskAttributes(point.attributesBuilderList)
		maskExemplars(point.exemplarsBuilderList)
	}

	/** 상위보다 넓힌 자리다 — 클래스 KDoc 참고. */
	private fun maskExemplars(exemplars: List<Exemplar.Builder>) {
		for (exemplar in exemplars) {
			masker.maskAttributes(exemplar.filteredAttributesBuilderList)
		}
	}

	fun maskTraces(request: ExportTraceServiceRequest.Builder) {
		for (resourceSpans in request.resourceSpansBuilderList) {
			masker.maskAttributes(resourceSpans.resourceBuilder.attributesBuilderList)
			for (scopeSpans in resourceSpans.scopeSpansBuilderList) {
				masker.maskAttributes(scopeSpans.scopeBuilder.attributesBuilderList)
				for (span in scopeSpans.spansBuilderList) {
					masker.maskAttributes(span.attributesBuilderList)
					for (event in span.eventsBuilderList) {
						masker.maskAttributes(event.attributesBuilderList)
					}
				}
			}
		}
	}
}
