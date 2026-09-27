package com.team376.pulsemetry.persistence.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * 정규화 2판의 두 분석 테이블 DDL(`V2__telemetry_analysis_tables.sql`)과 교체 저장의 의미를 실제
 * ClickHouse 위에서 고정한다 (ADR 0020 §1·§3·§7).
 *
 * **행은 손으로 쓴다.** 행 인코더와 sink 는 이 테스트의 대상이 아니다 — 여기서 보는 것은 엔진이
 * `row_version` 으로 관측당 한 행을 고르는 동작과, `FINAL` 뒤에 붙는 조건이 구 행을 되살리지
 * 못한다는 조회 규칙이다. 둘 다 엔진의 동작이라 대역으로 대체할 수 없다.
 *
 * 기대값은 ADR 0020 의 규칙에서 나온다. `row_version` 은 `(normalizer_rev << 32) | ingest_seq` 다.
 *
 * 컨테이너 구성은 [TelemetryAnalysisSinksTest] 와 같다(배포 이미지 태그, `default` 유저 네트워크 접근).
 */
@Testcontainers
class TelemetryAnalysisTablesTest {

	private lateinit var client: ClickHouseHttpClient

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient("http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}")
		ClickHouseSchemaMigrator(client).apply()
		client.execute("TRUNCATE TABLE IF EXISTS $EVENTS")
		client.execute("TRUNCATE TABLE IF EXISTS $METRICS")
	}

	// ── 스키마 ──────────────────────────────────────────────────────────────

	@Test
	@DisplayName("스키마를 두 번 더 적용해도 안전하다 — 두 테이블 모두 CREATE TABLE IF NOT EXISTS 다")
	fun applyingTheSchemaTwiceIsSafe() {
		ClickHouseSchemaMigrator(client).apply()
		ClickHouseSchemaMigrator(client).apply()

		assertThat(query("EXISTS TABLE $EVENTS")).isEqualTo("1")
		assertThat(query("EXISTS TABLE $METRICS")).isEqualTo("1")
	}

	@Test
	@DisplayName("컬럼 수는 107·85 이고 앞 49 컬럼(봉투)은 두 테이블이 이름·타입·순서까지 같다")
	fun bothTablesShareTheFortyNineColumnEnvelope() {
		val events = columns(EVENTS)
		val metrics = columns(METRICS)

		assertThat(events).hasSize(107)
		assertThat(metrics).hasSize(85)
		assertThat(events.take(ENVELOPE_SIZE)).containsExactlyElementsOf(metrics.take(ENVELOPE_SIZE))

		val envelope = events.take(ENVELOPE_SIZE).map { it.first }
		assertThat(envelope.first()).isEqualTo("tenant_id")
		assertThat(envelope.last()).isEqualTo("enrichment_json")
		// 조직 보강의 세 승격 컬럼이 model 바로 뒤에 이 순서로 온다 (ADR 0020 §1·§5).
		assertThat(envelope.subList(envelope.indexOf("model"), envelope.indexOf("model") + 4))
			.containsExactly("model", "member_id", "team_id_as_of", "team_ids_as_of")
		assertThat(events.toMap()).containsEntry("member_id", "Nullable(String)")
			.containsEntry("team_id_as_of", "Nullable(String)")
			.containsEntry("team_ids_as_of", "Array(String)")
			.containsEntry("source_time", "DateTime64(9, 'UTC')")
			.containsEntry("cost_estimated_usd", "Nullable(Decimal(38, 12))")
			.containsEntry("row_version", "UInt64")
		assertThat(events.map { it.first }).doesNotContain("raw_json")
		assertThat(metrics.toMap()).containsEntry("bucket_counts", "Array(UInt64)")
			.containsEntry("hist_count", "Nullable(UInt64)")
	}

	@Test
	@DisplayName("엔진·파티션·정렬 키가 교체 저장의 근거다 — 정렬 키는 원본 불변값뿐이다")
	fun engineAndKeysAreTheReplacementContract() {
		for (table in listOf(EVENTS, METRICS)) {
			val row = query(
				"SELECT engine, partition_key, sorting_key, primary_key FROM system.tables " +
					"WHERE database = currentDatabase() AND name = '$table' FORMAT TSV",
			).split('\t')

			assertThat(row[0]).isEqualTo("ReplacingMergeTree")
			assertThat(row[1]).isEqualTo("toYYYYMM(source_time)")
			assertThat(row[2]).isEqualTo("tenant_id, source_time, installation_id, observation_id")
			assertThat(row[3]).isEqualTo(row[2])
			assertThat(query("SELECT engine_full FROM system.tables WHERE database = currentDatabase() AND name = '$table'"))
				.startsWith("ReplacingMergeTree(row_version)")
		}
		assertThat(skipIndexes(EVENTS)).containsExactly("idx_event_type", "idx_installation", "idx_session")
		assertThat(skipIndexes(METRICS)).containsExactly("idx_installation", "idx_metric_name", "idx_session")
	}

	// ── 교체 저장 (ADR 0020 §3) ─────────────────────────────────────────────

	@Test
	@DisplayName("재전송을 포함한 10행 한 블록이 관측 넷으로 줄고, 병합 뒤에도 넷이며, 관측마다 row_version 최댓값이 남는다")
	fun resendsInOneBlockCollapseToOneRowPerObservation() {
		insertEvents(
			event("o1", rev = 1, seq = 10, tokensInput = 10),
			event("o1", rev = 1, seq = 30, tokensInput = 30),
			event("o1", rev = 1, seq = 20, tokensInput = 20),
			event("o2", rev = 1, seq = 5, tokensInput = 5, sourceTime = "2026-09-24 15:31:00.000000000"),
			event("o2", rev = 1, seq = 7, tokensInput = 7, sourceTime = "2026-09-24 15:31:00.000000000"),
			event("o2", rev = 1, seq = 6, tokensInput = 6, sourceTime = "2026-09-24 15:31:00.000000000"),
			event("o3", rev = 1, seq = 1, tokensInput = 1, sourceTime = "2026-09-24 15:32:00.000000000"),
			event("o3", rev = 1, seq = 2, tokensInput = 2, sourceTime = "2026-09-24 15:32:00.000000000"),
			event("o4", rev = 1, seq = 9, tokensInput = 9, sourceTime = "2026-09-24 15:33:00.000000000"),
			event("o4", rev = 1, seq = 8, tokensInput = 8, sourceTime = "2026-09-24 15:33:00.000000000"),
		)

		assertThat(latest("count()")).isEqualTo("4")
		// 관측(= source_time 순) 마다 이긴 행의 값. o1 은 seq 30, o2 는 7, o3 는 2, o4 는 9 가 이긴다.
		assertThat(latest("arrayMap(x -> x.2, arraySort(groupArray((source_time, tokens_input))))"))
			.isEqualTo("[30,7,2,9]")

		client.execute("OPTIMIZE TABLE $EVENTS FINAL")

		assertThat(query("SELECT count() FROM $EVENTS")).isEqualTo("4")
		assertThat(query("SELECT sum(tokens_input) FROM $EVENTS")).isEqualTo("48")
	}

	@Test
	@DisplayName("normalizer_rev 2 의 정정 뒤 rev 1 의 재전송이 늦게 와도 이기지 못한다 — ingest_seq 가 더 커도")
	fun anOlderRevisionArrivingLateNeverWins() {
		insertEvents(event("o1", rev = 1, seq = 100, tokensInput = 100))
		insertEvents(event("o1", rev = 2, seq = 50, tokensInput = 120))
		insertEvents(event("o1", rev = 1, seq = 4_000_000_000, tokensInput = 100))

		assertThat(latest("groupArray(tokens_input)")).isEqualTo("[120]")
		assertThat(latest("any(normalizer_rev)")).isEqualTo("2")

		client.execute("OPTIMIZE TABLE $EVENTS FINAL")
		assertThat(query("SELECT groupArray(tokens_input) FROM $EVENTS")).isEqualTo("[120]")
	}

	@Test
	@DisplayName("소속 편집 뒤 같은 rev 로 재처리하면 새 귀속이 이긴다 — team_id_as_of 와 has(team_ids_as_of) 가 새 값이다")
	fun reprocessingAfterAMembershipEditReplacesTheAttribution() {
		insertEvents(event("o1", rev = 1, seq = 100, teamIdAsOf = TEAM_A, teamIdsAsOf = listOf(TEAM_A)))
		insertEvents(event("o1", rev = 1, seq = 101, teamIdAsOf = TEAM_B, teamIdsAsOf = listOf(TEAM_B)))

		assertThat(latest("count()", where = "team_id_as_of = '$TEAM_B'")).isEqualTo("1")
		assertThat(latest("count()", where = "team_id_as_of = '$TEAM_A'")).isEqualTo("0")
		assertThat(latest("countIf(has(team_ids_as_of, '$TEAM_B'))")).isEqualTo("1")
		assertThat(latest("countIf(has(team_ids_as_of, '$TEAM_A'))")).isEqualTo("0")
		// 조직 보강은 analysis_hash 의 재료가 아니다 — 두 행의 hash 가 같아야 정상 갱신이다.
		assertThat(query("SELECT uniqExact(analysis_hash) FROM $EVENTS")).isEqualTo("1")
	}

	@Test
	@DisplayName("excluded 행이 구 active 행을 덮고, FINAL 뒤의 record_status 필터가 그 관측을 되살리지 못한다")
	fun anExcludedRowIsNotResurrectedByAStatusFilter() {
		insertEvents(event("o1", rev = 1, seq = 100, tokensInput = 100))
		insertEvents(event("o1", rev = 2, seq = 100, recordStatus = "excluded", exclusionReason = "rule_retired"))

		assertThat(latest("count()", where = "record_status = 'active'")).isEqualTo("0")
		assertThat(latest("sumOrNull(tokens_input)", where = "record_status = 'active' AND usage_role = 'primary'"))
			.isEqualTo(NULL)
		assertThat(latest("count()", where = "record_status = 'excluded'")).isEqualTo("1")
		assertThat(latest("any(exclusion_reason)")).isEqualTo("rule_retired")
	}

	@Test
	@DisplayName("추정 비용을 null 로 정정하면 최신 행 전체가 선택되어 null 이 남는다 — 이전 금액을 가져오지 않는다")
	fun aNullCorrectionStaysNull() {
		insertEvents(event("o1", rev = 1, seq = 100, costEstimated = BigDecimal("0.02")))
		insertEvents(event("o1", rev = 2, seq = 100, costEstimated = null))

		assertThat(latest("sumOrNull(cost_estimated_usd)")).isEqualTo(NULL)
		assertThat(latest("countIf(isNotNull(cost_estimated_usd))")).isEqualTo("0")
		assertThat(latest("any(cost_estimated_usd)")).isEqualTo(NULL)
	}

	// ── 조회 규칙 (ADR 0020 §7 · 부록 B) ────────────────────────────────────

	@Test
	@DisplayName("sumOrNull 은 측정 0 을 세고 미보고 null 을 빼며, 전부 null 이면 null 이다 — Decimal 합은 정확하다")
	fun aggregatesDistinguishZeroFromUnreported() {
		insertEvents(
			event("o1", rev = 1, seq = 1, tokensInput = 100, costEstimated = BigDecimal("0.1")),
			event("o2", rev = 1, seq = 1, tokensInput = 0, costEstimated = BigDecimal("0.2"), sourceTime = "2026-09-24 15:31:00.000000000"),
			event("o3", rev = 1, seq = 1, tokensInput = null, sourceTime = "2026-09-24 15:32:00.000000000"),
		)

		assertThat(latest("sumOrNull(tokens_input)")).isEqualTo("100")
		assertThat(latest("countIf(isNotNull(tokens_input))")).isEqualTo("2")
		assertThat(latest("count()")).isEqualTo("3")
		assertThat(latest("sumOrNull(tokens_output)")).isEqualTo(NULL)
		assertThat(latest("sumOrNull(cost_estimated_usd) = toDecimal128('0.3', 12)")).isEqualTo("1")
		assertThat(latest("toTypeName(sumOrNull(cost_estimated_usd))")).isEqualTo("Nullable(Decimal(38, 12))")
	}

	@Test
	@DisplayName("일자는 tenant timezone 으로 묶는다 — UTC 15:30 은 서울 기준 다음 날이다")
	fun dailyGroupingUsesTheTenantTimezone() {
		insertEvents(event("o1", rev = 1, seq = 1, sourceTime = "2026-09-24 15:30:00.000000001"))

		assertThat(latest("any(toDate(source_time, 'Asia/Seoul'))")).isEqualTo("2026-09-25")
		assertThat(latest("any(toDate(source_time))")).isEqualTo("2026-09-24")
		// 나노초까지 보존된다 — Double 초를 거치지 않는다.
		assertThat(latest("any(toString(source_time))")).isEqualTo("2026-09-24 15:30:00.000000001")
	}

	@Test
	@DisplayName("attrs 의 스칼라 키로 거를 수 있고, 없는 키는 빈 문자열이다")
	fun attrsMapIsFilterable() {
		insertEvents(
			event("o1", rev = 1, seq = 1, attrs = mapOf("event.kind" to "response.completed")),
			event("o2", rev = 1, seq = 1, attrs = mapOf("event.kind" to "response.created"), sourceTime = "2026-09-24 15:31:00.000000000"),
			event("o3", rev = 1, seq = 1, sourceTime = "2026-09-24 15:32:00.000000000"),
		)

		assertThat(latest("count()", where = "attrs['event.kind'] = 'response.completed'")).isEqualTo("1")
		assertThat(latest("count()", where = "attrs['event.kind'] = ''")).isEqualTo("1")
	}

	// ── metric point ────────────────────────────────────────────────────────

	@Test
	@DisplayName("metric point 도 관측당 한 행으로 교체되고 UInt64 bucket·count 가 전 범위로 보존된다")
	fun metricPointsReplaceAndKeepUInt64Buckets() {
		insertMetrics(
			metricPoint("m1", rev = 1, seq = 1, bucketCounts = listOf("1", "2"), histCount = "3"),
			metricPoint("m1", rev = 1, seq = 2, bucketCounts = listOf(UINT64_MAX, "0"), histCount = UINT64_MAX),
		)

		assertThat(query("SELECT count() FROM $METRICS FINAL")).isEqualTo("1")
		assertThat(query("SELECT toString(bucket_counts) FROM $METRICS FINAL")).isEqualTo("[$UINT64_MAX,0]")
		assertThat(query("SELECT toString(hist_count) FROM $METRICS FINAL")).isEqualTo(UINT64_MAX)
		assertThat(query("SELECT toString(explicit_bounds) FROM $METRICS FINAL")).isEqualTo("[10]")
	}

	// ── 도구 ────────────────────────────────────────────────────────────────

	private fun query(sql: String): String = client.execute(sql).trim()

	/**
	 * 최신 행을 고른 뒤 집계한다. 부록 B 의 모양 그대로 — `FINAL` 과
	 * `do_not_merge_across_partitions_select_final` 을 켜고, 조건은 `FINAL` 뒤에 붙는다.
	 */
	private fun latest(select: String, where: String = "1"): String =
		query(
			"SELECT $select FROM $EVENTS FINAL WHERE tenant_id = '$TENANT' AND ($where) " +
				"SETTINGS do_not_merge_across_partitions_select_final = 1",
		)

	private fun columns(table: String): List<Pair<String, String>> =
		query(
			"SELECT name, type FROM system.columns WHERE database = currentDatabase() AND table = '$table' " +
				"ORDER BY position FORMAT TSVRaw", // TSV 는 타입 이름의 작은따옴표를 \' 로 이스케이프한다
		).lines().map { line -> line.split('\t').let { it[0] to it[1] } }

	private fun skipIndexes(table: String): List<String> =
		query(
			"SELECT name FROM system.data_skipping_indices WHERE database = currentDatabase() AND table = '$table' " +
				"ORDER BY name",
		).lines()

	private fun insertEvents(vararg rows: Map<String, Any?>) = insert(EVENTS, rows.toList())

	private fun insertMetrics(vararg rows: Map<String, Any?>) = insert(METRICS, rows.toList())

	/** 한 번의 INSERT 가 한 블록이다. */
	private fun insert(table: String, rows: List<Map<String, Any?>>) {
		val body = rows.joinToString("\n") { json(it) }
		client.execute("INSERT INTO $table FORMAT JSONEachRow", body.toByteArray())
	}

	/** 봉투에서 이 테스트가 쓰지 않는 컬럼도 명시한다 — 누락 컬럼 기본값에 기대지 않는다. */
	private fun envelope(
		observation: String,
		rev: Long,
		seq: Long,
		signal: String,
		sourceTime: String,
		sourceTimeOrigin: String,
	): LinkedHashMap<String, Any?> = linkedMapOf(
		"tenant_id" to TENANT,
		"installation_id" to INSTALLATION,
		"observation_id" to sha256Hex("observation:$observation"),
		"row_version" to ((rev shl 32) or seq),
		"normalizer_rev" to rev,
		"analysis_hash" to sha256Hex("analysis:$observation"),
		"schema_version" to 1,
		"identity_version" to "v1",
		"source_identity_kind" to "fingerprint",
		"source_identity_namespace" to null,
		"native_observation_id" to null,
		"record_status" to "active",
		"exclusion_reason" to null,
		"mapping_status" to "mapped",
		"quality_flags" to emptyList<String>(),
		"source_time" to sourceTime,
		"source_time_origin" to sourceTimeOrigin,
		"event_time" to null,
		"observed_time" to null,
		"received_time" to "2026-09-24 15:40:00.000000000",
		"signal" to signal,
		"product" to "codex",
		"surface" to "app_server",
		"product_version" to null,
		"service_name" to "codex-app-server",
		"service_version" to null,
		"service_instance_id" to null,
		"scope_name" to null,
		"scope_version" to null,
		"resource_schema_url" to null,
		"scope_schema_url" to null,
		"original_name" to null,
		"mapping_version" to "test",
		"enrichment_version" to "test",
		"usage_role" to "none",
		"usage_scope" to "unknown",
		"workload_kind" to "unknown",
		"session_id" to null,
		"session_id_namespace" to null,
		"model" to null,
		"member_id" to MEMBER,
		"team_id_as_of" to null,
		"team_ids_as_of" to emptyList<String>(),
		"archive_ref" to null,
		"archive_selector" to null,
		"masking_version" to "test",
		"metadata_json" to "{}",
		"attrs" to emptyMap<String, String>(),
		"enrichment_json" to "{}",
	)

	private fun event(
		observation: String,
		rev: Long,
		seq: Long,
		sourceTime: String = "2026-09-24 15:30:00.000000001",
		recordStatus: String = "active",
		exclusionReason: String? = null,
		tokensInput: Long? = 100,
		costEstimated: BigDecimal? = null,
		teamIdAsOf: String? = null,
		teamIdsAsOf: List<String> = emptyList(),
		attrs: Map<String, String> = emptyMap(),
	): Map<String, Any?> = envelope(observation, rev, seq, "log", sourceTime, "otlp_event").apply {
		this["record_status"] = recordStatus
		this["exclusion_reason"] = exclusionReason
		this["usage_role"] = "primary"
		this["usage_scope"] = "response"
		this["workload_kind"] = "main"
		this["team_id_as_of"] = teamIdAsOf
		this["team_ids_as_of"] = teamIdsAsOf
		this["attrs"] = attrs
		this["event_type"] = "model.response.usage"
		this["operation"] = "none"
		this["ttft_scope"] = "none"
		this["span_kind"] = "none"
		this["span_status_code"] = "none"
		this["tokens_input"] = tokensInput
		this["tokens_output"] = null
		this["input_semantics"] = "unknown"
		this["output_semantics"] = "unknown"
		this["cost_estimated_usd"] = costEstimated
		this["reported_cost_basis"] = "unknown"
		this["error_type"] = "none"
		this["tool_origin"] = "none"
		this["tool_action"] = "none"
		this["decision"] = "none"
		this["decision_source"] = "none"
		this["decision_scope"] = "none"
		this["stop_reason"] = "none"
		this["reasoning_effort"] = "none"
	}

	private fun metricPoint(
		observation: String,
		rev: Long,
		seq: Long,
		bucketCounts: List<String>,
		histCount: String,
	): Map<String, Any?> =
		envelope(observation, rev, seq, "metric", "2026-09-24 15:30:00.000000000", "metric_point").apply {
			this["usage_role"] = "diagnostic"
			this["usage_scope"] = "interval"
			this["series_id"] = null
			this["series_identity_status"] = "unverified"
			this["structural_status"] = "valid"
			this["metric_name"] = "codex.turn.tool.call"
			this["metric_type"] = "histogram"
			this["metric_family"] = "tool.calls.per_turn"
			this["raw_unit"] = ""
			this["canonical_unit"] = "unknown"
			this["token_component"] = "none"
			this["temporality"] = "delta"
			this["temporality_code"] = 1
			this["is_monotonic"] = null
			this["start_time"] = "2026-09-24 15:29:00.000000000"
			this["hist_count"] = RawNumber(histCount)
			this["hist_sum"] = 12.0
			this["hist_buckets_present"] = true
			this["explicit_bounds"] = listOf(10.0)
			this["bucket_counts"] = bucketCounts.map { RawNumber(it) }
			this["point_flags"] = 0
		}

	/** JSON 숫자 토큰을 그대로 쓴다 — UInt64 전 범위를 Long·Double 을 거치지 않고 보낸다. */
	private class RawNumber(val text: String)

	private fun json(value: Any?): String = when (value) {
		null -> "null"
		is Boolean -> value.toString()
		is RawNumber -> value.text
		is BigDecimal -> value.toPlainString()
		is Number -> value.toString()
		is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
		is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> json(k as String) + ":" + json(v) }
		is List<*> -> value.joinToString(",", "[", "]") { json(it) }
		else -> error("테스트 행에 쓸 수 없는 값: ${value::class}")
	}

	private fun sha256Hex(text: String): String =
		MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

	companion object {
		private const val HTTP_PORT: Int = 8123
		private const val EVENTS: String = "telemetry_events"
		private const val METRICS: String = "telemetry_metric_points"
		private const val ENVELOPE_SIZE: Int = 49
		private const val NULL: String = "\\N"
		private const val UINT64_MAX: String = "18446744073709551615"
		private const val TENANT: String = "33333333-3333-3333-3333-333333333333"
		private const val INSTALLATION: String = "44444444-4444-4444-4444-444444444444"
		private const val MEMBER: String = "55555555-5555-5555-5555-555555555555"
		private const val TEAM_A: String = "11111111-1111-1111-1111-111111111111"
		private const val TEAM_B: String = "22222222-2222-2222-2222-222222222222"

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
