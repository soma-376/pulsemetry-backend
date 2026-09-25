package com.team376.pulsemetry.dashboard.support

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 원천 저장소의 시드 — 분석 테이블 두 개(`default`)와 enrollment. 앱 경로가 아니라 관리 연결로 쓴다.
 *
 * 분석 행은 필요한 열만 적는다. 나머지 열은 ClickHouse 기본값이다 — 조회가 읽지 않는 열이다. 값의 의미는 ADR 0020 의 어휘다.
 */
object SourceFixtures {

	private val DATETIME64 = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC)

	/** 테스트가 고른 이름에서 64자 관측 ID 를 만든다. 같은 이름 = 같은 관측. */
	fun observationId(name: String): String =
		MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }

	data class Event(
		val name: String,
		val sourceTime: Instant,
		val usage: Boolean = true,
		val rowVersion: Long = 1,
		val recordStatus: String = "active",
		val mappingStatus: String = "mapped",
		val product: String = "claude_code",
		val serviceName: String? = "claude-code",
		val productVersion: String? = "2.1.282",
		val model: String? = "claude-sonnet-4",
		val memberId: UUID? = null,
		val teamId: UUID? = null,
		val sessionId: String? = "session-1",
		val tokensInput: Long? = 100,
		val tokensOutput: Long? = 10,
		val tokensCacheRead: Long? = 0,
		val tokensCacheCreate: Long? = 0,
		val tokensInputUncached: Long? = 100,
		val tokensTotalDerived: Long? = 110,
		val semanticsProfile: String? = "claude-code-exclusive-v1",
		val costEstimatedUsd: BigDecimal? = null,
		val pricingVersion: String? = null,
		val receivedTime: Instant = sourceTime,
		val qualityFlags: List<String> = emptyList(),
	)

	fun insertEvents(tenantId: UUID, vararg events: Event) {
		val lines = events.joinToString("\n") { event ->
			json(
				"tenant_id" to tenantId.toString(),
				"installation_id" to "installation-1",
				"observation_id" to observationId(event.name),
				"row_version" to event.rowVersion,
				"source_time" to DATETIME64.format(event.sourceTime),
				"received_time" to DATETIME64.format(event.receivedTime),
				"record_status" to event.recordStatus,
				"mapping_status" to event.mappingStatus,
				"signal" to "log",
				"event_type" to if (event.usage) "model.response.usage" else "tool.result",
				"usage_role" to if (event.usage) "primary" else "none",
				"usage_scope" to if (event.usage) "response" else "unknown",
				"workload_kind" to "main",
				"product" to event.product,
				"surface" to "unknown",
				"service_name" to event.serviceName,
				"product_version" to event.productVersion,
				"model" to event.model,
				"member_id" to event.memberId?.toString(),
				"team_id_as_of" to event.teamId?.toString(),
				"session_id" to event.sessionId,
				"session_id_namespace" to event.sessionId?.let { "claude_code.session" },
				"tokens_input" to event.tokensInput,
				"tokens_output" to event.tokensOutput,
				"tokens_cache_read" to event.tokensCacheRead,
				"tokens_cache_create" to event.tokensCacheCreate,
				"tokens_input_uncached" to event.tokensInputUncached,
				"tokens_total_derived" to event.tokensTotalDerived,
				"semantics_profile" to event.semanticsProfile,
				"cost_estimated_usd" to event.costEstimatedUsd,
				"pricing_version" to event.pricingVersion,
				"quality_flags" to event.qualityFlags,
			)
		}
		DashboardTestStores.clickHouseAdmin("INSERT INTO default.telemetry_events FORMAT JSONEachRow\n$lines")
	}

	fun insertMetricPoint(tenantId: UUID, name: String, sourceTime: Instant, recordStatus: String = "active") {
		val line = json(
			"tenant_id" to tenantId.toString(),
			"installation_id" to "installation-1",
			"observation_id" to observationId(name),
			"row_version" to 1L,
			"source_time" to DATETIME64.format(sourceTime),
			"received_time" to DATETIME64.format(sourceTime),
			"record_status" to recordStatus,
			"mapping_status" to "mapped",
			"signal" to "metric",
			"product" to "claude_code",
		)
		DashboardTestStores.clickHouseAdmin("INSERT INTO default.telemetry_metric_points FORMAT JSONEachRow\n$line")
	}

	fun insertTeam(tenantId: UUID, name: String, archived: Boolean = false): UUID {
		val id = UUID.randomUUID()
		DashboardTestStores.writer.sql(
			"INSERT INTO enrollment.teams (id, tenant_id, name, status) VALUES (:id, :tenant, :name, CAST(:status AS enrollment.team_status))",
		).param("id", id).param("tenant", tenantId).param("name", name).param("status", if (archived) "archived" else "active").update()
		return id
	}

	fun insertMember(tenantId: UUID, email: String, role: String = "member", status: String = "active", displayName: String? = null): UUID {
		val id = UUID.randomUUID()
		DashboardTestStores.writer.sql(
			"INSERT INTO enrollment.members (id, tenant_id, email, display_name, role, status) " +
				"VALUES (:id, :tenant, :email, :name, CAST(:role AS enrollment.member_role), CAST(:status AS enrollment.member_status))",
		).param("id", id).param("tenant", tenantId).param("email", email).param("name", displayName)
			.param("role", role).param("status", status).update()
		return id
	}

	fun insertMembership(teamId: UUID, memberId: UUID, joinedAt: Instant, leftAt: Instant? = null) {
		DashboardTestStores.writer.sql(
			"INSERT INTO enrollment.team_memberships (team_id, member_id, joined_at, left_at) VALUES (:team, :member, :joined, :left)",
		).param("team", teamId).param("member", memberId)
			.param("joined", java.sql.Timestamp.from(joinedAt)).param("left", leftAt?.let(java.sql.Timestamp::from)).update()
	}

	fun setBoundary(tenantId: UUID, deletedBefore: Instant, policyEpoch: Long) {
		DashboardTestStores.writer.sql(
			"INSERT INTO telemetry_ops.tenant_retention_boundary (tenant_id, deleted_before, policy_epoch) VALUES (:tenant, :before, :epoch) " +
				"ON CONFLICT (tenant_id) DO UPDATE SET deleted_before = EXCLUDED.deleted_before, policy_epoch = EXCLUDED.policy_epoch",
		).param("tenant", tenantId).param("before", java.sql.Timestamp.from(deletedBefore)).param("epoch", policyEpoch).update()
	}

	private fun json(vararg fields: Pair<String, Any?>): String =
		fields.joinToString(",", "{", "}") { (key, value) -> "\"$key\":${literal(value)}" }

	private fun literal(value: Any?): String = when (value) {
		null -> "null"
		is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
		is BigDecimal -> value.toPlainString()
		is Number -> value.toString()
		is List<*> -> value.joinToString(",", "[", "]") { literal(it) }
		else -> error("지원하지 않는 값: $value")
	}
}
