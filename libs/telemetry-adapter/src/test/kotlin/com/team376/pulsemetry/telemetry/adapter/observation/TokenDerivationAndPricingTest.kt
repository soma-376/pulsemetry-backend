package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.pricing.ModelPrice
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingCatalog
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.CacheWriteStatus
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.SemanticsProfile
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.SemanticsScope
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.TokenDerivation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

/**
 * 파생 토큰과 가격 단계(ADR 0020 §4). 기대값은 ADR 의 산식 표와 설명용 예시(input 100·output 20·cache read 40·
 * cache create 0 → inclusive 120/60, exclusive 160/100)에서 계산했다. 여기 쓰는 프로파일은 **테스트용**이며
 * 어떤 producer 의 검증도 주장하지 않는다.
 */
class TokenDerivationAndPricingTest {

	private fun profile(
		input: InputSemantics,
		output: OutputSemantics = OutputSemantics.INCLUSIVE_REASONING_TOOL,
		write: CacheWriteStatus = CacheWriteStatus.REPORTED,
		id: String = "test-${input.wire}-${output.wire}-${write.wire}",
	) = SemanticsProfile(id, input, output, write, SemanticsScope(Product.CODEX, "test", "unknown", "test"), "test/EVIDENCE.md")

	private fun usage(
		input: Long? = 100,
		output: Long? = 20,
		read: Long? = 40,
		create: Long? = 0,
		reasoning: Long? = null,
		tool: Long? = null,
		reportedTotal: Long? = null,
		model: String? = "model-a",
		eventType: EventType = EventType.MODEL_RESPONSE_USAGE,
	) = EventObservation(
		envelope = ObservationEnvelope(
			tenantId = "t", installationId = "i", observationId = "b".repeat(64), analysisHash = AnalysisHash.PLACEHOLDER,
			identityVersion = ObservationIds.IDENTITY_VERSION, sourceIdentityKind = SourceIdentityKind.FINGERPRINT,
			mappingStatus = MappingStatus.MAPPED, sourceTime = EpochNanos(1_000), sourceTimeOrigin = SourceTimeOrigin.OTLP_EVENT,
			receivedTime = EpochNanos(2_000), signal = ObservationSignal.LOG, product = Product.CODEX, surface = Surface.CLI,
			mappingVersion = "test", usageRole = UsageRole.PRIMARY, usageScope = UsageScope.RESPONSE, workloadKind = WorkloadKind.MAIN,
			model = model, maskingVersion = "masking-v2", metadataJson = TypedMetadata.EMPTY.toJson(),
		),
		eventType = eventType,
		tokensInput = input, tokensOutput = output, tokensCacheRead = read, tokensCacheCreate = create,
		tokensReasoning = reasoning, tokensTool = tool, tokensTotalReported = reportedTotal,
	)

	// ── 파생 토큰 ────────────────────────────────────────────────────────────

	@ParameterizedTest(name = "{0} / write {1} / create {2} -> uncached {3}, total {4}")
	@CsvSource(
		nullValues = ["null"],
		value = [
			// input 의미,       cache write,     create, uncached, total
			"INCLUSIVE_CACHE,   REPORTED,        0,      60,       120", // 설명용 예시
			"EXCLUSIVE_CACHE,   REPORTED,        0,      100,      160", // 설명용 예시
			"INCLUSIVE_CACHE,   NOT_APPLICABLE,  null,   60,       120", // write 해당 없음 → input − cache_read
			"EXCLUSIVE_CACHE,   NOT_APPLICABLE,  null,   100,      160", // input + cache_read + output
			"INCLUSIVE_CACHE,   REPORTED,        null,   null,     120", // 미보고 write 를 0 으로 추정하지 않는다
			"EXCLUSIVE_CACHE,   REPORTED,        null,   100,      null",
			"INCLUSIVE_CACHE,   REPORTED,        10,     50,       120",
			"EXCLUSIVE_CACHE,   REPORTED,        10,     100,      170",
			"INCLUSIVE_CACHE,   UNKNOWN,         null,   null,     120", // write 를 모르면 uncached 를 못 뺀다
			"UNKNOWN,           REPORTED,        0,      null,     null",
		],
	)
	@DisplayName("ADR 0020 §4 의 산식 표")
	fun derivationTable(input: InputSemantics, write: CacheWriteStatus, create: Long?, uncached: Long?, total: Long?) {
		val derived = TokenDerivation.apply(usage(create = create), profile(input, write = write))

		assertThat(derived.tokensInputUncached).isEqualTo(uncached)
		assertThat(derived.tokensTotalDerived).isEqualTo(total)
		// 보고 성분은 그대로다.
		assertThat(listOf(derived.tokensInput, derived.tokensOutput, derived.tokensCacheRead, derived.tokensCacheCreate))
			.containsExactly(100L, 20L, 40L, create)
		assertThat(derived.inputSemantics).isEqualTo(input)
	}

	@Test
	@DisplayName("프로파일이 없으면 파생값 null, 의미 unknown, semantics_profile null, 사용량이면 usage_semantics_unverified")
	fun noProfile() {
		val derived = TokenDerivation.apply(usage(), null)

		assertThat(derived.tokensInputUncached).isNull()
		assertThat(derived.tokensTotalDerived).isNull()
		assertThat(derived.inputSemantics).isEqualTo(InputSemantics.UNKNOWN)
		assertThat(derived.semanticsProfile).isNull()
		assertThat(derived.envelope.qualityFlags).containsExactly(QualityFlag.USAGE_SEMANTICS_UNVERIFIED)
	}

	@Test
	@DisplayName("input 의미가 unknown 인 프로파일도 unverified 다 — 프로파일 ID 는 남는다")
	fun unknownInputSemanticsIsUnverified() {
		val derived = TokenDerivation.apply(usage(), profile(InputSemantics.UNKNOWN))

		assertThat(derived.semanticsProfile).isEqualTo("test-unknown-inclusive_reasoning_tool-reported")
		assertThat(derived.envelope.qualityFlags).containsExactly(QualityFlag.USAGE_SEMANTICS_UNVERIFIED)
	}

	@Test
	@DisplayName("해당 없음으로 확정했는데 cache write 가 보고됐다 — 프로파일이 맞지 않는다: 파생값 null + derived_value_invalid")
	fun notApplicableButReported() {
		val derived = TokenDerivation.apply(usage(create = 5), profile(InputSemantics.INCLUSIVE_CACHE, write = CacheWriteStatus.NOT_APPLICABLE))

		assertThat(derived.tokensInputUncached).isNull()
		assertThat(derived.tokensTotalDerived).isNull()
		assertThat(derived.envelope.qualityFlags).containsExactly(QualityFlag.DERIVED_VALUE_INVALID)
	}

	@Test
	@DisplayName("포함관계 위반(캐시가 input 보다 큼)은 파생 input과 total을 null로 하고 환산하지 않는다")
	fun inclusionViolation() {
		val derived = TokenDerivation.apply(usage(input = 30, read = 40), profile(InputSemantics.INCLUSIVE_CACHE))

		assertThat(derived.tokensInputUncached).isNull()
		assertThat(derived.tokensTotalDerived).isNull()
		assertThat(derived.envelope.qualityFlags).containsExactly(QualityFlag.DERIVED_VALUE_INVALID)
		assertThat(priced(PricingStage(PricingCatalog(listOf(price()))), derived).costEstimatedUsd).isNull()
	}

	@ParameterizedTest
	@CsvSource(nullValues = ["null"], value = ["40, null", "null, 40", "20, 20"])
	@DisplayName("다른 캐시 성분이 미보고여도 보고 성분의 포함관계 위반은 파생 total을 막는다")
	fun cacheInclusionViolationWithMissingCounterpart(read: Long?, create: Long?) {
		val derived = TokenDerivation.apply(usage(input = 30, read = read, create = create, reportedTotal = 50), inclusive)

		assertThat(derived.tokensInputUncached).isNull()
		assertThat(derived.tokensTotalDerived).isNull()
		assertThat(derived.tokensInput).isEqualTo(30)
		assertThat(derived.tokensCacheRead).isEqualTo(read)
		assertThat(derived.tokensCacheCreate).isEqualTo(create)
		assertThat(derived.tokensTotalReported).isEqualTo(50)
		assertThat(derived.envelope.qualityFlags).containsExactly(QualityFlag.DERIVED_VALUE_INVALID)
		assertThat(priced(PricingStage(PricingCatalog(listOf(price()))), derived).costEstimatedUsd).isNull()
	}

	@ParameterizedTest
	@CsvSource(nullValues = ["null"], value = ["0, null", "null, 0", "30, null", "null, 30"])
	@DisplayName("보고 캐시 성분이 input을 넘지 않으면 다른 성분의 누락은 모순으로 단정하지 않는다")
	fun missingCacheCounterpartRemainsUnknown(read: Long?, create: Long?) {
		val derived = TokenDerivation.apply(usage(input = 30, read = read, create = create, reportedTotal = 50), inclusive)

		assertThat(derived.tokensInputUncached).isNull()
		assertThat(derived.tokensTotalDerived).isEqualTo(50)
		assertThat(derived.tokensCacheRead).isEqualTo(read)
		assertThat(derived.tokensCacheCreate).isEqualTo(create)
		assertThat(derived.envelope.qualityFlags).doesNotContain(QualityFlag.DERIVED_VALUE_INVALID)
		assertThat(priced(PricingStage(PricingCatalog(listOf(price()))), derived).costEstimatedUsd).isNull()
	}

	@Test
	@DisplayName("overflow 는 null + derived_value_invalid — 최댓값으로 자르지 않는다")
	fun overflow() {
		val derived = TokenDerivation.apply(usage(input = Long.MAX_VALUE, read = 0), profile(InputSemantics.INCLUSIVE_CACHE))

		assertThat(derived.tokensTotalDerived).isNull()
		assertThat(derived.tokensInputUncached).isEqualTo(Long.MAX_VALUE)
		assertThat(derived.envelope.qualityFlags).containsExactly(QualityFlag.DERIVED_VALUE_INVALID)
		assertThat(priced(PricingStage(PricingCatalog(listOf(price()))), derived).costEstimatedUsd).isNull()
	}

	@Test
	@DisplayName("보고 total이 계산 total과 다르면 원천 성분과 유효한 uncached만 보존하고 환산하지 않는다")
	fun reportedTotalContradiction() {
		val derived = TokenDerivation.apply(usage(reportedTotal = 121), inclusive)

		assertThat(derived.tokensTotalDerived).isNull()
		assertThat(derived.tokensInputUncached).isEqualTo(60)
		assertThat(listOf(derived.tokensInput, derived.tokensOutput, derived.tokensCacheRead, derived.tokensCacheCreate, derived.tokensTotalReported))
			.containsExactly(100L, 20L, 40L, 0L, 121L)
		assertThat(derived.envelope.qualityFlags).containsExactly(QualityFlag.DERIVED_VALUE_INVALID)
		assertThat(priced(PricingStage(PricingCatalog(listOf(price()))), derived).costEstimatedUsd).isNull()
	}

	@ParameterizedTest
	@CsvSource(nullValues = ["null"], value = ["21, null", "null, 21"])
	@DisplayName("output에 포함된 reasoning 또는 tool이 output을 넘으면 파생 total과 환산액을 만들지 않는다")
	fun includedOutputContradiction(reasoning: Long?, tool: Long?) {
		val derived = TokenDerivation.apply(usage(reasoning = reasoning, tool = tool), inclusive)

		assertThat(derived.tokensTotalDerived).isNull()
		assertThat(derived.tokensInputUncached).isEqualTo(60)
		assertThat(derived.tokensOutput).isEqualTo(20)
		assertThat(derived.tokensReasoning).isEqualTo(reasoning)
		assertThat(derived.tokensTool).isEqualTo(tool)
		assertThat(derived.envelope.qualityFlags).containsExactly(QualityFlag.DERIVED_VALUE_INVALID)
		assertThat(priced(PricingStage(PricingCatalog(listOf(price()))), derived).costEstimatedUsd).isNull()
	}

	@Test
	@DisplayName("계산할 수 없는 total은 보고 total과 비교하지 않으며 누락된 output을 0으로 채우지 않는다")
	fun missingRequiredComponentIsNotAnArithmeticContradiction() {
		val derived = TokenDerivation.apply(usage(output = null, reportedTotal = 120, reasoning = 21), inclusive)

		assertThat(derived.tokensTotalDerived).isNull()
		assertThat(derived.tokensInputUncached).isEqualTo(60)
		assertThat(derived.tokensTotalReported).isEqualTo(120)
		assertThat(derived.envelope.qualityFlags).doesNotContain(QualityFlag.DERIVED_VALUE_INVALID)
		assertThat(priced(PricingStage(PricingCatalog(listOf(price()))), derived).costEstimatedUsd).isNull()
	}

	@Test
	@DisplayName("reasoning·tool — output 에 포함이면 다시 더하지 않고, 밖이면 둘 다 보고될 때만 더한다")
	fun reasoningAndTool() {
		val inclusive = TokenDerivation.apply(usage(reasoning = 7, tool = 3), profile(InputSemantics.INCLUSIVE_CACHE))
		val exclusive = TokenDerivation.apply(usage(reasoning = 7, tool = 3), profile(InputSemantics.INCLUSIVE_CACHE, OutputSemantics.EXCLUSIVE_REASONING_TOOL))
		val missingTool = TokenDerivation.apply(usage(reasoning = 7), profile(InputSemantics.INCLUSIVE_CACHE, OutputSemantics.EXCLUSIVE_REASONING_TOOL))
		val unknown = TokenDerivation.apply(usage(), profile(InputSemantics.INCLUSIVE_CACHE, OutputSemantics.UNKNOWN))

		assertThat(inclusive.tokensTotalDerived).isEqualTo(120)
		assertThat(exclusive.tokensTotalDerived).isEqualTo(130)
		assertThat(missingTool.tokensTotalDerived).isNull()
		assertThat(unknown.tokensTotalDerived).isNull()
		assertThat(unknown.tokensInputUncached).isEqualTo(60)
	}

	@Test
	@DisplayName("0 은 유효한 측정이다 — output 0 이면 total 은 input 이다")
	fun zeroIsAMeasurement() {
		assertThat(TokenDerivation.apply(usage(output = 0), profile(InputSemantics.INCLUSIVE_CACHE)).tokensTotalDerived).isEqualTo(100)
	}

	@Test
	@DisplayName("명시한 0과 보고 합계 일치·선택 성분 미보고는 유효한 계약이며 정상 환산한다")
	fun validReportedZeroAndAbsentOptionalComponents() {
		val stage = PricingStage(PricingCatalog(listOf(price())))
		val zero = TokenDerivation.apply(usage(input = 0, output = 0, read = 0, create = 0, reasoning = 0, tool = 0, reportedTotal = 0), inclusive)
		val reported = TokenDerivation.apply(usage(reportedTotal = 120), inclusive)
		val absent = TokenDerivation.apply(usage(), inclusive)
		// reasoning과 tool이 서로 배타적인 성분이라는 근거는 없으므로 둘의 합으로 포함관계를 검증하지 않는다.
		val overlapping = TokenDerivation.apply(usage(reasoning = 20, tool = 20), inclusive)

		assertThat(zero.tokensTotalDerived).isZero()
		assertThat(zero.tokensInputUncached).isZero()
		assertThat(priced(stage, zero).costEstimatedUsd).isEqualByComparingTo(BigDecimal.ZERO)
		for (derived in listOf(reported, absent, overlapping)) {
			assertThat(derived.tokensTotalDerived).isEqualTo(120)
			assertThat(priced(stage, derived).costEstimatedUsd).isEqualByComparingTo("0.000492")
		}
		for (derived in listOf(zero, reported, absent, overlapping)) {
			assertThat(derived.envelope.qualityFlags).doesNotContain(QualityFlag.DERIVED_VALUE_INVALID)
		}
		assertThat(absent.tokensTotalReported).isNull()
		assertThat(absent.tokensReasoning).isNull()
		assertThat(absent.tokensTool).isNull()
	}

	@Test
	@DisplayName("토큰이 하나도 없는 사용량 아닌 관측은 건드리지 않는다")
	fun nonUsageWithoutTokensIsUntouched() {
		val observation = usage(input = null, output = null, read = null, create = null, eventType = EventType.TOOL_RESULT)

		assertThat(TokenDerivation.apply(observation, null)).isEqualTo(observation)
	}

	// ── 가격 ────────────────────────────────────────────────────────────────

	private val inclusive = profile(InputSemantics.INCLUSIVE_CACHE)

	private fun price(
		model: String = "model-a",
		semantics: Set<String> = setOf(inclusive.id),
		create: BigDecimal? = BigDecimal("3.75"),
	) = ModelPrice(model, semantics, BigDecimal("3"), BigDecimal("15"), BigDecimal("0.3"), create)

	private fun priced(stage: PricingStage, observation: EventObservation = TokenDerivation.apply(usage(), inclusive)) = stage.apply(observation)

	@Test
	@DisplayName("단일 카탈로그에 없는 모델은 추정 비용이 null 이다")
	fun unknownModelInSingleCatalog() {
		val result = priced(PricingStage())

		assertThat(result.costEstimatedUsd).isNull()
	}

	@Test
	@DisplayName("성분별 단가로 정확한 10진 계산 — 60×3 + 20×15 + 40×0.3 + 0×3.75 = 492 / 100만 USD")
	fun estimatesFromComponents() {
		val result = priced(PricingStage(PricingCatalog(listOf(price()))))

		assertThat(result.costEstimatedUsd).isEqualByComparingTo("0.000492")
	}

	@Test
	@DisplayName("산식 모순이 아닌 다른 필드의 품질 플래그만으로 유효한 성분의 환산을 막지 않는다")
	fun unrelatedQualityFlagDoesNotSuppressPrice() {
		val derived = TokenDerivation.apply(usage().withFlags(QualityFlag.INVALID_MEASUREMENT), inclusive)

		assertThat(derived.tokensTotalDerived).isEqualTo(120)
		assertThat(derived.envelope.qualityFlags).containsExactly(QualityFlag.INVALID_MEASUREMENT)
		assertThat(priced(PricingStage(PricingCatalog(listOf(price()))), derived).costEstimatedUsd).isEqualByComparingTo("0.000492")
	}

	@Test
	@DisplayName("계산하지 않는 경우 — 모르는 모델·의미 전제 불일치·단가 둘·write 단가 없음·파생 input 없음·사용량 아님")
	fun refusesWithoutEvidence() {
		fun stage(vararg prices: ModelPrice) = PricingStage(PricingCatalog(prices.toList()))

		assertThat(priced(stage(price(model = "model-b"))).costEstimatedUsd).isNull()
		assertThat(priced(stage(price(model = "model"))).costEstimatedUsd).describedAs("부분 문자열로 맞추지 않는다").isNull()
		assertThat(priced(stage(price(semantics = setOf("other")))).costEstimatedUsd).isNull()
		assertThat(priced(stage(price(), price())).costEstimatedUsd).describedAs("단가가 둘이면 고르지 않는다").isNull()
		assertThat(priced(stage(price(create = null)), TokenDerivation.apply(usage(create = 10), inclusive)).costEstimatedUsd).isNull()
		assertThat(priced(stage(price()), TokenDerivation.apply(usage(), null)).costEstimatedUsd).isNull()
		assertThat(priced(stage(price()), usage(model = null)).costEstimatedUsd).isNull()
		assertThat(priced(stage(price()), TokenDerivation.apply(usage(eventType = EventType.MODEL_REQUEST_ERROR), inclusive)).costEstimatedUsd).isNull()
	}

	@Test
	@DisplayName("가격 데이터 자체의 제약 — 음수·소수 여섯 자리 초과은 거부한다")
	fun priceDataConstraints() {
		assertThatThrownBy { price(create = BigDecimal("-1")) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { price(create = BigDecimal("0.0000001")) }.isInstanceOf(IllegalArgumentException::class.java)
	}
}
