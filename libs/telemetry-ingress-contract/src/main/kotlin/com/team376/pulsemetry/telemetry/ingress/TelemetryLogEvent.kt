package com.team376.pulsemetry.telemetry.ingress

/**
 * Gateway·Consumer 의 운영 로그 한 줄. 평문 `event=<code>` 와 `key=value` 를 공백으로 잇는다(ADR 0066 Decision 10).
 *
 * infra 가 CloudWatch Logs metric filter 의 **term match** 로 [code] 를 세고 경보를 건다 — 코드 문자열을 바꾸면 경보가 조용히
 * 멈춘다. 바꾸려면 infra 의 filter 와 함께 바꾼다.
 *
 * ## 담지 않는 것
 *
 * payload·Bearer·예외 메시지 원문. 값은 [format] 이 안전한 문자만 남기고 자른다 — 호출자가 실수로 원문을 넘겨도 공백·따옴표·
 * 줄바꿈이 사라져 한 줄 형식이 깨지지 않는다. 그래도 **원문을 넘기지 않는 것이 호출자의 책임이다.** 예외는 클래스 단순 이름만 남긴다.
 */
public enum class TelemetryLogEvent(public val code: String) {
	/** Gateway 가 401·405·413·415·400 으로 돌려보냈다. */
	GATEWAY_REJECTED("telemetry_gateway_rejected"),

	/** Gateway 가 기한 안에 broker ack 를 받지 못해 503 을 냈다. */
	PUBLISH_FAILED("telemetry_publish_failed"),

	/** Consumer 가 영구 오류로 확정했다 — 미적재. */
	PERMANENT_FAILURE("telemetry_permanent_failure"),

	/** Consumer 의 한 시도가 일시 오류로 실패했다. 다음 시도가 남아 있다. 경보 대상이 아니다. */
	TRANSIENT_FAILURE("telemetry_transient_failure"),

	/** Consumer 가 최초 + 3회 재시도를 모두 실패하고 확정했다 — 미적재. */
	RETRY_EXHAUSTED("telemetry_retry_exhausted"),

	/** offset commit 이 실패했다. 그 record 는 다시 처리된다. */
	COMMIT_FAILED("telemetry_commit_failed"),

	/**
	 * 플랫폼 trace 로 sampled 된 요청·record 의 처리가 끝났다(ADR 0069). 활성 span 안에서 한 줄 남겨 로그 → trace 이동의 기준점이 된다.
	 * 경보 대상이 아니다. 실패 코드 다섯과 term 이 겹치지 않도록 `telemetry_` 접두사를 쓰지 않는다.
	 */
	PLATFORM_TRACE_COMPLETED("platform_trace_completed"),
	;

	/** 값이 null 이면 `-` 다. 순서는 넘긴 순서 그대로다. */
	public fun format(vararg fields: Pair<String, Any?>): String = buildString {
		append("event=").append(code)
		for ((key, value) in fields) {
			append(' ').append(key).append('=').append(safe(value))
		}
	}

	public companion object {
		private const val MAX_VALUE_LENGTH = 128
		private val UNSAFE = Regex("""[^A-Za-z0-9._:/@-]""")

		/** 예외는 클래스 단순 이름만. 메시지에 payload·토큰이 섞일 수 있다. */
		public fun causeOf(throwable: Throwable?): String = throwable?.javaClass?.simpleName?.ifBlank { "Unknown" } ?: "-"

		private fun safe(value: Any?): String {
			val text = value?.toString() ?: return "-"
			if (text.isEmpty()) return "-"
			return UNSAFE.replace(text.take(MAX_VALUE_LENGTH), "_")
		}
	}
}
