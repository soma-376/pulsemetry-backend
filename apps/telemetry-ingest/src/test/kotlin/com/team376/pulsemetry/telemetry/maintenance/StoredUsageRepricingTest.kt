package com.team376.pulsemetry.telemetry.maintenance

import com.sun.net.httpserver.HttpServer
import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.telemetry.adapter.observation.*
import com.team376.pulsemetry.telemetry.maintenance.StoredUsageRepricing.Disposition
import com.team376.pulsemetry.telemetry.maintenance.StoredUsageRepricing.Reason
import com.team376.pulsemetry.telemetry.maintenance.StoredUsageSamples.row
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.InetSocketAddress

class StoredUsageRepricingTest {
	private val mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

	/** 실 적재 인코더가 생성한 행을 경계에서 캡처한다. 테스트에서 열·타입 인코더를 복제하지 않는다. */
	private fun encoded(row: StoredUsageRepricing.Row): ObjectNode {
		var body = ""
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			body = exchange.requestBody.bufferedReader().readText()
			exchange.sendResponseHeaders(200, -1)
			exchange.close()
		}
		server.start()
		try {
			TelemetryEventsSink(ClickHouseHttpClient("http://127.0.0.1:${server.address.port}")).insert(
				listOf(row.event), row.version, AnalysisWriteBoundary(row.event.observation.envelope.tenantId, null, 0),
			)
			return (mapper.readTree(body) as ObjectNode).also { if (row.version.normalizerRev < 10u) it.putNull("pricing_version") }
		} finally { server.stop(0) }
	}

	private fun target(source: StoredUsageRepricing.Row): StoredUsageRepricing.Row =
		StoredUsageRepricing.classify(source).also { assertThat(it.disposition).isEqualTo(Disposition.REPRICE) }.target!!

	@Test
	fun preservesExactTypedSourceAndRepricesOnlyCostHashAndRevision() {
		val old = row()
		val decoded = StoredUsageRepricing.decode(encoded(old))
		assertThat(decoded).isEqualTo(old)
		val actual = target(decoded)
		assertThat(actual.event.observation.costEstimatedUsd).isEqualByComparingTo("0.0011825")
		assertThat(actual.version).isEqualTo(RowVersioning.of(RowVersioning.NORMALIZER_REV, 123u))
		assertThat(actual.event.org).isEqualTo(old.event.org)
		assertThat(actual.event.observation.copy(
			costEstimatedUsd = null,
			envelope = actual.event.observation.envelope.copy(analysisHash = old.event.observation.envelope.analysisHash),
		)).isEqualTo(old.event.observation)
		assertThat(AnalysisHash.of(actual.event.observation)).isEqualTo(actual.event.observation.envelope.analysisHash)
		val restored = StoredUsageRepricing.decode(encoded(actual))
		assertThat(restored.event.observation.costEstimatedUsd).isEqualByComparingTo(actual.event.observation.costEstimatedUsd)
		assertThat(restored.event.observation.envelope.analysisHash).isEqualTo(actual.event.observation.envelope.analysisHash)
		assertThat(StoredUsageRepricing.classify(restored).disposition).isEqualTo(Disposition.CURRENT)
		assertThat(StoredUsageRepricing.classify(restored).target).isEqualTo(restored)
		val storedDecimal = StoredUsageRepricing.decode(encoded(actual).put("cost_estimated_usd", "0.001182500000"))
		assertThat(StoredUsageRepricing.classify(storedDecimal).target).isEqualTo(storedDecimal)
	}

	@Test
	fun repeatedTargetIsCurrentButConflictingCurrentRevisionIsRejected() {
		val once = target(row())
		val again = StoredUsageRepricing.classify(once)
		assertThat(again.disposition).isEqualTo(Disposition.CURRENT)
		assertThat(again.target).isEqualTo(once)
		assertThatThrownBy { StoredUsageRepricing.classify(row(rev = RowVersioning.NORMALIZER_REV)) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	fun deployedRevisionWithTrustedSemanticsIsRepriced() {
		val planned = StoredUsageRepricing.classify(row(rev = 1u))
		assertThat(planned.disposition).isEqualTo(Disposition.REPRICE)
		assertThat(planned.reason).isEqualTo(Reason.PRICED)
		assertThat(planned.target!!.version).isEqualTo(RowVersioning.of(RowVersioning.NORMALIZER_REV, 123u))
	}

	@Test
	fun unknownModelKeepsNullPriceInsteadOfStopping() {
		val planned = StoredUsageRepricing.classify(row(model = "unknown-model"))
		assertThat(planned.disposition).isEqualTo(Disposition.CURRENT)
		assertThat(planned.reason).isEqualTo(Reason.PRICE_UNAVAILABLE)
		assertThat(planned.target!!.event.observation.costEstimatedUsd).isNull()
	}

	@Test
	fun rowsWithoutTrustedSemanticsAreReportedNotRepriced() {
		val withArchive = StoredUsageRepricing.classify(row(rev = 1u, semanticsProfile = null))
		assertThat(withArchive.disposition).isEqualTo(Disposition.RENORMALIZE)
		assertThat(withArchive.reason).isEqualTo(Reason.SEMANTICS_MISSING)
		assertThat(withArchive.target).isNull()

		val withoutArchive = StoredUsageRepricing.classify(row(rev = 1u, semanticsProfile = null, archiveRef = null))
		assertThat(withoutArchive.disposition).isEqualTo(Disposition.UNRECOVERABLE)
		assertThat(withoutArchive.reason).isEqualTo(Reason.SEMANTICS_MISSING)

		// 배포되지 않은 개발 판은 프로파일 ID 가 있어도 의미를 보증하지 않는다.
		val development = StoredUsageRepricing.classify(row(rev = 5u))
		assertThat(development.disposition).isEqualTo(Disposition.RENORMALIZE)
		assertThat(development.reason).isEqualTo(Reason.UNSUPPORTED_NORMALIZER_REV)
	}

	@Test
	fun futureRevisionIsRejected() {
		assertThatThrownBy { StoredUsageRepricing.classify(row(rev = RowVersioning.NORMALIZER_REV + 1u)) }
			.isInstanceOf(StoredUsageRepricing.Stop::class.java)
	}

	@Test
	fun missingExtraAndTamperedColumnsAreRejected() {
		val input = encoded(row())
		assertThatThrownBy { StoredUsageRepricing.decode(input.deepCopy().also { it.remove("tokens_input") }) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { StoredUsageRepricing.decode(input.deepCopy().put("unexpected", 1)) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { StoredUsageRepricing.decode(input.deepCopy().put("tokens_input", "151")) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	fun legacyCheckpointMatchesHashesFromDeployedNormalizer() {
		// 변경 전 commit 9e36c7c의 컴파일된 Samples·PricingStage·AnalysisHash를 별도 JVM에서 실행한 고정 결과다.
		// 새 호환 함수로 기대값을 생성하지 않는다. 입력은 StoredUsageSamples.row(rev=9)다.
		val old = row(rev = 9u)
		assertThat(old.event.observation.envelope.analysisHash).isEqualTo("89fe0f1aef049a193ba532388ea8dcf7a2ec3a124e217ee239ecacde6bc57d4b")
		val priced = com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage().apply(old.event.observation)
		val legacy = "codeburn-0.9.24-reference-2026-10-16-v1"
		assertThat(AnalysisHash.ofLegacyPriced(priced, legacy)).isEqualTo("d876af0f32bb828b2b15d3881f21632e9a16383320daa983c7c79b53d8e236cd")
		val checkpoint = encoded(old).put("cost_estimated_usd", "0.001182500000")
			.put("pricing_version", legacy).put("analysis_hash", "d876af0f32bb828b2b15d3881f21632e9a16383320daa983c7c79b53d8e236cd")
		assertThat(StoredUsageRepricing.classify(StoredUsageRepricing.decode(checkpoint)).disposition).isEqualTo(Disposition.CURRENT)
	}

	@Test
	fun legacyVersionOnlyServesCheckpointValidationAndSameCostIsNotRewritten() {
		val old = row(rev = 9u)
		val priced = com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage().apply(old.event.observation)
		val legacy = priced.copy(envelope = priced.envelope.copy(analysisHash = AnalysisHash.ofLegacyPriced(priced, "legacy-price")))
		val source = old.copy(event = old.event.copy(observation = legacy))
		val json = encoded(source).put("pricing_version", "legacy-price")
		val decoded = StoredUsageRepricing.decode(json)
		assertThat(StoredUsageRepricing.classify(decoded).disposition).isEqualTo(Disposition.CURRENT)
		assertThat(StoredUsageRepricing.classify(decoded).target).isEqualTo(decoded)
		assertThatThrownBy { StoredUsageRepricing.decode(json.deepCopy().put("pricing_version", "tampered")) }
			.isInstanceOf(StoredUsageRepricing.Stop::class.java)
		assertThatThrownBy { StoredUsageRepricing.decode(json.deepCopy().also { it.remove("pricing_version") }) }
			.isInstanceOf(StoredUsageRepricing.Stop::class.java)
	}

	@Test
	fun deployedRevisionTenCheckpointKeepsItsHashAndEqualCostWithoutWriting() {
		// 배포 commit 6aaf3ec의 컴파일된 Samples·PricingStage·AnalysisHash를 별도 JVM에서 실행한 고정 값이다.
		val old = row(rev = 10u)
		assertThat(old.event.observation.envelope.analysisHash).isEqualTo("50f4847892f89f789fa160f3b53953d7379feac93a299b960fd6e4e3abc5f82d")
		val checkpoint = encoded(old).put("cost_estimated_usd", "0.001182500000")
			.put("analysis_hash", "428679a01d65c3ec8cf52c3240780820bb10d50987aa87376e7a7f091eb4a652")
		assertThat(checkpoint.has("pricing_version")).isFalse()
		val decoded = StoredUsageRepricing.decode(checkpoint)
		val planned = StoredUsageRepricing.classify(decoded)
		assertThat(planned.disposition).isEqualTo(Disposition.CURRENT)
		assertThat(planned.target).isEqualTo(decoded)
		assertThat(planned.target!!.version.normalizerRev).isEqualTo(10u)
		assertThat(StoredUsageRepricing.decode(checkpoint.deepCopy().putNull("pricing_version"))).isEqualTo(decoded)
		assertThatThrownBy { StoredUsageRepricing.decode(checkpoint.deepCopy().put("pricing_version", "not-allowed")) }
			.isInstanceOf(StoredUsageRepricing.Stop::class.java)
		// 금액만 바뀌는 명시 재평가도 rev10을 지원하며 ID·ingest_seq를 보존한다.
		val repriced = target(StoredUsageRepricing.decode(encoded(old)))
		assertThat(repriced.version).isEqualTo(RowVersioning.of(11u, 123u))
		assertThat(repriced.event.observation.envelope.observationId).isEqualTo(old.event.observation.envelope.observationId)
	}

	@Test
	fun newCheckpointRejectsUnexpectedVersionMetadata() {
		val current = target(row())
		assertThat(encoded(current).has("pricing_version")).isFalse()
		assertThatThrownBy { StoredUsageRepricing.decode(encoded(current).put("pricing_version", "not-allowed")) }
			.isInstanceOf(StoredUsageRepricing.Stop::class.java)
	}

	@Test
	fun longContextUsesOriginalPerRequestInput() {
		val old = row(model = "gpt-6-astra")
		val event = old.event.observation.copy(tokensInput = 300000, tokensInputUncached = 299950, tokensTotalDerived = 300020)
		val actual = target(old.copy(event = old.event.copy(observation = event.copy(envelope = event.envelope.copy(analysisHash = AnalysisHash.ofLegacyPriced(event, null))))))
		// 299950×20 + 40×2 + 10×25 + 20×75 = 6,000,830 / M
		assertThat(actual.event.observation.costEstimatedUsd).isEqualByComparingTo("6.000830")
	}
}
