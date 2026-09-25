package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.FieldErrorCode
import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.PageCursor
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.PageRequest
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.snapshot.SnapshotBuilder
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * 조직 팀 선택지 `GET /teams` (화면 요청서 공통 계약). 사용량 snapshot 과 무관한 **현재 디렉터리**다 — 활성 실제 팀만, 미배정 제외.
 *
 * 요청서가 목록 순서를 고정하는 `snapshotId` 를 받으므로 디렉터리에도 기준 시각 하나를 고정한다. 디렉터리의 snapshot ID 는 저장소에
 * 쓰지 않는 불투명 토큰(tenant·기준 시각)이고, 같은 ID 의 페이지는 기준 시각까지 만들어진 팀만 불변 팀 ID 오름차순으로 나눈다 —
 * 페이지 사이에 새 팀이 생겨도 중복·누락이 없다. 유효기간은 사용량 snapshot 과 같은 10분이고 지나면 409 다.
 * 팀 이름·상태는 현재 값이다(디렉터리는 과거 상태를 보관하지 않는다).
 *
 * `version` 은 팀 행의 마지막 갱신 시각(epoch 밀리초)이다 — 낙관적 잠금에 쓰는 단조 증가 값.
 */
class TeamDirectoryService(
	private val source: JdbcClient,
	private val codec: PageCursorCodec,
	private val mapper: ObjectMapper,
	private val clock: Clock,
) {

	fun teams(organization: Organization, search: String?, page: PageRequest, snapshotId: String?): TeamDirectoryResponse {
		val now = clock.instant()
		val requested = snapshotId ?: page.cursor?.snapshotId
		// 토큰이 밀리초로 담으므로 첫 페이지도 같은 정밀도의 기준 시각을 쓴다 — 페이지마다 같은 asOf 여야 한다.
		val asOf = if (requested == null) now.truncatedTo(ChronoUnit.MILLIS) else decode(requested, organization.id, now)
		val token = encode(organization.id, asOf)
		val scope = "teams:q=${digest(search)}"
		page.cursor?.let { cursor ->
			if (cursor.snapshotId != token || cursor.scope != scope) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR)
		}
		val after = page.cursor?.after?.lastOrNull()?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR) }

		val filter = "tenant_id = :tenant AND status = 'active' AND created_at <= :as_of" + (if (search != null) " AND name ILIKE :pattern ESCAPE '\\'" else "")
		fun JdbcClient.StatementSpec.bind() = param("tenant", organization.id).param("as_of", Timestamp.from(asOf))
			.let { if (search != null) it.param("pattern", "%" + escapeLike(search) + "%") else it }

		val total = source.sql("SELECT count(*) FROM enrollment.teams WHERE $filter").bind().query(Long::class.java).single()
		val rows = source.sql(
			"SELECT id, name, updated_at FROM enrollment.teams WHERE $filter" + (if (after != null) " AND id > :after" else "") + " ORDER BY id LIMIT :limit",
		).bind()
			.let { if (after != null) it.param("after", after) else it }
			.param("limit", page.limit + 1)
			.query { rs, _ ->
				DirectoryTeam(
					teamId = rs.getObject("id", UUID::class.java).toString(),
					teamName = rs.getString("name"),
					version = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant().toEpochMilli(),
				)
			}
			.list()
		val items = rows.take(page.limit)
		val next = if (rows.size > page.limit) codec.encode(PageCursor(token, scope, listOf(items.last().teamId))) else null

		return TeamDirectoryResponse(
			meta = CurrentMeta(
				organizationId = organization.id.toString(),
				generatedAt = now.toString(),
				asOf = asOf.toString(),
				snapshotId = token,
				currency = AnalyticsFrames.USD,
				timeZone = QueryReader.SEOUL_ID,
			),
			teams = Page(items, total.toInt(), next),
		)
	}

	private fun encode(tenantId: UUID, asOf: Instant): String {
		val json = mapper.createObjectNode().put("v", 1).put("k", KIND).put("t", tenantId.toString()).put("a", asOf.toEpochMilli())
		return PREFIX + ENCODER.encodeToString(mapper.writeValueAsBytes(json))
	}

	/** 다른 조직·다른 종류·해석 불가·만료는 모두 409 — 없는 snapshot 과 같다. */
	private fun decode(token: String, tenantId: UUID, now: Instant): Instant {
		if (!token.startsWith(PREFIX) || token.length > MAX_LENGTH) throw expired()
		val json = runCatching { mapper.readTree(DECODER.decode(token.removePrefix(PREFIX))) }.getOrNull() ?: throw expired()
		val asOf = json.get("a")?.takeIf { it.isNumber }?.let { Instant.ofEpochMilli(it.asLong()) }
		val valid = json.path("v").asInt() == 1 && json.path("k").asString() == KIND && json.path("t").asString() == tenantId.toString() &&
			asOf != null && !asOf.isAfter(now) && now.isBefore(asOf + SnapshotBuilder.API_LIFETIME)
		if (!valid) throw expired()
		return asOf!!
	}

	private fun expired() = DashboardException(ErrorCode.SNAPSHOT_EXPIRED)

	private fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

	private fun digest(value: String?): String =
		if (value == null) "-" else MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

	private companion object {
		const val PREFIX = "d."
		const val KIND = "team-directory"
		const val CURSOR = "cursor"
		const val MAX_LENGTH = 512
		val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
		val DECODER: Base64.Decoder = Base64.getUrlDecoder()
	}
}
