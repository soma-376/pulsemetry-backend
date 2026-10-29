package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CodeburnReferencePrices
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CurrentPublicPrices
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

class CodeburnReferencePricingTest {
	private val stage = com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage()
	private fun usage(model: String, claude: Boolean = false) = EventObservation(
		envelope = ObservationEnvelope(
			tenantId = "test", installationId = "test", observationId = "a".repeat(64), analysisHash = AnalysisHash.PLACEHOLDER,
			identityVersion = ObservationIds.IDENTITY_VERSION, sourceIdentityKind = SourceIdentityKind.FINGERPRINT,
			mappingStatus = MappingStatus.MAPPED, sourceTime = EpochNanos(1_000), sourceTimeOrigin = SourceTimeOrigin.OTLP_EVENT,
			receivedTime = EpochNanos(2_000), signal = ObservationSignal.LOG,
			product = if (claude) Product.CLAUDE_CODE else Product.CODEX, surface = Surface.CLI,
			mappingVersion = "test", usageRole = UsageRole.PRIMARY, usageScope = UsageScope.RESPONSE, workloadKind = WorkloadKind.MAIN,
			model = model, maskingVersion = "masking-v2", metadataJson = TypedMetadata.EMPTY.toJson(),
		),
		eventType = EventType.MODEL_RESPONSE_USAGE,
		tokensInput = if (claude) 1_000_000 else 3_000_000, tokensOutput = 1_000_000,
		tokensInputUncached = 1_000_000, tokensCacheRead = 1_000_000, tokensCacheCreate = 1_000_000,
		tokensTotalDerived = 4_000_000, tokensReasoning = 100, tokensTool = 4_000_000,
		inputSemantics = if (claude) InputSemantics.EXCLUSIVE_CACHE else InputSemantics.INCLUSIVE_CACHE,
		outputSemantics = OutputSemantics.INCLUSIVE_REASONING_TOOL,
		semanticsProfile = if (claude) "claude-code-exclusive-v1" else "codex-responses-inclusive-v1",
		costReportedUsd = BigDecimal("999"),
	)

	@TestFactory
	fun codeburnDistributedPriceFunctionParity(): List<DynamicTest> {
		// 우리 산식에서 기대값을 생성하지 않는다. Codeburn 배포 함수의 고정 실행 결과다.
		val expected = javaClass.getResourceAsStream("/pricing/codeburn-0.9.24-parity.tsv")!!.bufferedReader().useLines { lines ->
			lines.drop(1).filter { it.isNotBlank() }.associate { it.split('\t').let { parts -> parts[0] to parts[1] } }
		}
		assertThat(expected.keys).containsExactlyInAnyOrderElementsOf(CodeburnReferencePrices.ENTRIES.map { it.model })
		val legacyModels = CurrentPublicPrices.PRICES.map { it.model }.toSet()
		return CodeburnReferencePrices.ENTRIES.filter { it.model !in legacyModels }.map { entry ->
			DynamicTest.dynamicTest(entry.model) {
				val input = usage(entry.model, "claude-code-exclusive-v1" in entry.price.semanticsProfiles)
				val result = stage.apply(input)
				assertThat(result.costEstimatedUsd).isEqualByComparingTo(expected.getValue(entry.model))
				assertThat(result.envelope).isEqualTo(input.envelope)
				assertThat(result.costReportedUsd).isEqualTo(input.costReportedUsd)
			}
		}
	}

	@Test
	fun autoReviewUsesExplicitGpt55ReferenceWithoutChangingSourceModelOrDoubleCounting() {
		val entry = CodeburnReferencePrices.ENTRIES.single { it.model == "codex-auto-review" }
		assertThat(entry.referenceModel).isEqualTo("gpt-5.5")
		// 100 uncached×5 + 40 read×0.5 + 10 create×6.25 + 20 output×30 = 1182.5 / M
		val input = usage("codex-auto-review").copy(
			tokensInput = 150, tokensInputUncached = 100, tokensOutput = 20,
			tokensCacheRead = 40, tokensCacheCreate = 10, tokensTotalDerived = 170,
		)
		assertThat(stage.apply(input).costEstimatedUsd).isEqualByComparingTo("0.0011825")
		assertThat(stage.apply(input).envelope.model).isEqualTo("codex-auto-review")
	}

	@Test
	fun allPreviouslyVerifiedPricesAndLongContextRulesRemainIntact() {
		for (old in CurrentPublicPrices.PRICES) {
			val selected = CodeburnReferencePrices.CATALOG.prices.single { it.model == old.model }
			assertThat(selected).isEqualTo(old)
		}
		assertThat(CodeburnReferencePrices.ENTRIES).hasSize(315)
		assertThat(CodeburnReferencePrices.CATALOG.prices.map { it.model }).doesNotHaveDuplicates()
	}

	@Test
	fun missingComponentsUnverifiedSemanticsAndUnknownModelsStayUnknown() {
		val input = usage("codex-auto-review")
		for (invalid in listOf(
			input.copy(tokensCacheCreate = null), input.copy(tokensInputUncached = null), input.copy(tokensCacheRead = null),
			input.copy(tokensOutput = null), input.copy(semanticsProfile = "unverified"),
			input.copy(envelope = input.envelope.copy(model = "codex-auto-review-made-up")),
			usage("gpt-image-2"), usage("gpt-5.5:batch"),
		)) assertThat(stage.apply(invalid).costEstimatedUsd).isNull()
	}
}
