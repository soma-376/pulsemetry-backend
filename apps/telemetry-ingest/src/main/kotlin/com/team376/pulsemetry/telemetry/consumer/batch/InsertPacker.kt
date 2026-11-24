package com.team376.pulsemetry.telemetry.consumer.batch

import com.team376.pulsemetry.persistence.telemetry.AnalysisLinesSink

/** 원본 하나([owner])의 연속한 줄. [solo] 면 한 행만으로 상한 이상이라 독립 INSERT 다 — 이분할에서 뺀다. */
class Piece<K>(val owner: K, val lines: List<String>, val bytes: Long, val solo: Boolean)

/** INSERT 요청 하나에 실을 조각들. */
class Chunk<K>(val pieces: List<Piece<K>>)

/**
 * 분석 행 줄을 INSERT 본문 상한 **미만**으로 묶는다(PROJ-341 · 11차 개정안 2절 "큰 입력").
 *
 * 크기는 실제로 전송할 `JSONEachRow` 본문의 UTF-8 바이트(줄바꿈 포함)로 잰다([AnalysisLinesSink.bodyBytes]). 한 원본의 줄이 상한을 넘으면
 * 줄 경계에서 여러 조각으로 나눠 같은 원본에 연결한다. 한 줄만 담은 본문이 상한 이상이면 그 줄은 독립 INSERT(`solo`)다. 원본 순서를 지킨다.
 */
object InsertPacker {

	class Packed<K>(val chunks: List<Chunk<K>>, val pieceCounts: Map<K, Int>)

	fun <K> pack(items: List<Pair<K, List<String>>>, limit: Long): Packed<K> {
		require(limit > 0) { "본문 상한은 양수다: $limit" }
		val pieces = mutableListOf<Piece<K>>()
		val counts = LinkedHashMap<K, Int>()
		for ((owner, lines) in items) {
			var buffer = mutableListOf<String>()
			var bytes = 0L
			var count = 0
			fun flush() {
				if (buffer.isNotEmpty()) {
					pieces += Piece(owner, buffer, bytes, solo = false)
					count++
				}
				buffer = mutableListOf()
				bytes = 0
			}
			for (line in lines) {
				val size = AnalysisLinesSink.bodyBytes(line)
				if (size >= limit) {
					flush()
					pieces += Piece(owner, listOf(line), size, solo = true)
					count++
					continue
				}
				if (bytes + size >= limit) flush()
				buffer += line
				bytes += size
			}
			flush()
			counts[owner] = count
		}

		val chunks = mutableListOf<Chunk<K>>()
		var current = mutableListOf<Piece<K>>()
		var currentBytes = 0L
		fun close() {
			if (current.isNotEmpty()) chunks += Chunk(current)
			current = mutableListOf()
			currentBytes = 0
		}
		for (piece in pieces) {
			if (piece.solo) {
				close()
				chunks += Chunk(listOf(piece))
				continue
			}
			if (current.isNotEmpty() && currentBytes + piece.bytes >= limit) close()
			current += piece
			currentBytes += piece.bytes
		}
		close()
		return Packed(chunks, counts)
	}
}
