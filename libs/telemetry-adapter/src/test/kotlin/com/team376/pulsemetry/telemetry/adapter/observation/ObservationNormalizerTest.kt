package com.team376.pulsemetry.telemetry.adapter.observation

import com.google.protobuf.ByteString
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogRecordView
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MappedEvent
import com.team376.pulsemetry.telemetry.adapter.observation.profile.NativeIdentity
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanView
import com.team376.pulsemetry.telemetry.adapter.observation.profile.VersionSet
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.InstrumentationScope
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import io.opentelemetry.proto.metrics.v1.AggregationTemporality
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.NumberDataPoint
import io.opentelemetry.proto.metrics.v1.ResourceMetrics
import io.opentelemetry.proto.metrics.v1.ScopeMetrics
import io.opentelemetry.proto.metrics.v1.Sum
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Status
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 벤더와 무관한 정규화 규칙(ADR 0020 §4·부록 A). 벤더 매핑은 없다 — 프로파일이 필요한 곳은 테스트 전용 프로파일을 쓴다.
 */
class ObservationNormalizerTest {

	private val t0 = 1_758_758_400_000_000_000L

	private fun kv(key: String, value: String) =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

	private fun resource(vararg attributes: KeyValue) = Resource.newBuilder().addAllAttributes(attributes.toList()).build()

	private fun context(archive: ArchiveLocator? = ArchiveLocator { path -> ArchivePointer("s3://raw/k", "path=" + path.joinToString("/")) }) =
		ObservationContext("tenant-1", "installation-1", EpochNanos(t0 + 9), "masking-v2", "stamp-v1", archive)

	private fun logs(resource: Resource, vararg records: LogRecord) = ExportLogsServiceRequest.newBuilder().addResourceLogs(
		ResourceLogs.newBuilder().setResource(resource).addScopeLogs(
			ScopeLogs.newBuilder().setScope(InstrumentationScope.newBuilder().setName("scope-a").setVersion("1")).addAllLogRecords(records.toList()),
		),
	).build()

	private fun record(name: String? = "codex.user_prompt", vararg extra: KeyValue, time: Long = 0, observed: Long = t0) =
		LogRecord.newBuilder().setTimeUnixNano(time).setObservedTimeUnixNano(observed)
			.apply { if (name != null) addAttributes(kv("event.name", name)) }
			.addAllAttributes(extra.toList())
			.build()

	private fun normalize(request: com.google.protobuf.Message, registry: ProfileRegistry = ProfileRegistry.EMPTY, context: ObservationContext = context()) =
		ObservationNormalizer(registry).normalize(request, context)

	// ── 로그 generic ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("별칭 표에 없는 서비스 — product unknown, vendor.unknown, generic, usage none, allowlist 밖 속성은 metadata 에 없다")
	fun unregisteredServiceIsGeneric() {
		val result = normalize(logs(resource(kv("service.name", "node_repl"), kv("host.name", "h")), record("codex.browser_use.security_check", kv("prompt", "본문"))))

		val observation = result.events.single().observation
		with(observation.envelope) {
			assertThat(product).isEqualTo(Product.UNKNOWN)
			assertThat(surface).isEqualTo(Surface.UNKNOWN)
			assertThat(productVersion).isNull()
			assertThat(serviceName).isEqualTo("node_repl")
			assertThat(mappingStatus).isEqualTo(MappingStatus.GENERIC)
			assertThat(mappingVersion).isEqualTo(ObservationNormalizer.GENERIC_MAPPING_VERSION)
			assertThat(usageRole).isEqualTo(UsageRole.NONE)
			assertThat(originalName).isEqualTo("codex.browser_use.security_check")
			assertThat(metadataJson).contains("event.name").contains("service.name").doesNotContain("본문").doesNotContain("host.name")
			assertThat(attrs).containsOnlyKeys("event.name")
			assertThat(sourceTime).isEqualTo(EpochNanos(t0))
			assertThat(sourceTimeOrigin).isEqualTo(SourceTimeOrigin.OTLP_OBSERVED)
			assertThat(receivedTime).isEqualTo(EpochNanos(t0 + 9))
		}
		assertThat(observation.eventType).isEqualTo(EventType.VENDOR_UNKNOWN)
	}

	@Test
	@DisplayName("의미 이름이 없는 로그는 diagnostic 이다")
	fun logWithoutSemanticNameIsDiagnostic() {
		val observation = normalize(logs(resource(kv("service.name", "codex-app-server")), record(name = null))).events.single().observation

		assertThat(observation.eventType).isEqualTo(EventType.DIAGNOSTIC)
		assertThat(observation.envelope.originalName).isNull()
	}

	@Test
	@DisplayName("Codex 별칭 — codex-app-server 는 codex/app_server, product_version 은 service.version 이다")
	fun codexAliasResolvesProductAndSurface() {
		val observation = normalize(logs(resource(kv("service.name", "codex-app-server"), kv("service.version", "0.155.0")), record()))
			.events.single().observation

		assertThat(observation.envelope.product).isEqualTo(Product.CODEX)
		assertThat(observation.envelope.surface).isEqualTo(Surface.APP_SERVER)
		assertThat(observation.envelope.productVersion).isEqualTo("0.155.0")
		// 프로파일이 없으면 이름이 알려져도 generic 이다.
		assertThat(observation.eventType).isEqualTo(EventType.VENDOR_UNKNOWN)
	}

	@Test
	@DisplayName("서비스와 관측 이름의 제품 근거가 충돌하면 product unknown·ambiguous — 어느 쪽도 고르지 않는다")
	fun productEvidenceConflictIsAmbiguous() {
		val observation = normalize(logs(resource(kv("service.name", "claude-code")), record("codex.sse_event"))).events.single().observation

		assertThat(observation.envelope.product).isEqualTo(Product.UNKNOWN)
		assertThat(observation.envelope.mappingStatus).isEqualTo(MappingStatus.AMBIGUOUS)
	}

	@Test
	@DisplayName("service.name 이 서로 다른 값으로 두 번 오면 제품을 정하지 않는다 — ambiguous_attribute")
	fun conflictingServiceNames() {
		val observation = normalize(logs(resource(kv("service.name", "claude-code"), kv("service.name", "codex-app-server")), record()))
			.events.single().observation

		assertThat(observation.envelope.product).isEqualTo(Product.UNKNOWN)
		assertThat(observation.envelope.serviceName).isNull()
		assertThat(observation.envelope.qualityFlags).contains(QualityFlag.AMBIGUOUS_ATTRIBUTE)
	}

	@Test
	@DisplayName("source_time 을 못 얻으면 행이 없고 사유가 세어진다 — received_time 을 대입하지 않는다")
	fun unusableSourceTimeProducesNoRow() {
		val result = normalize(logs(resource(kv("service.name", "codex-app-server")), record(time = 0, observed = 0), record(observed = t0 + 1)))

		assertThat(result.events).hasSize(1)
		assertThat(result.stats.received).isEqualTo(2)
		assertThat(result.stats.sourceTimeRejections).containsEntry(SourceTimeRejection.UNSET, 1)
		assertThat(result.stats.rejectedCount).isEqualTo(1)
		assertThat(result.stats.sourceTimeMin).isEqualTo(EpochNanos(t0 + 1))
	}

	@Test
	@DisplayName("영수증이 있으면 archive_ref·selector 가 요청 경로로 채워지고, 없으면 null + archive_receipt_missing")
	fun archivePointer() {
		val request = logs(resource(kv("service.name", "codex-app-server")), record(), record(observed = t0 + 1))

		val located = normalize(request).events.map { it.observation.envelope }
		val missing = normalize(request, context = context(archive = null)).events.first().observation.envelope

		assertThat(located.map { it.archiveSelector }).containsExactly("path=0/0/0", "path=0/0/1")
		assertThat(located.first().archiveRef).isEqualTo("s3://raw/k")
		assertThat(located.first().qualityFlags).doesNotContain(QualityFlag.ARCHIVE_RECEIPT_MISSING)
		assertThat(missing.archiveRef).isNull()
		assertThat(missing.qualityFlags).contains(QualityFlag.ARCHIVE_RECEIPT_MISSING)
	}

	@Test
	@DisplayName("형식이 틀린 trace ID 는 null + invalid_relation_id, 유효하면 소문자 hex")
	fun relationIds() {
		val bad = record().toBuilder().setTraceId(ByteString.copyFrom(ByteArray(3) { 1 })).build()
		val good = record(observed = t0 + 1).toBuilder()
			.setTraceId(ByteString.copyFrom(ByteArray(16) { 0xAB.toByte() })).setSpanId(ByteString.copyFrom(ByteArray(8) { 1 })).build()

		val (first, second) = normalize(logs(resource(kv("service.name", "x")), bad, good)).events.map { it.observation }

		assertThat(first.traceId).isNull()
		assertThat(first.envelope.qualityFlags).contains(QualityFlag.INVALID_RELATION_ID)
		assertThat(second.traceId).isEqualTo("ab".repeat(16))
		assertThat(second.spanId).isEqualTo("01".repeat(8))
	}

	// ── 스팬 ────────────────────────────────────────────────────────────────

	private fun spans(serviceName: String, vararg names: String) = ExportTraceServiceRequest.newBuilder().addResourceSpans(
		ResourceSpans.newBuilder().setResource(resource(kv("service.name", serviceName))).addScopeSpans(
			ScopeSpans.newBuilder().addAllSpans(
				names.mapIndexed { i, name ->
					Span.newBuilder().setName(name).setStartTimeUnixNano(t0 + i).setEndTimeUnixNano(t0 + i + 1_000)
						.setTraceId(ByteString.copyFrom(ByteArray(16) { 2 })).setSpanId(ByteString.copyFrom(ByteArray(8) { (i + 1).toByte() }))
						.setKind(Span.SpanKind.SPAN_KIND_CLIENT).setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR))
						.build()
				},
			),
		),
	).build()

	@Test
	@DisplayName("프로파일이 없으면 스팬은 행이 되지 않는다 — 제외 수와 이름별 수만 남는다")
	fun spansWithoutProfileAreExcluded() {
		val result = normalize(spans("codex-app-server", "receiving", "handle_responses", "receiving"))

		assertThat(result.events).isEmpty()
		assertThat(result.stats.excludedSpans).isEqualTo(3)
		assertThat(result.stats.excludedSpanNames).containsEntry("receiving", 2).containsEntry("handle_responses", 1)
		assertThat(result.stats.rejectedCount).isEqualTo(3)
		assertThat(result.stats.sourceTimeMin).isNull()
	}

	@Test
	@DisplayName("허용 목록 안의 스팬만 행이 되고 시작·종료·지연·kind·status·ID 가 채워진다")
	fun allowlistedSpansBecomeRows() {
		val result = normalize(spans("codex-app-server", "session_task.turn", "receiving"), ProfileRegistry(listOf(TestCodexProfile)))

		val observation = result.events.single().observation
		assertThat(observation.eventType).isEqualTo(EventType.TURN)
		assertThat(observation.envelope.sourceTimeOrigin).isEqualTo(SourceTimeOrigin.SPAN_START)
		assertThat(observation.endTime).isEqualTo(EpochNanos(t0 + 1_000))
		assertThat(observation.durationNs).isEqualTo(1_000)
		assertThat(observation.spanKind).isEqualTo(SpanKind.CLIENT)
		assertThat(observation.spanStatusCode).isEqualTo(SpanStatusCode.ERROR)
		assertThat(observation.traceId).isEqualTo("02".repeat(16))
		assertThat(observation.envelope.mappingVersion).isEqualTo("test-codex-v1")
		assertThat(result.stats.excludedSpanNames).containsOnlyKeys("receiving")
	}

	@Test
	@DisplayName("제외한 스팬 이름은 64개까지만 따로 세고 나머지는 (other) 에 합친다")
	fun excludedSpanNameCardinalityIsBounded() {
		val result = normalize(spans("codex-app-server", *Array(100) { "dynamic-$it" }))

		assertThat(result.stats.excludedSpanNames).hasSize(NormalizationStats.MAX_EXCLUDED_NAMES + 1)
		assertThat(result.stats.excludedSpanNames[NormalizationStats.OTHER_NAMES]).isEqualTo(36)
	}

	// ── 메트릭 ──────────────────────────────────────────────────────────────

	@Test
	@DisplayName("프로파일 없는 metric 은 point 마다 vendor.metric 행 — 구조·이름·단위·temporality 보존, usage none")
	fun metricsAreGenericPerPoint() {
		val metric = Metric.newBuilder().setName("codex.turn.tool.call").setUnit("1").setDescription("d").setSum(
			Sum.newBuilder().setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA).setIsMonotonic(true)
				.addDataPoints(NumberDataPoint.newBuilder().setTimeUnixNano(t0).setStartTimeUnixNano(t0 - 60).setAsInt(3).addAttributes(kv("tool", "shell")))
				.addDataPoints(NumberDataPoint.newBuilder().setTimeUnixNano(t0).setAsDouble(Double.NaN)),
		).build()
		val request = ExportMetricsServiceRequest.newBuilder().addResourceMetrics(
			ResourceMetrics.newBuilder().setResource(resource(kv("service.name", "codex-app-server"))).addScopeMetrics(ScopeMetrics.newBuilder().addMetrics(metric)),
		).build()

		val (first, second) = normalize(request).metricPoints

		assertThat(first.metricFamily).isEqualTo(MetricFamily.VENDOR_METRIC)
		assertThat(first.metricType).isEqualTo(MetricType.SUM)
		assertThat(first.temporality).isEqualTo(Temporality.DELTA)
		assertThat(first.temporalityCode).isEqualTo(1)
		assertThat(first.isMonotonic).isTrue()
		assertThat(first.rawUnit).isEqualTo("1")
		assertThat(first.valueInt).isEqualTo(3)
		assertThat(first.startTime).isEqualTo(EpochNanos(t0 - 60))
		assertThat(first.envelope.usageRole).isEqualTo(UsageRole.NONE)
		assertThat(first.envelope.originalName).isEqualTo("codex.turn.tool.call")
		assertThat(first.seriesId).isNotNull()
		// 허용 차원이 없으면 point 속성은 metadata 에 없다(series 재료로는 쓰인다).
		assertThat(first.envelope.metadataJson).doesNotContain("shell")
		assertThat(first.envelope.observationId).isNotEqualTo(second.envelope.observationId)
		assertThat(second.valueDouble).isNull()
		assertThat(second.envelope.qualityFlags).contains(QualityFlag.NON_FINITE_VALUE)
		assertThat(first.envelope.archiveSelector).isEqualTo("path=0/0/0/0")
		assertThat(second.envelope.archiveSelector).isEqualTo("path=0/0/0/1")
	}

	// ── 프로파일 매핑 ────────────────────────────────────────────────────────

	@Test
	@DisplayName("프로파일이 registry 에 있는 이름을 매핑하고, 검증된 고유 ID 가 있으면 관측 ID 가 native 로 바뀐다")
	fun profileMappingAndNativeIdentity() {
		val result = normalize(
			logs(resource(kv("service.name", "codex-app-server")), record("codex.user_prompt", kv("prompt_length", "5")), record("codex.unknown_thing", observed = t0 + 1)),
			ProfileRegistry(listOf(TestCodexProfile)),
		)

		val (mapped, unregistered) = result.events.map { it.observation }
		assertThat(mapped.eventType).isEqualTo(EventType.PROMPT_SUBMITTED)
		assertThat(mapped.envelope.mappingStatus).isEqualTo(MappingStatus.MAPPED)
		assertThat(mapped.envelope.sourceIdentityKind).isEqualTo(SourceIdentityKind.NATIVE_ID)
		assertThat(mapped.envelope.nativeObservationId).isEqualTo("prompt-1")
		assertThat(mapped.envelope.observationId).isEqualTo(
			ObservationIds.native(
				IdentityMaterial("tenant-1", "installation-1", ObservationSignal.LOG, "codex-app-server", EpochNanos(t0)),
				"test.prompt", "prompt-1",
			),
		)
		assertThat(mapped.envelope.metadataJson).contains("prompt_length")
		assertThat(unregistered.eventType).isEqualTo(EventType.VENDOR_UNKNOWN)
		assertThat(unregistered.envelope.mappingStatus).isEqualTo(MappingStatus.GENERIC)
	}

	@Test
	@DisplayName("파이프라인이 analysis_hash 를 봉인한다 — 같은 입력은 같은 hash, 영수증만 다르면 같은 hash")
	fun pipelineSealsTheHash() {
		val request = logs(resource(kv("service.name", "codex-app-server")), record())

		val first = ObservationPipeline().run(request, context()).events.single()
		val again = ObservationPipeline().run(request, context(archive = null)).events.single()

		assertThat(first.envelope.analysisHash).isNotEqualTo(AnalysisHash.PLACEHOLDER)
		assertThat(Hex64.isValid(first.envelope.analysisHash)).isTrue()
		// 영수증 유무(archive_ref·selector·archive_receipt_missing)는 재료가 아니다.
		assertThat(again.envelope.analysisHash).isEqualTo(first.envelope.analysisHash)
		assertThat(ObservationPipeline().run(request, context()).events.single()).isEqualTo(first)
	}

	// ── 공급자 근거·파이프라인 순서 ───────────────────────────────────────────

	private fun usageRequest(vararg extra: KeyValue) =
		logs(resource(kv("service.name", "codex-app-server")), record("codex.sse_event", *extra))

	@Test
	@DisplayName("사용량 관측 — 검증된 공급자 키의 값이 하나면 근거가 있고, 없거나 서로 다르면 provider_unresolved")
	fun providerEvidence() {
		val registry = ProfileRegistry(listOf(TestCodexProfile))
		fun flags(vararg extra: KeyValue) = normalize(usageRequest(*extra), registry).events.single().observation.envelope.qualityFlags

		assertThat(flags(kv("provider", "openai"))).doesNotContain(QualityFlag.PROVIDER_UNRESOLVED)
		assertThat(flags()).contains(QualityFlag.PROVIDER_UNRESOLVED)
		assertThat(flags(kv("provider", "openai"), kv("provider", "azure"))).contains(QualityFlag.PROVIDER_UNRESOLVED)
		// 사용량이 아닌 관측에는 붙이지 않는다.
		assertThat(normalize(logs(resource(kv("service.name", "codex-app-server")), record()), registry).events.single().observation.envelope.qualityFlags)
			.doesNotContain(QualityFlag.PROVIDER_UNRESOLVED)
	}

	@Test
	@DisplayName("공급자 근거 키는 allowlist 에 있어야 한다 — 근거가 metadata 에 남아야 한다")
	fun providerKeysMustBeAllowlisted() {
		val broken = object : ProductProfile by TestCodexProfile {
			override val logs = object : LogProfile by TestCodexProfile.logs {
				override val providerEvidenceKeys = listOf("model_provider")
			}
		}

		org.assertj.core.api.Assertions.assertThatThrownBy { ProfileRegistry(listOf(broken)) }
			.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("model_provider")
	}

	@Test
	@DisplayName("파이프라인 순서 — 매핑 → 파생 토큰 → 가격 → hash. 가격이 바뀌면 hash 도 바뀐다")
	fun pipelineOrder() {
		val registry = ProfileRegistry(listOf(TestCodexProfile))
		val pricing = com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage(
			com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingProfile(
				"prices-test-1",
				listOf(
					com.team376.pulsemetry.telemetry.adapter.observation.pricing.ModelPrice(
						"model-a", EpochNanos(0), null, setOf(TestSemanticsHolder.value.id),
						java.math.BigDecimal("1"), java.math.BigDecimal("2"), java.math.BigDecimal("0.5"), null,
					),
				),
			),
		)
		val request = usageRequest(kv("provider", "openai"))

		val unpriced = ObservationPipeline(ObservationNormalizer(registry)).run(request, context()).events.single()
		val priced = ObservationPipeline(ObservationNormalizer(registry), pricing).run(request, context()).events.single()

		assertThat(unpriced.tokensInputUncached).isEqualTo(60)
		assertThat(unpriced.tokensTotalDerived).isEqualTo(120)
		assertThat(unpriced.costEstimatedUsd).isNull()
		// 60×1 + 20×2 + 40×0.5 = 120 → 0.000120 USD. cache write 는 해당 없음 프로파일이라 항이 없다.
		assertThat(priced.costEstimatedUsd).isEqualByComparingTo("0.000120")
		assertThat(priced.pricingVersion).isEqualTo("prices-test-1")
		assertThat(priced.envelope.analysisHash).isNotEqualTo(unpriced.envelope.analysisHash)
		assertThat(AnalysisHash.of(priced)).isEqualTo(priced.envelope.analysisHash)
	}

	/** 테스트 전용 Codex 프로파일 — 이름 두 개와 스팬 하나만 안다. 실제 Codex 매핑이 아니다. */
	private object TestCodexProfile : ProductProfile {
		override val product = Product.CODEX
		override val versions = VersionSet.ANY
		override val mappingVersion = "test-codex-v1"
		override val metrics = null
		override val logs = object : LogProfile {
			override val allowlist = MetadataAllowlist("test-v1", resource = setOf("service.name"), record = setOf("event.name", "prompt_length", "provider"))
			override val providerEvidenceKeys = listOf("provider")
			override fun semanticName(view: LogRecordView) = view.attributes.singleString("event.name")
			override fun map(name: String, view: LogRecordView, base: EventObservation): MappedEvent? = when (name) {
				"codex.user_prompt" -> MappedEvent(
					observation = base.copy(
						envelope = base.envelope.copy(mappingStatus = MappingStatus.MAPPED),
						eventType = EventType.PROMPT_SUBMITTED,
					),
					nativeIdentity = NativeIdentity("test.prompt", "prompt-1"),
				)
				// 테스트용 사용량 — 토큰은 설명용 예시 값이다.
				"codex.sse_event" -> MappedEvent(
					observation = base.copy(
						envelope = base.envelope.copy(
							mappingStatus = MappingStatus.MAPPED, usageRole = UsageRole.PRIMARY, usageScope = UsageScope.RESPONSE, model = "model-a",
						),
						eventType = EventType.MODEL_RESPONSE_USAGE,
						tokensInput = 100, tokensOutput = 20, tokensCacheRead = 40,
					),
					semantics = TestSemanticsHolder.value,
				)
				else -> null
			}
		}
		override val spans = object : SpanProfile {
			override val allowlist = MetadataAllowlist("test-v1", resource = setOf("service.name"))
			override fun allows(view: SpanView) = view.name == "session_task.turn"
			override fun map(view: SpanView, base: EventObservation) =
				MappedEvent(base.copy(envelope = base.envelope.copy(mappingStatus = MappingStatus.MAPPED), eventType = EventType.TURN))
		}
	}

	/** object 안에서 바깥 인스턴스 값을 쓸 수 없어 따로 둔다. */
	private object TestSemanticsHolder {
		val value = com.team376.pulsemetry.telemetry.adapter.observation.semantics.SemanticsProfile(
			id = "test-codex-semantics",
			inputSemantics = InputSemantics.INCLUSIVE_CACHE,
			outputSemantics = OutputSemantics.INCLUSIVE_REASONING_TOOL,
			cacheWrite = com.team376.pulsemetry.telemetry.adapter.observation.semantics.CacheWriteStatus.NOT_APPLICABLE,
			scope = com.team376.pulsemetry.telemetry.adapter.observation.semantics.SemanticsScope(Product.CODEX, "test", "unknown", "test"),
			evidencePath = "test/EVIDENCE.md",
		)
	}
}
