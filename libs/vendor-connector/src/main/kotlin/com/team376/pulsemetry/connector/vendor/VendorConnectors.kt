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
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64

/*
 * 벤더 좌석 커넥터 넷 (ADR 0048 §8). 요청·응답은 `docs/vendor-connector-evidence.md` 의 문서 근거를 따른다 — 근거 없는 엔드포인트·필드를 쓰지 않는다.
 * 좌석 목록·연결 확인(읽기)과, 문서가 요청·응답을 준 해제·복원(ADR 0049)·청구 누계(ADR 0050 — Claude Enterprise·Cursor Enterprise)를 한다.
 * 제어 결과는 벤더가 돌려준 상태 그대로다 — 요청을 받아들였다는 것을 완료로 올리지 않는다([ControlStatus]).
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

	/**
	 * 제거(`DELETE /v1/organizations/users/{user_id}`, `write:members`) — "returning any purchased seat they occupied to the organization's pool".
	 * 구성원 ID 가 필요하다(동기화가 남긴 벤더 내부 ID). 응답은 `{"type":"user_deleted"}`. 관리자 역할·SCIM 조직의 제거는 벤더가 400 으로 거절한다.
	 */
	override val release = SeatRelease { target, account ->
		val id = account.vendorAccountRef ?: throw ConnectorFailure(ConnectorFailure.Kind.VENDOR_REJECTED)
		val body = http.sendJson("DELETE", URI("$base/v1/organizations/users/${segment(id)}"), headers(target), null)
		if (text(body, "type") != "user_deleted") throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
		ControlResult(ControlStatus.COMPLETED)
	}

	/**
	 * 사용 비용 누계(`GET /v1/organizations/analytics/cost_report`, `read:analytics`) — 금액은 "Amount (post-discount, pre-credit) in fractional cents"
	 * (`"41280.000000"` = $412.80), 통화는 "Currently always USD"(USD 가 아니면 응답 해석 불가). 사용량 기반 Enterprise 의 사용 비용이고 좌석 기반 Enterprise 에서는
	 * 사용 크레딧만이다 — 좌석 구독료는 없다. 기간은 [monthStart](조직 달력의 이번 달 시작)부터 [now]까지, 한 시간 칸(`bucket_width=1h` — 서울 자정이 시 경계라
	 * 칸이 달의 경계와 맞는다)이고 `next_page` 가 없을 때까지 읽는다. 값은 30일까지 고쳐질 수 있어 확정이 아니다.
	 */
	override val billing = BillingReader { target, now, monthStart ->
		var total = BigDecimal.ZERO
		var page: String? = null
		repeat(VendorHttp.MAX_PAGES) {
			val query = "starting_at=" + VendorHttp.encode(monthStart.toString()) + "&ending_at=" + VendorHttp.encode(now.toString()) + "&bucket_width=1h" +
				(page?.let { "&page=" + VendorHttp.encode(it) } ?: "")
			val body = http.getJson(URI("$base/v1/organizations/analytics/cost_report?$query"), headers(target))
			array(body, "data").forEach { bucket ->
				array(bucket, "results").forEach { result ->
					if (text(result, "currency") != "USD") throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
					val cents = text(result, "amount").toBigDecimalOrNull() ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
					total += cents
				}
			}
			val more = body.path("has_more").takeIf { it.isBoolean }?.asBoolean() ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
			if (!more) return@BillingReader BilledAmount(monthStart, now, total.movePointLeft(2), "USD", BilledKind.USAGE_COST, finalized = false)
			val next = text(body, "next_page")
			if (next == page) throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
			page = next
		}
		throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
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

	/**
	 * 제거(`POST /teams/remove-member`, Enterprise) — 본문은 `userId` 또는 `email` 중 하나("but not both"). 벤더 내부 ID 가 있으면 그것을 쓴다.
	 * 응답 `success` 가 true 여야 한다. 마지막 유료 구성원·마지막 관리자는 벤더가 거절한다.
	 */
	override val release = SeatRelease { target, account ->
		val body = account.vendorAccountRef?.let { mapOf("userId" to it) } ?: mapOf("email" to account.account)
		val response = http.sendJson("POST", URI("$base/teams/remove-member"), headers(target), body)
		when (response.path("success").takeIf { it.isBoolean }?.asBoolean()) {
			true -> ControlResult(ControlStatus.COMPLETED)
			false -> throw ConnectorFailure(ConnectorFailure.Kind.VENDOR_REJECTED)
			null -> throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
		}
	}

	/**
	 * 이번 청구 주기의 사용 지출(`POST /teams/spend`) — 기간은 벤더가 정한 주기(`subscriptionCycleStart`, epoch 밀리초)부터 [now]까지다. 지난 주기를 고르는 매개변수는 없다.
	 * 금액은 구성원마다의 `spendCents`("On-demand spend in cents for the current billing cycle (excludes included usage)") 합이다 — 구독에 든 사용분(`overallSpendCents`)은
	 * 좌석 구독료로 이미 내는 몫이라 더하지 않는다. 단위는 센트(필드 이름의 `Cents`·`Dollars`로 USD). 페이지는 `totalPages` 까지다.
	 */
	override val billing = BillingReader { target, now, _ ->
		var total = BigDecimal.ZERO
		var cycleStart: Long? = null
		var page = 1
		while (page <= VendorHttp.MAX_PAGES) {
			val body = http.sendJson("POST", URI("$base/teams/spend"), headers(target), mapOf("page" to page, "pageSize" to SPEND_PAGE_SIZE))
			val start = body.path("subscriptionCycleStart").takeIf { it.isIntegralNumber }?.asLong() ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
			if (cycleStart != null && cycleStart != start) throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
			cycleStart = start
			array(body, "teamMemberSpend").forEach { member ->
				val cents = member.path("spendCents").takeIf { it.isNumber }?.decimalValue() ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
				total += cents
			}
			val pages = body.path("totalPages").takeIf { it.isIntegralNumber }?.asInt() ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
			if (page >= pages) {
				val from = Instant.ofEpochMilli(start)
				if (!from.isBefore(now)) throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
				return@BillingReader BilledAmount(from, now, total.movePointLeft(2), "USD", BilledKind.USAGE_SPEND, finalized = false)
			}
			page++
		}
		throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
	}

	private fun members(target: ConnectionTarget) = array(http.getJson(URI("$base/teams/members"), headers(target)), "teamMembers")

	private companion object {
		/** 문서에 최대값이 없다. */
		const val SPEND_PAGE_SIZE = 100
	}
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

	private fun selectedUsers(target: ConnectionTarget) = URI("$base/orgs/${segment(target.settings.getValue("organization"))}/copilot/billing/selected_users")

	/**
	 * 취소(`DELETE …/copilot/billing/selected_users`, 본문 `selected_usernames`) — "Sets seats … to 'pending cancellation'"이고 주기 말에 효력이 생긴다.
	 * 그래서 결과는 예정이다. 응답에 날짜가 없어 예정일은 다음 동기화(`pending_cancellation_date`)가 채운다. `seats_cancelled` 가 0 이면 취소되지 않았다
	 * (팀을 통해 배정된 좌석 등) — 벤더 거절이다.
	 */
	override val release = SeatRelease { target, account ->
		val body = http.sendJson("DELETE", selectedUsers(target), headers(target), mapOf("selected_usernames" to listOf(account.account)))
		if (count(body, "seats_cancelled") < 1) throw ConnectorFailure(ConnectorFailure.Kind.VENDOR_REJECTED)
		ControlResult(ControlStatus.SCHEDULED)
	}

	/**
	 * 재배정(`POST …/copilot/billing/selected_users`) — "Purchases a GitHub Copilot seat for each user"이고 응답 `seats_created` 는 새로 만들거나
	 * 되살린(refreshed) 좌석 수다. 0 이면 벤더 거절이다.
	 */
	override val restore = SeatRestore { target, account ->
		val body = http.sendJson("POST", selectedUsers(target), headers(target), mapOf("selected_usernames" to listOf(account.account)))
		if (count(body, "seats_created") < 1) throw ConnectorFailure(ConnectorFailure.Kind.VENDOR_REJECTED)
		ControlResult(ControlStatus.COMPLETED)
	}

	private fun count(body: JsonNode, field: String): Long =
		body.path(field).takeIf { it.isIntegralNumber }?.asLong() ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)

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

	/** 해제(`licensePool:unassign`, 본문 `{"usernames": [...]}`) — 성공 응답은 빈 본문이다. 자동 배정 구독이면 벤더가 다시 배정할 수 있다(다음 동기화가 보인다). */
	override val release = SeatRelease { target, account -> control(target, "unassign", account) }

	/** 배정(`licensePool:assign`) — 요청·응답 형식은 해제와 같다. 남은 라이선스가 없으면 벤더가 거절한다. */
	override val restore = SeatRestore { target, account -> control(target, "assign", account) }

	private fun control(target: ConnectionTarget, verb: String, account: VendorAccount): ControlResult {
		http.sendJson("POST", URI("$base/v1/${parent(target)}:$verb"), headers(target, accessToken(target)), mapOf("usernames" to listOf(account.account)))
		return ControlResult(ControlStatus.COMPLETED)
	}

	private fun parent(target: ConnectionTarget) =
		"billingAccounts/${segment(target.settings.getValue("billingAccount"))}/orders/${segment(target.settings.getValue("order"))}/licensePool"

	private fun headers(target: ConnectionTarget, token: String) = mapOf("Authorization" to "Bearer $token", "X-Goog-User-Project" to target.settings.getValue("project"))

	private fun page(target: ConnectionTarget, token: String, size: Int, pageToken: String?): JsonNode {
		val uri = URI("$base/v1/${parent(target)}:enumerateLicensedUsers?pageSize=$size" + (pageToken?.let { "&pageToken=" + VendorHttp.encode(it) } ?: ""))
		return http.getJson(uri, headers(target, token))
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
