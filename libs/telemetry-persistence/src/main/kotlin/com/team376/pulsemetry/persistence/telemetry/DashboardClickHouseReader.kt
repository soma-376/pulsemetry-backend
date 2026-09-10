package com.team376.pulsemetry.persistence.telemetry

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 조회 전용 클라이언트. 사용자 입력은 SQL에 보간하지 않고 ClickHouse 명명 파라미터로 보낸다. */
class DashboardClickHouseReader(
    private val endpoint: URI,
    private val database: String = "default",
    private val timeout: Duration = Duration.ofSeconds(30),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build(),
) {
    init {
        require(endpoint.scheme in setOf("http", "https") && endpoint.host != null)
        require(endpoint.rawQuery == null && endpoint.rawFragment == null && endpoint.userInfo == null)
        require(timeout > Duration.ZERO && timeout <= Duration.ofSeconds(30))
    }

    /** template은 앱의 정적 SQL 레지스트리만 공급한다. DDL/INSERT 용도로 호출할 수 없다. */
    fun query(template: String, parameters: Map<String, String>): String {
        require(template.trimStart().startsWith("SELECT ") || template.trimStart().startsWith("WITH "))
        require(!template.contains(';'))
        require(parameters.keys.all { Regex("[a-z][a-z0-9_]*").matches(it) })
        val settings = linkedMapOf("database" to database, "readonly" to "2", "max_execution_time" to "30",
            "max_result_rows" to "100000", "max_result_bytes" to "16777216", "result_overflow_mode" to "throw",
            "wait_end_of_query" to "1")
        parameters.forEach { (key, value) -> settings["param_$key"] = value }
        val uri = URI.create(endpoint.toString().trimEnd('/') + "/?" + settings.entries.joinToString("&") {
            encode(it.key) + "=" + encode(it.value)
        })
        val request = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "text/plain; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(template + " FORMAT JSON", StandardCharsets.UTF_8)).build()
        // ofString의 completion은 본문 수신까지 포함한다. 헤더만 도착한 채 멈춰도 제한 시간이 적용된다.
        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val response = try { future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join() }
        catch (e: CompletionException) {
            future.cancel(true)
            if (e.cause is TimeoutException || e.cause is java.net.http.HttpTimeoutException)
                throw DashboardReadException("query_timeout", 504)
            throw DashboardReadException("service_unavailable", 503)
        }
        if (response.statusCode() != 200) {
            val body = response.body()
            if (body.startsWith("Code: 159.") || body.startsWith("Code: 160.")) throw DashboardReadException("query_timeout", 504)
            if (body.startsWith("Code: 396.") || body.startsWith("Code: 307.")) throw DashboardReadException("query_too_wide", 422)
            throw DashboardReadException("service_unavailable", 503)
        }
        return response.body()
    }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

class DashboardReadException(val code: String, val status: Int) : RuntimeException(code)
