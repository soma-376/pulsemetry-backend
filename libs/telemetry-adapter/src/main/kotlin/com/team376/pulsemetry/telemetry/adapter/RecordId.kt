package com.team376.pulsemetry.telemetry.adapter

import com.team376.pulsemetry.telemetry.adapter.model.Envelope
import com.team376.pulsemetry.telemetry.adapter.model.Lifecycle
import com.team376.pulsemetry.telemetry.adapter.model.LlmCall
import com.team376.pulsemetry.telemetry.adapter.model.LlmResponse
import com.team376.pulsemetry.telemetry.adapter.model.MetricPoint
import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedLog
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedMetric
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedSpan
import com.team376.pulsemetry.telemetry.adapter.model.Prompt
import com.team376.pulsemetry.telemetry.adapter.model.ToolCall
import com.team376.pulsemetry.telemetry.adapter.model.ToolDecision

/**
 * `envelope.record_id` — 결정적 멱등 키.
 *
 * ClickHouse `enriched_events` 가 ReplacingMergeTree 라 **이 키가 같은 두 행은 하나로
 * 합쳐진다.** 재적재·replay 가 멱등해지는 근거이자, 어긋나면 조용히 과소·과대집계가 되는
 * 지점이다.
 *
 * 재료는 전부 입력에서 파생된 값뿐이다 — 벽시계도 난수도 처리 순서도 섞이지 않는다.
 *
 * ## 언제 부르는가
 *
 * 어댑터가 payload 를 채운 **직후**, 페어링 **전에** 부른다. 그래야 키가 "그 레코드 자체"를
 * 가리키고 이후 `call_id` mutation 에 흔들리지 않는다.
 *
 * ## 이식할 때 어긋나기 쉬운 곳 셋
 *
 * 1. **해시 재료가 Python `str()` 표기다.** `null` 은 `"None"`, `true` 는 `"True"` 다.
 *    Kotlin 기본 표기를 쓰면 전 이벤트가 어긋난다 — [Stringify] 를 통과시킨다.
 * 2. **나노초가 Double 을 거친다.** `timestamp` 는 이미 `nanos / 1e9` 로 깎인 값이고
 *    여기서 다시 `* 1e9` 한다. 1.7e18 은 Double 이 정확히 담는 범위(2^53) 밖이라 값이
 *    한 번 더 깎이는데, **그 깎임까지 같아야** 키가 맞는다. 정수로 되돌리지 마라.
 * 3. **스팬은 `sequence` 자리에 `-` 를 넣고 판별자 뒤에 `span_id` 를 붙인다.** 스팬은
 *    순번이 없어 (session, ts) 만으로 겹칠 수 있기 때문이다.
 */
internal object RecordId {

	/** payload 확정 후 [Envelope.recordId] 를 계산해 박는다. 받은 이벤트를 그대로 돌려준다. */
	fun <T : Normalized> finalize(event: T): T {
		val envelope = event.envelope
		val (sequence, type, discriminator) = idemFields(event)
		// 이식 원본과 같은 경로로 깎는다 — 위 KDoc 2번.
		// rint 는 짝수 반올림이다. Python round() 와 같은 규칙이라 .5 에서도 갈리지 않는다
		// (Math.round 는 올림이라 다르다).
		val nanos = Math.rint(envelope.timestamp * 1e9).toLong()
		val parts = listOf(
			envelope.identity.tenantId ?: Envelope.TENANT_KEY_FALLBACK,
			envelope.client.product,
			envelope.sessionId,
			sequence,
			nanos.toString(),
			type,
			discriminator,
		).joinToString("|")
		envelope.recordId = "idem-" + SourceRecordId.sha1Hex(parts).substring(0, 16)
		return event
	}

	/** 타입별로 (순번, type 값, 판별자) 를 뽑는다. */
	private fun idemFields(event: Normalized): Triple<String, String, String> = when (event) {
		is NormalizedMetric ->
			Triple("-", "metric", discriminator(event.point, callId = null))

		is NormalizedSpan -> Triple(
			"-",
			event.type.wire,
			// 스팬은 순번이 없다 → 전역 유일한 span_id 로 가른다.
			"${discriminator(event.payload, event.callId)}|${Stringify.of(event.spanId)}",
		)

		is NormalizedLog -> Triple(
			event.sequence?.toString() ?: "-",
			event.type.wire,
			discriminator(event.payload, event.callId),
		)
	}

	/**
	 * 같은 (session, sequence, ts) 에 이벤트가 겹칠 때 키를 가르는 꼬리표.
	 *
	 * payload 와 조인 키의 **내용에서만** 뽑는다 — 재읽기해도 같아야 하기 때문이다.
	 */
	private fun discriminator(payload: Any?, callId: String?): String = when (payload) {
		is LlmCall -> listOf(
			payload.model,
			payload.tokens.input,
			payload.tokens.output,
			payload.tokens.cacheRead,
			payload.tokens.cacheCreate,
			// request_id 가 있으면 섞어 유일성을 강화한다. 없으면 None 그대로.
			payload.requestId,
		).joinToString("|") { Stringify.of(it) }

		is LlmResponse ->
			"${Stringify.of(payload.model)}|${Stringify.of(payload.responseLength)}|" +
				Stringify.of(payload.requestId)

		is ToolCall -> "${Stringify.of(callId)}|${Stringify.of(payload.success)}"

		is ToolDecision ->
			"${Stringify.of(callId)}|${payload.decision.wire}|" +
				"${payload.decidedBy.wire}|${payload.scope.wire}"

		is Prompt -> "${Stringify.of(payload.length)}|${Stringify.of(payload.commandName)}"

		is Lifecycle -> payload.kind

		is MetricPoint -> {
			// Python sorted() 와 같은 코드 포인트 순이다 — [CanonicalJson.CODE_POINT_ORDER] 참고.
			val dimensions = payload.attrs.entries
				.sortedWith(compareBy(CanonicalJson.CODE_POINT_ORDER) { it.key })
				.joinToString("|") { "${it.key}=${it.value}" }
			"${payload.name}|${Stringify.of(payload.value)}|" +
				"${Stringify.of(payload.count)}|${Stringify.of(payload.sum)}|$dimensions"
		}

		else -> "-"
	}
}
