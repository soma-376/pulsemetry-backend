package com.team376.pulsemetry.dashboard

import java.time.*
import java.time.temporal.TemporalAdjusters

/** 하나의 요청은 동일한 now를 캡처한다. 월·일 경계는 tenant의 지역 시간으로 해석한다. */
class DashboardTime(val now: Instant, val zone: ZoneId) {
    fun resolve(raw: String): Instant {
        if (raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return LocalDate.parse(raw).atStartOfDay(zone).toInstant()
        if (!raw.startsWith("now")) return Instant.parse(raw)
        val match = Regex("now(?:-([0-9]{1,6})([smhdwM]))?(?:/([dwM]))?").matchEntire(raw)
            ?: throw IllegalArgumentException("invalid_time")
        var date = now.atZone(zone)
        val amount = match.groupValues[1].toLongOrNull() ?: 0
        date = when (match.groupValues[2]) {
            "s" -> date.minusSeconds(amount)
            "m" -> date.minusMinutes(amount)
            "h" -> date.minusHours(amount)
            "d" -> date.minusDays(amount)
            "w" -> date.minusWeeks(amount)
            "M" -> date.minusMonths(amount)
            else -> date
        }
        date = when (match.groupValues[3]) {
            "d" -> date.toLocalDate().atStartOfDay(zone)
            "w" -> date.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone)
            "M" -> date.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
            else -> date
        }
        return date.toInstant()
    }
    fun bucket(at: Instant, interval: String): Instant {
        val d = at.atZone(zone)
        return when (interval) {
            "1h" -> d.withMinute(0).withSecond(0).withNano(0)
            "6h" -> d.withHour(d.hour / 6 * 6).withMinute(0).withSecond(0).withNano(0)
            "1d" -> d.toLocalDate().atStartOfDay(zone)
            "1w" -> d.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone)
            "1M" -> d.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
            else -> throw IllegalArgumentException("invalid_interval")
        }.toInstant()
    }
    fun next(at: Instant, interval: String): Instant = when (interval) {
        "1h" -> at.plusSeconds(3600)
        "6h" -> at.atZone(zone).toLocalDateTime().plusHours(6).atZone(zone).toInstant()
        "1d" -> at.atZone(zone).plusDays(1).toInstant()
        "1w" -> at.atZone(zone).plusWeeks(1).toInstant()
        "1M" -> at.atZone(zone).plusMonths(1).toInstant()
        else -> throw IllegalArgumentException("invalid_interval")
    }
    fun compare(from: Instant, to: Instant, mode: String): Pair<Instant, Instant>? = when (mode) {
        "none" -> null
        "previous_period" -> from.minus(Duration.between(from, to)) to from
        "previous_week" -> from.atZone(zone).minusWeeks(1).toInstant() to to.atZone(zone).minusWeeks(1).toInstant()
        else -> throw IllegalArgumentException("invalid_compare")
    }
}
