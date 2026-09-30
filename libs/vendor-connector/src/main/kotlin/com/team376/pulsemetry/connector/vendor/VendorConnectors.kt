package com.team376.pulsemetry.connector.vendor

import com.team376.pulsemetry.connector.vendor.VendorHttp.Companion.array
import com.team376.pulsemetry.connector.vendor.VendorHttp.Companion.optionalInstant
import com.team376.pulsemetry.connector.vendor.VendorHttp.Companion.optionalText
import com.team376.pulsemetry.connector.vendor.VendorHttp.Companion.segment
import com.team376.pulsemetry.connector.vendor.VendorHttp.Companion.text
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64

/*
 * 벤더 좌석 커넥터 넷 (ADR 0048 §8). 요청·응답은 `docs/vendor-connector-evidence.md` 의 문서 근거를 따른다 — 근거 없는 엔드포인트·필드를 쓰지 않는다.
 * 지금은 좌석 목록과 연결 확인(읽기)만 한다. 해제·복원·청구는 구현하지 않았다(설명의 capability 가 좌석 목록뿐이다).
 * 기준 주소는 생성자로 받는다 — 모의 서버와 실서버가 같은 코드를 쓴다.
 */

private fun base(uri: URI) = uri.toString().trimEnd('/')

/**
 * Claude Enterprise — Admin API(`x-api-key`, `anthropic-version`). 구성원(`GET /v1/organizations/users`)이 좌석을 차지하고,
 * 대기 중인 초대(`GET /v1/organizations/invites`의 `pending`)도 좌석을 잡는다(벤더 문서 "a pending invite holds a seat") — 배정 대기로 낸다.
 * 두 목록 모두 ID 기반 페이지(`limit`·`after_id`, `has_more`·`last_id`)다. 등급·마지막 활동 시각은 응답에 없다.
 */
class ClaudeEnterpriseConnector(private val http: VendorHttp, baseUrl: URI = URI("https://api.anthropic.com")) : SeatConnector {
	override val descriptor = ConnectorDescriptors.CLAUDE_ENTERPRISE
	private val base = base(baseUrl)

	private fun headers(target: ConnectionTarget) = mapOf("x-api-key" to target.credential.reveal(), "anthropic-version" to API_VERSION)

	override fun verify(target: ConnectionTarget) {
		array(http.getJson(URI("$base/v1/organizations/users?limit=1"), headers(target)), "data")
	}

	override fun listSeats(target: ConnectionTarget): List<VendorSeat> {
		val members = pages(target, "users").map { member ->
			val email = text(member, "email")
			VendorSeat(account = email, vendorAccountRef = text(member, "id"), email = email, assignedAt = optionalInstant(member, "added_at"))
		}
		val memberEmails = members.map { it.account.trim().lowercase() }.toSet()
		val invites = pages(target, "invites").filter { text(it, "status") == "pending" }.map { invite ->
			val email = text(invite, "email")
			VendorSeat(account = email, email = email, state = VendorSeatState.PENDING_ASSIGNMENT, assignedAt = optionalInstant(invite, "invited_at"))
		}.filter { it.account.trim().lowercase() !in memberEmails }
		return members + invites
	}

	private fun pages(target: ConnectionTarget, resource: String): List<JsonNode> {
		val rows = mutableListOf<JsonNode>()
		var after: String? = null
		repeat(VendorHttp.MAX_PAGES) {
			val page = http.getJson(URI("$base/v1/organizations/$resource?limit=$PAGE_SIZE" + (after?.let { "&after_id=" + VendorHttp.encode(it) } ?: "")), headers(target))
			rows += array(page, "data")
			val more = page.path("has_more").takeIf { it.isBoolean }?.asBoolean() ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
			if (!more) return rows
			val last = text(page, "last_id")
			if (last == after) throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
			after = last
		}
		throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
	}

	private companion object {
		const val API_VERSION = "2023-06-01"
		/** 문서의 최대값("limit (default 20, max 1000)"). */
		const val PAGE_SIZE = 1000
	}
}

/**
 * Cursor Enterprise — Admin API(Basic 인증, API 키가 사용자 이름). `GET /teams/members`는 한 번에 전부를 준다(문서에 페이지가 없다).
 * `isRemoved: true` 인 구성원은 좌석이 아니다. 좌석 유형·배정 시각·마지막 활동은 응답에 없다.
 */
class CursorEnterpriseConnector(private val http: VendorHttp, baseUrl: URI = URI("https://api.cursor.com")) : SeatConnector {
	override val descriptor = ConnectorDescriptors.CURSOR_ENTERPRISE
	private val base = base(baseUrl)

	private fun headers(target: ConnectionTarget) =
		mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString((target.credential.reveal() + ":").toByteArray()))

	override fun verify(target: ConnectionTarget) {
		members(target)
	}

	override fun listSeats(target: ConnectionTarget): List<VendorSeat> = members(target).filterNot { member ->
		val removed = member.path("isRemoved")
		if (!removed.isMissingNode && !removed.isNull && !removed.isBoolean) throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
		removed.asBoolean(false)
	}.map { member ->
		val email = text(member, "email")
		VendorSeat(account = email, vendorAccountRef = text(member, "id"), email = email)
	}

	private fun members(target: ConnectionTarget) = array(http.getJson(URI("$base/teams/members"), headers(target)), "teamMembers")
}

/**
 * GitHub Copilot — `GET /orgs/{org}/copilot/billing/seats`(REST 권장 헤더: `Accept: application/vnd.github+json`, `Authorization: Bearer`,
 * `X-GitHub-Api-Version: 2022-11-28`). 페이지는 `page`·`per_page`(최대 100)이고 `link` 헤더에 `rel="next"`가 없으면 끝이다.
 * 계정 키는 `assignee.login`이다 — 이메일이 없다. `assignee`가 null 인 좌석은 계정 키가 없어 원장에 넣지 않는다.
 * `pending_cancellation_date`가 있으면 해제 예정(그날 효력). `plan_type`은 플랜이지 등급이 아니라 쓰지 않는다.
 */
class CopilotConnector(private val http: VendorHttp, baseUrl: URI = URI("https://api.github.com")) : SeatConnector {
	override val descriptor = ConnectorDescriptors.COPILOT
	private val base = base(baseUrl)

	private fun headers(target: ConnectionTarget) = mapOf(
		"Accept" to "application/vnd.github+json", "Authorization" to "Bearer " + target.credential.reveal(), "X-GitHub-Api-Version" to API_VERSION,
	)

	private fun seatsUri(target: ConnectionTarget, page: Int, perPage: Int) =
		URI("$base/orgs/${segment(target.settings.getValue("organization"))}/copilot/billing/seats?per_page=$perPage&page=$page")

	override fun verify(target: ConnectionTarget) {
		array(http.getJson(seatsUri(target, 1, 1), headers(target)), "seats")
	}

	override fun listSeats(target: ConnectionTarget): List<VendorSeat> {
		val seats = mutableListOf<VendorSeat>()
		for (page in 1..VendorHttp.MAX_PAGES) {
			val (body, responseHeaders) = http.getPage(seatsUri(target, page, PAGE_SIZE), headers(target))
			array(body, "seats").forEach { seat ->
				val assignee = seat.path("assignee")
				if (assignee.isNull || assignee.isMissingNode) return@forEach
				val cancellation = optionalText(seat, "pending_cancellation_date")?.let {
					try { LocalDate.parse(it) } catch (_: DateTimeParseException) { throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE) }
				}
				seats += VendorSeat(
					account = text(assignee, "login"),
					state = if (cancellation != null) VendorSeatState.PENDING_RELEASE else VendorSeatState.ASSIGNED,
					releaseEffectiveOn = cancellation,
					assignedAt = optionalInstant(seat, "created_at"),
					lastActivityAt = optionalInstant(seat, "last_activity_at"),
				)
			}
			if (responseHeaders.allValues("link").none { NEXT.containsMatchIn(it) }) return seats
		}
		throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
	}

	private companion object {
		const val API_VERSION = "2022-11-28"
		/** 문서의 최대값("per_page ... max 100"). */
		const val PAGE_SIZE = 100
		val NEXT = Regex("rel=\"next\"")
	}
}

/**
 * Gemini Code Assist — Cloud Commerce Consumer Procurement API 의 `licensePool:enumerateLicensedUsers`(`pageSize`·`pageToken`, 응답 `nextPageToken`이 없으면 끝).
 * 헤더는 OAuth 액세스 토큰(`Authorization: Bearer`, scope `cloud-platform`)과 `X-Goog-User-Project`.
 * 자격증명은 **서비스 계정 키(JSON)** 다 — 액세스 토큰은 한 시간이면 끝나 저장해 둘 수 없다. 호출마다 키로 JWT(RS256)를 서명해 토큰 엔드포인트
 * (`grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`)에서 토큰을 받는다(Google 서비스 계정 OAuth 문서).
 * 계정 키는 `username`(이메일)이고, `assignTime`은 배정 시각, `recentUsageTime`은 마지막 사용이다. 빈 목록은 `licensedUsers`가 없을 수 있다.
 */
class GeminiConnector(
	private val http: VendorHttp,
	baseUrl: URI = URI("https://cloudcommerceconsumerprocurement.googleapis.com"),
	private val tokenUrl: URI = URI(TOKEN_AUDIENCE),
	private val clock: Clock = Clock.systemUTC(),
) : SeatConnector {
	override val descriptor = ConnectorDescriptors.GEMINI
	private val base = base(baseUrl)
	private val mapper = JsonMapper.builder().build()

	override fun verify(target: ConnectionTarget) {
		page(target, accessToken(target), 1, null)
	}

	override fun listSeats(target: ConnectionTarget): List<VendorSeat> {
		val token = accessToken(target)
		val seats = mutableListOf<VendorSeat>()
		var pageToken: String? = null
		repeat(VendorHttp.MAX_PAGES) {
			val body = page(target, token, PAGE_SIZE, pageToken)
			val users = body.path("licensedUsers")
			if (!users.isMissingNode) array(body, "licensedUsers").forEach { user ->
				val username = text(user, "username")
				seats += VendorSeat(account = username, email = username, assignedAt = optionalInstant(user, "assignTime"),
					lastActivityAt = optionalInstant(user, "recentUsageTime"))
			}
			val next = optionalText(body, "nextPageToken")?.takeIf { it.isNotEmpty() } ?: return seats
			if (next == pageToken) throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
			pageToken = next
		}
		throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
	}

	private fun page(target: ConnectionTarget, token: String, size: Int, pageToken: String?): JsonNode {
		val parent = "billingAccounts/${segment(target.settings.getValue("billingAccount"))}/orders/${segment(target.settings.getValue("order"))}/licensePool"
		val uri = URI("$base/v1/$parent:enumerateLicensedUsers?pageSize=$size" + (pageToken?.let { "&pageToken=" + VendorHttp.encode(it) } ?: ""))
		return http.getJson(uri, mapOf("Authorization" to "Bearer $token", "X-Goog-User-Project" to target.settings.getValue("project")))
	}

	/** 서비스 계정 키로 서명한 JWT 를 액세스 토큰으로 바꾼다. 키가 서비스 계정 키 모양이 아니면 자격증명 무효다. */
	private fun accessToken(target: ConnectionTarget): String {
		val key = try { mapper.readTree(target.credential.reveal()) } catch (_: JacksonException) { null }
			?.takeIf { it.isObject } ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_CREDENTIALS)
		val email = key.path("client_email").takeIf { it.isString }?.asString() ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_CREDENTIALS)
		val pem = key.path("private_key").takeIf { it.isString }?.asString() ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_CREDENTIALS)
		val privateKey = try {
			val der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""))
			KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
		} catch (_: Exception) {
			throw ConnectorFailure(ConnectorFailure.Kind.INVALID_CREDENTIALS)
		}
		val now = clock.instant().epochSecond
		val url = Base64.getUrlEncoder().withoutPadding()
		val header = mapOf("alg" to "RS256", "typ" to "JWT") + (key.path("private_key_id").takeIf { it.isString }?.let { mapOf("kid" to it.asString()) } ?: emptyMap())
		val claims = mapOf("iss" to email, "scope" to SCOPE, "aud" to TOKEN_AUDIENCE, "iat" to now, "exp" to now + TOKEN_LIFETIME_SECONDS)
		val unsigned = url.encodeToString(mapper.writeValueAsBytes(header)) + "." + url.encodeToString(mapper.writeValueAsBytes(claims))
		val signature = Signature.getInstance("SHA256withRSA").apply { initSign(privateKey); update(unsigned.toByteArray()) }.sign()
		val response = try {
			http.postFormJson(tokenUrl, emptyMap(),
				mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion" to unsigned + "." + url.encodeToString(signature)))
		} catch (failure: ConnectorFailure) {
			// 토큰 엔드포인트가 서명한 주장을 거절하면(지운 키·틀린 계정) 자격증명 문제다.
			throw if (failure.kind == ConnectorFailure.Kind.VENDOR_REJECTED) ConnectorFailure(ConnectorFailure.Kind.INVALID_CREDENTIALS) else failure
		}
		return text(response, "access_token")
	}

	companion object {
		/** 토큰 엔드포인트이자 JWT 의 `aud`("When making an access token request this value is always https://oauth2.googleapis.com/token"). */
		const val TOKEN_AUDIENCE = "https://oauth2.googleapis.com/token"
		const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"
		/** JWT 수명 상한("a maximum of 1 hour after the issued time"). */
		private const val TOKEN_LIFETIME_SECONDS = 3600L
		/** 문서에 최대값이 없다("The service may return fewer than this value"). */
		private const val PAGE_SIZE = 100
	}
}
