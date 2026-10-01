package com.team376.pulsemetry.connector.vendor

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 벤더 호출의 운영 수치 (ADR 0048 §7). 모두 기본값 없는 필수 설정이고 앱이 준다.
 *
 * @property requestTimeout 연결과 응답 헤더까지의 시간 제한(호출 하나).
 * @property maxAttempts 호출 하나의 최대 시도 횟수(첫 시도 포함). 일시 장애(5xx·연결 실패)와 한도 초과(429)만 다시 시도한다.
 * @property retryBackoff 벤더가 대기 시간을 알려 주지 않은 일시 장애 뒤 기다리는 시간.
 * @property maxRetryWait 벤더가 알려 준 대기 시간의 상한. 이보다 길면 기다리지 않고 한도 초과로 실패한다(다음 동기화가 다시 한다).
 */
data class HttpPolicy(val requestTimeout: Duration, val maxAttempts: Int, val retryBackoff: Duration, val maxRetryWait: Duration) {
	init {
		require(!requestTimeout.isNegative && !requestTimeout.isZero) { "호출 시간 제한은 양수여야 한다" }
		require(maxAttempts >= 1) { "최대 시도 횟수는 1 이상이어야 한다" }
		require(!retryBackoff.isNegative) { "재시도 간격은 음수일 수 없다" }
		require(!maxRetryWait.isNegative) { "대기 상한은 음수일 수 없다" }
	}
}

/**
 * 커넥터들이 쓰는 JDK HTTP 호출 (ADR 0048 §8). 드라이버·SDK 를 쓰지 않는다 — 상태별 처분(영구·일시)이 곧 원장 규칙이라 이 클래스가 정한다.
 *
 * | 응답 | 처분 |
 * | --- | --- |
 * | 401 | 자격증명 무효(영구) |
 * | 403 | 권한 부족(영구). 단 한도 신호(`x-ratelimit-remaining: 0`·`retry-after`)가 있으면 한도 초과 |
 * | 429 | 한도 초과 — `Retry-After`(초·HTTP 날짜)나 `x-ratelimit-reset`(epoch 초)만큼 기다렸다 다시 시도 |
 * | 400·404·409·422 등 그 밖의 4xx | 벤더 거절(영구) — 설정(조직 이름·주문)이 틀렸거나 벤더 규칙 |
 * | 5xx·연결 실패·시간 초과 | 일시 장애 — [HttpPolicy.retryBackoff] 뒤 다시 시도 |
 * | 2xx 인데 JSON 이 아니거나 필수 필드가 없음 | 응답 해석 불가 |
 *
 * 오류 메시지·예외 원인에 요청 헤더(자격증명)와 응답 본문을 싣지 않는다.
 */
class VendorHttp(
	private val policy: HttpPolicy,
	private val client: HttpClient = HttpClient.newBuilder().connectTimeout(policy.requestTimeout).followRedirects(HttpClient.Redirect.NEVER).build(),
	private val clock: Clock = Clock.systemUTC(),
	private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
	private val mapper = JsonMapper.builder().build()

	fun getJson(uri: URI, headers: Map<String, String>): JsonNode = getPage(uri, headers).first

	/** 본문과 응답 헤더(페이지 링크 등). */
	fun getPage(uri: URI, headers: Map<String, String>): Pair<JsonNode, java.net.http.HttpHeaders> =
		send { HttpRequest.newBuilder(uri).GET().withHeaders(headers) }.let { json(it) to it.headers }

	fun postFormJson(uri: URI, headers: Map<String, String>, form: Map<String, String>): JsonNode = json(send {
		HttpRequest.newBuilder(uri).withHeaders(headers + ("Content-Type" to "application/x-www-form-urlencoded"))
			.POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }))
	})

	/**
	 * 상태를 바꾸는 호출(해제·복원). [body] 는 JSON 으로 보내고(null 이면 본문 없음) 응답 본문은 JSON 객체여야 한다 — 빈 본문은 빈 객체로 읽는다.
	 * 처분은 읽기와 같다. 같은 요청을 다시 보내는 재시도(일시 장애·한도 초과)도 같다 — 벤더 문서의 해제·복원은 같은 대상에 다시 불러도 상태가 같다.
	 */
	fun sendJson(method: String, uri: URI, headers: Map<String, String>, body: Any?): JsonNode = json(send {
		val publisher = body?.let { HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(it)) } ?: HttpRequest.BodyPublishers.noBody()
		HttpRequest.newBuilder(uri).withHeaders(if (body != null) headers + ("Content-Type" to "application/json") else headers).method(method, publisher)
	}, emptyAsObject = true)

	/** 성공 응답(2xx)의 본문과 헤더. */
	class Response(val status: Int, val body: String, val headers: java.net.http.HttpHeaders)

	fun send(build: () -> HttpRequest.Builder): Response {
		var attempt = 1
		while (true) {
			val request = build().timeout(policy.requestTimeout).build()
			val response = try {
				client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
			} catch (_: IOException) {
				null
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
				throw ConnectorFailure(ConnectorFailure.Kind.UNAVAILABLE)
			}
			if (response != null && response.statusCode() in 200..299) return Response(response.statusCode(), response.body(), response.headers())
			val failure = response?.let(::classify) ?: ConnectorFailure(ConnectorFailure.Kind.UNAVAILABLE)
			val wait = when (failure.kind) {
				ConnectorFailure.Kind.RATE_LIMITED -> failure.retryAfter ?: policy.retryBackoff
				ConnectorFailure.Kind.UNAVAILABLE -> policy.retryBackoff
				else -> throw failure
			}
			if (attempt >= policy.maxAttempts || wait > policy.maxRetryWait) throw failure
			sleep(wait)
			attempt++
		}
	}

	private fun classify(response: HttpResponse<String>): ConnectorFailure {
		val status = response.statusCode()
		val retryAfter = retryAfter(response)
		val limited = response.headers().firstValue("x-ratelimit-remaining").orElse(null) == "0" || response.headers().firstValue("retry-after").isPresent
		return when {
			status == 429 || (status == 403 && limited) -> ConnectorFailure(ConnectorFailure.Kind.RATE_LIMITED, retryAfter)
			status == 401 -> ConnectorFailure(ConnectorFailure.Kind.INVALID_CREDENTIALS)
			status == 403 -> ConnectorFailure(ConnectorFailure.Kind.INSUFFICIENT_PERMISSION)
			status >= 500 -> ConnectorFailure(ConnectorFailure.Kind.UNAVAILABLE)
			else -> ConnectorFailure(ConnectorFailure.Kind.VENDOR_REJECTED)
		}
	}

	/** `Retry-After`(초 또는 HTTP 날짜), 없으면 `x-ratelimit-reset`(UTC epoch 초)까지의 시간. 모르면 null. */
	private fun retryAfter(response: HttpResponse<String>): Duration? {
		val now = clock.instant()
		response.headers().firstValue("retry-after").orElse(null)?.trim()?.let { value ->
			value.toLongOrNull()?.let { return Duration.ofSeconds(maxOf(it, 0)) }
			try {
				return Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).coerceAtLeast(Duration.ZERO)
			} catch (_: DateTimeParseException) {
				return null
			}
		}
		return response.headers().firstValue("x-ratelimit-reset").orElse(null)?.trim()?.toLongOrNull()
			?.let { Duration.between(now, Instant.ofEpochSecond(it)).coerceAtLeast(Duration.ZERO) }
	}

	private fun json(response: Response, emptyAsObject: Boolean = false): JsonNode = try {
		if (emptyAsObject && response.body.isBlank()) mapper.createObjectNode()
		else mapper.readTree(response.body).takeIf { it.isObject } ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
	} catch (_: JacksonException) {
		throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
	}

	companion object {
		private fun HttpRequest.Builder.withHeaders(headers: Map<String, String>): HttpRequest.Builder = apply { headers.forEach { (name, value) -> header(name, value) } }

		fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

		/** 경로 한 조각. 퍼센트 인코딩(공백은 `%20`). */
		fun segment(value: String): String = encode(value).replace("+", "%20")

		/** 응답의 필수 문자열. 없으면 응답 해석 불가. */
		fun text(node: JsonNode, field: String): String =
			node.path(field).takeIf { it.isString && it.asString().isNotBlank() }?.asString() ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)

		/** 선택 문자열. 없거나 null 이면 null. */
		fun optionalText(node: JsonNode, field: String): String? = node.path(field).takeIf { it.isString }?.asString()

		/** 선택 시각(RFC 3339). 형식이 틀리면 응답 해석 불가 — 틀린 시각을 모름(null)으로 바꾸지 않는다. */
		fun optionalInstant(node: JsonNode, field: String): Instant? {
			val value = node.path(field)
			if (value.isMissingNode || value.isNull) return null
			if (!value.isString) throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)
			return try { OffsetDateTime.parse(value.asString()).toInstant() } catch (_: DateTimeParseException) { throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE) }
		}

		/** 필수 배열. */
		fun array(node: JsonNode, field: String): List<JsonNode> =
			node.path(field).takeIf { it.isArray }?.let { array -> (0 until array.size()).map { array.get(it) } } ?: throw ConnectorFailure(ConnectorFailure.Kind.INVALID_RESPONSE)

		/** 페이지를 넘기는 상한 — 벤더가 끝을 알리지 않고 같은 페이지를 되풀이해도 멈춘다. */
		const val MAX_PAGES = 10_000
	}
}
