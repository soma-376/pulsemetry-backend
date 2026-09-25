package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.pricing.ModelPrice
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingProfile
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
		tokensReasoning = reasoning, tokensTool = tool,
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
	@DisplayName("포함관계 위반(캐시가 input 보다 큼)은 그 파생값만 null + derived_value_invalid")
	fun inclusionViolation() {
		val derived = TokenDerivation.apply(usage(input = 30, read = 40), profile(InputSemantics.INCLUSIVE_CACHE))

		assertThat(derived.tokensInputUncached).isNull()
		assertThat(derived.tokensTotalDerived).isEqualTo(50)
		assertThat(derived.envelope.qualityFlags).containsExactly(QualityFlag.DERIVED_VALUE_INVALID)
	}

	@Test
	@DisplayName("overflow 는 null + derived_value_invalid — 최댓값으로 자르지 않는다")
	fun overflow() {
		val derived = TokenDerivation.apply(usage(input = Long.MAX_VALUE, read = 0), profile(InputSemantics.INCLUSIVE_CACHE))

		assertThat(derived.tokensTotalDerived).isNull()
		assertThat(derived.tokensInputUncached).isEqualTo(Long.MAX_VALUE)
		assertThat(derived.envelope.qualityFlags).containsExactly(QualityFlag.DERIVED_VALUE_INVALID)
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
	@DisplayName("토큰이 하나도 없는 사용량 아닌 관측은 건드리지 않는다")
	fun nonUsageWithoutTokensIsUntouched() {
		val observation = usage(input = null, output = null, read = null, create = null, eventType = EventType.TOOL_RESULT)

		assertThat(TokenDerivation.apply(observation, null)).isEqualTo(observation)
	}

	// ── 가격 ────────────────────────────────────────────────────────────────

	private val inclusive = profile(InputSemantics.INCLUSIVE_CACHE)

	private fun price(
		model: String = "model-a",
		from: Long = 0,
		until: Long? = null,
		semantics: Set<String> = setOf(inclusive.id),
		create: BigDecimal? = BigDecimal("3.75"),
	) = ModelPrice(model, EpochNanos(from), until?.let { EpochNanos(it) }, semantics, BigDecimal("3"), BigDecimal("15"), BigDecimal("0.3"), create)

	private fun priced(stage: PricingStage, observation: EventObservation = TokenDerivation.apply(usage(), inclusive)) = stage.apply(observation)

	@Test
	@DisplayName("가격 프로파일이 없으면 추정 비용과 pricing_version 은 null 이다(기본)")
	fun noPricingProfile() {
		val result = priced(PricingStage.NONE)

		assertThat(result.costEstimatedUsd).isNull()
		assertThat(result.pricingVersion).isNull()
	}

	@Test
	@DisplayName("성분별 단가로 정확한 10진 계산 — 60×3 + 20×15 + 40×0.3 + 0×3.75 = 492 / 100만 USD")
	fun estimatesFromComponents() {
		val result = priced(PricingStage(PricingProfile("prices-test-1", listOf(price()))))

		assertThat(result.costEstimatedUsd).isEqualByComparingTo("0.000492")
		assertThat(result.pricingVersion).isEqualTo("prices-test-1")
	}

	@Test
	@DisplayName("계산하지 않는 경우 — 모르는 모델·유효 기간 밖·의미 전제 불일치·단가 둘·write 단가 없음·파생 input 없음·사용량 아님")
	fun refusesWithoutEvidence() {
		fun stage(vararg prices: ModelPrice) = PricingStage(PricingProfile("p", prices.toList()))

		assertThat(priced(stage(price(model = "model-b"))).costEstimatedUsd).isNull()
		assertThat(priced(stage(price(model = "model"))).costEstimatedUsd).describedAs("부분 문자열로 맞추지 않는다").isNull()
		assertThat(priced(stage(price(from = 2_000))).costEstimatedUsd).isNull()
		assertThat(priced(stage(price(until = 1_000))).costEstimatedUsd).describedAs("반개방").isNull()
		assertThat(priced(stage(price(semantics = setOf("other")))).costEstimatedUsd).isNull()
		assertThat(priced(stage(price(), price(from = 1))).costEstimatedUsd).describedAs("단가가 둘이면 고르지 않는다").isNull()
		assertThat(priced(stage(price(create = null)), TokenDerivation.apply(usage(create = 10), inclusive)).costEstimatedUsd).isNull()
		assertThat(priced(stage(price()), TokenDerivation.apply(usage(), null)).costEstimatedUsd).isNull()
		assertThat(priced(stage(price()), usage(model = null)).costEstimatedUsd).isNull()
		assertThat(priced(stage(price()), TokenDerivation.apply(usage(eventType = EventType.MODEL_REQUEST_ERROR), inclusive)).costEstimatedUsd).isNull()
	}

	@Test
	@DisplayName("가격 데이터 자체의 제약 — 음수·소수 여섯 자리 초과·빈 유효 기간은 거부한다")
	fun priceDataConstraints() {
		assertThatThrownBy { price(create = BigDecimal("-1")) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { price(create = BigDecimal("0.0000001")) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { price(from = 5, until = 5) }.isInstanceOf(IllegalArgumentException::class.java)
	}
}
