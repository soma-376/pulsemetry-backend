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

/** 모델 허용 목록·승인 도구 목록 (ADR 0051 §2). 저장한 적 없는 목록은 비어 있고 판 0 이다. 항목은 코드 포인트 순서다. */
data class AlertList(val listId: String, val version: Long, val entries: List<String>, val updatedAt: Instant?)

/** 알림 규칙·목록 읽기와 켜기 판정 (ADR 0051). 명령(enrollment-api)과 설정 조회(dashboard-api)가 같은 판정을 쓴다. */
object AlertRules {
	const val SPEND_SPIKE = "spend_spike"
	const val QUOTA_EXCEEDED = "quota_exceeded"
	const val MODEL_NOT_ALLOWED = "model_not_allowed"
	const val TOOL_UNAPPROVED = "tool_unapproved"

	const val ALLOWED_MODELS = "allowed_models"
	const val APPROVED_TOOLS = "approved_tools"
	val LISTS = listOf(ALLOWED_MODELS, APPROVED_TOOLS)

	/** 목록에 기대는 규칙 — 목록이 비면 켤 수 없다. */
	val REQUIRED_LIST = mapOf(MODEL_NOT_ALLOWED to ALLOWED_MODELS, TOOL_UNAPPROVED to APPROVED_TOOLS)

	const val COMPLETENESS_NOT_AVAILABLE = "completeness_not_available"
	const val SOURCE_NOT_AVAILABLE = "source_not_available"
	const val ALLOWED_MODELS_NOT_CONFIGURED = "allowed_models_not_configured"
	const val APPROVED_TOOLS_NOT_CONFIGURED = "approved_tools_not_configured"

	/** 네 규칙의 현재 상태, 정의 표의 순서. */
	fun rules(jdbc: JdbcClient, tenant: UUID): List<AlertRuleState> {
		val lists = lists(jdbc, tenant)
		val stored = jdbc.sql("SELECT rule_id, enabled, version, updated_at FROM enrollment.organization_alert_rules WHERE tenant_id = :tenant")
			.param("tenant", tenant).query { rs, _ -> rs.getString("rule_id") to Triple(rs.getBoolean("enabled"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant()) }
			.list().toMap()
		// 수집 구간을 보고한 설치가 있어야 완전한 날이 생긴다(ADR 0040·0042).
		val reported = jdbc.sql("""SELECT EXISTS (SELECT 1 FROM enrollment.installation_collection_segments s
				JOIN enrollment.installations i ON i.id = s.installation_id WHERE i.tenant_id = :tenant)""")
			.param("tenant", tenant).query(Boolean::class.java).single()
		return jdbc.sql("""SELECT rule_id, category, threshold_value, threshold_unit, evaluation_window, comparison_window
				FROM enrollment.alert_rule_definitions ORDER BY position""")
			.query { rs, _ ->
				val ruleId = rs.getString("rule_id")
				val state = stored[ruleId]
				val reason = reason(ruleId, lists, reported)
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

	/** 두 목록. 저장한 적 없는 목록은 비어 있다. */
	fun lists(jdbc: JdbcClient, tenant: UUID): Map<String, AlertList> {
		val headers = jdbc.sql("SELECT list_id, version, updated_at FROM enrollment.organization_alert_lists WHERE tenant_id = :tenant")
			.param("tenant", tenant).query { rs, _ -> rs.getString("list_id") to (rs.getLong("version") to rs.getTimestamp("updated_at").toInstant()) }.list().toMap()
		val entries = jdbc.sql("SELECT list_id, entry FROM enrollment.organization_alert_list_entries WHERE tenant_id = :tenant")
			.param("tenant", tenant).query { rs, _ -> rs.getString("list_id") to rs.getString("entry") }.list()
			.groupBy({ it.first }, { it.second })
		return LISTS.associateWith { id -> AlertList(id, headers[id]?.first ?: 0, entries[id].orEmpty().sorted(), headers[id]?.second) }
	}

	/** 켤 수 없는 사유(ADR 0051 §1). null 이면 켤 수 있다. */
	internal fun reason(ruleId: String, lists: Map<String, AlertList>, reported: Boolean): String? = when (ruleId) {
		SPEND_SPIKE -> COMPLETENESS_NOT_AVAILABLE.takeUnless { reported }
		// 한도·쿼터 초과를 가리키는 관측이 실캡처로 확인되지 않았다 — 근거가 생기기 전까지 켤 수 없다.
		QUOTA_EXCEEDED -> SOURCE_NOT_AVAILABLE
		MODEL_NOT_ALLOWED -> ALLOWED_MODELS_NOT_CONFIGURED.takeIf { lists[ALLOWED_MODELS]?.entries.isNullOrEmpty() }
		TOOL_UNAPPROVED -> APPROVED_TOOLS_NOT_CONFIGURED.takeIf { lists[APPROVED_TOOLS]?.entries.isNullOrEmpty() }
		else -> SOURCE_NOT_AVAILABLE
	}

	/** 목록 항목이 형식(ADR 0051 §2)을 어기는가 — 개수·중복·길이·공백·제어 문자·끝이 아닌 자리의 `*`·`*` 하나뿐인 항목. */
	internal fun invalidEntries(entries: List<String>): Boolean =
		entries.size > MAX_ENTRIES || entries.toSet().size != entries.size || entries.any { entry ->
			entry.isEmpty() || entry.length > MAX_ENTRY_LENGTH || entry != entry.trim() || entry.any(Char::isISOControl) ||
				entry.dropLast(1).contains('*') || entry == "*"
		}

	const val MAX_ENTRIES = 200
	const val MAX_ENTRY_LENGTH = 200
}

/**
 * 알림 규칙의 켜기·끄기와 목록 교체 (ADR 0051 §3). 두 명령은 조직 행을 잠가 직렬화한다 — 켜진 규칙은 언제나 켤 수 있는 규칙이다.
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

	/** 목록을 통째로 바꾸고, 바뀐 목록과 네 규칙의 상태를 같은 트랜잭션에서 돌려준다. 내용이 같으면 판 그대로다. 켜진 규칙이 기대는 목록은 비울 수 없다(422). */
	fun replaceList(tenant: UUID, actor: UUID, listId: String, expectedVersion: Long, entries: List<String>): Pair<AlertList, List<AlertRuleState>> = write {
		lockOrganization(tenant, actor)
		if (listId !in AlertRules.LISTS) fail("not_found", 404)
		if (AlertRules.invalidEntries(entries)) fail("invalid_request", 400, "entries")
		val current = AlertRules.lists(jdbc, tenant).getValue(listId)
		if (expectedVersion != current.version) fail("version_conflict", 409, "expectedVersion")
		if (entries.sorted() != current.entries) {
			val dependent = AlertRules.REQUIRED_LIST.filterValues { it == listId }.keys
			if (entries.isEmpty() && AlertRules.rules(jdbc, tenant).any { it.ruleId in dependent && it.enabled }) fail("alert_list_in_use", 422, "entries")
			jdbc.sql("""INSERT INTO enrollment.organization_alert_lists (tenant_id, list_id, version, updated_at, updated_by)
					VALUES (:tenant, :list, 1, :now, :actor)
					ON CONFLICT (tenant_id, list_id) DO UPDATE SET version = organization_alert_lists.version + 1,
						updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by""")
				.param("tenant", tenant).param("list", listId).param("now", Timestamp.from(now())).param("actor", actor).update()
			jdbc.sql("DELETE FROM enrollment.organization_alert_list_entries WHERE tenant_id = :tenant AND list_id = :list")
				.param("tenant", tenant).param("list", listId).update()
			entries.forEach { entry ->
				jdbc.sql("INSERT INTO enrollment.organization_alert_list_entries (tenant_id, list_id, entry) VALUES (:tenant, :list, :entry)")
					.param("tenant", tenant).param("list", listId).param("entry", entry).update()
			}
		}
		AlertRules.lists(jdbc, tenant).getValue(listId) to AlertRules.rules(jdbc, tenant)
	}

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
