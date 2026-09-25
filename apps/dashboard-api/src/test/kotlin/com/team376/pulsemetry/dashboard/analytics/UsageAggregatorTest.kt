package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Axis
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Side
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.DatePeriod
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SnapshotAssembly
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 공통 계산기의 null 규칙. 기대값은 조회 규칙(ADR 0020 §7)과 수용 사례의 문장에서 쓴다.
 * 토큰 기본값은 행마다 input_uncached 100 · output 10 · cache_read 0 · cache_write 0 · total 110 (SourceFixtures.Event).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsageAggregatorTest {

	private val assembly by lazy { SnapshotAssembly() }
	private val aggregator by lazy { UsageAggregator(assembly.clickHouse) }

	@BeforeAll
	fun schemas() {
		DashboardTestStores.ensureSchemas()
	}

	private val day = ComparedPeriod(DatePeriod(LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-24"), QueryReader.SEOUL), CompareMode.NONE)

	private fun kst(time: String) = LocalDateTime.parse("2026-09-24T$time").atZone(QueryReader.SEOUL).toInstant()

	private fun snapshot(vararg events: Event): SnapshotManifestStore.Manifest {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(tenant, *events)
		return assembly.service.create(tenant, UUID.randomUUID(), day)
	}

	private fun organization(snapshot: SnapshotManifestStore.Manifest) = aggregator.totals(snapshot, Side.CURRENT, Axis.ORGANIZATION).getValue(emptyList())

	@Test
	@DisplayName("행이 없으면 모든 값이 없다 — 0 이 아니다")
	fun emptyGroupHasNoValues() {
		val totals = organization(snapshot())

		assertThat(totals.hasUsage).isFalse()
		assertThat(listOf(totals.activeUsers(), totals.sessionCount(), totals.tokens(totals.output), totals.apiTotal())).containsOnlyNulls()
		assertThat(totals.equivalentCost(pricingMixed = false)).isNull()
	}

	@Test
	@DisplayName("사례 5 — 단가 없는 행이 섞이면 금액은 조직·모델·팀·일자 모두 없고, 토큰·사용자는 그대로다")
	fun unpricedRowMakesCostNull() {
		val member = UUID.randomUUID()
		val team = UUID.randomUUID()
		val snapshot = snapshot(
			Event("priced-1", kst("10:00:00"), memberId = member, teamId = team, costEstimatedUsd = BigDecimal("1.25"), pricingVersion = "v1"),
			Event("priced-2", kst("11:00:00"), memberId = member, teamId = team, costEstimatedUsd = BigDecimal("2.75"), pricingVersion = "v1"),
			Event("unpriced", kst("12:00:00"), memberId = member, teamId = team),
		)

		val organization = organization(snapshot)
		assertThat(organization.equivalentCost(pricingMixed = false)).isNull()
		assertThat(organization.tokens(organization.output)).isEqualTo(30)
		assertThat(organization.apiTotal()).isEqualTo(330)
		assertThat(organization.activeUsers()).isEqualTo(1)
		for (axis in listOf(Axis.MODEL, Axis.TEAM, Axis.DAY)) {
			assertThat(aggregator.totals(snapshot, Side.CURRENT, axis).values.single().equivalentCost(pricingMixed = false)).describedAs(axis.name).isNull()
		}
	}

	@Test
	@DisplayName("단가가 전부 있으면 금액은 서버 정밀도의 합이다")
	fun pricedRowsSum() {
		val snapshot = snapshot(
			Event("a", kst("10:00:00"), costEstimatedUsd = BigDecimal("0.000000000001"), pricingVersion = "v1"),
			Event("b", kst("11:00:00"), costEstimatedUsd = BigDecimal("2.5"), pricingVersion = "v1"),
		)

		assertThat(organization(snapshot).equivalentCost(pricingMixed = false)).isEqualByComparingTo("2.500000000001")
		assertThat(Money.format(organization(snapshot).equivalentCost(pricingMixed = false)!!)).isEqualTo("2.500000000001")
	}

	@Test
	@DisplayName("사례 7 — snapshot 의 가격 판이 둘 이상이면 금액은 없다")
	fun mixedPricingMakesCostNull() {
		val snapshot = snapshot(
			Event("v3", kst("10:00:00"), costEstimatedUsd = BigDecimal("1"), pricingVersion = "v3"),
			Event("v4", kst("11:00:00"), costEstimatedUsd = BigDecimal("1"), pricingVersion = "v4"),
		)

		assertThat(snapshot.pricingVersions).containsExactly("v3", "v4")
		assertThat(organization(snapshot).equivalentCost(pricingMixed = snapshot.pricingVersions.size > 1)).isNull()
		assertThat(organization(snapshot).tokens(organization(snapshot).output)).isEqualTo(20)
	}

	@Test
	@DisplayName("사례 6 — 프로파일 unknown 인 Codex 행은 파생 성분이 없어 inputUncached·cacheWrite·total 이 없다")
	fun unverifiedCodexHasNoDerivedTokens() {
		val snapshot = snapshot(
			Event(
				"codex", kst("10:00:00"), product = "codex", serviceName = "codex-app-server", productVersion = "0.155.0-alpha.9.2",
				semanticsProfile = null, tokensCacheCreate = null, tokensInputUncached = null, tokensTotalDerived = null,
			),
		)
		val totals = organization(snapshot)

		assertThat(totals.tokens(totals.inputUncached)).isNull()
		assertThat(totals.tokens(totals.cacheWrite)).isNull()
		assertThat(totals.apiTotal()).isNull()
		// 의미가 검증되지 않은 성분은 보고됐어도 합으로 내지 않는다(ADR 0020 §7).
		assertThat(totals.tokens(totals.output)).isNull()
		assertThat(totals.activeUsers()).isNull()
	}

	@Test
	@DisplayName("서로 다른 의미 프로파일의 성분은 한 숫자로 더하지 않는다 — 검증된 행과 미검증 행이 섞여도 같다")
	fun mixedSemanticsAreNotSummed() {
		val snapshot = snapshot(
			Event("verified", kst("10:00:00")),
			Event("unverified", kst("11:00:00"), productVersion = "2.1.280", semanticsProfile = null),
		)
		val totals = organization(snapshot)

		assertThat(totals.tokens(totals.output)).isNull()
		assertThat(totals.apiTotal()).isNull()
		assertThat(totals.unverifiedRows).isEqualTo(1)
	}

	@Test
	@DisplayName("사례 9 — 세션 없는 행이 하나라도 있으면 sessionCount 는 없고 비용·토큰은 그대로다")
	fun sessionlessRowMakesSessionCountNull() {
		val snapshot = snapshot(
			Event("s", kst("10:00:00"), sessionId = "a", costEstimatedUsd = BigDecimal("1"), pricingVersion = "v1"),
			Event("none", kst("11:00:00"), sessionId = null, costEstimatedUsd = BigDecimal("1"), pricingVersion = "v1"),
		)
		val totals = organization(snapshot)

		assertThat(totals.sessionCount()).isNull()
		assertThat(totals.sessionlessRows).isEqualTo(1)
		assertThat(totals.equivalentCost(pricingMixed = false)).isEqualByComparingTo("2")
		assertThat(totals.tokens(totals.output)).isEqualTo(20)
	}

	@Test
	@DisplayName("사례 15 — 저장된 파생 total 이 있어도 cacheWrite 가 없으면 API total 은 없다")
	fun apiTotalRequiresEveryComponent() {
		val snapshot = snapshot(Event("no-write", kst("10:00:00"), tokensCacheCreate = null, tokensTotalDerived = 110))
		val totals = organization(snapshot)

		assertThat(totals.totalDerived.sum).isEqualTo(110)
		assertThat(totals.tokens(totals.cacheWrite)).isNull()
		assertThat(totals.apiTotal()).isNull()
		assertThat(totals.tokens(totals.output)).isEqualTo(10)
	}

	@Test
	@DisplayName("distinct 는 원래 식별자로 다시 센다 — 두 팀·두 날에 걸친 한 사람·한 세션은 조직에서 하나다")
	fun distinctIsRecountedNotSummed() {
		val member = UUID.randomUUID()
		val snapshot = snapshot(
			Event("t1", kst("10:00:00"), memberId = member, teamId = UUID.randomUUID(), sessionId = "shared"),
			Event("t2", kst("11:00:00"), memberId = member, teamId = UUID.randomUUID(), sessionId = "shared"),
			Event("anon", kst("12:00:00"), memberId = null, sessionId = "other"),
		)

		val teams = aggregator.totals(snapshot, Side.CURRENT, Axis.TEAM)
		assertThat(teams.values.sumOf { it.identifiedUsers }).isEqualTo(2)
		val organization = organization(snapshot)
		assertThat(organization.activeUsers()).isEqualTo(1)
		assertThat(organization.unidentifiedRows).isEqualTo(1)
		assertThat(organization.sessionCount()).isEqualTo(2)
		// 미식별 행은 미배분 팀(null 키)에 들어간다.
		assertThat(teams.keys).contains(listOf(null))
	}

	@Test
	@DisplayName("일자 축은 source_time 의 KST 날짜다")
	fun dayAxisUsesSourceDate() {
		val snapshot = assembly.service.create(
			UUID.randomUUID().also {
				SourceFixtures.insertEvents(it, Event("late", LocalDateTime.parse("2026-09-24T23:59:00").atZone(QueryReader.SEOUL).toInstant()))
			},
			UUID.randomUUID(),
			ComparedPeriod(DatePeriod(LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-25"), QueryReader.SEOUL), CompareMode.NONE),
		)

		assertThat(aggregator.totals(snapshot, Side.CURRENT, Axis.DAY).keys).containsExactly(listOf("2026-09-24"))
	}
}
