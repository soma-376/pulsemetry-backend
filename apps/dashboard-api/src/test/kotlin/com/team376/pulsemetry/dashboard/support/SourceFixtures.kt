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

	/** tenant 생애 요약 한 행(ADR 0021). 운영에서는 ingest 가 쓴다. */
	fun setSummary(
		tenantId: UUID,
		firstReceivedAt: Instant? = null,
		firstObservedAt: Instant? = null,
		lastReceivedAt: Instant? = null,
		hasPreLedgerHistory: Boolean = false,
	) {
		DashboardTestStores.writer.sql(
			"INSERT INTO telemetry_ops.tenant_ingest_summary (tenant_id, first_received_at, first_observed_at, last_received_at, has_pre_ledger_history) " +
				"VALUES (:tenant, :first_received, :first_observed, :last_received, :pre) " +
				"ON CONFLICT (tenant_id) DO UPDATE SET first_received_at=EXCLUDED.first_received_at, " +
				"first_observed_at=EXCLUDED.first_observed_at, last_received_at=EXCLUDED.last_received_at, " +
				"has_pre_ledger_history=EXCLUDED.has_pre_ledger_history",
		).param("tenant", tenantId)
			.param("first_received", firstReceivedAt?.let(java.sql.Timestamp::from))
			.param("first_observed", firstObservedAt?.let(java.sql.Timestamp::from))
			.param("last_received", lastReceivedAt?.let(java.sql.Timestamp::from))
			.param("pre", hasPreLedgerHistory)
			.update()
	}

	private const val BACKFILL = "pre-ledger-enriched-events"

	/** 요약 도입 전 이력의 백필이 끝났다는 기록(전역 한 행). 없으면 요약 부재를 "수집한 적 없음"으로 읽을 수 없다. */
	fun completeBackfill() {
		DashboardTestStores.writer.sql(
			"INSERT INTO telemetry_ops.tenant_summary_backfill (backfill, source, completed_at, tenants_marked) " +
				"VALUES ('$BACKFILL', 'enriched_events', now(), 0) ON CONFLICT (backfill) DO NOTHING",
		).update()
	}

	fun removeBackfill() {
		DashboardTestStores.writer.sql("DELETE FROM telemetry_ops.tenant_summary_backfill WHERE backfill = '$BACKFILL'").update()
	}

	/** 수신 ledger 한 행(ADR 0021). */
	fun insertLedger(tenantId: UUID, installationId: UUID, receivedTime: Instant) {
		val line = json(
			"tenant_id" to tenantId.toString(),
			"installation_id" to installationId.toString(),
			"received_time" to DATETIME64.format(receivedTime),
			"receipt_id" to UUID.randomUUID().toString(),
			"signal" to "logs",
			"product" to "claude_code",
			"record_count" to 1L,
			"rejected_count" to 0L,
			"masking_version" to "masking-v2",
		)
		DashboardTestStores.clickHouseAdmin("INSERT INTO default.telemetry_ingest_ledger FORMAT JSONEachRow\n$line")
	}

	/**
	 * 활성(또는 비활성) manifest 한 판. [privacy] 는 manifest 의 `privacy` 객체 JSON 이다. [signals] 를 주면 `signals` 객체를 싣는다.
	 * [activatedAt] 을 주면 그 시각에 활성화된 판이다(주지 않으면 활성 판만 지금 활성화된 것으로 둔다).
	 */
	fun insertManifest(tenantId: UUID, version: Int, createdBy: UUID, privacy: String = "{}", active: Boolean = true,
		signals: String? = null, activatedAt: Instant? = null): UUID {
		val id = UUID.randomUUID()
		DashboardTestStores.writer.sql(
			"INSERT INTO enrollment.manifests (id, tenant_id, version, manifest, is_active, created_by_member_id, activated_at) " +
				"VALUES (:id, :tenant, :version, CAST(:manifest AS jsonb), :active, :created_by, COALESCE(CAST(:activated_at AS timestamptz), CASE WHEN :active THEN now() END))",
		).param("id", id).param("tenant", tenantId).param("version", version)
			.param("manifest", """{"schema_version":1,"config_revision":$version,"privacy":$privacy${signals?.let { ""","signals":$it""" } ?: ""}}""")
			.param("active", active).param("created_by", createdBy).param("activated_at", activatedAt?.toString(), java.sql.Types.VARCHAR).update()
		return id
	}

	/** 설치의 등록 시각과 폐기 시각을 바꾼다. 폐기 시각을 주면 상태도 폐기다. */
	fun setInstallationTimes(installationId: UUID, createdAt: Instant, revokedAt: Instant? = null) {
		DashboardTestStores.writer.sql(
			"UPDATE enrollment.installations SET created_at = :created, revoked_at = CAST(:revoked AS timestamptz), " +
				"status = CAST(CASE WHEN CAST(:revoked AS timestamptz) IS NULL THEN 'active' ELSE 'revoked' END AS enrollment.installation_status) WHERE id = :id",
		).param("id", installationId).param("created", java.sql.Timestamp.from(createdAt))
			.param("revoked", revokedAt?.toString(), java.sql.Types.VARCHAR).update()
	}

	fun insertAssignment(installationId: UUID, manifestId: UUID, appliedAt: Instant?) {
		DashboardTestStores.writer.sql(
			"INSERT INTO enrollment.installation_manifest_assignments (installation_id, manifest_id, applied_at) VALUES (:installation, :manifest, :applied)",
		).param("installation", installationId).param("manifest", manifestId).param("applied", appliedAt?.let(java.sql.Timestamp::from)).update()
	}

	fun insertContract(tenantId: UUID, vendor: String, status: String = "active", startsAt: String = "2026-01-01", endsAt: String? = null) {
		DashboardTestStores.writer.sql(
			"INSERT INTO enrollment.contracts (tenant_id, vendor, contract_type, name, contracted_at, starts_at, ends_at, status) " +
				"VALUES (:tenant, CAST(:vendor AS enrollment.ai_vendor), 'term_commitment', :name, CAST(:starts AS date), CAST(:starts AS date), " +
				"CAST(:ends AS date), CAST(:status AS enrollment.contract_status))",
		).param("tenant", tenantId).param("vendor", vendor).param("name", "$vendor 계약").param("starts", startsAt).param("ends", endsAt)
			.param("status", status).update()
	}

	/** 구성원의 설치 하나 — 초대를 거쳐야 하는 외래 키를 채운다. */
	fun insertInstallation(tenantId: UUID, memberId: UUID, clientVersion: String? = null, status: String = "active"): UUID {
		val invitation = UUID.randomUUID()
		DashboardTestStores.writer.sql(
			"INSERT INTO enrollment.invitations (id, tenant_id, target_member_id, created_by_member_id, code_hash, expires_at) " +
				"VALUES (:id, :tenant, :member, :member, :hash, now() + interval '1 day')",
		).param("id", invitation).param("tenant", tenantId).param("member", memberId).param("hash", observationId(invitation.toString())).update()
		val installation = UUID.randomUUID()
		DashboardTestStores.writer.sql(
			"INSERT INTO enrollment.installations (id, tenant_id, member_id, invitation_id, platform, client_version, status) " +
				"VALUES (:id, :tenant, :member, :invitation, 'linux', :client_version, CAST(:status AS enrollment.installation_status))",
		).param("id", installation).param("tenant", tenantId).param("member", memberId).param("invitation", invitation)
			.param("client_version", clientVersion).param("status", status).update()
		return installation
	}

	/**
	 * 설치의 마지막 보고 한 행(ADR 0040). 운영에서는 enrollment-api 가 쓴다. 시각은 서버 시각이다.
	 * [pendingSince] 를 주면 그때부터 전달 대기가 이어진 것이다(ADR 0041).
	 */
	fun setHeartbeat(
		installationId: UUID,
		receivedAt: Instant,
		mode: String = "local",
		forwarding: Boolean = true,
		receivingSince: Instant? = receivedAt.minusSeconds(3600),
		lastDeliveredAt: Instant? = receivedAt.minusSeconds(30),
		pendingSince: Instant? = null,
	) {
		DashboardTestStores.writer.sql(
			"INSERT INTO enrollment.installation_heartbeats (installation_id, received_at, run_id, daemon_version, architecture, applied_config_revision, " +
				"mode, forwarding, receiving_since, delivered, lost, pending, last_delivered_at, pending_since) " +
				"VALUES (:id, :received, 'b3f1c2a49d5e4f60a1b2c3d4e5f60718', '0.2.0', 'arm64', 1, :mode, :forwarding, :since, 0, 0, :pending, :last, :pending_since) " +
				"ON CONFLICT (installation_id) DO UPDATE SET received_at=EXCLUDED.received_at, mode=EXCLUDED.mode, forwarding=EXCLUDED.forwarding, " +
				"receiving_since=EXCLUDED.receiving_since, pending=EXCLUDED.pending, last_delivered_at=EXCLUDED.last_delivered_at, pending_since=EXCLUDED.pending_since",
		).param("id", installationId).param("received", java.sql.Timestamp.from(receivedAt)).param("mode", mode).param("forwarding", forwarding)
			.param("since", receivingSince?.let(java.sql.Timestamp::from)).param("pending", if (pendingSince == null) 0L else 1L)
			.param("last", lastDeliveredAt?.let(java.sql.Timestamp::from)).param("pending_since", pendingSince?.let(java.sql.Timestamp::from)).update()
	}

	/** 설치가 수집 중이었다고 보고로 확인된 구간 한 행(ADR 0040). [lost] 가 0 이 아니면 손실 구간이다. */
	fun insertSegment(installationId: UUID, fromAt: Instant, toAt: Instant, lost: Long = 0) {
		DashboardTestStores.writer.sql(
			"INSERT INTO enrollment.installation_collection_segments (id, installation_id, run_id, from_at, to_at, lost) " +
				"VALUES (:id, :installation, 'b3f1c2a49d5e4f60a1b2c3d4e5f60718', :from, :to, :lost)",
		).param("id", UUID.randomUUID()).param("installation", installationId)
			.param("from", java.sql.Timestamp.from(fromAt)).param("to", java.sql.Timestamp.from(toAt)).param("lost", lost).update()
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
