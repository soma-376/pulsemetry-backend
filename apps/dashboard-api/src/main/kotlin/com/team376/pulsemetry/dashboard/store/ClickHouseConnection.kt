package com.team376.pulsemetry.dashboard.store

import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * ClickHouse HTTP 인터페이스 한 계정의 연결. 원천 읽기(`ClickHouseSourceReader`)와 캐시(`ClickHouseCacheClient`)가 각자의 계정으로
 * 하나씩 갖고, 요청마다 싣는 설정은 그 둘이 정한다.
 *
 * 적재 모듈의 `ClickHouseHttpClient` 와 따로 두는 것은 요구가 달라서다 — 이쪽은 계정 인증, 요청마다의 설정, 행 단위 해석이 필요하다.
 *
 * ## 공통으로 싣는 설정
 *
 * - `wait_end_of_query=1` — 결과를 서버가 끝까지 만든 뒤 보낸다. 스트리밍 도중 실패가 200 과 잘린 본문으로 오는 것을 막는다.
 * - 64비트 정수·Decimal 은 문자열로 받는다(`output_format_json_quote_*`). JSON 숫자로 받으면 Double 을 거쳐 값이 바뀐다.
 * - 시각은 ISO 8601(`date_time_output_format=iso`).
 * - 값은 서버 쪽 바인딩(`{name:Type}` + `param_<name>`)이다. SQL 에 이어 붙이지 않는다.
 *
 * ## 실패 분류
 *
 * 판정은 HTTP 상태보다 `X-ClickHouse-Exception-Code` 가 먼저다 — 24.8 은 상한 초과를 500(행·바이트)이나 408(시간)로,
 * 읽기 전용 위반을 500 으로 낸다(실측).
 *
 * 1. 상한 초과 → [StoreLimitExceededException](503).
 * 2. 읽기 전용 위반(READONLY) → [StoreQueryRejectedException](500) — 이 앱이 쓰기를 보냈거나 계정 프로필이 `readonly=1` 이다.
 * 3. 인증 실패, 연결·제한 시간, 5xx·429·408 → [StoreUnavailableException](503).
 * 4. 그 밖의 4xx(없는 테이블 404·문법 400 등) → [StoreQueryRejectedException](500 — 이 앱의 문장 결함).
 */
class ClickHouseConnection(
	private val baseUrl: String,
	private val database: String,
	private val username: String,
	private val password: String,
	private val timeout: Duration,
	private val mapper: ObjectMapper,
	private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
) {

	private val log = LoggerFactory.getLogger(ClickHouseConnection::class.java)

	/** 결과 행마다 [row] 로 바꾼다. [sql] 에 `FORMAT` 을 쓰지 않는다 — 형식은 이 연결이 정한다. */
	fun <T> query(
		sql: String,
		settings: Map<String, String>,
		params: Map<String, ClickHouseParam>,
		row: (JsonNode) -> T,
	): List<T> = send(sql, settings + ("default_format" to "JSONEachRow"), params).body
		.lineSequence()
		.filter { it.isNotEmpty() }
		.map { row(mapper.readTree(it)) }
		.toList()

	/**
	 * 결과가 없는 문장(DDL·`INSERT … SELECT`). 서버가 보고한 쓴 행 수(`X-ClickHouse-Summary` 의 `written_rows`)를 돌려준다 —
	 * 구체화 뷰가 있으면 입구와 모든 뷰 대상 테이블에 쓴 행의 합이다(24.8 실측).
	 */
	fun execute(sql: String, settings: Map<String, String>, params: Map<String, ClickHouseParam>): Long =
		send(sql, settings, params).writtenRows

	private data class Sent(val body: String, val writtenRows: Long)

	private fun send(sql: String, settings: Map<String, String>, params: Map<String, ClickHouseParam>): Sent {
		val request = HttpRequest.newBuilder(uri(settings, params))
			.timeout(timeout)
			.header(USER_HEADER, username)
			.header(KEY_HEADER, password)
			.POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
			.build()

		val response = try {
			httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
		} catch (e: IOException) {
			throw StoreUnavailableException("clickhouse 연결 실패: ${e.javaClass.simpleName}", e)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			throw StoreUnavailableException("clickhouse 요청이 중단됐다", e)
		}

		val exceptionCode = response.headers().firstValue(EXCEPTION_CODE_HEADER).orElse(null)?.toIntOrNull()
		if (response.statusCode() != OK || exceptionCode != null) {
			throw classify(response.statusCode(), exceptionCode, response.body())
		}
		val written = response.headers().firstValue(SUMMARY_HEADER).orElse(null)
			?.let { runCatching { mapper.readTree(it).path("written_rows").asString().toLong() }.getOrNull() }
			?: 0
		return Sent(response.body(), written)
	}

	private fun uri(settings: Map<String, String>, params: Map<String, ClickHouseParam>): URI {
		val all = linkedMapOf(
			"database" to database,
			"wait_end_of_query" to "1",
			"output_format_json_quote_64bit_integers" to "1",
			"output_format_json_quote_decimals" to "1",
			"date_time_output_format" to "iso",
		)
		all.putAll(settings)
		params.forEach { (name, param) ->
			require(IDENTIFIER.matches(name)) { "파라미터 이름이 허용 형식이 아니다: $name" }
			all["param_$name"] = param.value
		}
		val query = all.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
		return URI.create("${baseUrl.trimEnd('/')}/?$query")
	}

	private fun classify(status: Int, exceptionCode: Int?, body: String): RuntimeException {
		val detail = "clickhouse $status (code ${exceptionCode ?: "-"}): ${body.take(MAX_ERROR_DETAIL)}"
		return when {
			exceptionCode in LIMIT_CODES -> StoreLimitExceededException(detail).also { log.warn("ClickHouse 문장이 상한을 넘었다 — {}", detail) }
			exceptionCode == READONLY -> StoreQueryRejectedException(detail).also { log.error("ClickHouse 가 쓰기를 거부했다 — {}", detail) }
			status >= SERVER_ERROR || status in TRANSIENT_STATUSES || exceptionCode in UNAVAILABLE_CODES ->
				StoreUnavailableException(detail).also { log.error("ClickHouse 일시 장애 — {}", detail) }
			else -> StoreQueryRejectedException(detail).also { log.error("ClickHouse 가 문장을 거부했다 — {}", detail) }
		}
	}

	private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

	companion object {
		/** 파라미터 이름·DB 이름처럼 SQL 에 식별자로 들어가는 값의 허용 형식. */
		val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

		private const val USER_HEADER = "X-ClickHouse-User"
		private const val KEY_HEADER = "X-ClickHouse-Key"
		private const val EXCEPTION_CODE_HEADER = "X-ClickHouse-Exception-Code"
		private const val SUMMARY_HEADER = "X-ClickHouse-Summary"
		private const val OK = 200
		private const val SERVER_ERROR = 500
		private const val MAX_ERROR_DETAIL = 500
		private const val READONLY = 164

		/** 408 요청 시간 초과 · 429 과다 요청. */
		private val TRANSIENT_STATUSES = setOf(408, 429)

		/**
		 * 상한 초과: TOO_MANY_ROWS(158) · TIMEOUT_EXCEEDED(159) · TOO_SLOW(160) · MEMORY_LIMIT_EXCEEDED(241) ·
		 * TOO_MANY_BYTES(307) · TOO_MANY_ROWS_OR_BYTES(396).
		 */
		private val LIMIT_CODES = setOf(158, 159, 160, 241, 307, 396)

		/** 계정 설정의 문제라 요청자가 고칠 수 없다: AUTHENTICATION_FAILED(516) · UNKNOWN_USER(192) · WRONG_PASSWORD(193). */
		private val UNAVAILABLE_CODES = setOf(516, 192, 193)
	}
}
