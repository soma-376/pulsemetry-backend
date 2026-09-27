package com.team376.pulsemetry.retention

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.DefaultApplicationArguments
import java.time.Instant
import java.util.UUID

/**
 * 명령의 입력과 경계 계산(ADR 0024 §1·§5). 경계의 기대값은 조직 보존 설정의 규칙에서 온다 — asOf 의 KST 날짜에서 N 개월 전 같은 날의
 * KST 00:00, 그 날이 없는 달이면 말일.
 */
class RetentionCommandTest {

	private val tenant = UUID.randomUUID()

	@Test
	@DisplayName("사례 8 — asOf 2026-09-24 KST 의 12개월 보존 경계는 2025-09-24 00:00 KST(2025-09-23T15:00Z)다")
	fun case8Boundary() {
		val boundary = RetentionBoundaryRule.deletedBefore(Instant.parse("2026-09-24T01:00:00Z"), 12)

		assertThat(boundary).isEqualTo(Instant.parse("2025-09-23T15:00:00Z"))
	}

	@Test
	@DisplayName("날짜는 UTC 가 아니라 KST 로 정한다 — UTC 로는 전날인 시각도 KST 날짜를 쓴다")
	fun theDateIsTheKstDate() {
		// 2026-09-23T15:30Z = 2026-09-24 00:30 KST.
		assertThat(RetentionBoundaryRule.deletedBefore(Instant.parse("2026-09-23T15:30:00Z"), 12))
			.isEqualTo(Instant.parse("2025-09-23T15:00:00Z"))
		// 2026-09-23T14:59Z = 2026-09-23 23:59 KST.
		assertThat(RetentionBoundaryRule.deletedBefore(Instant.parse("2026-09-23T14:59:00Z"), 12))
			.isEqualTo(Instant.parse("2025-09-22T15:00:00Z"))
	}

	@Test
	@DisplayName("그 날이 없는 달이면 말일 — 3월 31일의 1개월 전은 2월 28일, 윤년이면 29일")
	fun monthEndIsClamped() {
		assertThat(RetentionBoundaryRule.deletedBefore(Instant.parse("2026-03-31T03:00:00Z"), 1))
			.isEqualTo(Instant.parse("2026-02-27T15:00:00Z"))
		assertThat(RetentionBoundaryRule.deletedBefore(Instant.parse("2028-03-31T03:00:00Z"), 1))
			.isEqualTo(Instant.parse("2028-02-28T15:00:00Z"))
	}

	@Test
	@DisplayName("인자 셋을 읽는다 — asOf 는 오프셋이 있는 ISO-8601 이고, 설정 인자가 함께 있어도 된다")
	fun parsesTheThreeArguments() {
		val command = parse("--tenant=$tenant", "--retention-months=24", "--as-of=2026-09-24T10:00:00+09:00", "--spring.main.banner-mode=off")

		assertThat(command).isEqualTo(RetentionCommand(tenant, 24, Instant.parse("2026-09-24T01:00:00Z")))
		assertThat(command.requestedBefore).isEqualTo(Instant.parse("2024-09-23T15:00:00Z"))
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"--retention-months=12|--as-of=2026-09-24T10:00:00+09:00",
			"--tenant=not-a-uuid|--retention-months=12|--as-of=2026-09-24T10:00:00+09:00",
			"--tenant=TENANT|--retention-months=0|--as-of=2026-09-24T10:00:00+09:00",
			"--tenant=TENANT|--retention-months=twelve|--as-of=2026-09-24T10:00:00+09:00",
			"--tenant=TENANT|--retention-months=12",
			"--tenant=TENANT|--retention-months=12|--as-of=2026-09-24T10:00:00",
			"--tenant=TENANT|--retention-months=12|--retention-months=24|--as-of=2026-09-24T10:00:00+09:00",
			"--tenant=TENANT|--retention-months=12|--as-of=2026-09-24T10:00:00+09:00|stray",
		],
	)
	@DisplayName("빠졌거나 형식이 틀리거나 겹치거나 위치 인자가 있으면 거부한다 — 오프셋 없는 시각도")
	fun rejectsBadArguments(joined: String) {
		val args = joined.replace("TENANT", tenant.toString()).split('|').toTypedArray()

		assertThatThrownBy { parse(*args) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	private fun parse(vararg args: String): RetentionCommand = RetentionCommand.parse(DefaultApplicationArguments(*args))
}
