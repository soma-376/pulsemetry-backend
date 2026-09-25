package com.team376.pulsemetry.telemetry.adapter.observation

import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.InstrumentationScope
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.metrics.v1.AggregationTemporality
import io.opentelemetry.proto.metrics.v1.Gauge
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.NumberDataPoint
import io.opentelemetry.proto.metrics.v1.Sum
import io.opentelemetry.proto.resource.v1.Resource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** series_id 의 재료와 상태(ADR 0020 §4). 한 재료씩 바꿔 같은지·다른지를 표로 본다. */
class SeriesIdsTest {

	private fun kv(key: String, value: String) =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

	private val resource = Resource.newBuilder().addAttributes(kv("service.name", "claude-code")).build()
	private val scope = InstrumentationScope.newBuilder().setName("com.anthropic.claude_code").setVersion("2.1.270").build()

	private fun point(time: Long = 5, value: Long = 3, vararg attributes: KeyValue = arrayOf(kv("type", "input"), kv("model", "m"))) =
		NumberDataPoint.newBuilder().setTimeUnixNano(time).setAsInt(value).addAllAttributes(attributes.toList()).build()

	private fun sum(
		name: String = "claude_code.token.usage",
		unit: String = "tokens",
		description: String = "Number of tokens used",
		temporality: AggregationTemporality = AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA,
		monotonic: Boolean = true,
		point: NumberDataPoint = point(),
	): Metric = Metric.newBuilder().setName(name).setUnit(unit).setDescription(description)
		.setSum(Sum.newBuilder().setAggregationTemporality(temporality).setIsMonotonic(monotonic).addDataPoints(point))
		.build()

	private fun series(
		metric: Metric = sum(),
		resource: Resource = this.resource,
		scope: InstrumentationScope = this.scope,
		tenant: String = "t",
		profileVerified: Boolean = false,
	) = SeriesIds.of(tenant, "i", resource, "", scope, "", metric, 0, profileVerified)

	@Test
	@DisplayName("재료가 아닌 것 — 설명·값·point 시각·point 속성 순서가 바뀌어도 같은 series 다")
	fun nonMaterialChangesKeepTheSeries() {
		val base = series().seriesId

		assertThat(series(sum(description = "다른 설명")).seriesId).isEqualTo(base)
		assertThat(series(sum(point = point(value = 999))).seriesId).isEqualTo(base)
		assertThat(series(sum(point = point(time = 77))).seriesId).isEqualTo(base)
		assertThat(series(sum(point = point(5, 3, kv("model", "m"), kv("type", "input")))).seriesId).isEqualTo(base)
	}

	@Test
	@DisplayName("재료 — 이름·단위·temporality·monotonic·point 차원·resource·scope·tenant 가 바뀌면 다른 series 다")
	fun materialChangesTheSeries() {
		val base = series().seriesId
		val variants = mapOf(
			"name" to series(sum(name = "claude_code.cost.usage")),
			"unit" to series(sum(unit = "USD")),
			"temporality" to series(sum(temporality = AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)),
			"monotonic" to series(sum(monotonic = false)),
			"dimension value" to series(sum(point = point(5, 3, kv("type", "output"), kv("model", "m")))),
			"dimension set" to series(sum(point = point(5, 3, kv("type", "input")))),
			"resource" to series(resource = resource.toBuilder().addAttributes(kv("service.version", "2.1.270")).build()),
			"scope" to series(scope = scope.toBuilder().setVersion("2.1.272").build()),
			"tenant" to series(tenant = "t2"),
		)

		variants.forEach { (what, identity) -> assertThat(identity.seriesId).describedAs(what).isNotEqualTo(base) }
		assertThat(variants.values.map { it.seriesId }.toSet()).hasSize(variants.size)
	}

	@Test
	@DisplayName("타입이 다르면 다른 series 다 — gauge 는 temporality·monotonic 이 없다")
	fun typeIsMaterial() {
		val gauge = Metric.newBuilder().setName("claude_code.token.usage").setUnit("tokens")
			.setGauge(Gauge.newBuilder().addDataPoints(point())).build()

		val identity = series(gauge)

		assertThat(identity.seriesId).isNotNull().isNotEqualTo(series().seriesId)
		assertThat(identity.status).isEqualTo(SeriesIdentityStatus.UNVERIFIED)
	}

	@Test
	@DisplayName("상태 — 기본 unverified, 프로파일이 검증하면 verified, 가려진 차원이 있으면 검증과 무관하게 unverified")
	fun statuses() {
		assertThat(series().status).isEqualTo(SeriesIdentityStatus.UNVERIFIED)
		assertThat(series(profileVerified = true).status).isEqualTo(SeriesIdentityStatus.VERIFIED)
		assertThat(series(sum(point = point(5, 3, kv("model", "sk-****"))), profileVerified = true).status)
			.isEqualTo(SeriesIdentityStatus.UNVERIFIED)
		assertThat(series(resource = resource.toBuilder().addAttributes(kv("user.id", "****")).build(), profileVerified = true).status)
			.isEqualTo(SeriesIdentityStatus.UNVERIFIED)
	}

	@Test
	@DisplayName("같은 차원 키가 다른 값으로 두 번 오면 ambiguous — ID 는 만들되 합산하지 않는다")
	fun conflictingDimensionsAreAmbiguous() {
		val identity = series(sum(point = point(5, 3, kv("type", "input"), kv("type", "output"))), profileVerified = true)

		assertThat(identity.status).isEqualTo(SeriesIdentityStatus.AMBIGUOUS)
		assertThat(identity.seriesId).isNotNull()
		assertThat(series(sum(point = point(5, 3, kv("type", "input"), kv("type", "input")))).status)
			.isEqualTo(SeriesIdentityStatus.UNVERIFIED)
	}

	@Test
	@DisplayName("series 를 말할 수 없으면 null + missing — 이름이 비었거나 데이터 종류가 없다")
	fun missing() {
		assertThat(series(sum(name = ""))).isEqualTo(SeriesIdentity(null, SeriesIdentityStatus.MISSING))
		assertThat(series(Metric.newBuilder().setName("m").build())).isEqualTo(SeriesIdentity(null, SeriesIdentityStatus.MISSING))
	}

	@Test
	@DisplayName("series_id 는 64자리 소문자 hex 이고 관측 ID 와 다른 규칙이다")
	fun format() {
		assertThat(Hex64.isValid(series().seriesId!!)).isTrue()
	}
}
