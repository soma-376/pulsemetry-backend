package com.team376.pulsemetry.dashboard.snapshot

import java.security.SecureRandom
import java.util.Base64

/** snapshot ID — 무작위 16바이트의 base64url(패딩 없음) 22자 (ADR 0023 §2). 불투명하고 추측할 수 없다. 권한 증명은 아니다. */
object SnapshotIds {

	private val random = SecureRandom()
	private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
	private val FORMAT = Regex("[A-Za-z0-9_-]{22}")

	fun next(): String = ByteArray(BYTES).also(random::nextBytes).let(encoder::encodeToString)

	fun isWellFormed(value: String): Boolean = FORMAT.matches(value)

	private const val BYTES = 16
}
