package com.team376.pulsemetry.enrollment.secret

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * telemetry token 을 DB 에 넣기 전에 통과시키는 HMAC-SHA256 해시.
 *
 * [Sha256] 이 아니라 HMAC 인 이유는 보안이 아니라 **auth-proxy 와의 계약** 때문이다.
 * OTLP 경로의 auth-proxy(ai-telemetry-pipeline)는 Bearer 토큰을
 * `HMAC-SHA256(TOKEN_HASH_SECRET, token)` 의 hex 로 해시해 `telemetry_tokens.token_hash` 를
 * 조회한다. 여기서 같은 키·같은 연산으로 저장하지 않으면 발급한 모든 토큰이 401 이 된다.
 * 해시 대상은 `ptt_` 접두어를 포함한 **발급 문자열 전문**이다 — auth-proxy 가 Bearer 값
 * 전문을 해시하기 때문이다.
 *
 * 키 회전은 드롭인이 아니다. 키가 바뀌면 이미 발급된 모든 토큰의 해시가 매칭 불가가 되어
 * 전 클라이언트가 401 을 받는다 (인프라도 같은 이유로 이 시크릿에 회전을 걸지 않는다 — ADR-0023).
 *
 * 초대 코드·installation credential 은 이 서버만 읽으므로 [Sha256] 을 그대로 쓴다.
 */
@Component
class TelemetryTokenHasher(properties: PulsemetryProperties) {

	private val key: SecretKeySpec

	init {
		val secret = properties.tokenHashSecret
		require(secret.isNotBlank()) {
			"pulsemetry.token-hash-secret 이 비어 있다. 빈 키로 해시하면 auth-proxy 조회가 " +
				"조용히 전부 실패하므로 기동을 막는다."
		}
		key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), ALGORITHM)
	}

	/** HMAC-SHA256 hex 소문자 64자. `Mac` 은 스레드 안전하지 않아 호출마다 새로 만든다. */
	fun hex(token: String): String {
		val mac = Mac.getInstance(ALGORITHM)
		mac.init(key)
		return mac.doFinal(token.toByteArray(StandardCharsets.UTF_8))
			.joinToString(separator = "") { byte -> HEX_DIGITS[byte.toInt() and 0xFF] }
	}

	private companion object {
		const val ALGORITHM = "HmacSHA256"
		val HEX_DIGITS: Array<String> = Array(256) { "%02x".format(it) }
	}
}
