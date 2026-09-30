package com.team376.pulsemetry.connector.vendor

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 벤더 API 를 흉내 내는 JDK 내장 HTTP 서버(테스트 전용 — 새 의존성 없이). 경로마다 응답을 차례로 꺼내고, 마지막 응답은 계속 되풀이한다.
 * 받은 요청(메서드·경로·쿼리·헤더·본문)을 남긴다.
 */
class MockVendorServer : AutoCloseable {
	data class Reply(val status: Int, val body: String, val headers: Map<String, List<String>> = emptyMap())
	data class Received(val method: String, val path: String, val query: String?, val headers: Map<String, List<String>>, val body: String) {
		fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
		fun param(name: String): String? = query?.split('&')?.map { it.split('=', limit = 2) }?.firstOrNull { it[0] == name }?.getOrNull(1)
			?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
	}

	private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
	private val routes = mutableMapOf<String, ArrayDeque<(Received) -> Reply>>()
	val received = ConcurrentLinkedQueue<Received>()

	init {
		server.createContext("/") { exchange ->
			val request = Received(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestURI.rawQuery,
				exchange.requestHeaders.mapValues { it.value.toList() }, exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
			received += request
			val queue = synchronized(routes) { routes["${request.method} ${request.path}"] }
			val reply = if (queue == null) Reply(404, """{"message":"Not Found"}""") else synchronized(queue) {
				(if (queue.size > 1) queue.removeFirst() else queue.first())(request)
			}
			reply.headers.forEach { (name, values) -> values.forEach { exchange.responseHeaders.add(name, it) } }
			exchange.responseHeaders.add("Content-Type", "application/json")
			val bytes = reply.body.toByteArray()
			exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	val base: URI get() = URI("http://127.0.0.1:${server.address.port}")

	/** [path] 는 퍼센트 인코딩된 경로 그대로다. 응답을 차례로 쓴다. */
	fun on(method: String, path: String, vararg replies: (Received) -> Reply) {
		synchronized(routes) { routes["$method $path"] = ArrayDeque(replies.toList()) }
	}

	fun requests(path: String) = received.filter { it.path == path }

	override fun close() = server.stop(0)
}

fun reply(status: Int, body: String, vararg headers: Pair<String, String>): (MockVendorServer.Received) -> MockVendorServer.Reply =
	{ MockVendorServer.Reply(status, body, headers.groupBy({ it.first }, { it.second })) }
