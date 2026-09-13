package com.team376.pulsemetry.dashboard.time

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.*
import java.time.*

class DashboardTimeTest {
    @Test fun `DST의 하루와 달은 고정 초 길이로 해석하지 않는다`() {
        val time = DashboardTime(Instant.parse("2026-03-09T04:00:00Z"), ZoneId.of("America/New_York"))
        assertThat(time.resolve("now-1d/d")).isEqualTo(Instant.parse("2026-03-08T05:00:00Z"))
        assertThat(time.next(time.resolve("now-1d/d"), "1d")).isEqualTo(time.now)
        assertThat(time.resolve("now/M")).isEqualTo(Instant.parse("2026-03-01T05:00:00Z"))
    }
    @Test fun `6시간 버킷은 DST 전환일에도 현지 0시 6시 경계를 유지한다`() {
        val time = DashboardTime(Instant.parse("2026-03-08T05:00:00Z"), ZoneId.of("America/New_York"))
        assertThat(time.next(time.now, "6h")).isEqualTo(Instant.parse("2026-03-08T10:00:00Z"))
        assertThat(time.bucket(Instant.parse("2026-03-08T11:00:00Z"), "6h"))
            .isEqualTo(Instant.parse("2026-03-08T10:00:00Z"))
    }
    @Test fun `월말과 주 시작 비교 기간을 해석하고 잘못된 식을 거부한다`() {
        val time = DashboardTime(Instant.parse("2026-03-31T12:00:00Z"), ZoneOffset.UTC)
        assertThat(time.resolve("now-1M")).isEqualTo(Instant.parse("2026-02-28T12:00:00Z"))
        assertThat(time.resolve("now/w")).isEqualTo(Instant.parse("2026-03-30T00:00:00Z"))
        val from = time.resolve("now-7d")
        assertThat(time.compare(from, time.now, "previous_period")).isEqualTo(time.resolve("now-14d") to from)
        for (bad in listOf("now+1d", "now-1y", "now-7d;SELECT", "yesterday"))
            assertThatThrownBy { time.resolve(bad) }.isInstanceOf(Exception::class.java)
    }
    @Test fun `계약 날짜는 요청 시간대 자정으로 해석하고 잘못된 날짜를 거부한다`() {
        val time = DashboardTime(Instant.parse("2026-03-09T12:00:00Z"),ZoneId.of("America/New_York"))
        assertThat(time.resolve("2026-03-08")).isEqualTo(Instant.parse("2026-03-08T05:00:00Z"))
        assertThat(time.resolve("2026-03-09")).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"))
        for (bad in listOf("2026-02-30","2026-13-01","2026-9-01"))
            assertThatThrownBy { time.resolve(bad) }.isInstanceOf(Exception::class.java)
    }

}
