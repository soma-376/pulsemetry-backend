package com.team376.pulsemetry.dashboard.support

import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/**
 * 요청 URI 가 [matches] 에 걸리면 **요청은 서버까지 보내 끝내고** [onMatched] 를 부른 뒤, 응답을 잃은 것처럼 [IOException] 을 던진다
 * ([loseResponse] 가 참일 때). 서버에는 쓰기가 끝났는데 클라이언트는 실패로 아는 상황을 만든다.
 */
class LossyHttpClient(
	private val matches: (HttpRequest) -> Boolean,
	private val loseResponse: Boolean = true,
	private val onMatched: () -> Unit = {},
	private val delegate: HttpClient = HttpClient.newHttpClient(),
) : HttpClient() {

	override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
		val response = delegate.send(request, handler)
		if (!matches(request)) return response
		onMatched()
		if (loseResponse) throw IOException("응답 유실(테스트)")
		return response
	}

	override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> =
		throw UnsupportedOperationException()

	override fun <T> sendAsync(
		request: HttpRequest,
		handler: HttpResponse.BodyHandler<T>,
		pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
	): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()

	override fun cookieHandler(): Optional<CookieHandler> = delegate.cookieHandler()
	override fun connectTimeout(): Optional<Duration> = delegate.connectTimeout()
	override fun followRedirects(): Redirect = delegate.followRedirects()
	override fun proxy(): Optional<ProxySelector> = delegate.proxy()
	override fun sslContext(): SSLContext = delegate.sslContext()
	override fun sslParameters(): SSLParameters = delegate.sslParameters()
	override fun authenticator(): Optional<Authenticator> = delegate.authenticator()
	override fun version(): Version = delegate.version()
	override fun executor(): Optional<Executor> = delegate.executor()
}
