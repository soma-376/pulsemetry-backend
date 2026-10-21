package com.team376.pulsemetry.telemetry.auth

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 키 파일을 읽는다. 개인키는 PKCS8 PEM, 공개키는 X.509 PEM 이고 둘 다 RSA 2048비트 이상이다(ADR 0065 Decision 2).
 *
 * 앱은 Secrets Manager 를 조회하지 않는다 — infra 가 CSI 로 파일을 mount 하고 앱은 `kid → 경로` 맵만 받는다.
 * 기동 때 한 번 읽으므로 파일만 바꿔서는 키가 바뀌지 않는다. 재기동이 교체다.
 *
 * **오류 메시지에 파일 내용을 싣지 않는다.** kid 와 경로만 남긴다 — 경로는 비밀이 아니고, 어느 mount 가 비었는지 알려 준다.
 */
public object TelemetryJwtKeys {

	/** `kid → 경로` 맵을 읽는다. 빈 경로·빈 kid·빈 맵은 거부한다 — 공급자가 비운 항목을 조용히 건너뛰지 않는다. */
	public fun publicKeys(files: Map<String, String>): Map<String, RSAPublicKey> {
		require(files.isNotEmpty()) { "telemetry JWT 공개키 맵(pulsemetry.telemetry-jwt.public-key-files)이 비어 있다" }
		return files.entries.associate { (kid, path) ->
			require(kid.isNotBlank()) { "telemetry JWT 공개키 맵에 빈 kid 가 있다" }
			require(path.isNotBlank()) { "telemetry JWT 공개키 경로가 비어 있다: kid=$kid" }
			kid to publicKey(Path.of(path), kid)
		}
	}

	public fun publicKey(path: Path, kid: String = "-"): RSAPublicKey {
		val key = try {
			KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pem(path, "PUBLIC KEY", kid))) as RSAPublicKey
		} catch (e: IllegalArgumentException) {
			throw e
		} catch (e: Exception) {
			throw IllegalArgumentException("telemetry JWT 공개키가 RSA X.509 PEM 이 아니다: kid=$kid path=$path")
		}
		requireSize(key.modulus.bitLength(), kid, path)
		return key
	}

	public fun privateKey(path: Path): RSAPrivateKey {
		val key = try {
			KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pem(path, "PRIVATE KEY", "active"))) as RSAPrivateKey
		} catch (e: IllegalArgumentException) {
			throw e
		} catch (e: Exception) {
			throw IllegalArgumentException("telemetry JWT 개인키가 RSA PKCS8 PEM 이 아니다: path=$path")
		}
		requireSize(key.modulus.bitLength(), "active", path)
		return key
	}

	private fun requireSize(bits: Int, kid: String, path: Path) {
		require(bits >= TelemetryJwtContract.MIN_RSA_BITS) {
			"telemetry JWT 키가 ${TelemetryJwtContract.MIN_RSA_BITS}비트보다 작다: kid=$kid path=$path bits=$bits"
		}
	}

	private fun pem(path: Path, label: String, kid: String): ByteArray {
		val text = try {
			Files.readString(path)
		} catch (e: Exception) {
			throw IllegalArgumentException("telemetry JWT 키 파일을 읽을 수 없다: kid=$kid path=$path")
		}
		require(text.isNotBlank()) { "telemetry JWT 키 파일이 비어 있다: kid=$kid path=$path" }
		val begin = "-----BEGIN $label-----"
		val end = "-----END $label-----"
		val start = text.indexOf(begin)
		val stop = text.indexOf(end)
		require(start >= 0 && stop > start) { "telemetry JWT 키 파일에 $label PEM 블록이 없다: kid=$kid path=$path" }
		val body = text.substring(start + begin.length, stop).replace(WHITESPACE, "")
		return try {
			Base64.getDecoder().decode(body)
		} catch (e: IllegalArgumentException) {
			throw IllegalArgumentException("telemetry JWT 키 파일의 PEM 본문이 Base64 가 아니다: kid=$kid path=$path")
		}
	}

	private val WHITESPACE = Regex("\\s")
}
