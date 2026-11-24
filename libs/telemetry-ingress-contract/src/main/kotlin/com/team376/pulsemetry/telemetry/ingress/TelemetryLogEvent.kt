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

	/**
	 * Consumer 가 record 의 `traceparent` header 가 손상돼(중복·길이·형식) 부모 없이 새 trace 로 처리를 시작했다(ADR 0069).
	 * Gateway trace 와 끊긴 이유를 찾는 진단 로그다. sampling 과 무관하게 시도마다 남고 경보 대상이 아니다. header 가 없으면 남기지 않는다.
	 */
	PLATFORM_TRACE_CONTEXT_REPLACED("platform_trace_context_replaced"),

	/**
	 * Kafka 배치가 ClickHouse 적재 없이 S3 넘김 목록에 레코드를 올리고 확정했다(PROJ-341) — ClickHouse 미적재. 배치마다 한 줄이고
	 * 경보 대상이다. 재적재는 넘김 목록을 읽는 별도 모듈의 몫이다.
	 */
	ARCHIVE_HANDOFF("telemetry_archive_handoff"),

	/** Kafka 배치가 넘길 근거가 없는 장애(인증 DB·S3·넘김 목록 쓰기)로 긴 대기에 들어갔다(PROJ-341). 배치마다 한 번이다. */
	BATCH_WAITING("telemetry_batch_waiting"),

	/** Kafka 배치가 분류하지 않은 예외로 실패했다(PROJ-341). 같은 배치에서 처음 났을 때 한 번 남긴다. 레코드를 건너뛰지 않는다. */
	BATCH_UNEXPECTED_FAILURE("telemetry_batch_unexpected_failure"),

	/** Kafka 배치가 호출 마감에 닿았는데 진척이 없었다(PROJ-341). 같은 배치에서 반복되면 남긴다. */
	BATCH_STALLED("telemetry_batch_stalled"),

	/** 대기하던 Kafka 배치가 실제 쓰기로 복구해 확정했다(PROJ-341). 경보 대상이 아니다. */
	BATCH_RECOVERED("ingest_batch_recovered"),

	/** 소비자의 ClickHouse 차단기가 열렸다 — 이후 배치는 탐침 전까지 넘김으로 간다(PROJ-341). 경보 대상이 아니다(넘김 줄이 경보다). */
	BREAKER_OPENED("ingest_breaker_opened"),

	/** 소비자의 ClickHouse 차단기가 실제 쓰기 성공으로 닫혔다(PROJ-341). 경보 대상이 아니다. */
	BREAKER_CLOSED("ingest_breaker_closed"),

	/**
	 * 미완료 배치의 재시도 중 재할당이 일어나 옛 배치의 ack 등록을 건너뛰었다(PROJ-341). 커밋 시도는 0건이고 다음 소유자가 확정
	 * 위치부터 이어간다. 경보 대상이 아니다.
	 */
	REBALANCE_ACK_SKIPPED("kafka_rebalance_ack_skipped"),
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
