package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.snapshot.SnapshotBuilder
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * 선택 기간과 무관한 **현재 상태** 목록(팀 선택지·회수 후보)의 snapshot ID. 요청서의 `CurrentMeta.snapshotId` 는 목록 순서를 고정하는 수단인데,
 * 이 목록들은 사용량 payload 가 필요 없으므로 저장소에 쓰지 않는 불투명 토큰 — 목록 종류·tenant·기준 시각 — 으로 둔다.
 *
 * 유효기간은 사용량 snapshot 과 같은 10분이다. 다른 종류·다른 tenant·해석 불가·만료는 모두 409 — 없는 snapshot 과 같다.
 * 기준 시각은 밀리초로 담는다. 첫 응답도 같은 정밀도의 시각을 쓴다.
 */
class CurrentStateTokens(
	private val mapper: ObjectMapper,
) {

	data class Token(val value: String, val asOf: Instant)

	fun issue(kind: String, tenantId: UUID, now: Instant): Token {
		val asOf = now.truncatedTo(ChronoUnit.MILLIS)
		return Token(encode(kind, tenantId, asOf), asOf)
	}

	/** [value] 가 있으면 해석하고(아니면 409), 없으면 새로 낸다. */
	fun resolve(kind: String, tenantId: UUID, value: String?, now: Instant): Token =
		if (value == null) issue(kind, tenantId, now) else Token(value, decode(kind, value, tenantId, now))

	private fun encode(kind: String, tenantId: UUID, asOf: Instant): String {
		val json = mapper.createObjectNode().put("v", 1).put("k", kind).put("t", tenantId.toString()).put("a", asOf.toEpochMilli())
		return PREFIX + ENCODER.encodeToString(mapper.writeValueAsBytes(json))
	}

	private fun decode(kind: String, token: String, tenantId: UUID, now: Instant): Instant {
		if (!token.startsWith(PREFIX) || token.length > MAX_LENGTH) throw expired()
		val json = runCatching { mapper.readTree(DECODER.decode(token.removePrefix(PREFIX))) }.getOrNull() ?: throw expired()
		val asOf = json.get("a")?.takeIf { it.isNumber }?.let { Instant.ofEpochMilli(it.asLong()) }
		val valid = json.path("v").asInt() == 1 && json.path("k").asString() == kind && json.path("t").asString() == tenantId.toString() &&
			asOf != null && !asOf.isAfter(now) && now.isBefore(asOf + SnapshotBuilder.API_LIFETIME)
		if (!valid) throw expired()
		return asOf
	}

	private fun expired() = DashboardException(ErrorCode.SNAPSHOT_EXPIRED)

	private companion object {
		const val PREFIX = "d."
		const val MAX_LENGTH = 512
		val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
		val DECODER: Base64.Decoder = Base64.getUrlDecoder()
	}
}
