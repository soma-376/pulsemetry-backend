package com.team376.pulsemetry.persistence.enrollment.alert

import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.persistence.enrollment.seat.RegisteredProducts
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 알림 규칙 하나의 현재 상태 (ADR 0051 §1·§4). [available] 은 "켤 수 있는 근거가 있는가"이고 아니면 [reason] 이 그 사유다.
 * 저장한 적 없는 규칙은 꺼짐·판 0 이다. 임계값·창은 정의 표(기준 데이터)의 값이다.
 */
data class AlertRuleState(
	val ruleId: String,
	val category: String,
	val enabled: Boolean,
	val version: Long,
	val available: Boolean,
	val reason: String?,
	val thresholdValue: BigDecimal,
	val thresholdUnit: String,
	val evaluationWindow: String,
	val comparisonWindow: String?,
	val updatedAt: Instant?,
)

/** 확인한 알림 (ADR 0051 §6). [version] 은 확인할 때 본 알림의 판이다. */
data class AlertAcknowledgement(val alertId: UUID, val version: Long, val acknowledgedBy: UUID, val acknowledgedAt: Instant)

/** 등록 제품 기준 알림 규칙 읽기와 켜기 판정 (허브 ADR 0008). 명령(enrollment-api)과 설정 조회(dashboard-api)가 같은 판정을 쓴다. */
object AlertRules {
	const val SPEND_SPIKE = "spend_spike"
	const val QUOTA_EXCEEDED = "quota_exceeded"
	const val MODEL_NOT_ALLOWED = "model_not_allowed"
	const val TOOL_UNAPPROVED = "tool_unapproved"

	const val PRODUCT_NOT_REGISTERED = "product_not_registered"

	const val COMPLETENESS_NOT_AVAILABLE = "completeness_not_available"
	const val SOURCE_NOT_AVAILABLE = "source_not_available"
	const val REGISTERED_PRODUCTS_NOT_CONFIGURED = "registered_products_not_configured"

	/** 활성 규칙의 현재 상태, 정의 표의 순서. */
	fun rules(jdbc: JdbcClient, tenant: UUID): List<AlertRuleState> {
		val registered = registeredProducts(jdbc, tenant)
		val stored = jdbc.sql("SELECT rule_id, enabled, version, updated_at FROM enrollment.organization_alert_rules WHERE tenant_id = :tenant")
			.param("tenant", tenant).query { rs, _ -> rs.getString("rule_id") to Triple(rs.getBoolean("enabled"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant()) }
			.list().toMap()
		// 수집 구간을 보고한 설치가 있어야 완전한 날이 생긴다(ADR 0040·0042).
		val reported = jdbc.sql("""SELECT EXISTS (SELECT 1 FROM enrollment.installation_collection_segments s
				JOIN enrollment.installations i ON i.id = s.installation_id WHERE i.tenant_id = :tenant)""")
			.param("tenant", tenant).query(Boolean::class.java).single()
		return jdbc.sql("""SELECT rule_id, category, threshold_value, threshold_unit, evaluation_window, comparison_window
				FROM enrollment.alert_rule_definitions WHERE active ORDER BY position""")
			.query { rs, _ ->
				val ruleId = rs.getString("rule_id")
				val state = stored[ruleId]
				val reason = reason(ruleId, registered.isNotEmpty(), reported)
				AlertRuleState(
					ruleId = ruleId,
					category = rs.getString("category"),
					enabled = state?.first ?: false,
					version = state?.second ?: 0,
					available = reason == null,
					reason = reason,
					thresholdValue = rs.getBigDecimal("threshold_value").stripTrailingZeros(),
					thresholdUnit = rs.getString("threshold_unit"),
					evaluationWindow = rs.getString("evaluation_window"),
					comparisonWindow = rs.getString("comparison_window"),
					updatedAt = state?.third,
				)
			}.list()
	}

	/** 현재 등록 제품. 계약 만료·미입력은 제품 등록을 해제하지 않는다(허브 ADR 0008). */
	fun registeredProducts(jdbc: JdbcClient, tenant: UUID): Set<String> =
		jdbc.sql("SELECT kind FROM enrollment.managed_vendors WHERE tenant_id = :tenant AND NOT archived")
			.param("tenant", tenant).query { rs, _ -> rs.getString("kind") }.list().toSet()

	/** 켤 수 없는 사유. null이면 켤 수 있다. */
	internal fun reason(ruleId: String, registered: Boolean, reported: Boolean): String? = when (ruleId) {
		SPEND_SPIKE -> COMPLETENESS_NOT_AVAILABLE.takeUnless { reported }
		QUOTA_EXCEEDED -> SOURCE_NOT_AVAILABLE
		PRODUCT_NOT_REGISTERED -> REGISTERED_PRODUCTS_NOT_CONFIGURED.takeUnless { registered }
		else -> SOURCE_NOT_AVAILABLE
	}

}

/**
 * 알림 규칙의 켜기·끄기 (허브 ADR 0008). 명령은 조직 행을 잠가 직렬화한다. 등록이 사라지면 평가는 사유와 함께 건너뛴다.
 * 실패는 [ManagementException](코드·상태·필드)이다.
 */
class AlertRuleStore(private val jdbc: JdbcClient, manager: PlatformTransactionManager, private val clock: Clock) {
	private val tx = TransactionTemplate(manager)

	/** 규칙을 켜거나 끈다. 값이 같으면 판 그대로 현재 상태다. 켤 수 없는 규칙을 켜면 422 다(끄기는 언제나 된다). */
	fun setEnabled(tenant: UUID, actor: UUID, ruleId: String, expectedVersion: Long, enabled: Boolean): AlertRuleState = write {
		lockOrganization(tenant, actor)
		val current = AlertRules.rules(jdbc, tenant).firstOrNull { it.ruleId == ruleId } ?: fail("not_found", 404)
		if (expectedVersion != current.version) fail("version_conflict", 409, "expectedVersion")
		if (enabled != current.enabled) {
			if (enabled && !current.available) throw ManagementException("alert_rule_unavailable", 422, "enabled", mapOf("reason" to current.reason))
			jdbc.sql("""INSERT INTO enrollment.organization_alert_rules (tenant_id, rule_id, enabled, version, updated_at, updated_by)
					VALUES (:tenant, :rule, :enabled, 1, :now, :actor)
					ON CONFLICT (tenant_id, rule_id) DO UPDATE SET enabled = EXCLUDED.enabled, version = organization_alert_rules.version + 1,
						updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by""")
				.param("tenant", tenant).param("rule", ruleId).param("enabled", enabled).param("now", Timestamp.from(now())).param("actor", actor).update()
		}
		AlertRules.rules(jdbc, tenant).first { it.ruleId == ruleId }
	}

	/**
	 * 알림을 확인한다 (ADR 0051 §6). 알림은 dashboard-api 의 평가 기록(`dashboard_cache.alerts`)을 읽어 그 조직의 것인지 본다 — 없거나 다른 조직이면 404.
	 * 이미 확인했으면 그 기록을 그대로 돌려준다(멱등). 아니면 [expectedVersion] 이 지금 알림의 판이어야 한다(409 — 그 사이 묶음이 늘었다).
	 */
	fun acknowledge(tenant: UUID, actor: UUID, alertId: UUID, expectedVersion: Long): AlertAcknowledgement = write {
		lockOrganization(tenant, actor)
		val version = jdbc.sql("SELECT version FROM dashboard_cache.alerts WHERE alert_id = :id AND tenant_id = :tenant AND qualified")
			.param("id", alertId).param("tenant", tenant).query(Long::class.java).optional().orElse(null) ?: fail("not_found", 404)
		val existing = acknowledgement(tenant, alertId)
		if (existing != null) return@write existing
		if (expectedVersion != version) fail("version_conflict", 409, "expectedVersion")
		jdbc.sql("""INSERT INTO enrollment.alert_acknowledgements (tenant_id, alert_id, alert_version, acknowledged_by, acknowledged_at)
				VALUES (:tenant, :id, :version, :actor, :now)""")
			.param("tenant", tenant).param("id", alertId).param("version", version).param("actor", actor).param("now", Timestamp.from(now())).update()
		acknowledgement(tenant, alertId)!!
	}

	private fun acknowledgement(tenant: UUID, alertId: UUID): AlertAcknowledgement? =
		jdbc.sql("SELECT alert_version, acknowledged_by, acknowledged_at FROM enrollment.alert_acknowledgements WHERE tenant_id = :tenant AND alert_id = :id")
			.param("tenant", tenant).param("id", alertId)
			.query { rs, _ -> AlertAcknowledgement(alertId, rs.getLong("alert_version"), rs.getObject("acknowledged_by", UUID::class.java), rs.getTimestamp("acknowledged_at").toInstant()) }
			.optional().orElse(null)

	/** 활성 조직의 owner·admin 인지 DB 에서 다시 확인하고 조직 행을 잠근다. */
	private fun lockOrganization(tenant: UUID, actor: UUID) {
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		jdbc.sql("SELECT id FROM enrollment.tenants WHERE id = :tenant FOR UPDATE").param("tenant", tenant).query(UUID::class.java).single()
	}

	private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
	@Suppress("UNCHECKED_CAST")
	private fun <T> write(block: () -> T): T = tx.execute { block() } as T
	private fun fail(code: String, status: Int, field: String? = null): Nothing = throw ManagementException(code, status, field)
}
