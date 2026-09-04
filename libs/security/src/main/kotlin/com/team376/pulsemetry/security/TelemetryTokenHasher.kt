package com.team376.pulsemetry.security

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * telemetry token 의 HMAC-SHA256 해시.
 *
 * 무염 SHA-256 이 아니라 HMAC 인 이유는 보안이 아니라 **계약** 때문이다. 발급하는 쪽
 * (`:apps:enrollment-api`)과 검증하는 쪽(이 모듈)이 같은 키로 같은 연산을 해야
 * `telemetry_tokens.token_hash` 조회가 성립한다. **한쪽만 고치면 발급된 모든 토큰이 조용히 401 이 된다.**
 * 계약의 단일 출처는 허브 `contracts/enrollment-api.md` §4 다.
 *
 * 이 클래스가 앱이 아니라 `:libs:` 에 있는 이유가 그것이다 — 정의가 두 벌이 되면 안 되는 코드다
 * (ADR 0008 규칙 3·5).
 *
 * 해시 대상은 `ptt_` 접두어를 포함한 **발급 문자열 전문**이다. 접두어를 떼면 안 된다.
 *
 * 키 회전은 드롭인이 아니다. 키가 바뀌면 이미 발급된 모든 토큰의 해시가 매칭 불가가 되어
 * 전 클라이언트가 401 을 받는다 (인프라도 같은 이유로 이 시크릿에 회전을 걸지 않는다 — infra ADR 0023).
 *
 * **스테레오타입을 달지 않는다** (ADR 0011). 빈 등록은 이 모듈을 쓰는 앱이 한다.
 */
class TelemetryTokenHasher(secret: String) {

	private val key: SecretKeySpec

	init {
		require(secret.isNotBlank()) {
			"pulsemetry.token-hash-secret 이 비어 있다. 빈 키로 해시하면 토큰 조회가 " +
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
