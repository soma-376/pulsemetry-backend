package com.team376.pulsemetry.persistence.telemetry

import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * HTTP/1.1 chunked 본문을 테스트가 정한 때에 한 조각씩 보낸다. JDK 클라이언트의 스트리밍 본문은 조각을 보내는 시점을 보장하지
 * 않아(측정 중 첫 조각이 서버에 닿지 않았다) 소켓에 직접 쓴다.
 */
internal class ChunkedInsert(host: String, port: Int, target: String) : AutoCloseable {
	private val socket = Socket(host, port).apply { soTimeout = 30_000 }
	private val out = socket.getOutputStream()

	init {
		out.write("POST $target HTTP/1.1\r\nHost: $host:$port\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
		out.flush()
	}

	fun chunk(text: String) {
		val bytes = text.toByteArray(StandardCharsets.UTF_8)
		out.write("${Integer.toHexString(bytes.size)}\r\n".toByteArray(StandardCharsets.US_ASCII))
		out.write(bytes)
		out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
		out.flush()
	}

	/** 본문을 끝내고 응답 상태 코드를 돌려준다. */
	fun finish(): Int {
		out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
		out.flush()
		val status = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine()
		return status.split(' ')[1].toInt()
	}

	override fun close() = socket.close()
}
