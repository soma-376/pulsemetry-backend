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
 * 아니라 계약이다 — 드라이버는 응답을 자기 예외 계층으로 옮기므로, 어떤 상태가 일시 장애이고
 * 어떤 상태가 영구 오류인지를 이 클래스가 정할 수 없게 된다. 그 분류가 곧 HTTP 계약이다
 * (허브 ADR 0006).
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
	// ClickHouse 는 리다이렉트를 내지 않는다. 따라가면 POST 본문이 상태별로 다르게 처리된다.
	private val httpClient: HttpClient =
		HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build(),
) {

	/**
	 * 쿼리 하나를 실행하고 응답 본문을 돌려준다.
	 *
	 * DDL·SELECT 는 [body] 가 `null` 이고, INSERT 는 행 바이트를 싣는다.
	 *
	 * **실패는 둘로 갈린다** — 연결 계열과 `5xx · 429 · 408` 은
	 * [TelemetrySinkUnavailableException](일시 장애 → 503), 그 밖의 4xx 는
	 * [TelemetrySinkRejectedException](영구 오류 → 400)이다. 근거는 허브 ADR 0006 이다.
	 *
	 * 타임아웃은 응답 **헤더** 도착까지만 잰다(`HttpRequest.timeout` 의 의미). 본문 수신은 재지
	 * 않으므로 헤더만 보내고 본문을 끄는 서버에는 더 오래 매달릴 수 있다 — DDL·INSERT 응답 본문이
	 * 짧아 실무 위험은 낮다.
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

		val status = response.statusCode()
		if (status >= FIRST_ERROR_STATUS) {
			// 본문을 자른다. ClickHouse 의 오류 본문은 스택 트레이스까지 실려 로그를 덮는다.
			val detail = response.body().take(MAX_ERROR_DETAIL)
			val message = "clickhouse $status: $detail"
			throw if (isTransient(status)) {
				TelemetrySinkUnavailableException(message)
			} else {
				TelemetrySinkRejectedException(message)
			}
		}
		return response.body()
	}

	private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

	/**
	 * 다시 보내면 답이 달라질 수 있는 상태인가.
	 *
	 * 5xx 는 서버 쪽 사정이고, `429` 는 과부하, `408` 은 타이밍이다. 나머지 4xx 는 구문·인증·
	 * 대상 부재라 같은 배치를 다시 보내도 같은 답이 온다. **목록을 넓히지 마라** — 넓히면
	 * 스키마 불일치가 다시 재시도로 맴돈다(허브 ADR 0006).
	 */
	private fun isTransient(status: Int): Boolean =
		status >= FIRST_SERVER_ERROR_STATUS || status == TOO_MANY_REQUESTS || status == REQUEST_TIMEOUT

	public companion object {
		public const val DEFAULT_DATABASE: String = "default"

		/** 이식 원본의 `urlopen(timeout=30)` 과 같은 값이다. */
		public val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)

		private const val FIRST_ERROR_STATUS: Int = 400
		private const val FIRST_SERVER_ERROR_STATUS: Int = 500
		private const val REQUEST_TIMEOUT: Int = 408
		private const val TOO_MANY_REQUESTS: Int = 429
		private const val MAX_ERROR_DETAIL: Int = 500
	}
}
