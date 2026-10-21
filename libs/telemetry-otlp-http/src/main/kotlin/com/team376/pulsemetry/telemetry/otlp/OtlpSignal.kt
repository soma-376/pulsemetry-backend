package com.team376.pulsemetry.telemetry.otlp

/**
 * OTLP 시그널 세 종의 HTTP 경로. 상위 `otlpreceiver` 의 기본값이고 우리 계약은 세 경로를 고정한다
 * (허브 `contracts/telemetry-ingest.md` §3).
 *
 * 경로의 진실원은 여기다. 수집 모듈의 `Signal`(디코딩·마스킹 대상)과 Gateway·ingest 의 라우팅이 이 값을 읽는다.
 */
public enum class OtlpSignal(public val path: String) {
	LOGS("/v1/logs"),
	TRACES("/v1/traces"),
	METRICS("/v1/metrics"),
	;

	public companion object {
		private val BY_PATH = entries.associateBy { it.path }

		/** 세 경로 전부. 보안 체인·컨트롤러 매핑이 쓴다. */
		public val PATHS: List<String> = entries.map { it.path }

		/** 모르는 경로면 null. 상위는 라우트를 등록하지 않아 404 가 된다. */
		public fun ofPath(path: String): OtlpSignal? = BY_PATH[path]
	}
}
