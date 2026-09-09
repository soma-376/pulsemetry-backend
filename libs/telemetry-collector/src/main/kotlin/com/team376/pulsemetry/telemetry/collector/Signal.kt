package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.Message
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse

/**
 * OTLP 시그널 세 종과 그 HTTP 경로.
 *
 * 경로는 상위 `otlpreceiver` 의 기본값이다. 상위는 `traces_url_path` 등으로 바꿀 수 있게 두지만
 * 우리 계약은 세 경로를 고정한다(허브 `contracts/telemetry-ingest.md` §3).
 *
 * **`masked` 는 현행 설정을 그대로 옮긴 것이다.** metrics 파이프라인에는 `redaction/secrets` 가
 * 걸려 있지 않다 — 허브 계약 §5 가 M6 으로 등록한 결함이고, 이 이식은 동작 동일성이 판정 기준이라
 * 고치지 않는다(ADR 0012). 고치는 것은 별도 티켓이다. **여기를 무심코 `true` 로 바꾸지 마라** —
 * 그 순간 이 모듈은 현행과 다른 물건이 된다.
 */
public enum class Signal(
	public val path: String,
	public val masked: Boolean,
) {
	LOGS("/v1/logs", masked = true),
	TRACES("/v1/traces", masked = true),
	METRICS("/v1/metrics", masked = false),
	;

	/** 빈 요청 빌더. 수신한 바이트를 여기에 채운다. */
	public fun newRequestBuilder(): Message.Builder = when (this) {
		LOGS -> ExportLogsServiceRequest.newBuilder()
		TRACES -> ExportTraceServiceRequest.newBuilder()
		METRICS -> ExportMetricsServiceRequest.newBuilder()
	}

	/**
	 * 빈 성공 응답. 상위는 언제나 새 빈 응답을 돌려주고 `partial_success` 를 채우지 않는다
	 * (`otlpreceiver/internal/{logs,trace,metrics}`). 우리도 같다.
	 */
	public fun emptyResponse(): Message = when (this) {
		LOGS -> ExportLogsServiceResponse.getDefaultInstance()
		TRACES -> ExportTraceServiceResponse.getDefaultInstance()
		METRICS -> ExportMetricsServiceResponse.getDefaultInstance()
	}

	public companion object {
		private val BY_PATH = entries.associateBy { it.path }

		/** 모르는 경로면 null. 상위는 라우트를 등록하지 않아 404 가 된다. */
		public fun ofPath(path: String): Signal? = BY_PATH[path]
	}
}
