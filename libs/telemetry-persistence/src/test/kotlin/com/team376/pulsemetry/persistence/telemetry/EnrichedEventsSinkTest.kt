package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.enricher.Enriched
import com.team376.pulsemetry.telemetry.enricher.support.GoldenEvents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * 실제 ClickHouse 위에서 스키마 적용과 적재를 본다.
 *
 * **대역으로 대체할 수 없다.** 이 티켓의 핵심 계약이 `ReplacingMergeTree` 의 `FINAL` dedup 이고,
 * 그것은 엔진의 동작이라 흉내 내면 아무것도 검증하지 못한다.
 *
 * ## 컨테이너 구성이 배포와 같다
 *
 * 이미지 태그를 고정하고 `CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1` 을 켠 것은 infra 의 배포
 * 구성 그대로다(infra ADR-0019). 이 환경변수가 없으면 이미지 entrypoint 가 `default` 유저의
 * 네트워크 접근을 막아 자격 증명을 보내지 않는 이 클라이언트가 전부 403 으로 죽는다 —
 * 실제로 배포에서 그렇게 깨진 적이 있다.
 *
 * 전용 Testcontainers 모듈을 쓰지 않는 것은 그것이 `JdbcDatabaseContainer` 라 기동 대기에
 * JDBC 드라이버를 요구하기 때문이다. 이 모듈은 HTTP 로만 말한다.
 */
@Testcontainers
class EnrichedEventsSinkTest {

	private lateinit var client: ClickHouseHttpClient
	private lateinit var sink: EnrichedEventsSink

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient("http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}")
		sink = EnrichedEventsSink(client)
		ClickHouseSchemaMigrator(client).apply()
		client.execute("TRUNCATE TABLE IF EXISTS ${EnrichedEventsSink.TABLE}")
	}

	private fun goldenItems(limit: Int = 5): List<Enriched> =
		GoldenEvents.events(GoldenEvents.CLAUDE_CODE_LOGS).take(limit).map { event ->
			Enriched(event, teamIdsAsOf = listOf(TEAM_A, TEAM_B)).also {
				it.annotations["ai_analysis"] = emptyMap<String, Any?>()
				it.annotations["github"] = emptyMap<String, Any?>()
				it.annotations["jira"] = emptyMap<String, Any?>()
				it.annotations["org"] = mapOf("team_ids" to listOf(TEAM_A, TEAM_B))
			}
		}

	private fun query(sql: String): String = client.execute(sql).trim()

	// ── 스키마 적용 ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("스키마를 두 번 적용해도 안전하다 — 매 기동마다 전량이 다시 돈다")
	fun applyingTheSchemaTwiceIsSafe() {
		ClickHouseSchemaMigrator(client).apply()
		ClickHouseSchemaMigrator(client).apply()

		assertThat(query("EXISTS TABLE ${EnrichedEventsSink.TABLE}")).isEqualTo("1")
	}

	@Test
	@DisplayName("DDL 이 아홉 컬럼을 화이트리스트와 같은 순서로 만든다")
	fun theTableHasExactlyTheWhitelistColumns() {
		val columns = query("SELECT name FROM system.columns WHERE table = '${EnrichedEventsSink.TABLE}'")
			.lines()

		assertThat(columns).containsExactlyElementsOf(EnrichedEventRow.COLUMNS)
	}

	@Test
	@DisplayName("엔진과 정렬 키가 멱등의 근거다")
	fun theEngineIsReplacingMergeTreeOrderedByEventId() {
		val engine = query("SELECT engine FROM system.tables WHERE name = '${EnrichedEventsSink.TABLE}'")
		val sortingKey = query("SELECT sorting_key FROM system.tables WHERE name = '${EnrichedEventsSink.TABLE}'")

		assertThat(engine).isEqualTo("ReplacingMergeTree")
		assertThat(sortingKey).isEqualTo("event_id")
	}

	// ── 적재 ────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("golden 이벤트를 적재하고 컬럼 값이 그대로 들어간다")
	fun goldenRowsLandWithTheirColumnValues() {
		val items = goldenItems()

		assertThat(sink.insert(items)).isEqualTo(items.size)

		val first = items.first()
		val stored = query(
			"SELECT tenant_id, installation_id, signal, product, toString(ts), " +
				"arrayStringConcat(team_ids_as_of, ',') " +
				"FROM ${EnrichedEventsSink.TABLE} FINAL WHERE event_id = '${first.eventId}' FORMAT TSV",
		).split('\t')

		assertThat(stored[0]).isEqualTo(first.tenantId)
		assertThat(stored[1]).isEqualTo(first.event.envelope.identity.installationId)
		assertThat(stored[2]).isEqualTo("log")
		assertThat(stored[3]).isEqualTo("claude_code")
		assertThat(stored[5]).isEqualTo("$TEAM_A,$TEAM_B")
	}

	@Test
	@DisplayName("raw_json 이 golden 의 event 와 같은 값으로 돌아온다")
	fun rawJsonSurvivesTheRoundTrip() {
		val items = goldenItems(1)
		sink.insert(items)

		val stored = query(
			"SELECT raw_json FROM ${EnrichedEventsSink.TABLE} FINAL " +
				"WHERE event_id = '${items.first().eventId}' FORMAT TSVRaw",
		)

		assertThat(GoldenEvents.canonicalize(GoldenEvents.parse(stored)))
			.isEqualTo(GoldenEvents.canonicalize(GoldenEvents.trees(GoldenEvents.CLAUDE_CODE_LOGS).first()))
	}

	@Test
	@DisplayName("enrichment_json 이 현행 파이프라인과 같은 키 셋으로 저장된다")
	fun enrichmentJsonKeepsTheStubKeys() {
		val items = goldenItems(1)
		sink.insert(items)

		val stored = query(
			"SELECT enrichment_json FROM ${EnrichedEventsSink.TABLE} FINAL " +
				"WHERE event_id = '${items.first().eventId}' FORMAT TSVRaw",
		)

		assertThat(stored)
			.isEqualTo("""{"ai_analysis":{},"github":{},"jira":{},"org":{"team_ids":["$TEAM_A","$TEAM_B"]}}""")
	}

	@Test
	@DisplayName("빈 배치는 요청을 보내지 않는다")
	fun anEmptyBatchIsANoOp() {
		assertThat(sink.insert(emptyList())).isZero()
		assertThat(sink.countDistinct()).isZero()
	}

	// ── 재적재 멱등 ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("같은 배치를 다시 적재해도 중복 제거 후 행 수가 그대로다")
	fun reinsertingTheSameBatchIsIdempotent() {
		val items = goldenItems()
		val distinctKeys = items.map { it.eventId }.distinct().size

		sink.insert(items)
		assertThat(sink.countDistinct()).isEqualTo(distinctKeys)

		sink.insert(items)
		sink.insert(items)

		// FINAL 이 필요하다 — 병합 전에는 물리적으로 세 벌이 남아 있다.
		assertThat(sink.countDistinct()).isEqualTo(distinctKeys)
		assertThat(query("SELECT count() FROM ${EnrichedEventsSink.TABLE}").toInt())
			.isGreaterThan(distinctKeys)
	}

	@Test
	@DisplayName("event_id 가 같으면 다른 배치의 행도 하나로 합쳐진다")
	fun rowsSharingAnEventIdCollapse() {
		val item = goldenItems(1).first()
		val other = Enriched(item.event, teamIdsAsOf = listOf("team-c"))

		sink.insert(listOf(item))
		sink.insert(listOf(other))

		assertThat(sink.countDistinct()).isEqualTo(1)
	}

	companion object {
		private const val HTTP_PORT: Int = 8123
		private const val TEAM_A: String = "11111111-1111-1111-1111-111111111111"
		private const val TEAM_B: String = "22222222-2222-2222-2222-222222222222"

		/** 태그를 infra 의 배포 이미지에 맞춘다 — entrypoint 동작이 태그마다 달라진다. */
		@Container
		@JvmStatic
		val clickhouse: GenericContainer<*> =
			GenericContainer("clickhouse/clickhouse-server:24.8-alpine")
				.withExposedPorts(HTTP_PORT)
				// 이게 없으면 entrypoint 가 default 유저를 루프백 전용으로 잠근다 (infra ADR-0019).
				.withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
				.waitingFor(Wait.forHttp("/ping").forPort(HTTP_PORT).forStatusCode(200))
	}
}
