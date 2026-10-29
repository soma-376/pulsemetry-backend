package com.team376.pulsemetry.telemetry.ingress

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Kafka mTLS의 파일 위치만 보관한다(ADR 0070). 비밀번호는 client를 만들 때 읽고 표준 Kafka 비밀번호 키로 넘긴다.
 *
 * Strimzi Secret volume은 symlink를 사용하므로 실제 파일 링크를 허용한다. 자동 갱신 감시는 하지 않는다.
 * 갱신은 infra가 Secret revision을 확인한 뒤 앱을 재배포하여 반영한다. 이 타입은 Spring·Kafka에 의존하지 않는다.
 */
public data class TelemetryKafkaTls(
	public val truststoreLocation: String = "",
	public val truststorePasswordFile: String = "",
	public val keystoreLocation: String = "",
	public val keystorePasswordFile: String = "",
) {
	/** 비밀번호가 포함되므로 반환값 자체를 로그로 출력하지 않는다. */
	internal fun clientProperties(): Map<String, Any> {
		val trustPassword = password(truststorePasswordFile, "TRUSTSTORE_PASSWORD_FILE")
		var keyPassword = charArrayOf()
		try {
			keyPassword = password(keystorePasswordFile, "KEYSTORE_PASSWORD_FILE")
			val trust = store(truststoreLocation, trustPassword, "TRUSTSTORE_LOCATION")
			val keys = store(keystoreLocation, keyPassword, "KEYSTORE_LOCATION")
			// CA 회전 기간에는 만료된 이전 CA가 함께 남을 수 있다. 유효한 신뢰 인증서가 하나 이상 있어야 한다.
			val trustedCertificates = trust.aliases().toList().filter { trust.isCertificateEntry(it) }
				.mapNotNull { trust.getCertificate(it) as? X509Certificate }
			require(trustedCertificates.any { valid(it) }) { "Kafka mTLS truststore에 유효한 X.509 신뢰 인증서가 없다" }
			val keyAliases = keys.aliases().toList().filter { keys.isKeyEntry(it) }
			require(keyAliases.size == 1) { "Kafka mTLS keystore에는 사용자 개인키가 정확히 하나 필요하다" }
			val alias = keyAliases.single()
			val privateKey = safe("KEYSTORE_LOCATION") { keys.getKey(alias, keyPassword) }
			require(privateKey is PrivateKey) { "Kafka mTLS keystore에 사용자 개인키가 없다" }
			val chain = keys.getCertificateChain(alias).orEmpty()
			require(chain.isNotEmpty() && chain.all { it is X509Certificate && valid(it) }) {
				"Kafka mTLS keystore 인증서가 없거나 유효기간 밖이다"
			}
			return mapOf(
				"security.protocol" to "SSL",
				"ssl.endpoint.identification.algorithm" to "https",
				"ssl.truststore.type" to "PKCS12",
				"ssl.truststore.location" to truststoreLocation,
				"ssl.truststore.password" to String(trustPassword),
				"ssl.keystore.type" to "PKCS12",
				"ssl.keystore.location" to keystoreLocation,
				"ssl.keystore.password" to String(keyPassword),
				"ssl.key.password" to String(keyPassword),
			)
		} finally {
			trustPassword.fill('\u0000')
			keyPassword.fill('\u0000')
		}
	}

	// 경로에도 운영 세부정보가 담길 수 있어 로그에는 접속 방식만 남긴다.
	override fun toString(): String = "TelemetryKafkaTls(PKCS12 files)"

	private fun password(location: String, key: String): CharArray {
		val bytes = bytes(location, key, 4096)
		return try {
			val text = safe(key) { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
			require(text.isNotEmpty()) { "PULSEMETRY_KAFKA_TLS_$key 파일이 비어 있다" }
			text.toCharArray()
		} finally {
			bytes.fill(0)
		}
	}

	private fun store(location: String, password: CharArray, key: String): KeyStore {
		val bytes = bytes(location, key, 1024 * 1024)
		return try {
			safe(key) { KeyStore.getInstance("PKCS12").apply { bytes.inputStream().use { load(it, password) } } }
		} finally {
			bytes.fill(0)
		}
	}

	private fun bytes(location: String, key: String, limit: Int): ByteArray {
		require(location.isNotBlank()) { "PULSEMETRY_KAFKA_TLS_$key 경로가 필요하다" }
		return safe(key) {
			val path = Path.of(location)
			require(path.isAbsolute && Files.isRegularFile(path) && Files.isReadable(path))
			Files.newInputStream(path).use { it.readNBytes(limit + 1) }.also {
				require(it.isNotEmpty() && it.size <= limit)
			}
		}
	}

	private fun valid(certificate: X509Certificate): Boolean = runCatching { certificate.checkValidity() }.isSuccess

	/** KeyStore·파일 예외의 메시지·cause에 자격 원문이나 파일 내용이 섞이지 않게 제거한다. */
	private fun <T> safe(key: String, action: () -> T): T = try {
		action()
	} catch (_: Exception) {
		throw IllegalArgumentException("PULSEMETRY_KAFKA_TLS_$key 파일을 읽거나 PKCS12 자격을 검증할 수 없다")
	}
}
