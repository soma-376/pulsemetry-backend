package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.DatePeriod
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class TeamScreenValuesTest {

	private val day = LocalDate.parse("2026-09-07")
	private val period = ComparedPeriod(DatePeriod(day, day, QueryReader.SEOUL), CompareMode.PREV_WEEK)
	private val sample = UsageTotals.EMPTY.copy(
		usageRows = 2, identifiedUsers = 2, identifiedSessions = 4,
		inputUncached = UsageTotals.Component(40, 0), output = UsageTotals.Component(10, 0),
		cacheRead = UsageTotals.Component(50, 0), cacheWrite = UsageTotals.Component(0, 0),
		totalDerived = UsageTotals.Component(100, 0), semanticsProfiles = 1, cost = BigDecimal("12"),
	)

	private fun frame(currentComplete: Boolean = true, previousComplete: Boolean = true): AnalyticsFrames.Frame {
		val snapshot = mock(SnapshotManifestStore.Manifest::class.java)
		val complete = buildSet { if (currentComplete) add(day); if (previousComplete) add(day.minusDays(7)) }
		return AnalyticsFrames.Frame(
			Organization(UUID.randomUUID(), "org", "Asia/Seoul"), period, snapshot, IngestStatusReader.History(null),
			setOf(day), complete, Coverage(if (currentComplete) "complete" else "partial", 1),
			Coverage(if (previousComplete) "complete" else "none", if (previousComplete) 1 else 0),
			if (currentComplete) "ready" else "partial", currentComplete && previousComplete,
			Instant.parse("2026-09-08T00:00:00Z"),
			mapOf(day to listOf("collection_gap"), day.minusDays(7) to listOf("collection_gap")),
		)
	}

	@Test
	fun `관측 근거 없는 빈 팀은 값 없음이며 완전한 기간의 미사용은 0이다`() {
		val unknown = TeamScreenValues.metrics(frame(false), UsageTotals.EMPTY, null)
		assertThat(unknown.equivalentCostUsd.value).isNull()
		assertThat(unknown.sessionCount.status).isEqualTo("unavailable")
		assertThat(unknown.sessionCount.reasons).contains("current_period_incomplete", "collection_gap")
		val zero = TeamScreenValues.metrics(frame(), UsageTotals.EMPTY, UsageTotals.EMPTY)
		assertThat(zero.equivalentCostUsd.value).isEqualTo("0.000000")
		assertThat(zero.totalTokens.value).isZero()
		assertThat(zero.sessionCount.value).isZero()
		assertThat(zero.equivalentCostUsd.comparison.change).isEqualTo("unchanged")
		assertThat(zero.tokensPerUser.value).isNull()
		assertThat(zero.tokensPerUser.reasons).containsExactly("zero_denominator")
	}

	@Test
	fun `미식별 사용량은 식별된 인원으로 나누지 않으며 세션 기준 값은 유지한다`() {
		val metrics = TeamScreenValues.metrics(frame(), sample.copy(unidentifiedRows = 1), sample)
		assertThat(metrics.activeUsers.value).isEqualTo(2)
		assertThat(metrics.activeUsers.status).isEqualTo("partial")
		assertThat(metrics.equivalentCostPerUserUsd.value).isNull()
		assertThat(metrics.tokensPerUser.value).isNull()
		assertThat(metrics.sessionsPerUser.value).isNull()
		assertThat(metrics.tokensPerUser.reasons).containsExactly("member_identity_missing")
		assertThat(metrics.equivalentCostPerSessionUsd.value).isEqualTo("3.000000")
		assertThat(metrics.tokensPerSession.value).isEqualTo(25.0)
		assertThat(metrics.equivalentCostPerUserUsd.comparison.status).isEqualTo("unavailable")
	}

	@Test
	fun `이전 기간 근거 부족은 현재 값을 훼손하지 않고 비교만 막는다`() {
		val metrics = TeamScreenValues.metrics(frame(previousComplete = false), sample, null)
		assertThat(metrics.equivalentCostUsd.status).isEqualTo("complete")
		assertThat(metrics.equivalentCostUsd.reasons).isEmpty()
		assertThat(metrics.equivalentCostUsd.comparison.status).isEqualTo("unavailable")
		assertThat(metrics.equivalentCostUsd.comparison.reasons).containsExactly("previous_period_incomplete", "previous_collection_gap")
		assertThat(metrics.equivalentCostUsd.comparison.previousValue).isNull()
	}

	@Test
	fun `세션 ID 결손과 가격 결손은 해당 파생값만 막는다`() {
		val metrics = TeamScreenValues.metrics(frame(), sample.copy(sessionlessRows = 1, unpricedRows = 1), sample)
		assertThat(metrics.equivalentCostUsd.reasons).containsExactly("pricing_missing")
		assertThat(metrics.sessionCount.reasons).containsExactly("session_identity_missing")
		assertThat(metrics.totalTokens.value).isEqualTo(100)
		assertThat(metrics.tokensPerUser.value).isEqualTo(50.0)
		assertThat(metrics.tokensPerSession.value).isNull()
	}

	@Test
	fun `부분 기간의 모델과 산점도를 완전으로 표시하지 않는다`() {
		val model = TeamScreenValues.model(frame(false), "model", sample)
		assertThat(model.status).isEqualTo("partial")
		assertThat(model.reasons).contains("collection_gap")
		assertThat(TeamsService.section(listOf(model)) { TeamModelMix.of(listOf(model)) }.availability).isEqualTo("partial")
		assertThat(TeamScreenValues.scatter(model, 1).status).isEqualTo("partial")
		val missing = TeamScreenValues.model(frame(), "model", sample.copy(apiTotalMissingRows = 1))
		assertThat(missing.status).isEqualTo("partial")
		assertThat(missing.equivalentCostUsd).isEqualTo("12.000000")
		assertThat(TeamScreenValues.scatter(missing, 1).status).isEqualTo("unavailable")
		assertThat(TeamScreenValues.scatter(missing, 1).reasons).containsExactly("token_data_incomplete")
	}

	@Test
	fun `추이의 확인된 0과 관측 공백 및 선행 누적 공백을 구별한다`() {
		val zero = TeamScreenValues.point(frame(), day, UsageTotals.EMPTY, 0, emptyList())
		assertThat(zero.status).isEqualTo("complete")
		assertThat(zero.equivalentCostUsd).isEqualTo("0.000000")
		assertThat(zero.totalTokens).isZero()
		val unknown = TeamScreenValues.point(frame(false), day, UsageTotals.EMPTY, null, listOf("cumulative_period_incomplete"))
		assertThat(unknown.status).isEqualTo("unavailable")
		assertThat(unknown.reasons).contains("collection_gap", "cumulative_period_incomplete")
		val cumulativeGap = TeamScreenValues.point(frame(), day, sample, null, listOf("cumulative_period_incomplete", "collection_gap"))
		assertThat(cumulativeGap.status).isEqualTo("partial")
		assertThat(cumulativeGap.equivalentCostUsd).isEqualTo("12.000000")
		assertThat(cumulativeGap.cumulativeSessionCount).isNull()
	}

	@Test
	fun `캐시 적중률은 의미가 혼재하거나 분모가 0이면 확정하지 않는다`() {
		val mixed = TeamScreenValues.cache(frame(), sample.copy(semanticsProfiles = 2))
		assertThat(mixed.value).isNull()
		assertThat(mixed.reasons).contains("token_semantics_incompatible")
		val zero = TeamScreenValues.cache(frame(), sample.copy(inputUncached = UsageTotals.Component(0, 0), cacheRead = UsageTotals.Component(0, 0)))
		assertThat(zero.value).isNull()
		assertThat(zero.reasons).contains("zero_denominator")
		assertThat(TeamScreenValues.cache(frame(false), sample).status).isEqualTo("partial")
	}
}
