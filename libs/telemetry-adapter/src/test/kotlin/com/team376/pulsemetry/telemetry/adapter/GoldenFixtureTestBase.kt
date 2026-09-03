package com.team376.pulsemetry.telemetry.adapter

import com.google.protobuf.Message
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 입력·기대출력 한 쌍을 대조하는 공통 검사.
 *
 * 하위 클래스는 fixture 두 개와 신호 종류를 준다. 봉투 불변식을 볼 것이면
 * [expectedProduct]·[expectedAdapterVersion] 도 준다.
 *
 * **기대값은 구 파이프라인의 Python normalizer 가 구운 것이다.** 여기서 실패한다는 것은
 * 이식본이 현행과 다른 값을 낸다는 뜻이다 — 기대값을 고치지 말고 이식본을 고쳐라.
 */
internal abstract class GoldenFixtureTestBase(
	private val otlpFixture: String,
	private val goldenFixture: String,
	private val signal: GoldenFixtures.Signal,
	private val expectedProduct: String? = null,
	private val expectedAdapterVersion: Int? = null,
) {

	protected val requests: List<Message> by lazy { GoldenFixtures.requests(otlpFixture, signal) }
	protected val golden: List<GoldenFixtures.Row> by lazy { GoldenFixtures.golden(goldenFixture) }

	protected fun eventsOf(documentIndex: Int): List<Map<String, Any?>> =
		GoldenFixtures.eventsOf(golden, documentIndex)

	@Suppress("UNCHECKED_CAST")
	protected fun Map<String, Any?>.at(vararg path: String): Any? {
		var cursor: Any? = this
		for (key in path) cursor = (cursor as Map<String, Any?>)[key]
		return cursor
	}

	@Test
	@DisplayName("정규화 결과가 기대출력과 이벤트 단위로 정확히 일치한다")
	fun matchesGoldenEventByEvent() {
		val actual = GoldenFixtures.normalize(requests)

		assertThat(actual).hasSameSizeAs(golden)
		actual.forEachIndexed { index, produced ->
			val expected = golden[index]
			assertThat(produced.documentIndex)
				.`as`("행 $index 의 document_index")
				.isEqualTo(expected.documentIndex)
			assertThat(produced.eventIndex)
				.`as`("행 $index 의 event_index")
				.isEqualTo(expected.eventIndex)
			assertThat(produced.event)
				.`as`("행 $index 의 event")
				.isEqualTo(expected.event)
		}
	}

	@Test
	@DisplayName("같은 요청을 두 번 태워도 record_id 가 같다")
	fun recordIdIsDeterministicAcrossRuns() {
		val first = GoldenFixtures.normalize(requests).map { it.at("envelope", "record_id") }
		val second = GoldenFixtures.normalize(requests).map { it.at("envelope", "record_id") }

		assertThat(first).isEqualTo(second)
		assertThat(first).allSatisfy { key -> assertThat(key as String).startsWith("idem-") }
	}

	@Test
	@DisplayName("원본 추적 해시도 재읽기에 흔들리지 않는다")
	fun sourceRecordIdIsDeterministicAcrossRuns() {
		val keys = GoldenFixtures.normalize(requests)
			.map { it.at("envelope", "_ingest", "source_record_id") }

		assertThat(keys).isNotEmpty()
		assertThat(keys).allSatisfy { key -> assertThat(key as String).startsWith("raw-") }
	}

	@Test
	@DisplayName("모든 이벤트가 기대한 제품·어댑터 버전을 달고 나온다")
	fun envelopeIsStampedByTheExpectedAdapter() {
		val product = expectedProduct ?: return
		golden.forEach { row ->
			@Suppress("UNCHECKED_CAST")
			val event = row.event as Map<String, Any?>
			assertThat(event.at("envelope", "client", "product")).isEqualTo(product)
			assertThat(event.at("envelope", "client", "surface")).isEqualTo("cli")
			assertThat(event.at("envelope", "schema_version")).isEqualTo(1L)
			assertThat(event.at("envelope", "_ingest", "adapter_version"))
				.isEqualTo(expectedAdapterVersion?.toLong())
		}
	}

	private fun GoldenFixtures.Row.at(vararg path: String): Any? {
		@Suppress("UNCHECKED_CAST")
		return (event as Map<String, Any?>).at(*path)
	}
}
