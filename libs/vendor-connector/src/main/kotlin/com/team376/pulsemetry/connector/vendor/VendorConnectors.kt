package com.team376.pulsemetry.connector.vendor

import com.team376.pulsemetry.connector.vendor.VendorHttp.Companion.array
import com.team376.pulsemetry.connector.vendor.VendorHttp.Companion.optionalInstant
import com.team376.pulsemetry.connector.vendor.VendorHttp.Companion.segment
import com.team376.pulsemetry.connector.vendor.VendorHttp.Companion.text
import tools.jackson.databind.JsonNode
import java.net.URI
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64

/*
 * 벤더 좌석 커넥터 둘 (ADR 0048 §8, ADR 0054). 요청·응답은 `docs/vendor-connector-evidence.md` 의 문서 근거를 따른다 — 근거 없는 엔드포인트·필드를 쓰지 않는다.
 * 좌석 목록·연결 확인(읽기)과, 문서가 요청·응답을 준 해제(ADR 0049)·청구 누계(ADR 0050)를 한다.
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
