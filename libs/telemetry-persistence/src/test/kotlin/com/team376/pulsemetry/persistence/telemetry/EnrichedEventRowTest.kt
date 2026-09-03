package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.NormalizedJson
import com.team376.pulsemetry.telemetry.enricher.Enriched
import com.team376.pulsemetry.telemetry.enricher.support.GoldenEvents
import com.team376.pulsemetry.telemetry.enricher.support.TestEvents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 화이트리스트 매핑을 고정한다 — 이식 원본 `sink_clickhouse.to_row` 의 자리다.
 *
 * 여기서 어긋나는 것은 전부 **저장되는 값**의 차이라 조용히 지나간다. 컬럼이 하나 늘거나,
 * `ts` 가 반올림되거나, `enrichment_json` 의 키 순서가 바뀌어도 적재는 성공한다.
 */
class EnrichedEventRowTest {

	private val installationId: UUID = UUID.randomUUID()
	private val at: Instant = Instant.parse("2026-06-01T00:00:00Z")

	private fun row(item: Enriched): Map<String, Any?> = EnrichedEventRow.of(item)

	// ── 화이트리스트 ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("행은 아홉 컬럼이고 순서가 DDL 과 같다")
	fun theRowIsExactlyTheWhitelist() {
		val row = row(Enriched(TestEvents.log(installationId, at)))

		assertThat(row.keys).containsExactlyElementsOf(EnrichedEventRow.COLUMNS)
		assertThat(EnrichedEventRow.COLUMNS).hasSize(9)
	}

	@Test
	@DisplayName("member_id 는 컬럼이 아니다 — raw_json 안에만 남는다 (파이프라인 ADR 0006)")
	fun memberIdIsNotPromotedToAColumn() {
		val row = row(Enriched(TestEvents.log(installationId, at)))

		assertThat(row).doesNotContainKey("member_id")
		assertThat(row["raw_json"] as String).contains("alice@acme.test")
	}

	@Test
	@DisplayName("승격 컬럼은 team_ids_as_of 하나뿐이고 provider 주석은 enrichment_json 에만 있다")
	fun onlyTeamIdsArePromoted() {
		val item = Enriched(TestEvents.log(installationId, at))
		item.teamIdsAsOf = listOf("team-a", "team-b")
		item.annotations["github"] = mapOf("repo" to "team376/pulsemetry-backend")

		val row = row(item)

		assertThat(row["team_ids_as_of"]).isEqualTo(listOf("team-a", "team-b"))
		assertThat(row).doesNotContainKey("repo")
		assertThat(row["enrichment_json"] as String).contains("team376/pulsemetry-backend")
	}

	// ── 값 변환 ─────────────────────────────────────────────────────────────

	@Test
	@DisplayName("ts 는 초로 절사된다 — 반올림이 아니다")
	fun subSecondPrecisionIsTruncated() {
		val item = Enriched(TestEvents.log(installationId, at.plusMillis(500)))

		assertThat(row(item)["ts"]).isEqualTo(at.epochSecond)
	}

	@Test
	@DisplayName("없는 신원·분류 값은 빈 문자열이 된다 — 컬럼이 non-nullable 이다")
	fun missingValuesBecomeEmptyStrings() {
		val item = Enriched(TestEvents.log(installationId = null, at = at, tenantId = null))

		val row = row(item)

		assertThat(row["tenant_id"]).isEqualTo("")
		assertThat(row["installation_id"]).isEqualTo("")
	}

	@Test
	@DisplayName("멱등 키는 record_id 이고, 비면 source_record_id 로 떨어진다")
	fun eventIdFallsBackToTheSourceHash() {
		val item = Enriched(GoldenEvents.events(GoldenEvents.CLAUDE_CODE_LOGS).first())
		assertThat(row(item)["event_id"]).isEqualTo(item.event.envelope.recordId)

		item.event.envelope.recordId = ""
		assertThat(row(item)["event_id"]).isEqualTo(item.event.envelope.ingest.sourceRecordId)
	}

	// ── raw_json 과 enrichment_json ─────────────────────────────────────────

	@Test
	@DisplayName("raw_json 이 golden 의 event 와 같은 값이다")
	fun rawJsonIsTheGoldenEvent() {
		val expected = GoldenEvents.trees(GoldenEvents.CLAUDE_CODE_LOGS)
		val events = GoldenEvents.events(GoldenEvents.CLAUDE_CODE_LOGS)

		events.forEachIndexed { index, event ->
			val rawJson = row(Enriched(event))["raw_json"] as String
			assertThat(GoldenEvents.canonicalize(GoldenEvents.parse(rawJson)))
				.`as`("%d 번째 이벤트의 raw_json", index)
				.isEqualTo(GoldenEvents.canonicalize(expected[index]))
			assertThat(rawJson).isEqualTo(NormalizedJson.toJson(event))
		}
	}

	@Test
	@DisplayName("enrichment_json 은 키를 정렬한다 — 주석을 넣은 순서와 무관하다")
	fun enrichmentJsonSortsItsKeys() {
		val item = Enriched(TestEvents.log(installationId, at))
		item.annotations["org"] = mapOf("team_ids" to listOf("team-a"))
		item.annotations["jira"] = emptyMap<String, Any?>()
		item.annotations["github"] = emptyMap<String, Any?>()
		item.annotations["ai_analysis"] = emptyMap<String, Any?>()

		// 현행 파이프라인이 적재하는 값 그대로다. no-op 스텁이 빠지면 키 셋이 달라진다.
		assertThat(row(item)["enrichment_json"])
			.isEqualTo("""{"ai_analysis":{},"github":{},"jira":{},"org":{"team_ids":["team-a"]}}""")
	}

	@Test
	@DisplayName("주석이 없으면 빈 객체다")
	fun emptyAnnotationsBecomeAnEmptyObject() {
		assertThat(row(Enriched(TestEvents.log(installationId, at)))["enrichment_json"]).isEqualTo("{}")
	}

	// ── 행 한 줄 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("행 JSON 은 컬럼 순서를 지키고 enrichment_json 만 정렬된다")
	fun theRowLineKeepsColumnOrder() {
		val item = Enriched(TestEvents.log(installationId, at))
		item.annotations["org"] = mapOf("team_ids" to emptyList<String>())

		val line = EnrichedEventRow.toJson(item)

		// 행은 삽입 순서, 그 안의 enrichment_json 문자열만 키 정렬 — 표기 규칙이 둘이다.
		assertThat(line).startsWith("{\"event_id\":")
		assertThat(line.indexOf("\"ts\"")).isLessThan(line.indexOf("\"tenant_id\""))
		assertThat(line).doesNotContain("\n")
	}
}
