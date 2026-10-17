package com.team376.pulsemetry.telemetry.maintenance

import com.sun.net.httpserver.HttpServer
import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.telemetry.adapter.observation.*
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CodeburnReferencePrices
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant

class StoredUsageRepricingTest {
	private val mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
	private fun source(model: String = "codex-auto-review"): StoredUsageRepricing.Row {
		val observation = EventObservation(
			envelope = ObservationEnvelope(
				tenantId = "00000000-0000-0000-0000-000000000001", installationId = "install",
				observationId = "a".repeat(64), analysisHash = AnalysisHash.PLACEHOLDER,
				identityVersion = ObservationIds.IDENTITY_VERSION, sourceIdentityKind = SourceIdentityKind.FINGERPRINT,
				mappingStatus = MappingStatus.MAPPED, sourceTime = EpochNanos.of(Instant.parse("2026-09-13T12:34:56.123456789Z")),
				sourceTimeOrigin = SourceTimeOrigin.OTLP_EVENT, receivedTime = EpochNanos.of(Instant.parse("2026-10-16T01:02:03.987654321Z")),
				signal = ObservationSignal.LOG, product = Product.CODEX, surface = Surface.CLI,
				mappingVersion = "test", usageRole = UsageRole.PRIMARY, usageScope = UsageScope.RESPONSE, workloadKind = WorkloadKind.MAIN,
				model = model, maskingVersion = "mask-v2", metadataJson = TypedMetadata.EMPTY.toJson(),
				archiveRef = "file:///private/original", archiveSelector = "bytes=0+100;path=0/0/0", sessionId = "session",
			),
			eventType = EventType.MODEL_RESPONSE_USAGE, tokensInput = 150, tokensInputUncached = 100,
			tokensOutput = 20, tokensCacheRead = 40, tokensCacheCreate = 10, tokensTotalDerived = 170,
			tokensTotalReported = 9_007_199_254_740_993L, costReportedUsd = BigDecimal("12345678901234567890123456.123456789012"), success = true,
			inputSemantics = InputSemantics.INCLUSIVE_CACHE, outputSemantics = OutputSemantics.INCLUSIVE_REASONING_TOOL,
			semanticsProfile = "codex-responses-inclusive-v1",
		)
		return StoredUsageRepricing.Row(EnrichedEvent(AnalysisHash.seal(observation), OrgAttribution("member", "team", listOf("team"), "{}", "org-v1")), RowVersioning.of(8u, 123u))
	}

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

	@Test
	fun preservesExactTypedSourceAndRepricesOnlyCostHashAndRevision() {
		val old = source()
		val decoded = StoredUsageRepricing.decode(encoded(old))
		assertThat(decoded).isEqualTo(old)
		val actual = StoredUsageRepricing.reprice(decoded)
		assertThat(actual.event.observation.costEstimatedUsd).isEqualByComparingTo("0.0011825")
		assertThat(actual.event.observation.pricingVersion).isEqualTo(CodeburnReferencePrices.VERSION)
		assertThat(actual.version).isEqualTo(RowVersioning.of(9u, 123u))
		assertThat(actual.event.org).isEqualTo(old.event.org)
		assertThat(actual.event.observation.copy(
			costEstimatedUsd = null, pricingVersion = null,
			envelope = actual.event.observation.envelope.copy(analysisHash = old.event.observation.envelope.analysisHash),
		)).isEqualTo(old.event.observation)
		assertThat(AnalysisHash.of(actual.event.observation)).isEqualTo(actual.event.observation.envelope.analysisHash)
		val restored = StoredUsageRepricing.decode(encoded(actual))
		assertThat(restored.event.observation.costEstimatedUsd).isEqualByComparingTo(actual.event.observation.costEstimatedUsd)
		assertThat(restored.event.observation.envelope.analysisHash).isEqualTo(actual.event.observation.envelope.analysisHash)
		assertThat(StoredUsageRepricing.reprice(restored)).isEqualTo(restored)
		val storedDecimal = StoredUsageRepricing.decode(encoded(actual).put("cost_estimated_usd", "0.001182500000"))
		assertThat(StoredUsageRepricing.reprice(storedDecimal)).isEqualTo(storedDecimal)
	}

	@Test
	fun repeatedTargetIsIdenticalButConflictingCurrentRevisionIsRejected() {
		val once = StoredUsageRepricing.reprice(source())
		assertThat(StoredUsageRepricing.reprice(once)).isEqualTo(once)
		assertThatThrownBy { StoredUsageRepricing.reprice(source().copy(version = RowVersioning.of(9u, 123u))) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	fun unknownModelAndFutureRevisionAreRejected() {
		assertThatThrownBy { StoredUsageRepricing.reprice(source("unknown-model")) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { StoredUsageRepricing.reprice(source().copy(version = RowVersioning.of(10u, 123u))) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	fun missingExtraAndTamperedColumnsAreRejected() {
		val input = encoded(source())
		assertThatThrownBy { StoredUsageRepricing.decode(input.deepCopy().also { it.remove("tokens_input") }) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { StoredUsageRepricing.decode(input.deepCopy().put("unexpected", 1)) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { StoredUsageRepricing.decode(input.deepCopy().put("tokens_input", "151")) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	fun longContextUsesOriginalPerRequestInput() {
		val old = source("gpt-6-astra")
		val event = old.event.observation.copy(tokensInput = 300000, tokensInputUncached = 299950, tokensTotalDerived = 300020)
		val actual = StoredUsageRepricing.reprice(old.copy(event = old.event.copy(observation = AnalysisHash.seal(event))))
		// 299950×20 + 40×2 + 10×25 + 20×75 = 6,000,830 / M
		assertThat(actual.event.observation.costEstimatedUsd).isEqualByComparingTo("6.000830")
	}
}
