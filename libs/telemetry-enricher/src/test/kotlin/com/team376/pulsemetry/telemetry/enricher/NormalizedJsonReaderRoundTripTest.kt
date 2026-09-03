package com.team376.pulsemetry.telemetry.enricher

import com.team376.pulsemetry.telemetry.adapter.NormalizedJson
import com.team376.pulsemetry.telemetry.enricher.support.GoldenEvents
import com.team376.pulsemetry.telemetry.enricher.support.NormalizedJsonReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * **이 테스트가 `NormalizedJsonReader` 를 믿을 근거 전부다.**
 *
 * 어댑터에는 JSON → `Normalized` 역직렬화기가 없어 리더를 새로 썼다. 그 리더가 golden 이
 * 말하는 그 이벤트를 만드는지는 되쓴 값이 원본과 같은지로만 확인할 수 있다. 여기가 깨지면
 * **기대값이 아니라 리더를 고친다** — golden 은 이식의 오라클이다.
 *
 * 왕복이 성립하면 보강·적재 테스트가 실측 이벤트로 돌 수 있다. 특히 적재의 `raw_json` 은
 * 이 트리를 그대로 담으므로, 여기서 어긋나면 그 컬럼이 통째로 어긋난다.
 */
class NormalizedJsonReaderRoundTripTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("fixtures")
	@DisplayName("golden 을 되읽어 다시 쓰면 원래 값과 같다")
	fun readingAndWritingBackYieldsTheSameValue(resource: String) {
		val trees = GoldenEvents.trees(resource)
		assertThat(trees).isNotEmpty()

		trees.forEachIndexed { index, expected ->
			val rewritten = NormalizedJson.toTree(NormalizedJsonReader.read(expected))
			assertThat(GoldenEvents.canonicalize(rewritten))
				.`as`("$resource 의 %d 번째 이벤트", index)
				.isEqualTo(GoldenEvents.canonicalize(expected))
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("fixtures")
	@DisplayName("되읽은 이벤트가 봉투의 신뢰 키와 멱등 키를 그대로 들고 있다")
	fun envelopeKeysSurviveTheRoundTrip(resource: String) {
		GoldenEvents.events(resource).forEach { event ->
			val envelope = event.envelope
			assertThat(envelope.recordId).startsWith("idem-")
			assertThat(envelope.ingest.sourceRecordId).startsWith("raw-")
			assertThat(envelope.schemaVersion).isEqualTo(1)
		}
	}

	companion object {
		@JvmStatic
		fun fixtures(): List<String> = GoldenEvents.FIXTURES
	}
}
