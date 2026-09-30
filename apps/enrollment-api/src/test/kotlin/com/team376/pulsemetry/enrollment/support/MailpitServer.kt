package com.team376.pulsemetry.enrollment.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 메일 수신 컨테이너. 테스트 JVM 전체가 하나를 같이 쓰고, 각 테스트가 시작할 때 [reset] 으로 받은 메일과 거절 주입을 비운다.
 * SMTP 1025, 조회 API 8025. 아무 계정이나 받는다.
 */
object MailpitServer {
    private val mapper = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()
    private val container: GenericContainer<*> = GenericContainer("axllent/mailpit:v1.27").withExposedPorts(1025, 8025)
        .withEnv("MP_ENABLE_CHAOS", "true").withEnv("MP_SMTP_AUTH_ACCEPT_ANY", "1").withEnv("MP_SMTP_AUTH_ALLOW_INSECURE", "1")
        .waitingFor(Wait.forHttp("/readyz").forPort(8025)).also { it.start(); awaitSmtp(it) }

    val host: String get() = container.host
    val smtpPort: Int get() = container.getMappedPort(1025)

    /** `@DynamicPropertySource` 에서 부른다. */
    fun register(registry: DynamicPropertyRegistry) {
        registry.add("pulsemetry.mail.smtp.host") { host }
        registry.add("pulsemetry.mail.smtp.port") { smtpPort }
    }

    fun reset() {
        api("DELETE", "/api/v1/messages")
        chaos()
    }

    /** SMTP 단계마다 지정한 코드로 거절하게 한다. 인자가 없으면 정상이다. */
    fun chaos(recipient: Int? = null, sender: Int? = null, authentication: Int? = null) {
        fun trigger(code: Int?, fallback: Int) = mapOf("ErrorCode" to (code ?: fallback), "Probability" to if (code == null) 0 else 100)
        api("PUT", "/api/v1/chaos", mapper.writeValueAsString(mapOf("Recipient" to trigger(recipient, 451), "Sender" to trigger(sender, 451), "Authentication" to trigger(authentication, 535))))
    }

    /** 받은 메일의 요약(`ID`·`To[].Address`·`From.Address`·`Subject`). */
    fun received(): List<JsonNode> = api("GET", "/api/v1/messages").path("messages").toList()
    fun recipients(message: JsonNode): List<String> = message.path("To").toList().map { it.path("Address").asString() }
    /** 받은 메일의 본문. 줄바꿈은 LF 로 맞춘다. */
    fun text(message: JsonNode): String = api("GET", "/api/v1/message/${message.path("ID").asString()}").path("Text").asString().replace("\r\n", "\n").trim()
    fun header(message: JsonNode, name: String): List<String> = api("GET", "/api/v1/message/${message.path("ID").asString()}/headers").path(name).toList().map { it.asString() }

    private fun api(method: String, path: String, body: String? = null): JsonNode {
        val request = HttpRequest.newBuilder(URI("http://$host:${container.getMappedPort(8025)}$path")).header("Content-Type", "application/json")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "$method $path → ${response.statusCode()}" }
        return if (response.body().trimStart().startsWith("{")) mapper.readTree(response.body()) else mapper.createObjectNode()
    }

    /** 조회 API 가 먼저 뜬다. SMTP 가 인사말(220)을 줄 때까지 기다리지 않으면 첫 발송들이 연결 실패로 끝난다. */
    private fun awaitSmtp(container: GenericContainer<*>) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (true) {
            val ready = runCatching {
                Socket(container.host, container.getMappedPort(1025)).use { socket ->
                    socket.soTimeout = 2000
                    socket.getInputStream().bufferedReader().readLine()?.startsWith("220") == true
                }
            }.getOrDefault(false)
            if (ready) return
            check(System.nanoTime() < deadline) { "메일 수신 컨테이너의 SMTP 가 준비되지 않았다" }
            Thread.sleep(100)
        }
    }
}
