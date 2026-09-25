package com.team376.pulsemetry.dashboard.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** 실제 포트로 요청을 보내는 테스트 도우미. 필터 체인 전부를 거친다. */
class DashboardHttp(private val port: Int) {

	private val client: HttpClient = HttpClient.newHttpClient()

	fun send(
		path: String,
		method: String = "GET",
		headers: Map<String, String> = emptyMap(),
	): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
			.method(method, HttpRequest.BodyPublishers.noBody())
		headers.forEach { (name, value) -> builder.header(name, value) }
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
	}

	companion object {
		private val mapper: JsonMapper = JsonMapper.builder().build()

		fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())
	}
}
