package com.team376.pulsemetry.telemetry.collector.masking

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest

/**
 * 마스킹이 닿는 자리를 훑는다. 상위 `processResourceLog` · `processResourceSpan` 이식.
 *
 * 값을 어떻게 바꿀지는 [SecretMasker] 가 정하고, 이 클래스는 **어디를 바꾸는지**만 정한다.
 *
 * ## 상위가 훑는 자리 (그대로 옮겼다)
 *
 * | 시그널 | 자리 |
 * |---|---|
 * | logs | resource 속성 · scope 속성 · 레코드 속성 · **레코드 body(재귀)** |
 * | traces | resource 속성 · scope 속성 · span 속성 · **span event 속성** |
 *
 * ## 상위가 훑지 않는 자리 — 일부러 비워 뒀다
 *
 * - **span link 의 속성.** `processResourceSpan` 은 `processSpanEvents` 만 부르고 링크는 지나친다.
 *   상위의 공백이고, 이식은 동작 동일성이 기준이라 메우지 않는다. 메우려면 별도 티켓이다.
 * - **metrics 전체.** 배포 설정의 metrics 파이프라인에 `redaction/secrets` 가 없다
 *   (허브 계약 §5 M6). 그래서 이 클래스에 metrics 진입점이 아예 없다 — 없는 것이 곧 문서다.
 *   `Signal.METRICS.masked` 가 `false` 인 것과 짝을 이룬다.
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
