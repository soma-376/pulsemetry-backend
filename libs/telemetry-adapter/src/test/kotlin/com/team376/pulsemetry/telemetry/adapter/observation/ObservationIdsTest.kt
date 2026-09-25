package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.fixture.JsonTree
import com.team376.pulsemetry.telemetry.adapter.observation.fixture.OtlpJsonV2
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.InstrumentationScope
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import io.opentelemetry.proto.resource.v1.Resource
import kotlin.reflect.full.primaryConstructor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 관측 ID(ADR 0020 §2). 기대값은 규칙에서 나온 관계(같다·다르다)이지 구현의 출력이 아니다. */
class ObservationIdsTest {

	private val material = IdentityMaterial(
		tenantId = "tenant-1",
		installationId = "installation-1",
		signal = ObservationSignal.LOG,
		serviceName = "codex-app-server",
		sourceTime = EpochNanos(1_758_758_400_000_000_001),
	)

	private fun kv(key: String, value: String) =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

	private fun kvInt(key: String, value: Long) =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build()

	private val resource = Resource.newBuilder()
		.addAttributes(kv("service.name", "codex-app-server"))
		.addAttributes(kv("tenant.id", "tenant-1"))
		.build()
	private val scope = InstrumentationScope.newBuilder().setName("codex_otel").setVersion("0.155.0").build()

	private fun record(seq: Long = 1, vararg extra: KeyValue) = LogRecord.newBuilder()
		.setObservedTimeUnixNano(1_758_758_400_000_000_001)
		.addAttributes(kv("event.name", "codex.tool_result"))
		.addAttributes(kv("call_id", "call-1"))
		.addAttributes(kvInt("tool_result_seq", seq))
		.addAllAttributes(extra.toList())
		.build()

	private fun id(record: LogRecord, resource: Resource = this.resource, material: IdentityMaterial = this.material) =
		ObservationIds.fingerprint(material, resource, "", scope, "", record)

	@Test
	@DisplayName("같은 레코드는 OTLP/JSON 으로 오든 protobuf 로 오든 같은 ID 다 — 명시 기본값·속성 순서 차이도 같다")
	fun jsonAndProtobufGiveTheSameId() {
		// 같은 레코드를 JSON 으로 적되 기본값을 명시하고 속성 순서를 바꿨다.
		val json = """
			{"resourceLogs":[{"resource":{"attributes":[
			   {"key":"tenant.id","value":{"stringValue":"tenant-1"}},
			   {"key":"service.name","value":{"stringValue":"codex-app-server"}}],"droppedAttributesCount":0},
			 "scopeLogs":[{"scope":{"name":"codex_otel","version":"0.155.0"},
			  "logRecords":[{"observedTimeUnixNano":"1758758400000000001","timeUnixNano":"0","severityNumber":0,
			   "droppedAttributesCount":0,"flags":0,"attributes":[
			    {"key":"tool_result_seq","value":{"intValue":"1"}},
			    {"key":"call_id","value":{"stringValue":"call-1"}},
			    {"key":"event.name","value":{"stringValue":"codex.tool_result"}}]}]}]}]}
		""".trimIndent()
		val builder = ExportLogsServiceRequest.newBuilder()
		OtlpJsonV2.merge(JsonTree.parse(json) as Map<*, *>, builder)
		val fromJson = builder.build().getResourceLogs(0)
		val viaBytes = LogRecord.parseFrom(record().toByteArray())

		val jsonId = ObservationIds.fingerprint(
			material, fromJson.resource, fromJson.schemaUrl, fromJson.getScopeLogs(0).scope,
			fromJson.getScopeLogs(0).schemaUrl, fromJson.getScopeLogs(0).getLogRecords(0),
		)

		assertThat(jsonId).isEqualTo(id(record())).isEqualTo(id(viaBytes))
		assertThat(Hex64.isValid(jsonId)).isTrue()
	}

	@Test
	@DisplayName("요청 안의 위치와 함께 온 다른 레코드가 달라도 같은 ID 다")
	fun batchPlacementDoesNotMatter() {
		val target = record()
		val alone = ExportLogsServiceRequest.newBuilder().addResourceLogs(
			ResourceLogs.newBuilder().setResource(resource).addScopeLogs(ScopeLogs.newBuilder().setScope(scope).addLogRecords(target)),
		).build()
		val crowded = ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().setResource(Resource.newBuilder().addAttributes(kv("service.name", "claude-code"))))
			.addResourceLogs(
				ResourceLogs.newBuilder().setResource(resource).addScopeLogs(
					ScopeLogs.newBuilder().setScope(scope).addLogRecords(record(seq = 9)).addLogRecords(target).addLogRecords(record(seq = 3)),
				),
			).build()

		val first = alone.getResourceLogs(0).getScopeLogs(0).getLogRecords(0)
		val second = crowded.getResourceLogs(1).getScopeLogs(0).getLogRecords(1)

		assertThat(id(first, alone.getResourceLogs(0).resource)).isEqualTo(id(second, crowded.getResourceLogs(1).resource))
	}

	@Test
	@DisplayName("같은 call_id 라도 result seq 가 다르면 다른 관측이다")
	fun differentResultSequencesAreDifferentObservations() {
		assertThat(id(record(seq = 1))).isNotEqualTo(id(record(seq = 2)))
	}

	@Test
	@DisplayName("속성 중복 개수는 재료다 — 같은 속성이 두 번이면 한 번과 다르다")
	fun duplicateMultiplicityIsMaterial() {
		assertThat(id(record(1, kv("x", "1")))).isNotEqualTo(id(record(1, kv("x", "1"), kv("x", "1"))))
	}

	@Test
	@DisplayName("tenant·installation·신호·원본 service·source_time·resource 가 다르면 다른 ID 다")
	fun alwaysPresentMaterialChangesTheId() {
		val base = id(record())

		assertThat(id(record(), material = material.copy(tenantId = "tenant-2"))).isNotEqualTo(base)
		assertThat(id(record(), material = material.copy(installationId = "installation-2"))).isNotEqualTo(base)
		assertThat(id(record(), material = material.copy(signal = ObservationSignal.SPAN))).isNotEqualTo(base)
		assertThat(id(record(), material = material.copy(serviceName = "Codex Desktop"))).isNotEqualTo(base)
		assertThat(id(record(), material = material.copy(sourceTime = EpochNanos(material.sourceTime.value + 1)))).isNotEqualTo(base)
		assertThat(id(record(), resource = resource.toBuilder().addAttributes(kv("service.version", "x")).build())).isNotEqualTo(base)
	}

	@Test
	@DisplayName("재료 타입에는 다섯 자리뿐이다 — mapping_version·product·event_type·received_time·archive 는 자리가 없다")
	fun materialHasNoRoomForDerivedValues() {
		assertThat(IdentityMaterial::class.primaryConstructor!!.parameters.map { it.name })
			.containsExactly("tenantId", "installationId", "signal", "serviceName", "sourceTime")
	}

	@Test
	@DisplayName("native ID — 같은 네임스페이스·ID 는 같고, 네임스페이스나 공통 재료가 다르면 다르다")
	fun nativeIds() {
		val first = ObservationIds.native(material, "claude_code.request", "req-1")

		assertThat(ObservationIds.native(material, "claude_code.request", "req-1")).isEqualTo(first)
		assertThat(ObservationIds.native(material, "codex.request", "req-1")).isNotEqualTo(first)
		assertThat(ObservationIds.native(material.copy(sourceTime = EpochNanos(1)), "claude_code.request", "req-1")).isNotEqualTo(first)
		assertThat(first).isNotEqualTo(id(record()))
	}

	@Test
	@DisplayName("metric point — 같은 metric 의 point 는 서로 다르고, 다른 point 가 몇 개 섞였든 한 point 의 ID 는 같다")
	fun metricPointsAreIdentifiedIndividually() {
		val json = """
			{"resourceMetrics":[{"resource":{},"scopeMetrics":[{"metrics":[{"name":"codex.turn.tool.call","unit":"1",
			 "sum":{"aggregationTemporality":1,"isMonotonic":true,"dataPoints":[
			  {"timeUnixNano":"5","asInt":"1","attributes":[{"key":"tool","value":{"stringValue":"shell"}}]},
			  {"timeUnixNano":"5","asInt":"2","attributes":[{"key":"tool","value":{"stringValue":"apply_patch"}}]}]}}]}]}]}
		""".trimIndent()
		val builder = ExportMetricsServiceRequest.newBuilder()
		OtlpJsonV2.merge(JsonTree.parse(json) as Map<*, *>, builder)
		val metric = builder.build().getResourceMetrics(0).getScopeMetrics(0).getMetrics(0)
		val metricMaterial = material.copy(signal = ObservationSignal.METRIC, sourceTime = EpochNanos(5))

		fun pointId(metric: io.opentelemetry.proto.metrics.v1.Metric, index: Int) =
			ObservationIds.fingerprint(metricMaterial, null, "", null, "", MetricPoints.single(metric, index))

		val onlySecond = metric.toBuilder().setSum(metric.sum.toBuilder().removeDataPoints(0)).build()

		assertThat(pointId(metric, 0)).isNotEqualTo(pointId(metric, 1))
		assertThat(pointId(metric, 1)).isEqualTo(pointId(onlySecond, 0))
		assertThat(MetricPoints.pointCount(metric)).isEqualTo(2)
		assertThat((MetricPoints.single(metric, 1) as io.opentelemetry.proto.metrics.v1.Metric).sum.dataPointsCount).isEqualTo(1)
	}
}
