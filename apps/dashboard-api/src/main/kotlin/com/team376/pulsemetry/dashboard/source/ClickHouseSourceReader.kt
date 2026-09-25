package com.team376.pulsemetry.dashboard.source

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
 * 분석 원천(ClickHouse)의 **읽기 전용** 클라이언트 (ADR 0022 §4).
 *
 * 적재 모듈의 `ClickHouseHttpClient` 와 따로 두는 것은 요구가 반대라서다 — 이쪽은 계정 인증, 요청마다의 읽기 전용 강제,
 * 결과 상한, 행 단위 해석이 필요하고 쓰기가 없다.
 *
 * ## 요청마다 싣는 설정
 *
 * - `readonly=2` — 읽기만 허용하되 아래 설정은 바꿀 수 있게 한다. 계정 권한이 1차 강제이고 이것은 2차다.
 *   계정 프로필이 `readonly=1` 이면 설정 변경이 거부되므로 프로필은 2 이하여야 한다.
 * - `max_execution_time`·`max_result_rows`·`max_result_bytes` + `result_overflow_mode=throw` — 넘으면 **실패**한다.
 *   잘린 결과를 정상 총액으로 내지 않는다.
 * - `wait_end_of_query=1` — 결과를 서버가 끝까지 만든 뒤 보낸다. 스트리밍 도중 실패가 200 과 잘린 본문으로 오는 것을 막는다.
 * - 64비트 정수·Decimal 은 문자열로 받는다(`output_format_json_quote_*`). JSON 숫자로 받으면 Double 을 거쳐 값이 바뀐다.
 * - 시각은 ISO 8601(`date_time_output_format=iso`).
 *
 * ## 실패 분류
 *
 * 판정은 HTTP 상태보다 `X-ClickHouse-Exception-Code` 가 먼저다 — 24.8 은 상한 초과를 500(행·바이트)이나 408(시간)로,
 * 읽기 전용 위반을 500 으로 낸다(실측).
 *
 * 1. 상한 초과 → [SourceLimitExceededException](503).
 * 2. 읽기 전용 위반(READONLY) → [SourceQueryRejectedException](500) — 이 앱이 쓰기를 보냈거나 계정 프로필이 `readonly=1` 이다.
 * 3. 인증 실패, 연결·제한 시간, 5xx·429·408 → [SourceUnavailableException](503).
 * 4. 그 밖의 4xx(없는 테이블 404·문법 400 등) → [SourceQueryRejectedException](500 — 이 앱의 조회 결함).
 */
class ClickHouseSourceReader(
	private val baseUrl: String,
	private val database: String,
	private val username: String,
	private val password: String,
	private val queryTimeout: Duration,
	private val maxResultRows: Long,
	private val maxResultBytes: Long,
	private val mapper: ObjectMapper,
	private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(queryTimeout).build(),
) {

	private val log = LoggerFactory.getLogger(ClickHouseSourceReader::class.java)

	/**
	 * [sql] 을 실행해 행마다 [row] 로 바꾼다. [sql] 에 `FORMAT` 을 쓰지 않는다 — 형식은 이 클라이언트가 정한다.
	 */
	fun <T> query(sql: String, params: Map<String, ClickHouseParam> = emptyMap(), row: (JsonNode) -> T): List<T> {
		val request = HttpRequest.newBuilder(uri(params))
			.timeout(queryTimeout)
			.header(USER_HEADER, username)
			.header(KEY_HEADER, password)
			.POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
			.build()

		val response = try {
			httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
		} catch (e: IOException) {
			throw SourceUnavailableException("clickhouse 연결 실패: ${e.javaClass.simpleName}", e)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			throw SourceUnavailableException("clickhouse 요청이 중단됐다", e)
		}

		val exceptionCode = response.headers().firstValue(EXCEPTION_CODE_HEADER).orElse(null)?.toIntOrNull()
		if (response.statusCode() != OK || exceptionCode != null) {
			throw classify(response.statusCode(), exceptionCode, response.body())
		}
		return response.body().lineSequence().filter { it.isNotEmpty() }.map { row(mapper.readTree(it)) }.toList()
	}

	private fun uri(params: Map<String, ClickHouseParam>): URI {
		val settings = linkedMapOf(
			"database" to database,
			"default_format" to "JSONEachRow",
			"readonly" to "2",
			"max_execution_time" to queryTimeout.toSeconds().toString(),
			"max_result_rows" to maxResultRows.toString(),
			"max_result_bytes" to maxResultBytes.toString(),
			"result_overflow_mode" to "throw",
			"wait_end_of_query" to "1",
			"output_format_json_quote_64bit_integers" to "1",
			"output_format_json_quote_decimals" to "1",
			"date_time_output_format" to "iso",
		)
		params.forEach { (name, param) ->
			require(PARAM_NAME.matches(name)) { "파라미터 이름이 허용 형식이 아니다: $name" }
			settings["param_$name"] = param.value
		}
		val query = settings.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
		return URI.create("${baseUrl.trimEnd('/')}/?$query")
	}

	private fun classify(status: Int, exceptionCode: Int?, body: String): RuntimeException {
		val detail = "clickhouse $status (code ${exceptionCode ?: "-"}): ${body.take(MAX_ERROR_DETAIL)}"
		return when {
			exceptionCode in LIMIT_CODES -> SourceLimitExceededException(detail).also { log.warn("원천 조회가 상한을 넘었다 — {}", detail) }
			exceptionCode == READONLY -> SourceQueryRejectedException(detail).also { log.error("원천이 쓰기를 거부했다 — {}", detail) }
			status >= SERVER_ERROR || status in TRANSIENT_STATUSES || exceptionCode in UNAVAILABLE_CODES ->
				SourceUnavailableException(detail).also { log.error("원천 조회 일시 장애 — {}", detail) }
			else -> SourceQueryRejectedException(detail).also { log.error("원천이 조회를 거부했다 — {}", detail) }
		}
	}

	private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

	private companion object {
		const val USER_HEADER = "X-ClickHouse-User"
		const val KEY_HEADER = "X-ClickHouse-Key"
		const val EXCEPTION_CODE_HEADER = "X-ClickHouse-Exception-Code"
		const val OK = 200
		const val SERVER_ERROR = 500
		const val MAX_ERROR_DETAIL = 500

		val PARAM_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

		/** 408 요청 시간 초과 · 429 과다 요청. */
		val TRANSIENT_STATUSES = setOf(408, 429)

		/**
		 * 상한 초과: TOO_MANY_ROWS(158) · TIMEOUT_EXCEEDED(159) · TOO_SLOW(160) · MEMORY_LIMIT_EXCEEDED(241) ·
		 * TOO_MANY_BYTES(307) · TOO_MANY_ROWS_OR_BYTES(396).
		 */
		val LIMIT_CODES = setOf(158, 159, 160, 241, 307, 396)

		const val READONLY = 164

		/** 계정 설정의 문제라 요청자가 고칠 수 없다: AUTHENTICATION_FAILED(516) · UNKNOWN_USER(192) · WRONG_PASSWORD(193). */
		val UNAVAILABLE_CODES = setOf(516, 192, 193)
	}
}
