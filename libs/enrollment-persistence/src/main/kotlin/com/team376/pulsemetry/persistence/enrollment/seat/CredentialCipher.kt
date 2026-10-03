package com.team376.pulsemetry.persistence.enrollment.seat

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 행이 쓰는 키 ID 가 설정에 없다. 평문으로 떨어지지 않는다 (ADR 0048 §6). */
class CredentialKeyUnavailable : RuntimeException("credential_key_unavailable")

/**
 * 벤더 자격증명의 AES-256-GCM 암호화 (ADR 0048 §6). 키는 키 ID → Base64 32바이트이고 새 암호문은 현재 키로 만든다.
 * 옛 키는 그 키로 암호화된 행이 남아 있는 동안 복호화에만 쓴다. 추가 인증 데이터는 호출자가 행의 식별자로 묶는다.
 */
class CredentialCipher(keys: Map<String, String>, private val currentKeyId: String) {
	private val keys: Map<String, SecretKeySpec>
	private val random = SecureRandom()

	init {
		require(keys.isNotEmpty()) { "pulsemetry.vendor-connections.credential-keys 가 비어 있다" }
		this.keys = keys.mapValues { (id, value) ->
			require(KEY_ID.matches(id)) { "자격증명 키 ID 는 [A-Za-z0-9_-]{1,64} 여야 한다" }
			val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
			require(bytes != null && bytes.size == 32) { "자격증명 키 $id 는 Base64 32바이트여야 한다" }
			SecretKeySpec(bytes, "AES")
		}
		require(currentKeyId in this.keys) { "pulsemetry.vendor-connections.credential-key-id 가 키 목록에 없다" }
	}

	/** 암호문과 그것을 만든 키 ID. 평문을 담지 않는다. */
	class Sealed(val keyId: String, val ciphertext: String)

	fun seal(plaintext: String, aad: String): Sealed {
		val nonce = ByteArray(12).also(random::nextBytes)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
			init(Cipher.ENCRYPT_MODE, keys.getValue(currentKeyId), GCMParameterSpec(128, nonce))
			updateAAD(aad.toByteArray())
		}
		return Sealed(currentKeyId, Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plaintext.toByteArray())))
	}

	fun open(keyId: String, ciphertext: String, aad: String): String {
		val key = keys[keyId] ?: throw CredentialKeyUnavailable()
		val bytes = Base64.getDecoder().decode(ciphertext)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
			init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
			updateAAD(aad.toByteArray())
		}
		return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
	}

	override fun toString(): String = "CredentialCipher(keys=${keys.keys}, current=$currentKeyId)"

	private companion object {
		val KEY_ID = Regex("[A-Za-z0-9_-]{1,64}")
	}
}
