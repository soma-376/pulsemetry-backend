package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.DatePeriod
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.dashboard.store.StoreQueryRejectedException
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * snapshot build (ADR 0023 §1·§2). 수용 사례의 입력과 기대는 ADR 0023·ADR 0020 §7 의 문장에서 쓴다.
 * 시각은 KST 로 생각하고 UTC 로 적는다 — 24일 23:59 KST = 24일 14:59Z.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotBuilderTest {

	private val mapper = JsonMapper.builder().build()

	@BeforeAll
	fun schemas() {
		DashboardTestStores.ensureSchemas()
	}

	private fun builder(
		resolution: ModelResolution = ModelResolution.NONE,
		sourceDatabase: String = "default",
		source: JdbcClient = DashboardTestStores.writer,
	): SnapshotBuilder {
		val cache = DashboardTestStores.writer
		val clickHouse = ClickHouseCacheClient(
			ClickHouseConnection(DashboardTestStores.clickHouseUrl(), DashboardTestStores.CACHE_DATABASE, "default", "", Duration.ofSeconds(30), mapper),
			Duration.ofSeconds(30),
		)
		return SnapshotBuilder(
			boundaries = RetentionBoundaryReader(source),
			cache = cache,
			references = SnapshotReferenceCopier(source, cache),
			clickHouse = clickHouse,
			sql = SnapshotCopySql(sourceDatabase, resolution),
			resolution = resolution,
			buildTimeout = Duration.ofSeconds(60),
			purgeGrace = Duration.ofSeconds(60),
			clock = Clock.systemUTC(),
		)
	}

	private fun period(start: String, end: String, mode: CompareMode = CompareMode.NONE) =
		ComparedPeriod(DatePeriod(LocalDate.parse(start), LocalDate.parse(end), QueryReader.SEOUL), mode)

	private fun build(tenant: UUID, period: ComparedPeriod, builder: SnapshotBuilder = builder()) =
		builder.build(SnapshotBuilder.Request(tenant, UUID.randomUUID(), period))

	private fun rows(sql: String): List<List<String>> =
		DashboardTestStores.clickHouseAdmin("$sql FORMAT TSVRaw").lineSequence().filter { it.isNotEmpty() }.map { it.split('\t') }.toList()

	private fun usage(built: SnapshotBuilder.Built, columns: String): List<List<String>> =
		rows("SELECT $columns FROM dashboard_cache.snapshot_usage WHERE build_id = '${built.buildId}' ORDER BY source_time, observation_id")

	private fun days(built: SnapshotBuilder.Built): List<String> =
		rows(
			"SELECT toString(observed_date) FROM dashboard_cache.snapshot_observed_days WHERE build_id = '${built.buildId}' " +
				"GROUP BY observed_date ORDER BY observed_date",
		).map { it[0] }

	private fun kst(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()

	@Test
	@DisplayName("사례 11 — 24일 23:59 KST 사용량이 25일에 수신돼도 24일의 관측·사용량이다")
	fun sourceDateNotReceiptDate() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(tenant, Event("late", kst("2026-09-24T23:59:00"), receivedTime = kst("2026-09-25T00:01:00")))

		val built = build(tenant, period("2026-09-24", "2026-09-24"))

		assertThat(built.usageRows).isEqualTo(1)
		assertThat(usage(built, "in_current")).containsExactly(listOf("true"))
		assertThat(days(built)).containsExactly("2026-09-24")
	}

	@Test
	@DisplayName("사례 12 — 24일·26일에만 관측이 있으면 25일은 관측 일자가 아니다")
	fun gapsAreNotFilled() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(tenant, Event("d24", kst("2026-09-24T10:00:00")), Event("d26", kst("2026-09-26T10:00:00")))

		assertThat(days(build(tenant, period("2026-09-24", "2026-09-26")))).containsExactly("2026-09-24", "2026-09-26")
	}

	@Test
	@DisplayName("사례 31 — 메트릭·generic 관측만 있는 날은 관측 일자이고 사용량 행은 없다")
	fun nonUsageObservationsOnlyMarkDays() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(
			tenant,
			Event("usage", kst("2026-09-24T10:00:00")),
			Event("generic", kst("2026-09-25T10:00:00"), usage = false, mappingStatus = "unmapped", tokensOutput = 999),
		)
		SourceFixtures.insertMetricPoint(tenant, "metric", kst("2026-09-26T10:00:00"))

		val built = build(tenant, period("2026-09-24", "2026-09-26"))

		assertThat(days(built)).containsExactly("2026-09-24", "2026-09-25", "2026-09-26")
		assertThat(usage(built, "tokens_output")).containsExactly(listOf("10"))
		val origins = rows(
			"SELECT toString(observed_date), origin, sum(observations) FROM dashboard_cache.snapshot_observed_days " +
				"WHERE build_id = '${built.buildId}' GROUP BY observed_date, origin ORDER BY observed_date",
		)
		assertThat(origins).containsExactly(
			listOf("2026-09-24", "telemetry_events", "1"),
			listOf("2026-09-25", "telemetry_events", "1"),
			listOf("2026-09-26", "telemetry_metric_points", "1"),
		)
	}

	@Test
	@DisplayName("사례 16 — build 뒤 원본이 교체돼도 snapshot 의 행과 값은 그대로다")
	fun snapshotIsImmutableAfterReplacement() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(tenant, Event("a", kst("2026-09-24T10:00:00"), tokensOutput = 10))
		val built = build(tenant, period("2026-09-24", "2026-09-24"))

		SourceFixtures.insertEvents(
			tenant,
			Event("a", kst("2026-09-24T10:00:00"), rowVersion = 2, tokensOutput = 999),
			Event("b", kst("2026-09-24T11:00:00")),
		)

		assertThat(usage(built, "tokens_output")).containsExactly(listOf("10"))
		// 새 build 는 새 원본을 본다.
		assertThat(usage(build(tenant, period("2026-09-24", "2026-09-24")), "tokens_output")).containsExactly(listOf("999"), listOf("10"))
	}

	@Test
	@DisplayName("최신 행이 excluded 면 그 아래의 옛 active 행을 복사하지 않는다 — 조건은 FINAL 뒤다")
	fun latestExcludedHidesOlderActive() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(
			tenant,
			Event("x", kst("2026-09-24T10:00:00")),
			Event("x", kst("2026-09-24T10:00:00"), rowVersion = 2, recordStatus = "excluded"),
		)

		val built = build(tenant, period("2026-09-24", "2026-09-24"))

		assertThat(built.usageRows).isZero()
		assertThat(days(built)).isEmpty()
	}

	@Test
	@DisplayName("사례 18 — build 뒤 팀 이름·로스터가 바뀌어도 snapshot 의 참조는 그대로다")
	fun referencesAreFixed() {
		val tenant = DashboardTestStores.insertTenant()
		val team = SourceFixtures.insertTeam(tenant, "플랫폼")
		val archived = SourceFixtures.insertTeam(tenant, "옛 팀", archived = true)
		val member = SourceFixtures.insertMember(tenant, "dev@example.test", role = "owner", displayName = "개발자")
		SourceFixtures.insertMembership(team, member, joinedAt = Instant.parse("2026-01-01T00:00:00Z"))
		// 같은 팀의 소속 행이 겹쳐도 현재 팀은 하나다.
		SourceFixtures.insertMembership(team, member, joinedAt = Instant.parse("2026-02-01T00:00:00Z"))
		// 이미 끝난 소속은 현재 팀이 아니다.
		SourceFixtures.insertMembership(archived, member, joinedAt = Instant.parse("2025-01-01T00:00:00Z"), leftAt = Instant.parse("2025-06-01T00:00:00Z"))
		val idle = SourceFixtures.insertMember(tenant, "idle@example.test", status = "invited")

		val built = build(tenant, period("2026-09-24", "2026-09-24"))

		DashboardTestStores.writer.sql("UPDATE enrollment.teams SET name = '바뀐 이름' WHERE id = :id").param("id", team).update()
		SourceFixtures.insertMember(tenant, "late@example.test")

		val teams = DashboardTestStores.writer.sql(
			"SELECT team_id, name, archived FROM dashboard_cache.snapshot_teams WHERE snapshot_id = :s ORDER BY name",
		).param("s", built.snapshotId).query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getBoolean(3)) }.list()
		assertThat(teams).containsExactlyInAnyOrder(Triple(team, "플랫폼", false), Triple(archived, "옛 팀", true))

		val members = DashboardTestStores.writer.sql(
			"SELECT member_id, account, display_name, role::text, status::text, current_team_ids FROM dashboard_cache.snapshot_members " +
				"WHERE snapshot_id = :s ORDER BY account",
		).param("s", built.snapshotId).query { rs, _ ->
			listOf(
				rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				(rs.getArray(6).array as Array<*>).toList(),
			)
		}.list()
		assertThat(members).containsExactly(
			listOf(member, "dev@example.test", "개발자", "owner", "active", listOf(team)),
			listOf(idle, "idle@example.test", null, "member", "invited", emptyList<UUID>()),
		)
	}

	@Test
	@DisplayName("AC1·AC2 — 비교 기간에만 쓴 팀, 겹친 기간의 관측은 한 번, 여러 팀·날짜의 세션, null 성분이 그대로 복사된다")
	fun comparisonScopeAndNullsSurviveCopy() {
		val tenant = UUID.randomUUID()
		val teamA = UUID.randomUUID()
		val teamB = UUID.randomUUID()
		SourceFixtures.insertEvents(
			tenant,
			// 28일 선택의 prev_week 는 08-25..09-21 — 09-10 은 두 기간 모두에 속한다.
			Event("both", kst("2026-09-10T10:00:00"), teamId = teamA, sessionId = "s-shared"),
			Event("previous-only", kst("2026-08-26T10:00:00"), teamId = teamB, sessionId = "s-shared"),
			Event("current-only", kst("2026-09-27T10:00:00"), teamId = teamA, tokensCacheCreate = null, tokensTotalDerived = null),
			Event("outside", kst("2026-08-20T10:00:00")),
		)

		val built = build(tenant, period("2026-09-01", "2026-09-28", CompareMode.PREV_WEEK))

		assertThat(usage(built, "team_id_as_of, session_id, in_current, in_previous, ifNull(toString(tokens_cache_create), 'NULL'), ifNull(toString(tokens_total_derived), 'NULL')"))
			.containsExactly(
				listOf(teamB.toString(), "s-shared", "false", "true", "0", "110"),
				listOf(teamA.toString(), "s-shared", "true", "true", "0", "110"),
				listOf(teamA.toString(), "session-1", "true", "false", "NULL", "NULL"),
			)
		assertThat(built.usageRows).isEqualTo(3)
	}

	@Test
	@DisplayName("compare=none 이면 현재 기간 밖의 관측을 복사하지 않는다")
	fun compareNoneCopiesOnlyCurrent() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(tenant, Event("now", kst("2026-09-24T10:00:00")), Event("week-before", kst("2026-09-17T10:00:00")))

		val built = build(tenant, period("2026-09-24", "2026-09-24", CompareMode.NONE))

		assertThat(usage(built, "in_current, in_previous")).containsExactly(listOf("true", "false"))
		assertThat(days(built)).containsExactly("2026-09-24")
	}

	@Test
	@DisplayName("다른 tenant 의 관측은 복사하지 않는다")
	fun otherTenantsAreNotCopied() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(UUID.randomUUID(), Event("theirs", kst("2026-09-24T10:00:00")))

		assertThat(build(tenant, period("2026-09-24", "2026-09-24")).usageRows).isZero()
	}

	@Test
	@DisplayName("삭제 경계 앞의 관측은 복사하지 않고 manifest 가 경계·epoch 를 기록한다")
	fun deletionBoundaryIsApplied() {
		val tenant = UUID.randomUUID()
		SourceFixtures.setBoundary(tenant, kst("2026-09-10T00:00:00"), policyEpoch = 3)
		SourceFixtures.insertEvents(tenant, Event("before", kst("2026-09-09T23:59:00")), Event("after", kst("2026-09-10T00:00:00")))

		val built = build(tenant, period("2026-09-01", "2026-09-30"))

		assertThat(built.usageRows).isEqualTo(1)
		assertThat(days(built)).containsExactly("2026-09-10")
		assertThat(built.policyEpoch).isEqualTo(3)
		val (deletedBefore, epoch) = DashboardTestStores.writer.sql(
			"SELECT deleted_before, policy_epoch FROM dashboard_cache.snapshots WHERE snapshot_id = :s",
		).param("s", built.snapshotId).query { rs, _ -> rs.getObject(1, java.time.OffsetDateTime::class.java).toInstant() to rs.getLong(2) }.single()
		assertThat(deletedBefore).isEqualTo(kst("2026-09-10T00:00:00"))
		assertThat(epoch).isEqualTo(3)
	}

	@Test
	@DisplayName("삭제 경계를 읽지 못하면 manifest 도 만들지 않고 실패한다 — 과거 행을 허용하는 쪽으로 실패하지 않는다")
	fun boundaryReadFailureFailsFirst() {
		val tenant = UUID.randomUUID()
		val broken = JdbcClient.create(DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "x", "x"))

		assertThatThrownBy { build(tenant, period("2026-09-24", "2026-09-24"), builder(source = broken)) }
			.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
		assertThat(
			DashboardTestStores.writer.sql("SELECT count(*) FROM dashboard_cache.snapshots WHERE tenant_id = :t").param("t", tenant)
				.query(Long::class.java).single(),
		).isZero()
	}

	@Test
	@DisplayName("복사가 실패하면 manifest 는 failed 이고 원래 예외가 나온다")
	fun copyFailureMarksManifestFailed() {
		val tenant = UUID.randomUUID()

		assertThatThrownBy { build(tenant, period("2026-09-24", "2026-09-24"), builder(sourceDatabase = "no_such_database")) }
			.isInstanceOf(StoreQueryRejectedException::class.java)
		val (status, reason) = DashboardTestStores.writer.sql(
			"SELECT status::text, failure_reason FROM dashboard_cache.snapshots WHERE tenant_id = :t",
		).param("t", tenant).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
		assertThat(status).isEqualTo("failed")
		assertThat(reason).isEqualTo("rejected")
	}

	@Test
	@DisplayName("manifest 는 building 이고 분석 범위·기준 시각·해석 판·제한 시각을 적는다")
	fun manifestRecordsTheScope() {
		val tenant = UUID.randomUUID()
		val before = Instant.now()

		val built = build(tenant, period("2026-09-07", "2026-09-13", CompareMode.PREV_PERIOD))

		val row = DashboardTestStores.writer.sql(
			"SELECT status::text, compare_mode::text, current_start_date::text, current_end_date::text, previous_start_date::text, " +
				"previous_end_date::text, time_zone, query_contract, access_scope, model_resolution_version, " +
				"extract(epoch FROM build_deadline - created_at)::int, as_of, snapshot_id " +
				"FROM dashboard_cache.snapshots WHERE build_id = :b",
		).param("b", built.buildId).query { rs, _ -> (1..11).map { rs.getString(it) } + rs.getObject(12, java.time.OffsetDateTime::class.java).toInstant().toString() + rs.getString(13) }.single()

		assertThat(row.take(11)).containsExactly(
			"building", "prev_period", "2026-09-07", "2026-09-13", "2026-08-31", "2026-09-06",
			"Asia/Seoul", "dashboard-v1", "organization", "model-id-v1", "60",
		)
		assertThat(Instant.parse(row[11])).isBetween(before.minusSeconds(1), Instant.now())
		assertThat(SnapshotIds.isWellFormed(row[12])).isTrue()
	}

	@Test
	@DisplayName("구성원별 마지막 사용은 asOf 까지·팀과 무관한 사용량 행의 최댓값이다 — 기간 밖도 본다")
	fun memberActivityIsAsOfAndTeamAgnostic() {
		val tenant = UUID.randomUUID()
		val member = UUID.randomUUID()
		SourceFixtures.insertEvents(
			tenant,
			Event("old", kst("2026-08-01T10:00:00"), memberId = member, teamId = UUID.randomUUID()),
			Event("later", kst("2026-08-15T10:00:00"), memberId = member, teamId = UUID.randomUUID()),
			Event("tool", kst("2026-08-20T10:00:00"), usage = false, memberId = member),
		)

		val built = build(tenant, period("2026-09-24", "2026-09-24"))

		assertThat(built.usageRows).isZero()
		val activity = rows("SELECT member_id, last_used_at FROM dashboard_cache.snapshot_member_activity WHERE build_id = '${built.buildId}'")
		assertThat(activity).hasSize(1)
		assertThat(activity[0][0]).isEqualTo(member.toString())
		assertThat(Instant.parse(activity[0][1].replace(' ', 'T') + "Z")).isEqualTo(kst("2026-08-15T10:00:00"))
	}

	@Test
	@DisplayName("가격 판 집합을 manifest 에 고정한다 — 둘이면 혼재다")
	fun pricingVersionsAreFixed() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(
			tenant,
			Event("p3", kst("2026-09-24T10:00:00"), costEstimatedUsd = BigDecimal("1.5"), pricingVersion = "v3"),
			Event("p4", kst("2026-09-24T11:00:00"), costEstimatedUsd = BigDecimal("2.5"), pricingVersion = "v4"),
			Event("unpriced", kst("2026-09-24T12:00:00")),
		)

		val built = build(tenant, period("2026-09-24", "2026-09-24"))

		assertThat(built.pricingVersions).containsExactly("v3", "v4")
		val stored = DashboardTestStores.writer.sql("SELECT pricing_versions FROM dashboard_cache.snapshots WHERE build_id = :b")
			.param("b", built.buildId).query { rs, _ -> (rs.getArray(1).array as Array<*>).toList() }.single()
		assertThat(stored).containsExactly("v3", "v4")
		assertThat(usage(built, "ifNull(toString(cost_estimated_usd), 'NULL')")).containsExactly(listOf("1.5"), listOf("2.5"), listOf("NULL"))
	}

	@Test
	@DisplayName("공급자 근거가 없으면 unknown/<product>/<원래 모델> 로 보존하고 원래 모델명은 바꾸지 않는다")
	fun unresolvedProviderKeepsRawModel() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(
			tenant,
			Event("codex", kst("2026-09-24T10:00:00"), product = "codex", serviceName = "codex-app-server", model = "gpt-5.1/codex~1"),
			Event("no-model", kst("2026-09-24T11:00:00"), product = "codex", serviceName = "codex-app-server", model = null),
		)

		val built = build(tenant, period("2026-09-24", "2026-09-24"))

		assertThat(usage(built, "ifNull(provider, 'NULL'), model_id, ifNull(model, 'NULL'), model_resolution_version")).containsExactly(
			listOf("NULL", "unknown/codex/gpt-5.1%2Fcodex%7E1", "gpt-5.1/codex~1", "model-id-v1"),
			listOf("NULL", "unknown/codex/~", "NULL", "model-id-v1"),
		)
		// ClickHouse 식과 Kotlin 규칙은 같은 ID 를 낸다.
		assertThat(ModelResolution.NONE.resolve("codex", "codex-app-server", "2.1.282", "gpt-5.1/codex~1").modelId)
			.isEqualTo("unknown/codex/gpt-5.1%2Fcodex%7E1")
	}

	@Test
	@DisplayName("사례 23 — 같은 공급자의 별칭 A·B 는 canonical C 로 먼저 모여 $120 이 $100 인 D 보다 앞선다")
	fun aliasesMergeBeforeRanking() {
		val tenant = UUID.randomUUID()
		val resolution = ModelResolution(
			version = "test-resolution-1",
			providerScopes = listOf(ModelResolution.ProviderScope("claude_code", "claude-code", setOf("2.1.282"), "anthropic")),
			aliases = mapOf("anthropic" to mapOf("model-a" to "model-c", "model-b" to "model-c")),
		)
		SourceFixtures.insertEvents(
			tenant,
			Event("a", kst("2026-09-24T10:00:00"), model = "model-a", costEstimatedUsd = BigDecimal("60")),
			Event("b", kst("2026-09-24T11:00:00"), model = "model-b", costEstimatedUsd = BigDecimal("60")),
			Event("d", kst("2026-09-24T12:00:00"), model = "model-d", costEstimatedUsd = BigDecimal("100")),
			// 적용 범위 밖의 판은 공급자 미확인이다.
			Event("other-version", kst("2026-09-24T13:00:00"), productVersion = "2.1.280", model = "model-a", costEstimatedUsd = BigDecimal("1")),
		)

		val built = build(tenant, period("2026-09-24", "2026-09-24"), builder(resolution = resolution))

		val ranking = rows(
			"SELECT model_id, sum(cost_estimated_usd) AS cost FROM dashboard_cache.snapshot_usage WHERE build_id = '${built.buildId}' " +
				"GROUP BY model_id ORDER BY cost DESC, model_id",
		)
		assertThat(ranking.map { it[0] to BigDecimal(it[1]).stripTrailingZeros().toPlainString() }).containsExactly(
			"anthropic/model-c" to "120",
			"anthropic/model-d" to "100",
			"unknown/claude_code/model-a" to "1",
		)
		assertThat(usage(built, "model").map { it[0] }).containsExactly("model-a", "model-b", "model-d", "model-a")
		assertThat(resolution.resolve("claude_code", "claude-code", "2.1.282", "model-b"))
			.isEqualTo(ModelResolution.Resolved("anthropic", "anthropic/model-c"))
	}
}
