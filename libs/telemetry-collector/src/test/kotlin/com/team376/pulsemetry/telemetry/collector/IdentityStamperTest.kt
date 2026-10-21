package com.team376.pulsemetry.telemetry.collector

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 스탬프 규칙을 직접 고정한다(ADR 0016). 수집 코어는 이제 언제나 검증된 신원 둘을 넘기지만(ADR 0067), 규칙 자체는
 * 문자열 위에서 정의돼 있어 빈 값·없는 값의 동작을 여기서 따로 지킨다 — 예전에는 핸들러 테스트가 인증 없는 호출로 이것을 봤다.
 */
class IdentityStamperTest {

	private fun request(vararg resourceAttributes: Pair<String, String>): ExportLogsServiceRequest.Builder {
		val attributes = resourceAttributes.joinToString(",") { (key, value) ->
			"""{"key":"$key","value":{"stringValue":"$value"}}"""
		}
		val json = """
			{"resourceLogs":[{"resource":{"attributes":[$attributes]},
			 "scopeLogs":[{"logRecords":[{"body":{"stringValue":"hi"}}]}]}]}
		""".trimIndent().toByteArray()
		return ExportLogsServiceRequest.newBuilder().also { OtlpJson.fromJson(json, it) }
	}

	private fun keys(builder: ExportLogsServiceRequest.Builder) =
		builder.getResourceLogs(0).resource.attributesList.map { it.key to it.value.stringValue }

	@Test
	@DisplayName("빈 값은 건너뛴다 — 없는 신원으로 있는 값을 지우지 않는다")
	fun anEmptyValueIsNotStamped() {
		val builder = request("service.name" to "claude-code", "tenant.id" to "self")

		IdentityStamper.stamp(builder, StampedIdentity(tenantId = "", installationId = null))

		assertThat(keys(builder)).containsExactly("service.name" to "claude-code", "tenant.id" to "self")
	}

	@Test
	@DisplayName("둘 다 없으면 아무것도 심지 않는다")
	fun nothingToStamp() {
		val builder = request("service.name" to "claude-code")

		IdentityStamper.stamp(builder, StampedIdentity(tenantId = null, installationId = null))

		assertThat(keys(builder)).containsExactly("service.name" to "claude-code")
	}

	@Test
	@DisplayName("있는 값만 덮고 없는 키는 뒤에 더한다 — 항목의 위치와 개수는 그대로다")
	fun upsertKeepsPositions() {
		val builder = request("tenant.id" to "self", "service.name" to "claude-code")

		IdentityStamper.stamp(builder, StampedIdentity(tenantId = "ten-real", installationId = "inst-real"))

		assertThat(keys(builder)).containsExactly(
			"tenant.id" to "ten-real",
			"service.name" to "claude-code",
			"developer.installation_id" to "inst-real",
		)
	}
}
