package com.team376.pulsemetry.persistence.enrollment.seat

import com.team376.pulsemetry.persistence.enrollment.management.ManagementException

/**
 * 좌석 CSV (ADR 0048 §3 — 수동 원천의 가져오기). RFC 4180 모양(쉼표 구분, 큰따옴표 감싸기, `""` 이스케이프, CRLF·LF), UTF-8, 첫 줄은 머리글.
 *
 * | 열 | 필수 | 값 |
 * | --- | --- | --- |
 * | `account` | 예 | 벤더 계정 — 이메일(Copilot 은 GitHub 로그인) |
 * | `status` | 아니오 | `assigned`(기본)·`released` |
 * | `tier` | 아니오 | 현재 계약의 등급 ID 또는 표시 이름. 비우면 새 좌석은 등급 없음, 있는 좌석은 그대로 |
 * | `member_email` | 아니오 | 이 좌석을 잇는 구성원의 이메일(관리자 연결). 비우면 새 좌석은 이메일 일치 규칙, 있는 좌석은 그대로 |
 *
 * **이메일 외의 개인 정보를 받지 않는다** — 모르는 열(이름·전화 등)이 있으면 파일 전체를 거절한다. 메모 열도 두지 않는다.
 * 파일 자체의 문제(머리글·크기·따옴표)는 400 `invalid_csv`(사유는 `details.reason`), 행의 문제는 가져오기 결과의 행별 오류다.
 */
object SeatCsv {
	const val MAX_ROWS = 5_000
	const val MAX_CHARS = 1_048_576
	val COLUMNS = listOf("account", "status", "tier", "member_email")

	data class Row(val line: Int, val account: String, val status: String?, val tier: String?, val memberEmail: String?, val columnCountMismatch: Boolean)

	fun parse(text: String): List<Row> {
		if (text.length > MAX_CHARS) invalid("too_large")
		val records = records(text.removePrefix("﻿"))
		val header = records.firstOrNull() ?: invalid("missing_header")
		val names = header.second.map { it.trim().lowercase() }
		names.firstOrNull { it !in COLUMNS }?.let { invalid("unknown_column", mapOf("column" to it)) }
		if (names.toSet().size != names.size) invalid("duplicate_column")
		if ("account" !in names) invalid("missing_account_column")
		val data = records.drop(1).filterNot { (_, fields) -> fields.size == 1 && fields[0].isBlank() }
		if (data.size > MAX_ROWS) invalid("too_many_rows", mapOf("maxRows" to MAX_ROWS))
		return data.map { (line, fields) ->
			fun column(name: String) = names.indexOf(name).takeIf { it >= 0 }?.let { fields.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
			Row(line, column("account").orEmpty(), column("status"), column("tier"), column("member_email"), fields.size != names.size)
		}
	}

	/** (레코드가 시작한 줄 번호, 필드) 목록. 따옴표 안의 줄바꿈은 필드의 일부다. */
	private fun records(text: String): List<Pair<Int, List<String>>> {
		val records = mutableListOf<Pair<Int, List<String>>>()
		var fields = mutableListOf<String>()
		val field = StringBuilder()
		var quoted = false
		var line = 1
		var start = 1
		var i = 0
		fun endRecord() {
			fields.add(field.toString()); field.clear()
			records.add(start to fields); fields = mutableListOf()
		}
		while (i < text.length) {
			val c = text[i]
			when {
				quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
				quoted && c == '"' -> {
					quoted = false
					val next = text.getOrNull(i + 1)
					if (next != null && next != ',' && next != '\r' && next != '\n') invalid("malformed_quotes", mapOf("line" to line))
				}
				quoted -> { if (c == '\n') line++; field.append(c) }
				c == '"' -> { if (field.isNotEmpty()) invalid("malformed_quotes", mapOf("line" to line)); quoted = true }
				c == ',' -> { fields.add(field.toString()); field.clear() }
				c == '\r' && text.getOrNull(i + 1) == '\n' -> Unit
				c == '\n' || c == '\r' -> { endRecord(); line++; start = line }
				else -> field.append(c)
			}
			i++
		}
		if (quoted) invalid("malformed_quotes", mapOf("line" to start))
		if (field.isNotEmpty() || fields.isNotEmpty()) endRecord()
		return records
	}

	private fun invalid(reason: String, extra: Map<String, Any> = emptyMap()): Nothing =
		throw ManagementException("invalid_csv", 400, "csv", mapOf("reason" to reason) + extra)
}
