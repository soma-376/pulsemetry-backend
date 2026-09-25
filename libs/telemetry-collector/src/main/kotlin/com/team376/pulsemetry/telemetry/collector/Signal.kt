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
 * **세 시그널 모두 마스킹한다.** 이식 원본의 metrics 파이프라인에는 `redaction/secrets` 가 없었고
 * (허브 계약 §5 의 M6), 처음에는 그것까지 그대로 옮겼다. 원본 아카이브가 보존 기간이 있는 버킷으로
 * 옮겨지면서(ADR 0012) 그 결함의 노출이 길어져 metrics 도 마스킹한다 — 무엇을 덮는지는
 * `AttributeWalker`, 정책의 버전은 `MaskingPolicy` 가 담는다. `masked` 를 `false` 로 되돌리면 비밀이
 * 아카이브와 분석 저장소에 남는다.
 */
public enum class Signal(
	public val path: String,
	public val masked: Boolean,
) {
	LOGS("/v1/logs", masked = true),
	TRACES("/v1/traces", masked = true),
	METRICS("/v1/metrics", masked = true),
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
