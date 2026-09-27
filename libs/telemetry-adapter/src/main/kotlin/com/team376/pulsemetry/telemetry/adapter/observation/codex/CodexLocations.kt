package com.team376.pulsemetry.telemetry.adapter.observation.codex

/**
 * `codex.api_request` 를 내는 producer 코드 위치(최상위 `eventName`)를 버전별로 등록한 표(ADR 0020 §4·부록 A.2).
 * 행 번호는 버전마다 다르다 — 등록하지 않은 버전·위치는 판별 근거가 없다(`operation = unknown`).
 * 근거는 테스트 리소스의 `otlp-v2/codex/PROFILE-EVIDENCE.md` 5 절이다.
 */
internal object CodexLocations {

	/** 위치가 가리키는 emitter. */
	enum class ApiRequestEmitter {
		/** 모델 목록 요청 전용 emitter — `endpoint` 는 `/models` 상수다. */
		MODELS_LIST,

		/** 세션의 API 요청 emitter — 여러 요청 경로가 공유하고 `endpoint` 가 경로를 싣는다. */
		SESSION_REQUEST,
	}

	private const val MODELS_ENDPOINT_FILE = "event model-provider/src/models_endpoint.rs"
	private const val SESSION_TELEMETRY_FILE = "event otel/src/events/session_telemetry.rs"

	private val BEFORE_0_155 = mapOf(
		"$MODELS_ENDPOINT_FILE:204" to ApiRequestEmitter.MODELS_LIST,
		"$SESSION_TELEMETRY_FILE:665" to ApiRequestEmitter.SESSION_REQUEST,
	)
	private val FROM_0_155 = mapOf(
		"$MODELS_ENDPOINT_FILE:230" to ApiRequestEmitter.MODELS_LIST,
		"$SESSION_TELEMETRY_FILE:688" to ApiRequestEmitter.SESSION_REQUEST,
	)

	private val API_REQUEST: Map<String, Map<String, ApiRequestEmitter>> = mapOf(
		"0.153.4" to BEFORE_0_155,
		"0.154.0-alpha.6.2" to BEFORE_0_155,
		"0.155.0-alpha.2.6" to FROM_0_155,
		"0.155.0-alpha.9.2" to FROM_0_155,
	)

	init {
		// 프로파일이 적용되는 버전마다 위치가 등록돼 있어야 한다.
		check(API_REQUEST.keys == CodexProfile.VERIFIED_VERSIONS.toSet()) { "위치 표의 버전이 프로파일과 다르다: ${API_REQUEST.keys}" }
	}

	fun apiRequestEmitter(version: String?, eventName: String): ApiRequestEmitter? = API_REQUEST[version]?.get(eventName)
}
