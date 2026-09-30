package com.team376.pulsemetry.dashboard.snapshot

import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 참조 데이터를 snapshot 에 복제한다(ADR 0023 §2) — 원천(`enrollment`)은 원천 계정으로 읽고 캐시 계정으로 쓴다. 두 계정의 권한이 섞이지 않는다.
 *
 * - 팀: tenant 의 모든 팀. 보관된 팀도 넣는다 — 사용 이력의 `team_id_as_of` 가 가리키는 과거 팀의 표시 이름이 필요하다.
 * - 로스터: tenant 의 모든 구성원(사용량이 없는 사람 포함). 현재 팀은 [asOf] 에 유효한 소속의 **서로 다른** 팀 ID 다 —
 *   같은 팀의 소속 행이 겹쳐도 하나다.
 *
 * - 관측 제품 매핑: 카탈로그의 명시 매핑(ADR 0044)과 카탈로그 제품의 표시 이름·순서. 제품 축(ADR 0045)이 이것만으로 잇는다.
 *
 * 두 저장소를 한 트랜잭션으로 읽지 않는다. 일관성의 범위는 이 복제본 자체다.
 */
class SnapshotReferenceCopier(
	private val source: JdbcClient,
	private val cache: JdbcClient,
) {

	data class Copied(val teams: Int, val members: Int, val products: Int = 0)

	fun copy(snapshotId: String, tenantId: UUID, asOf: Instant): Copied {
		val teams = source.sql("SELECT id, name, status::text AS status FROM enrollment.teams WHERE tenant_id = :tenant")
			.param("tenant", tenantId)
			.query { rs, _ -> Team(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("status") == ARCHIVED) }
			.list()
		val members = source.sql(
			"""
			SELECT m.id, m.email, m.display_name, m.role::text AS role, m.status::text AS status, m.updated_at,
			       ARRAY(
			           SELECT DISTINCT tm.team_id FROM enrollment.team_memberships tm
			           WHERE tm.member_id = m.id AND tm.joined_at <= :as_of AND (tm.left_at IS NULL OR tm.left_at > :as_of)
			           ORDER BY tm.team_id
			       ) AS current_team_ids
			FROM enrollment.members m
			WHERE m.tenant_id = :tenant
			""".trimIndent(),
		)
			.param("tenant", tenantId)
			.param("as_of", Timestamp.from(asOf))
			.query { rs, _ ->
				Member(
					id = rs.getObject("id", UUID::class.java),
					account = rs.getString("email"),
					displayName = rs.getString("display_name"),
					role = rs.getString("role"),
					status = rs.getString("status"),
					updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
					currentTeamIds = (rs.getArray("current_team_ids").array as Array<*>).map { it as UUID },
				)
			}
			.list()

		for (team in teams) {
			cache.sql("INSERT INTO dashboard_cache.snapshot_teams (snapshot_id, team_id, name, archived) VALUES (:snapshot, :team, :name, :archived)")
				.param("snapshot", snapshotId)
				.param("team", team.id)
				.param("name", team.name)
				.param("archived", team.archived)
				.update()
		}
		for (member in members) {
			cache.sql(
				"""
				INSERT INTO dashboard_cache.snapshot_members
				    (snapshot_id, member_id, account, display_name, role, status, current_team_ids, updated_at)
				VALUES (:snapshot, :member, :account, :display_name,
				    CAST(:role AS dashboard_cache.member_role), CAST(:status AS dashboard_cache.member_status),
				    CAST(:teams AS uuid[]), :updated_at)
				""".trimIndent(),
			)
				.param("snapshot", snapshotId)
				.param("member", member.id)
				.param("account", member.account)
				.param("display_name", member.displayName)
				.param("role", member.role)
				.param("status", member.status)
				.param("teams", member.currentTeamIds.joinToString(",", "{", "}"))
				.param("updated_at", member.updatedAt)
				.update()
		}
		val products = source.sql(
			"""SELECT o.observed_product, o.product_id, p.display_name, p.sort_order FROM enrollment.vendor_catalog_observed_products o
			JOIN enrollment.vendor_catalog_products p ON p.id = o.product_id""",
		)
			.query { rs, _ -> listOf(rs.getString("observed_product"), rs.getString("product_id"), rs.getString("display_name")) to rs.getInt("sort_order") }
			.list()
		for ((product, order) in products) {
			cache.sql(
				"""INSERT INTO dashboard_cache.snapshot_products (snapshot_id, observed_product, product_id, display_name, sort_order)
				VALUES (:snapshot, :observed, :product, :name, :order)""",
			)
				.param("snapshot", snapshotId)
				.param("observed", product[0])
				.param("product", product[1])
				.param("name", product[2])
				.param("order", order)
				.update()
		}
		return Copied(teams.size, members.size, products.size)
	}

	private data class Team(val id: UUID, val name: String, val archived: Boolean)

	private data class Member(
		val id: UUID,
		val account: String,
		val displayName: String?,
		val role: String,
		val status: String,
		val updatedAt: OffsetDateTime,
		val currentTeamIds: List<UUID>,
	)

	private companion object {
		const val ARCHIVED = "archived"
	}
}
