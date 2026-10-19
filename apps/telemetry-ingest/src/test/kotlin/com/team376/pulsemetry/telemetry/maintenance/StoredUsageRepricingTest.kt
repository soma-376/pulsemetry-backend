package com.team376.pulsemetry.telemetry.maintenance

import com.sun.net.httpserver.HttpServer
import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.telemetry.adapter.observation.*
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CodeburnReferencePrices
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
			return mapper.readTree(body) as ObjectNode
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
		assertThat(actual.event.observation.pricingVersion).isEqualTo(CodeburnReferencePrices.VERSION)
		assertThat(actual.version).isEqualTo(RowVersioning.of(RowVersioning.NORMALIZER_REV, 123u))
		assertThat(actual.event.org).isEqualTo(old.event.org)
		assertThat(actual.event.observation.copy(
			costEstimatedUsd = null, pricingVersion = null,
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
		assertThat(planned.disposition).isEqualTo(Disposition.REPRICE)
		assertThat(planned.reason).isEqualTo(Reason.PRICE_UNAVAILABLE)
		assertThat(planned.target!!.event.observation.costEstimatedUsd).isNull()
		assertThat(planned.target!!.event.observation.pricingVersion).isNull()
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
	fun longContextUsesOriginalPerRequestInput() {
		val old = row(model = "gpt-6-astra")
		val event = old.event.observation.copy(tokensInput = 300000, tokensInputUncached = 299950, tokensTotalDerived = 300020)
		val actual = target(old.copy(event = old.event.copy(observation = AnalysisHash.seal(event))))
		// 299950×20 + 40×2 + 10×25 + 20×75 = 6,000,830 / M
		assertThat(actual.event.observation.costEstimatedUsd).isEqualByComparingTo("6.000830")
	}
}
