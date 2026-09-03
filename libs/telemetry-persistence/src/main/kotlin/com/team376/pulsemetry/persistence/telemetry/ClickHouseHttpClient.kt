package com.team376.pulsemetry.persistence.telemetry

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * ClickHouse HTTP 인터페이스(:8123)를 직접 부르는 최소 클라이언트.
 *
 * **드라이버를 쓰지 않는다.** 이식 원본이 stdlib 만 쓴 것과 같은 선택이고, 이유는 취향이
 * 아니라 계약이다 — 드라이버는 응답을 자기 예외 계층으로 옮기므로 "4xx 까지 전부 일시 장애"
 * 라는 [TelemetrySinkUnavailableException] 의 고정 동작을 유지할 수 없다.
 *
 * ## 조립
 *
 * Spring 스테레오타입을 달지 않는다(ADR 0011). 접속 정보는 **생성자로 받고**, 빈 등록은
 * 조립 앱이 한다. 이식 원본은 호출마다 환경변수를 읽었지만 여기서는 앱이 값을 주입한다 —
 * 라이브러리가 환경을 뒤지지 않는 편이 테스트도 배포도 명확하다.
 *
 * ## 인증
 *
 * 자격 증명을 보내지 않는다. 현행 배포의 ClickHouse `default` 유저에는 비밀번호가 없고
 * 접근 통제를 보안 그룹이 맡는다(infra ADR-0019). 그 전제가 바뀌면 여기에 헤더가 붙는다.
 */
public class ClickHouseHttpClient(
	private val baseUrl: String,
	private val database: String = DEFAULT_DATABASE,
	private val timeout: Duration = DEFAULT_TIMEOUT,
	private val httpClient: HttpClient =
		HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build(),
) {

	/**
	 * 쿼리 하나를 실행하고 응답 본문을 돌려준다.
	 *
	 * DDL·SELECT 는 [body] 가 `null` 이고, INSERT 는 행 바이트를 싣는다.
	 *
	 * **모든 실패가 [TelemetrySinkUnavailableException] 이다** — 상태 코드가 4xx 든 5xx 든,
	 * 연결이 아예 안 되든 같다. 그 비대칭의 근거는 그 예외의 KDoc 에 있다.
	 */
	public fun execute(query: String, body: ByteArray? = null): String {
		val uri = URI.create(
			baseUrl.trimEnd('/') +
				"/?query=" + encode(query) +
				"&database=" + encode(database),
		)
		val request = HttpRequest.newBuilder(uri)
			.timeout(timeout)
			.POST(body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
			.build()

		val response = try {
			httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
		} catch (exception: IOException) {
			throw TelemetrySinkUnavailableException("clickhouse unreachable: $exception", exception)
		} catch (exception: InterruptedException) {
			// 인터럽트 상태를 복구해 두지 않으면 상위의 취소 처리가 신호를 잃는다.
			Thread.currentThread().interrupt()
			throw TelemetrySinkUnavailableException("clickhouse unreachable: $exception", exception)
		}

		if (response.statusCode() >= FIRST_ERROR_STATUS) {
			// 본문을 자른다. ClickHouse 의 오류 본문은 스택 트레이스까지 실려 로그를 덮는다.
			val detail = response.body().take(MAX_ERROR_DETAIL)
			throw TelemetrySinkUnavailableException("clickhouse ${response.statusCode()}: $detail")
		}
		return response.body()
	}

	private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

	public companion object {
		public const val DEFAULT_DATABASE: String = "default"

		/** 이식 원본의 `urlopen(timeout=30)` 과 같은 값이다. */
		public val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)

		private const val FIRST_ERROR_STATUS: Int = 400
		private const val MAX_ERROR_DETAIL: Int = 500
	}
}
