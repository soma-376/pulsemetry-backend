package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CurrentPublicPrices
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingBasis
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingProfile
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
	private fun priced(event: EventObservation = usage()) = CurrentPublicPrices.select(CurrentPublicPrices.VERSION).apply(event)
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
		assertThat(result.pricingVersion).isEqualTo(CurrentPublicPrices.VERSION)
		assertThat(result.copy(costEstimatedUsd = null, pricingVersion = null)).isEqualTo(input)
	}

	@Test
	fun fixedReferenceDoesNotFabricateHistoricalValidity() {
		val catalog = CurrentPublicPrices.PROFILE
		assertThat(catalog.basis).isEqualTo(PricingBasis.CURRENT_REFERENCE)
		assertThat(catalog.reference!!.asOf).isGreaterThan(usage().envelope.sourceTime)
		val historical = PricingStage(PricingProfile("historical-same-rates", catalog.prices))
		assertThat(historical.apply(usage()).costEstimatedUsd).isNull()
		assertThat(priced().costEstimatedUsd).isEqualByComparingTo("0.000808")
	}

	@Test
	fun earlierCatalogRemainsSelectableWithSameRatesAndReference() {
		val v1 = CurrentPublicPrices.PROFILE_V1
		assertThat(v1.pricingVersion).isEqualTo("public-standard-global-reference-2026-10-16-v1")
		assertThat(CurrentPublicPrices.AS_OF_V1.toString()).isEqualTo("2026-10-15T23:02:00Z")
		assertThat(v1.prices.map { it.model }).containsExactly("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-haiku-4-5-20251001")
		val oldStage = CurrentPublicPrices.select(CurrentPublicPrices.VERSION_V1)
		listOf("claude-opus-5-5" to "0.000808", "claude-opus-5" to "0.001020", "claude-fable-5-1" to "0.002010", "claude-haiku-4-5-20251001" to "0.000204").forEach { (model, expected) ->
			assertThat(oldStage.apply(usage(model)).costEstimatedUsd).isEqualByComparingTo(expected)
			assertThat(oldStage.apply(usage(model)).pricingVersion).isEqualTo(v1.pricingVersion)
		}
		assertThat(oldStage.apply(usage("claude-sonnet-5-5")).costEstimatedUsd).isNull()
		assertThat(oldStage.apply(usage("claude-opus-4-8")).costEstimatedUsd).isNull()
		val v2 = CurrentPublicPrices.PROFILE_V2
		assertThat(v2.pricingVersion).isEqualTo("public-standard-global-reference-2026-10-16-v2")
		assertThat(CurrentPublicPrices.AS_OF_V2.toString()).isEqualTo("2026-10-15T23:29:51Z")
		assertThat(v2.prices).hasSize(6)
		assertThat(v2.reference!!.asOf).isGreaterThan(v1.reference!!.asOf)
		val v2Stage = CurrentPublicPrices.select(CurrentPublicPrices.VERSION_V2)
		assertThat(v2Stage.apply(usage("claude-sonnet-5-5")).costEstimatedUsd).isEqualByComparingTo("0.000404")
		assertThat(v2Stage.apply(usage("claude-opus-4-8")).costEstimatedUsd).isEqualByComparingTo("0.001020")
		for (stage in listOf(oldStage, v2Stage)) {
			assertThat(stage.apply(usage(create = 10)).costEstimatedUsd).isNull()
			assertThat(stage.apply(usage(create = null)).costEstimatedUsd).isNull()
			assertThat(stage.apply(codexUsage()).costEstimatedUsd).isNull()
		}
		assertThat((v1.prices + v2.prices).all { it.cacheCreatePerMillion == null && it.cacheCreateReferenceTtlSeconds == null && it.longContext == null }).isTrue()
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
		assertThat(CurrentPublicPrices.PROFILE.prices.single { it.model == model }.cacheCreateReferenceTtlSeconds).isEqualTo(300)
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
		assertThat(result.copy(costEstimatedUsd = null, pricingVersion = null)).isEqualTo(input)
		assertThat(priced(input.copy(tokensReasoning = null, tokensTool = null)).costEstimatedUsd).isEqualByComparingTo("0.002165")
		assertThat(CurrentPublicPrices.PROFILE.prices.single { it.model == "gpt-6-astra" }.cacheCreateReferenceTtlSeconds).isNull()
	}

	@ParameterizedTest
	@CsvSource("271999,2.720655", "272000,2.720665", "272001,5.440850")
	fun codexLongContextBoundaryUsesFullPromptAndAllApplicableRates(input: Long, expected: String) {
		// input에는 read40/create10이 포함된다. 초과 시 uncached/read/create는2배, output20은1.5배다.
		assertThat(priced(codexUsage(input)).costEstimatedUsd).isEqualByComparingTo(expected)
	}

	@Test
	fun v3RetainsOriginalRatesReferenceAndSupportBoundary() {
		val old = CurrentPublicPrices.PROFILE_V3
		val stage = CurrentPublicPrices.select(CurrentPublicPrices.VERSION_V3)
		assertThat(old.prices).hasSize(7)
		assertThat(CurrentPublicPrices.AS_OF_V3.toString()).isEqualTo("2026-10-16T06:59:11Z")
		assertThat(stage.apply(codexUsage()).costEstimatedUsd).isEqualByComparingTo("0.002165")
		assertThat(stage.apply(usage(create = 10)).costEstimatedUsd).isEqualByComparingTo("0.000858")
		assertThat(stage.apply(codexUsage(model = "gpt-5.6-luna")).costEstimatedUsd).isNull()
		assertThat(stage.apply(codexUsage(model = "gpt-6-luna")).costEstimatedUsd).isNull()
		assertThat(old.prices).isEqualTo(CurrentPublicPrices.PROFILE.prices.take(7).mapIndexed { index, price ->
			price.copy(validFrom = old.prices[index].validFrom)
		})
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
		assertThat(actual.copy(costEstimatedUsd = null, pricingVersion = null)).isEqualTo(original)
	}

	@Test
	fun lunaMissingComponentsAndUnpricedReviewModelStayUnknown() {
		for (model in listOf("gpt-5.6-luna", "gpt-6-luna")) {
			val original = codexUsage(model = model)
			assertThat(priced(original.copy(tokensCacheCreate = null)).costEstimatedUsd).isNull()
			assertThat(priced(original.copy(semanticsProfile = "unknown")).costEstimatedUsd).isNull()
			assertThat(priced(codexUsage(model = model + "-alias")).costEstimatedUsd).isNull()
		}
		// 같은 역할에 API-key 경로가 Luna를 선택해도 codex-auto-review의 모델/단가 동일성은 아니다.
		assertThat(priced(codexUsage(model = "codex-auto-review")).costEstimatedUsd).isNull()
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
	fun explicitZeroKeepsVersionAndReportedCostSeparate() {
		val zero = usage().copy(tokensInput = 0, tokensInputUncached = 0, tokensOutput = 0, tokensCacheRead = 0, tokensTotalDerived = 0)
		assertThat(priced(zero).costEstimatedUsd).isEqualByComparingTo("0")
		assertThat(priced(zero).pricingVersion).isEqualTo(CurrentPublicPrices.VERSION)
		assertThat(priced(zero).costReportedUsd).isEqualByComparingTo("123.456")
	}

	@Test
	fun referenceConfigurationIsExplicitAndUnknownNamesFailClosed() {
		assertThat(CurrentPublicPrices.PROFILE.pricingVersion).isEqualTo("public-standard-global-reference-2026-10-16-v4")
		assertThat(CurrentPublicPrices.AS_OF.toString()).isEqualTo("2026-10-16T08:03:52Z")
		assertThat(CurrentPublicPrices.PROFILE.prices).hasSize(9)
		assertThat(CurrentPublicPrices.select("none").apply(usage()).costEstimatedUsd).isNull()
		assertThatThrownBy { CurrentPublicPrices.select("typo") }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { PricingProfile("invalid", emptyList(), PricingBasis.CURRENT_REFERENCE) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { PricingProfile("invalid", emptyList(), reference = CurrentPublicPrices.PROFILE.reference) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	fun hashChangesWithPriceVersionButSourceIdentityDoesNot() {
		val before = AnalysisHash.seal(usage())
		val after = AnalysisHash.seal(priced())
		assertThat(after.envelope.analysisHash).isNotEqualTo(before.envelope.analysisHash)
		assertThat(after.envelope.observationId).isEqualTo(before.envelope.observationId)
		assertThat(AnalysisHash.seal(priced())).isEqualTo(after)
	}
}
