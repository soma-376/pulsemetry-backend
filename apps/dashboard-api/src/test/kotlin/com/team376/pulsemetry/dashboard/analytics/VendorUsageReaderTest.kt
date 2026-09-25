package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.snapshot.ModelResolution
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * 벤더 지표의 원천 — 검증된 공급자 범위 안의 사용만 공급자에 귀속한다 (사례 21·22). `product` 로 공급자를 채우지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VendorUsageReaderTest {

	@BeforeAll
	fun schemas() {
		DashboardTestStores.ensureSchemas()
	}

	private val reader = ClickHouseSourceReader(
		ClickHouseConnection(DashboardTestStores.clickHouseUrl(), "default", "default", "", Duration.ofSeconds(10), JsonMapper.builder().build()),
		Duration.ofSeconds(10), 100_000, 16_000_000,
	)

	private fun usage(resolution: ModelResolution, tenant: UUID, asOf: Instant) =
		VendorUsageReader(reader, resolution, RetentionBoundaryReader(DashboardTestStores.writer)).usage(tenant, asOf, QueryReader.SEOUL)

	private fun kst(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()

	private fun codex(name: String, time: String, version: String, member: UUID) = Event(
		name, kst(time), product = "codex", serviceName = "codex-app-server", productVersion = version, memberId = member, semanticsProfile = null,
	)

	@Test
	@DisplayName("사례 21 — 공급자 근거가 없는 Codex 사용은 어떤 공급자에도 귀속하지 않고 미확인으로 센다")
	fun unverifiedCodexIsUnresolved() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(tenant, codex("$tenant-1", "2026-09-20T10:00:00", "0.155.0-alpha.9.2", UUID.randomUUID()))

		val result = usage(ModelResolution.NONE, tenant, kst("2026-09-21T00:00:00"))

		assertThat(result.byProvider).isEmpty()
		assertThat(result.unresolvedRows).isEqualTo(1)
		assertThat(result.unresolvedRows7d).isEqualTo(1)
	}

	@Test
	@DisplayName("사례 22 — 검증된 설정 범위 안의 사용은 그 공급자로, 범위 밖(다른 판)은 새 근거가 없어 미확인이다")
	fun verifiedScopeOnly() {
		val tenant = UUID.randomUUID()
		val alice = UUID.randomUUID()
		val bob = UUID.randomUUID()
		SourceFixtures.insertEvents(
			tenant,
			codex("$tenant-in-1", "2026-08-25T10:00:00", "0.155.0-alpha.9.2", alice),
			codex("$tenant-in-2", "2026-09-19T10:00:00", "0.155.0-alpha.9.2", bob),
			codex("$tenant-out", "2026-09-20T10:00:00", "0.156.0", alice),
		)
		val resolution = ModelResolution(
			"test-openai-scope",
			listOf(ModelResolution.ProviderScope("codex", "codex-app-server", setOf("0.155.0-alpha.9.2"), "openai")),
			emptyMap(),
		)

		val result = usage(resolution, tenant, kst("2026-09-21T00:00:00"))

		val openai = result.byProvider.getValue("openai")
		assertThat(openai.firstSeenAt).isEqualTo(kst("2026-08-25T10:00:00"))
		assertThat(openai.lastSeenAt).isEqualTo(kst("2026-09-19T10:00:00"))
		// 7일 창은 asOf 날짜(9/21)를 끝으로 9/15 부터 — bob 만. 30일 창은 둘 다.
		assertThat(openai.users7d).isEqualTo(1)
		assertThat(openai.users30d).isEqualTo(2)
		assertThat(result.unresolvedRows).isEqualTo(1)
		assertThat(result.byProvider.keys).containsExactly("openai")
	}

	@Test
	@DisplayName("asOf 뒤의 사용과 사용량 대표 행이 아닌 관측은 세지 않는다")
	fun onlyUsageRowsBeforeAsOf() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(
			tenant,
			Event("$tenant-after", kst("2026-09-22T10:00:00")),
			Event("$tenant-tool", kst("2026-09-20T10:00:00"), usage = false),
		)

		val result = usage(ModelResolution.NONE, tenant, kst("2026-09-21T00:00:00"))

		assertThat(result.unresolvedRows).isZero()
		assertThat(result.byProvider).isEmpty()
	}
}
