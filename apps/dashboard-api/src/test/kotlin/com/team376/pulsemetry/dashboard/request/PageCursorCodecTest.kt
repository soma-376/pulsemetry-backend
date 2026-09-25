package com.team376.pulsemetry.dashboard.request

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class PageCursorCodecTest {

	private val codec = PageCursorCodec(JsonMapper.builder().build())

	@Test
	@DisplayName("cursor 는 URL 에 그대로 실을 수 있는 문자열이고 되읽힌다")
	fun roundTripIsUrlSafe() {
		val cursor = PageCursor("snap/+=", "members:q=가나다", listOf(null, "0.000000000001", "7c9e6679-7425-40de-944b-e07fc1f90ae7"))

		val token = codec.encode(cursor)

		assertThat(token).matches("[A-Za-z0-9_-]+")
		assertThat(codec.decode(token)).isEqualTo(cursor)
	}

	@Test
	@DisplayName("지나치게 긴 입력은 해석하지 않는다")
	fun overlongIsRejected() {
		assertThat(codec.decode("A".repeat(2049))).isNull()
	}
}
