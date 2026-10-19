package com.team376.pulsemetry.telemetry.maintenance

import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CodeburnReferencePrices
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import com.team376.pulsemetry.telemetry.maintenance.StoredUsageSamples.row
import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 재평가 명령의 쓰기·재개를 실제 ClickHouse·RDS 경계 위에서 본다. 입력은 운영 절차(`docs/pricing/stored-usage-repricing.md`)와
 * 같은 `FINAL` 내보내기다. 쓰기 중 실패한 뒤 같은 입력으로 다시 실행하면 이어서 끝나고, 세 번째 실행의 쓰기는 0건이어야 한다.
 */
class RepriceStoredUsageApplyTest : AbstractIngestIntegrationTest() {

	@TempDir
	lateinit var dir: Path

	private val client = ClickHouseHttpClient(clickHouseUrl())
	private val sink = TelemetryEventsSink(client)
	private val mapper = JsonMapper.builder().build()
	private val tenant: String = UUID.randomUUID().toString()

	/** 재평가 셋(develop 판 둘·이전 검증 판 하나), 원본만 있는 Codex 하나, 원본도 없는 Codex 하나. */
	private fun seedMixed() = listOf(
		row(tenant, id = 'a', rev = 1u, ingestSeq = 101u),
		row(tenant, id = 'b', rev = 8u, ingestSeq = 102u),
		row(tenant, id = 'c', rev = 1u, ingestSeq = 103u),
		row(tenant, id = 'd', rev = 1u, ingestSeq = 104u, semanticsProfile = null),
		row(tenant, id = 'e', rev = 1u, ingestSeq = 105u, semanticsProfile = null, archiveRef = null),
	).onEach { store(it) }

	private fun store(row: StoredUsageRepricing.Row) {
		sink.insert(listOf(row.event), row.version, AnalysisWriteBoundary(tenant, null, 0))
	}

	/** 운영 절차의 내보내기 그대로. */
	private fun export(name: String): Pair<Path, String> {
		val body = client.execute(
			"SELECT * FROM telemetry_events FINAL WHERE tenant_id = {t:String} AND event_type = 'model.response.usage' AND usage_role = 'primary' " +
				"ORDER BY observation_id SETTINGS output_format_json_quote_64bit_integers=1, output_format_json_quote_decimals=1 FORMAT JSONEachRow",
			params = mapOf("t" to tenant),
		)
		val path = dir.resolve(name)
		Files.writeString(path, body)
		val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
		return path to sha
	}

	private fun rows(): Map<String, JsonNode> = client.execute(
		"SELECT * FROM telemetry_events FINAL WHERE tenant_id = {t:String} ORDER BY observation_id " +
			"SETTINGS output_format_json_quote_64bit_integers=1, output_format_json_quote_decimals=1 FORMAT JSONEachRow",
		params = mapOf("t" to tenant),
	).lineSequence().filter(String::isNotBlank).map(mapper::readTree).associateBy { it["observation_id"].asString().take(1) }

	private fun apply(chunkSize: Int = 1, insert: (List<EnrichedEvent>, RowVersioning, AnalysisWriteBoundary) -> Int = sink::insert) =
		RepriceStoredUsage.Apply(client, retentionBoundaries::read, insert, chunkSize)

	@Test
	@DisplayName("분류 — 의미가 있는 행만 재평가하고, 원본만 있는 행과 원본도 없는 행은 사유와 건수로 남긴다")
	fun planClassifiesStoredRows() {
		seedMixed()
		val (input, sha) = export("before.jsonl")

		val plan = RepriceStoredUsage.plan(input, sha, tenant)

		assertThat(plan.rows).isEqualTo(5)
		assertThat(plan.counts[StoredUsageRepricing.Disposition.REPRICE]).isEqualTo(mapOf("priced" to 3))
		assertThat(plan.counts[StoredUsageRepricing.Disposition.RENORMALIZE]).isEqualTo(mapOf("semantics_missing" to 1))
		assertThat(plan.counts[StoredUsageRepricing.Disposition.UNRECOVERABLE]).isEqualTo(mapOf("semantics_missing" to 1))
		assertThat(plan.archiveRefs).containsExactly("file:///private/original")
		assertThat(plan.costUsd).isEqualByComparingTo("0.0035475")
		assertThatThrownBy { RepriceStoredUsage.plan(input, "0".repeat(64), tenant) }.isInstanceOf(StoredUsageRepricing.Stop::class.java)
		assertThatThrownBy { RepriceStoredUsage.plan(input, sha, UUID.randomUUID().toString()) }.isInstanceOf(StoredUsageRepricing.Stop::class.java)
	}

	@Test
	@DisplayName("쓰기 중 실패하면 같은 입력으로 이어서 끝나고, 끝난 입력을 다시 돌리면 쓰기는 0건이다")
	fun interruptedApplyResumesAndThenWritesNothing() {
		seedMixed()
		val before = rows()
		val (input, sha) = export("before.jsonl")
		RepriceStoredUsage.plan(input, sha, tenant)

		var calls = 0
		val failing: (List<EnrichedEvent>, RowVersioning, AnalysisWriteBoundary) -> Int = { events, version, boundary ->
			check(++calls < 2) { "simulated failure" }
			sink.insert(events, version, boundary)
		}
		assertThatThrownBy { apply(insert = failing).run(input, tenant) }.isInstanceOf(IllegalStateException::class.java)
		assertThat(rows().values.count { it["normalizer_rev"].asLong() == RowVersioning.NORMALIZER_REV.toLong() }).isEqualTo(1)

		val resumed = apply().run(input, tenant)
		assertThat(resumed).isEqualTo(RepriceStoredUsage.Apply.Result(written = 2, alreadyApplied = 1, superseded = 0, removed = 0, outsideBoundary = 0))

		val again = apply(chunkSize = 250).run(input, tenant)
		assertThat(again).isEqualTo(RepriceStoredUsage.Apply.Result(written = 0, alreadyApplied = 3, superseded = 0, removed = 0, outsideBoundary = 0))

		val after = rows()
		val changeable = setOf("row_version", "normalizer_rev", "analysis_hash", "cost_estimated_usd", "pricing_version")
		for (id in listOf("a", "b", "c")) {
			val row = after.getValue(id)
			assertThat(row["normalizer_rev"].asLong()).isEqualTo(RowVersioning.NORMALIZER_REV.toLong())
			assertThat(row["pricing_version"].asString()).isEqualTo(CodeburnReferencePrices.VERSION)
			assertThat(row["cost_estimated_usd"].asString().toBigDecimal()).isEqualByComparingTo("0.0011825")
			// 원래 ingest_seq 를 유지하고 판만 올린다.
			assertThat(row["row_version"].asString().toULong() and 0xFFFF_FFFFuL)
				.isEqualTo(before.getValue(id)["row_version"].asString().toULong() and 0xFFFF_FFFFuL)
			for (column in before.getValue(id).properties().map { it.key }) {
				if (column !in changeable) assertThat(row[column]).describedAs(column).isEqualTo(before.getValue(id)[column])
			}
		}
		// 재정규화 후보와 복구 불가 행은 추정으로 채우지 않고 그대로 둔다.
		for (id in listOf("d", "e")) assertThat(after.getValue(id)).isEqualTo(before.getValue(id))

		val (next, nextSha) = export("after.jsonl")
		val replanned = RepriceStoredUsage.plan(next, nextSha, tenant)
		assertThat(replanned.counts[StoredUsageRepricing.Disposition.CURRENT]).isEqualTo(mapOf("priced" to 3))
		assertThat(replanned.counts[StoredUsageRepricing.Disposition.REPRICE]).isEmpty()
	}

	@Test
	@DisplayName("내보낸 뒤 지워졌거나 경계 밖으로 밀렸거나 더 새 결과가 생긴 행은 건너뛰고 끝까지 진행한다")
	fun rowsThatMovedAfterExportAreSkipped() {
		val source = Instant.parse("2026-09-13T12:00:00Z")
		listOf(
			row(tenant, id = 'a', rev = 1u, ingestSeq = 101u, sourceTime = source.plus(2, ChronoUnit.HOURS)),
			row(tenant, id = 'b', rev = 1u, ingestSeq = 102u, sourceTime = source),
			row(tenant, id = 'c', rev = 1u, ingestSeq = 103u, sourceTime = source.plus(2, ChronoUnit.HOURS)),
		).forEach(::store)
		val (input, sha) = export("before.jsonl")
		RepriceStoredUsage.plan(input, sha, tenant)

		client.execute("DELETE FROM telemetry_events WHERE tenant_id = {t:String} AND observation_id = {id:String}", params = mapOf("t" to tenant, "id" to "a".repeat(64)))
		retentionBoundaries.advance(UUID.fromString(tenant), source.plus(1, ChronoUnit.HOURS))
		// 배포된 수집이 같은 관측을 나중 receipt 로 다시 적재했다.
		val live = StoredUsageRepricing.classify(row(tenant, id = 'c', rev = 1u, sourceTime = source.plus(2, ChronoUnit.HOURS))).target!!
		store(live.copy(version = RowVersioning.of(RowVersioning.NORMALIZER_REV, 999u)))

		val result = apply().run(input, tenant)

		assertThat(result).isEqualTo(RepriceStoredUsage.Apply.Result(written = 0, alreadyApplied = 0, superseded = 1, removed = 1, outsideBoundary = 1))
		assertThat(rows().keys).containsExactlyInAnyOrder("b", "c")
		assertThat(rows().getValue("b")["normalizer_rev"].asLong()).isEqualTo(1)
	}

	@Test
	@DisplayName("내보낸 뒤 같은 판에서 바뀐 행이 있으면 오래된 체크포인트로 덮어쓰지 않고 중단한다")
	fun rowChangedAfterExportStops() {
		store(row(tenant, id = 'a', rev = 1u, ingestSeq = 101u))
		val (input, sha) = export("before.jsonl")
		RepriceStoredUsage.plan(input, sha, tenant)
		store(row(tenant, id = 'a', rev = 1u, ingestSeq = 202u, model = "gpt-6-astra"))

		assertThatThrownBy { apply().run(input, tenant) }
			.isInstanceOf(StoredUsageRepricing.Stop::class.java)
			.hasMessage("계획 이후 관측이 변경됐다")
		assertThat(rows().getValue("a")["normalizer_rev"].asLong()).isEqualTo(1)
	}
}
