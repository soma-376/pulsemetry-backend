package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CurrentPublicPrices
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class CurrentReferencePricingTest {
	private fun usage(model: String = "claude-opus-5-5", create: Long? = 0) = EventObservation(
		envelope = ObservationEnvelope(
			tenantId = "test", installationId = "test", observationId = "a".repeat(64), analysisHash = AnalysisHash.PLACEHOLDER,
			identityVersion = ObservationIds.IDENTITY_VERSION, sourceIdentityKind = SourceIdentityKind.FINGERPRINT,
			mappingStatus = MappingStatus.MAPPED, sourceTime = EpochNanos(1_000), sourceTimeOrigin = SourceTimeOrigin.OTLP_EVENT,
			receivedTime = EpochNanos(2_000), signal = ObservationSignal.LOG, product = Product.CLAUDE_CODE, surface = Surface.CLI,
			mappingVersion = "test", usageRole = UsageRole.PRIMARY, usageScope = UsageScope.RESPONSE, workloadKind = WorkloadKind.MAIN,
			model = model, maskingVersion = "masking-v2", metadataJson = TypedMetadata.EMPTY.toJson(),
		),
		eventType = EventType.MODEL_RESPONSE_USAGE,
		tokensInput = 100, tokensOutput = 20, tokensCacheRead = 40, tokensCacheCreate = create,
		tokensInputUncached = 100, tokensTotalDerived = create?.let { 160 + it },
		inputSemantics = InputSemantics.EXCLUSIVE_CACHE, outputSemantics = OutputSemantics.INCLUSIVE_REASONING_TOOL,
		semanticsProfile = "claude-code-exclusive-v1", costReportedUsd = BigDecimal("123.456"),
	)
	private fun priced(event: EventObservation = usage()) = PricingStage().apply(event)
	private fun codexUsage(input: Long = 150, model: String = "gpt-6-astra") = usage(model, create = 10).let {
		it.copy(
			envelope = it.envelope.copy(product = Product.CODEX, surface = Surface.APP_SERVER),
			tokensInput = input, tokensInputUncached = input - 50, tokensTotalDerived = input + 20,
			tokensReasoning = 5, tokensTool = input + 20,
			inputSemantics = InputSemantics.INCLUSIVE_CACHE, semanticsProfile = "codex-responses-inclusive-v1",
		)
	}

	@ParameterizedTest
	@CsvSource(
		// 독립 산식: (100×input + 20×output + 40×read) / 1,000,000. API나 제품 함수를 기대값으로 쓰지 않는다.
		"claude-opus-5-5,0.000808",
		"claude-opus-5,0.001020",
		"claude-fable-5-1,0.002010",
		"claude-haiku-4-5-20251001,0.000204",
		"claude-sonnet-5-5,0.000404",
		"claude-opus-4-8,0.001020",
	)
	fun officialComponentArithmetic(model: String, expected: String) {
		val input = usage(model)
		val result = priced(input)
		assertThat(result.costEstimatedUsd).isEqualByComparingTo(expected)
		assertThat(result.copy(costEstimatedUsd = null)).isEqualTo(input)
	}

	@Test
	fun catalogAppliesIndependentOfEventTimeWithoutChangingSource() {
		val old = usage()
		val recent = old.copy(envelope = old.envelope.copy(sourceTime = EpochNanos.of(java.time.Instant.parse("2026-10-29T00:00:00Z"))))
		assertThat(CurrentPublicPrices.REFERENCE.asOf).isGreaterThan(old.envelope.sourceTime)
		assertThat(priced(old).costEstimatedUsd).isEqualByComparingTo(priced(recent).costEstimatedUsd)
		assertThat(priced(old).envelope).isEqualTo(old.envelope)
	}

	@ParameterizedTest
	@CsvSource(
		// 각 기존 산식에 명시 cache-create 10 × 공식 5분 단가 / 1,000,000을 더한 독립 기대값이다.
		"claude-opus-5-5,0.000858",
		"claude-opus-5,0.0010825",
		"claude-fable-5-1,0.002135",
		"claude-haiku-4-5-20251001,0.0002165",
		"claude-sonnet-5-5,0.000429",
		"claude-opus-4-8,0.0010825",
	)
	fun claudeCacheCreationUsesExplicitFiveMinuteReference(model: String, expected: String) {
		assertThat(priced(usage(model, create = 10)).costEstimatedUsd).isEqualByComparingTo(expected)
		assertThat(CurrentPublicPrices.PRICES.single { it.model == model }.cacheCreateReferenceTtlSeconds).isEqualTo(300)
	}

	@Test
	fun missingComponentsRemainUnknown() {
		assertThat(priced(usage(create = null)).costEstimatedUsd).isNull()
		assertThat(priced(usage().copy(tokensOutput = null)).costEstimatedUsd).isNull()
		assertThat(priced(usage().copy(tokensCacheRead = null)).costEstimatedUsd).isNull()
		assertThat(priced(usage().copy(tokensInputUncached = null)).costEstimatedUsd).isNull()
		assertThat(priced(codexUsage().copy(tokensCacheCreate = null)).costEstimatedUsd).isNull()
		assertThat(priced(codexUsage().copy(tokensInputUncached = null)).costEstimatedUsd).isNull()
	}

	@Test
	fun codexUsesDisjointComponentsAndDoesNotAddReasoningOrToolAgain() {
		// (100 uncached × 10 + 40 read × 1 + 10 create × 12.5 + 20 output × 50) / 1,000,000.
		val input = codexUsage()
		val result = priced(input)
		assertThat(result.costEstimatedUsd).isEqualByComparingTo("0.002165")
		assertThat(result.copy(costEstimatedUsd = null)).isEqualTo(input)
		assertThat(priced(input.copy(tokensReasoning = null, tokensTool = null)).costEstimatedUsd).isEqualByComparingTo("0.002165")
		assertThat(CurrentPublicPrices.PRICES.single { it.model == "gpt-6-astra" }.cacheCreateReferenceTtlSeconds).isNull()
	}

	@ParameterizedTest
	@CsvSource("271999,2.720655", "272000,2.720665", "272001,5.440850")
	fun codexLongContextBoundaryUsesFullPromptAndAllApplicableRates(input: Long, expected: String) {
		// input에는 read40/create10이 포함된다. 초과 시 uncached/read/create는2배, output20은1.5배다.
		assertThat(priced(codexUsage(input)).costEstimatedUsd).isEqualByComparingTo(expected)
	}

	@ParameterizedTest
	@CsvSource(
		// (100 uncached × input + 40 read × read + 10 write × write + 20 output × output) / 1M.
		"gpt-5.6-luna,150,0.0000473",
		"gpt-6-luna,150,0.00002165",
		// >272000에서만 모든 input/cache 2배, output 1.5배. 경계 바로 양쪽을 독립 산식으로 고정한다.
		"gpt-5.6-luna,271999,0.0544171",
		"gpt-5.6-luna,272000,0.0544173",
		"gpt-5.6-luna,272001,0.1088230",
		"gpt-6-luna,271999,0.02720655",
		"gpt-6-luna,272000,0.02720665",
		"gpt-6-luna,272001,0.05440850",
	)
	fun lunaPricesUsePublicComponentsAndRequestLength(model: String, input: Long, expected: String) {
		val original = codexUsage(input, model)
		val actual = priced(original)
		assertThat(actual.costEstimatedUsd).isEqualByComparingTo(expected)
		assertThat(actual.copy(costEstimatedUsd = null)).isEqualTo(original)
	}

	@Test
	fun lunaMissingComponentsAndUnknownAliasesStayUnknown() {
		for (model in listOf("gpt-5.6-luna", "gpt-6-luna")) {
			val original = codexUsage(model = model)
			assertThat(priced(original.copy(tokensCacheCreate = null)).costEstimatedUsd).isNull()
			assertThat(priced(original.copy(semanticsProfile = "unknown")).costEstimatedUsd).isNull()
			assertThat(priced(codexUsage(model = model + "-alias")).costEstimatedUsd).isNull()
		}
	}

	@Test
	fun unknownSemanticsModelAndNonUsageNeverGetFallback() {
		assertThat(priced(usage("gpt-6-astra").copy(semanticsProfile = null)).costEstimatedUsd).isNull()
		assertThat(priced(usage("e2e-unknown-model-a")).costEstimatedUsd).isNull()
		assertThat(priced(usage("claude-opus-5-5-prefix")).costEstimatedUsd).isNull()
		assertThat(priced(usage().copy(semanticsProfile = "unverified")).costEstimatedUsd).isNull()
		assertThat(priced(usage().copy(outputSemantics = OutputSemantics.UNKNOWN)).costEstimatedUsd).isNull()
		assertThat(priced(usage().copy(eventType = EventType.MODEL_REQUEST_ERROR)).costEstimatedUsd).isNull()
	}

	@Test
	fun explicitZeroKeepsReportedCostSeparate() {
		val zero = usage().copy(tokensInput = 0, tokensInputUncached = 0, tokensOutput = 0, tokensCacheRead = 0, tokensTotalDerived = 0)
		assertThat(priced(zero).costEstimatedUsd).isEqualByComparingTo("0")
		assertThat(priced(zero).costReportedUsd).isEqualByComparingTo("123.456")
	}

	@Test
	fun hashChangesWithCalculatedCostButSourceIdentityDoesNot() {
		val before = AnalysisHash.seal(usage())
		val after = AnalysisHash.seal(priced())
		assertThat(after.envelope.analysisHash).isNotEqualTo(before.envelope.analysisHash)
		assertThat(after.envelope.observationId).isEqualTo(before.envelope.observationId)
		assertThat(AnalysisHash.seal(priced())).isEqualTo(after)
	}
}
