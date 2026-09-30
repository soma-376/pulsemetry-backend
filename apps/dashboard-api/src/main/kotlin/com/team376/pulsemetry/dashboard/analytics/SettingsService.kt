package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.persistence.enrollment.management.ContractStatus
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.FieldErrorCode
import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.PageCursor
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.PageRequest
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.persistence.enrollment.installation.AppliedPolicyVersion
import com.team376.pulsemetry.persistence.enrollment.management.VendorCatalog
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 설정 화면의 **현재 상태 조회** (설정 명세). 계약·정책·설치는 enrollment 를 원천 계정으로 읽고, 벤더 관측 지표는 카탈로그의 명시 매핑으로 잇는
 * 관측만 쓴다([VendorObservations], ADR 0044). 관리 명령은 enrollment-api가 처리한다.
 *
 * - **벤더** = 조직에 등록한 제품. 관측만 있는 제품이나 기존 기간 약정은 등록 제품으로 자동 변환하지 않는다 — 관측됐지만 등록하지 않은 제품은
 *   `summary.detectedProducts`, 매핑 없는 관측은 `summary.unmappedObservations` 로 따로 낸다.
 * - **계약** = 등록 UUID의 버전 이력. 조회 기준 시각까지의 최신 버전으로 읽는다.
 * - **관측 지표**(처음·마지막 관측, 최근 7·30일 구성원, 관측 상태) = 그 제품의 도구가 관측됐다는 사실이다. 좌석 배정·청구와 잇지 않는다 —
 *   `activeSeats7d` 는 채우지 않는다. 기준 시각마다 한 번 계산해 고정하고 같은 기준 시각의 목록·상세가 그 값을 읽는다. 실제 청구액도 추측하지 않는다.
 * - **수집 정책** = 활성 manifest(설치에 내려가는 설정)다. 원문 수집 선택은 manifest의 프롬프트·응답 설정
 *   중 하나라도 켜져 있는지다. 회수 유휴 일수는 구성원 화면과 같은 설정 값이다. 보존 기간 설정 원천이 없어 집계·원문 보존은 null(무기한 — 지우는
 *   작업이 없다)이다. 활성 manifest 가 없으면 설정이 없는 조직이라 404 다.
 * - **정책 적용**: 설치가 지금 집행하는 판([AppliedPolicyVersion] — 마지막 설치 보고의 판, 보고가 없으면 적용 확인 기록, ADR 0040·0043)이
 *   목표 이상이면 applied, 낮으면 outdated, 알 수 없으면 unknown 이다 — 적용 보고가 없으면 적용 완료로 추정하지 않는다.
 *   `lastHeartbeatAt` 은 마지막 설치 보고를 받은 시각이다(ledger 수신 시각을 넣지 않는다). 설치 버전은 마지막으로 보고한(없으면 등록 때의) `client_version` 이다.
 * - **업데이트 안내**(ADR 0043): 안내 채널(관리 기능 + 메일)이 켜진 배포에서만 보낼 수 있다. 적용이 확인되지 않은(outdated·unknown) 설치 중
 *   구성원이 활성인 설치만 `canNotify` 다. 안내는 확인을 부탁하는 메일이지 원격 업데이트가 아니다.
 * - **알림 규칙**: 규칙 저장소·평가 엔진이 없어 모두 비활성·`unavailable` 이다. 임계값과 창은 요청서의 초기 제안 기준이다.
 */
class SettingsService(
	private val source: JdbcClient,
	private val observations: VendorObservations,
	private val frames: AnalyticsFrames,
	private val tokens: CurrentStateTokens,
	private val codec: PageCursorCodec,
	private val mapper: ObjectMapper,
	private val idleDays: Int,
	private val clock: Clock,
	private val managementEnabled: Boolean = false,
	private val catalog: VendorCatalog,
	/** 설치 업데이트 안내를 보낼 채널이 있는가(관리 기능과 메일이 모두 켜진 배포 — ADR 0043). */
	private val notificationsEnabled: Boolean = false,
) {

	/** 설치 목록의 정책 적용 필터. 없으면 전부다. */
	enum class PolicyStatus(val wire: String) {
		APPLIED("applied"), OUTDATED("outdated"), UNKNOWN("unknown");

		companion object {
			val BY_WIRE = entries.associateBy { it.wire }
		}
	}

	fun settings(organization: Organization): SettingsResponse {
		val now = clock.instant()
		val token = tokens.issue(SETTINGS_KIND, organization.id, now)
		val manifest = activeManifest(organization.id) ?: throw DashboardException(ErrorCode.NOT_FOUND)
		val observed = observations.fix(organization.id, token.asOf, QueryReader.SEOUL)
		val vendors = vendors(organization.id, token.asOf, observed)
		val activeVendors = vendors.filter { it.contractStatus == ContractStatus.active }
		val rollout = rollout(organization.id, manifest.version)
		val products = if (managementEnabled) catalog.snapshot().products else emptyList()
		return SettingsResponse(
			meta = meta(organization, now, token),
			ingest = frames.ingest(organization, now),
			capabilities = SettingsCapabilities(editContracts = managementEnabled, editCollectionPolicy = managementEnabled, editAlertRules = false, notifyInstallations = notificationsEnabled),
			summary = SettingsSummary(
				configuredVendors = vendors.count { it.state == CONFIGURED }.toLong(),
				unconfiguredVendors = vendors.count { it.state != CONFIGURED }.toLong(),
				monthlySeatFeeUsd = activeVendors.takeIf { it.all { vendor -> vendor.contract?.monthlySeatFeeUsd != null } }
					?.sumOf { it.contract!!.monthlySeatFeeUsd!!.toBigDecimal() }?.let(Money::format),
				contractedSeats = activeVendors.takeIf { it.all { vendor -> vendor.contract != null } }
					?.sumOf { it.contract!!.tiers.sumOf { tier -> tier.seats } },
				activeSeats7d = null,
				meteredMonthToDate = Section(Availability.UNAVAILABLE, Availability.SOURCE_NOT_AVAILABLE, null),
				detectedProducts = detected(observed, vendors.map { it.kind }.toSet()),
				unmappedObservations = observed.observed[VendorObservations.UNMAPPED]?.let { row ->
					val shown = observed.present(VendorObservations.UNMAPPED)
					UnmappedObservations(row.observedProducts, row.firstSeenAt.toString(), row.lastSeenAt.toString(), shown.users7d, shown.users30d, shown.observation)
				},
			),
			catalog = if (managementEnabled) Catalog(
				products.map { CatalogKind(it.id, it.displayName) },
				products.flatMap { product -> product.plans.map { CatalogPlan(it.id, product.id, it.displayName, it.billing, it.separateUsageBilling) } },
			) else Catalog(source.sql("SELECT id,display_name FROM enrollment.vendor_catalog_vendors " +
				"WHERE id IN (SELECT unnest(enum_range(NULL::enrollment.ai_vendor))::text) ORDER BY id")
				.query { r, _ -> CatalogKind(r.getString("id"), r.getString("display_name")) }.list(), emptyList()),
			vendors = page(vendors, PageRequest(FIRST_PAGE, null), token, VENDORS_SCOPE),
			collectionPolicy = CollectionPolicy(
				version = manifest.version,
				collectRawContent = manifest.collectsRawContent,
				reclaimIdleDays = idleDays,
				aggregateRetentionMonths = null,
				rawContentRetentionDays = null,
				effectiveAt = manifest.effectiveAt.toString(),
				updatedBy = manifest.createdBy.toString(),
			),
			policyRollout = rollout,
			alertRules = ALERT_RULES,
		)
	}

	fun vendors(organization: Organization, page: PageRequest, snapshotId: String?): VendorsResponse {
		val now = clock.instant()
		val requested = snapshotId ?: page.cursor?.snapshotId
		val token = tokens.resolve(SETTINGS_KIND, organization.id, requested, now)
		return VendorsResponse(meta(organization, now, token), page(vendors(organization.id, token.asOf, observed(organization, token, requested != null)), page, token, VENDORS_SCOPE))
	}

	/** [snapshotId] 를 주면 그 기준 시각의 관측 고정을 쓴다 — 목록과 같은 값이다. 없으면 새 기준 시각이다. */
	fun vendor(organization: Organization, vendorId: String, snapshotId: String? = null): VendorResponse {
		val now = clock.instant()
		val token = tokens.resolve(SETTINGS_KIND, organization.id, snapshotId, now)
		val vendor = vendors(organization.id, token.asOf, observed(organization, token, snapshotId != null)).firstOrNull { it.vendorId == vendorId }
			?: throw DashboardException(ErrorCode.NOT_FOUND)
		return VendorResponse(meta(organization, now, token), vendor)
	}

	/**
	 * 기준 시각의 관측 고정(ADR 0044). 이어 읽는 요청([resumed])은 고정된 값만 쓴다 — 없으면(만료·정리·이전 판) 409 이고 다시 계산해 섞지 않는다.
	 * 새 기준 시각은 계산해 고정한다.
	 */
	private fun observed(organization: Organization, token: CurrentStateTokens.Token, resumed: Boolean): VendorObservations.Fixed =
		if (resumed) observations.read(organization.id, token.asOf) ?: throw DashboardException(ErrorCode.SNAPSHOT_EXPIRED)
		else observations.fix(organization.id, token.asOf, QueryReader.SEOUL)

	/** 매핑으로 관측됐지만 등록하지 않은 카탈로그 제품. 카탈로그 표시 순서. */
	private fun detected(fixed: VendorObservations.Fixed, registered: Set<String>): List<DetectedProduct> {
		val names = catalog.snapshot().products.associate { it.id to it.displayName }
		val order = catalog.snapshot().products.withIndex().associate { it.value.id to it.index }
		return fixed.observed.keys.filter { it != VendorObservations.UNMAPPED && it !in registered }
			.sortedBy { order[it] ?: Int.MAX_VALUE }
			.map { kind ->
				val shown = fixed.present(kind)
				DetectedProduct(kind, names[kind] ?: kind, DETECTED_UNCONFIGURED, shown.firstSeenAt?.toString(), shown.lastSeenAt?.toString(), shown.users7d, shown.users30d, shown.observation)
			}
	}

	fun installations(organization: Organization, status: PolicyStatus?, page: PageRequest, snapshotId: String?): InstallationsResponse {
		val now = clock.instant()
		val token = tokens.resolve(INSTALLATIONS_KIND, organization.id, snapshotId ?: page.cursor?.snapshotId, now)
		val manifest = activeManifest(organization.id) ?: throw DashboardException(ErrorCode.NOT_FOUND)
		// 처음 둘은 필터가 outdated 뿐이던 때의 범위 이름이다 — 그 cursor 가 그대로 이어진다.
		val scope = when (status) {
			null -> "installations:outdated=false"
			PolicyStatus.OUTDATED -> "installations:outdated=true"
			else -> "installations:status=${status.wire}"
		}
		page.cursor?.let { if (it.snapshotId != token.value || it.scope != scope) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR) }
		val after = page.cursor?.after?.lastOrNull()?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR) }

		val rows = installationRows(organization.id, token.asOf, manifest.version)
			.filter { status == null || it.status(manifest.version) == status }
			.sortedBy { it.id.toString() }
		val start = after?.let { id -> rows.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) ?: throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR) } ?: 0
		val items = rows.drop(start).take(page.limit)
		val next = if (start + items.size < rows.size) codec.encode(PageCursor(token.value, scope, listOf(items.last().id.toString()))) else null
		return InstallationsResponse(
			meta = meta(organization, now, token),
			desiredPolicyVersion = manifest.version,
			installations = Page(
				items.map {
					InstallationRow(
						installationId = it.id.toString(),
						memberId = it.memberId.toString(),
						account = it.account,
						team = it.team,
						agentVersion = it.clientVersion,
						appliedPolicyVersion = it.appliedVersion,
						lastHeartbeatAt = it.lastHeartbeatAt?.toString(),
						canNotify = notificationsEnabled && it.memberActive && it.status(manifest.version) != PolicyStatus.APPLIED,
					)
				},
				rows.size,
				next,
			),
		)
	}

	private fun meta(organization: Organization, now: Instant, token: CurrentStateTokens.Token) =
		CurrentMeta(organization.id.toString(), now.toString(), token.asOf.toString(), token.value, AnalyticsFrames.USD, QueryReader.SEOUL_ID)

	private fun page(vendors: List<Vendor>, page: PageRequest, token: CurrentStateTokens.Token, scope: String): Page<Vendor> {
		page.cursor?.let { if (it.snapshotId != token.value || it.scope != scope) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR) }
		val start = page.cursor?.let { cursor ->
			vendors.indexOfFirst { it.vendorId == cursor.after.lastOrNull() }.takeIf { it >= 0 }?.plus(1) ?: throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR)
		} ?: 0
		val items = vendors.drop(start).take(page.limit)
		val next = if (start + items.size < vendors.size) codec.encode(PageCursor(token.value, scope, listOf(items.last().vendorId))) else null
		return Page(items, vendors.size, next)
	}

	/** 등록 제품만 반환한다. 계약 UUID 오름차순. 관측 지표는 그 제품(`kind`)의 명시 매핑으로만 붙인다(ADR 0044). */
	private fun vendors(tenantId: UUID, asOf: Instant, observed: VendorObservations.Fixed): List<Vendor> = managedVendors(tenantId, asOf).map { vendor ->
		val shown = observed.present(vendor.kind)
		vendor.copy(firstSeenAt = shown.firstSeenAt?.toString(), lastSeenAt = shown.lastSeenAt?.toString(), activeUsers7d = shown.users7d, activeUsers30d = shown.users30d,
			observation = shown.observation)
	}.sortedBy { it.vendorId }

	/** 저장된 버전 이력을 기준 시각으로 읽는다. 공급사·모델로 추측해 사용량을 붙이지 않는다. */
	private fun managedVendors(tenantId: UUID, asOf: Instant): List<Vendor> = source.sql("""
		SELECT v.vendor_id,v.kind,v.source,c.* FROM enrollment.managed_vendors v
		JOIN LATERAL (SELECT version,display_name,contract::text,archived FROM enrollment.vendor_contract_versions
		 WHERE tenant_id=v.tenant_id AND vendor_id=v.vendor_id AND recorded_at<=:as_of ORDER BY version DESC LIMIT 1) c ON true
		WHERE v.tenant_id=:tenant AND v.created_at<=:as_of AND NOT c.archived
	""").param("tenant", tenantId).param("as_of", Timestamp.from(asOf)).query { rs, _ ->
		val contract = rs.getString("contract")?.let { mapper.readValue(it, VendorContract::class.java) }
		val status = ContractStatus.at(contract?.effectiveFrom?.let(LocalDate::parse), contract?.effectiveTo?.let(LocalDate::parse), asOf)
		Vendor(rs.getString("vendor_id"), rs.getString("display_name"), rs.getString("kind"), rs.getString("source"), rs.getLong("version"),
			null, null, null, null, VendorObservations.UNOBSERVED, if (status == ContractStatus.active) CONFIGURED else NEEDS_REVIEW, contract, status,
			Section(Availability.UNAVAILABLE, Availability.SOURCE_NOT_AVAILABLE, null), emptyList())
	}.list()

	private data class Manifest(val version: Long, val collectsRawContent: Boolean, val effectiveAt: Instant, val createdBy: UUID)

	private fun activeManifest(tenantId: UUID): Manifest? =
		source.sql(
			"SELECT version, manifest::text AS manifest, coalesce(activated_at, created_at) AS effective_at, created_by_member_id " +
				"FROM enrollment.manifests WHERE tenant_id = :tenant AND is_active",
		)
			.param("tenant", tenantId)
			.query { rs, _ ->
				val privacy = mapper.readTree(rs.getString("manifest")).path("privacy")
				Manifest(
					version = rs.getLong("version"),
					collectsRawContent = RAW_CONTENT_FLAGS.any { privacy.path(it).asBoolean(false) },
					effectiveAt = rs.getObject("effective_at", OffsetDateTime::class.java).toInstant(),
					createdBy = rs.getObject("created_by_member_id", UUID::class.java),
				)
			}
			.optional()
			.orElse(null)

	/** 설치 목록의 필터와 같은 분류로 센다 — 집계와 목록이 어긋나지 않는다. */
	private fun rollout(tenantId: UUID, desired: Long): PolicyRollout {
		val rows = installationRows(tenantId, clock.instant(), desired)
		val applied = rows.count { it.status(desired) == PolicyStatus.APPLIED }.toLong()
		val outdated = rows.count { it.status(desired) == PolicyStatus.OUTDATED }.toLong()
		return PolicyRollout(desired, rows.size.toLong(), applied, outdated, rows.size - applied - outdated)
	}

	private data class Installation(
		val id: UUID,
		val memberId: UUID,
		val account: String,
		val clientVersion: String?,
		val appliedVersion: Long?,
		val team: TeamRef,
		val lastHeartbeatAt: Instant?,
		val memberActive: Boolean,
	) {
		/** 적용이 확인된 판이 없으면 unknown — 적용 완료나 미적용으로 추정하지 않는다. */
		fun status(desired: Long): PolicyStatus = when {
			appliedVersion == null -> PolicyStatus.UNKNOWN
			appliedVersion >= desired -> PolicyStatus.APPLIED
			else -> PolicyStatus.OUTDATED
		}
	}

	/** 활성 설치와 지금 집행하는 판([AppliedPolicyVersion]), 마지막 설치 보고 시각, 구성원의 상태와 현재 팀. */
	private fun installationRows(tenantId: UUID, asOf: Instant, desired: Long): List<Installation> =
		source.sql(
			"""
			SELECT i.id, i.member_id, m.email, i.client_version, (m.status = 'active') AS member_active,
			       (SELECT h.received_at FROM enrollment.installation_heartbeats h WHERE h.installation_id = i.id) AS last_heartbeat_at,
			       ${AppliedPolicyVersion.SQL} AS applied_version,
			       ARRAY(SELECT DISTINCT t.name FROM enrollment.team_memberships tm JOIN enrollment.teams t ON t.id = tm.team_id
			              WHERE tm.member_id = i.member_id AND tm.joined_at <= :as_of AND (tm.left_at IS NULL OR tm.left_at > :as_of)
			              ORDER BY t.name) AS team_names,
			       ARRAY(SELECT DISTINCT tm.team_id FROM enrollment.team_memberships tm
			              WHERE tm.member_id = i.member_id AND tm.joined_at <= :as_of AND (tm.left_at IS NULL OR tm.left_at > :as_of)) AS team_ids
			FROM enrollment.installations i JOIN enrollment.members m ON m.id = i.member_id
			WHERE i.tenant_id = :tenant AND i.status = 'active'
			""".trimIndent(),
		)
			.param("tenant", tenantId)
			.param("as_of", Timestamp.from(asOf))
			.query { rs, _ ->
				val names = (rs.getArray("team_names").array as Array<*>).map { it as String }
				val ids = (rs.getArray("team_ids").array as Array<*>).map { it as UUID }
				Installation(
					id = rs.getObject("id", UUID::class.java),
					memberId = rs.getObject("member_id", UUID::class.java),
					account = rs.getString("email"),
					clientVersion = rs.getString("client_version"),
					appliedVersion = rs.getLong("applied_version").takeUnless { rs.wasNull() },
					lastHeartbeatAt = rs.getTimestamp("last_heartbeat_at")?.toInstant(),
					memberActive = rs.getBoolean("member_active"),
					// 현재 소속이 여럿이면 하나를 고르지 않는다(구성원 화면과 같은 규칙).
					team = when (ids.size) {
						0 -> TeamRef(null, TeamsService.UNASSIGNED_NAME)
						1 -> TeamRef(ids.single().toString(), names.single())
						else -> TeamRef(null, names.joinToString(", "))
					},
				)
			}
			.list()

	companion object {
		const val SETTINGS_KIND = "settings"
		const val INSTALLATIONS_KIND = "installations"
		private const val VENDORS_SCOPE = "vendors"
		private const val FIRST_PAGE = 20
		private const val CURSOR = "cursor"
		private const val CONFIGURED = "configured"
		private const val NEEDS_REVIEW = "needs_review"
		private const val DETECTED_UNCONFIGURED = "detected_unconfigured"

		/** 온보딩의 원문 선택은 프롬프트·응답만 제어한다(ADR 0029). 다른 privacy 설정은 그대로 둔다. */
		private val RAW_CONTENT_FLAGS = listOf(
			"collect_user_prompts", "collect_assistant_responses",
		)

		/** 요청서의 초기 제안 기준. 규칙 저장소·평가 엔진이 없어 모두 비활성이다. */
		val ALERT_RULES = listOf(
			AlertRule("spend_spike", 0, false, Availability.UNAVAILABLE, Availability.EVALUATION_NOT_CONFIGURED, AlertThreshold(0.4, "ratio"),
				"last_complete_7_calendar_days", "preceding_7_calendar_days"),
			AlertRule("quota_exceeded", 0, false, Availability.UNAVAILABLE, Availability.SOURCE_NOT_AVAILABLE, AlertThreshold(5.0, "users"),
				"rolling_24_hours", null),
			AlertRule("model_not_allowed", 0, false, Availability.UNAVAILABLE, Availability.EVALUATION_NOT_CONFIGURED, AlertThreshold(1.0, "events"),
				"rolling_24_hours", null),
			AlertRule("tool_unapproved", 0, false, Availability.UNAVAILABLE, Availability.EVALUATION_NOT_CONFIGURED, AlertThreshold(1.0, "events"),
				"rolling_24_hours", null),
		)
	}
}
