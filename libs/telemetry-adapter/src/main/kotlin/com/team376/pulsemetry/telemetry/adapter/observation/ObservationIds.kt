package com.team376.pulsemetry.telemetry.adapter.observation

import com.google.protobuf.Message
import com.google.protobuf.MessageOrBuilder

/**
 * 관측 ID 의 **항상 들어가는** 재료(ADR 0020 §2). 이 다섯 밖의 것은 재료가 될 수 없다 — 파생 product·event_type·
 * event_time, 총계·가격·조직 보강, row_version, received_time, 아카이브 객체 이름·배치 안 위치, mapping_version 은
 * 여기 자리가 없다. 매핑을 고쳐도 관측 ID 가 그대로인 근거가 이 타입의 모양이다.
 */
public data class IdentityMaterial(
	val tenantId: String,
	val installationId: String,
	val signal: ObservationSignal,
	/** resource 의 원본 `service.name`. 없으면 null. 별칭 해석 결과(`product`)가 아니다. */
	val serviceName: String?,
	val sourceTime: EpochNanos,
)

/**
 * `observation_id` — [IDENTITY_VERSION] 규칙의 canonical typed 원본을 SHA-256 한 64자리 소문자 hex(ADR 0020 §2).
 *
 * - [native]: 검증된 관측 고유 ID 가 있을 때. 네임스페이스는 그 ID 가 유일한 범위(producer·종류)를 적는다.
 * - [fingerprint]: 고유 ID 가 없을 때. 마스킹·신원 스탬프를 거친 resource·scope·레코드(또는 point)의
 *   canonical 내용이 재료다. 내용과 시각까지 같은 별개의 발생은 구분하지 못한다 — exactly-once 가 아니다.
 *
 * 같은 레코드는 요청 안의 위치·함께 온 다른 레코드와 무관하게 같은 ID 다 — 레코드 자신과 그 resource·scope
 * 만 본다. 규칙을 바꾸면 [IDENTITY_VERSION] 을 올린다. 그것은 일반 매핑 정정과 다른 변경이다.
 */
public object ObservationIds {

	public const val IDENTITY_VERSION: String = "id-v1"

	public fun native(material: IdentityMaterial, namespace: String, nativeId: String): String {
		require(namespace.isNotBlank() && nativeId.isNotBlank()) { "native ID 와 네임스페이스는 비어 있지 않다" }
		return hash(material, SourceIdentityKind.NATIVE_ID) { out ->
			field(out, "namespace") { Canonical.writeString(it, namespace) }
			out.append(',')
			field(out, "native_id") { Canonical.writeString(it, nativeId) }
		}
	}

	/**
	 * @param record 로그 레코드·스팬 하나, 또는 [MetricPoints.single] 이 만든 point 하나짜리 metric.
	 */
	public fun fingerprint(
		material: IdentityMaterial,
		resource: MessageOrBuilder?,
		resourceSchemaUrl: String,
		scope: MessageOrBuilder?,
		scopeSchemaUrl: String,
		record: MessageOrBuilder,
	): String = hash(material, SourceIdentityKind.FINGERPRINT) { out ->
		field(out, "record") { it.append(Canonical.encode(record)) }
		out.append(',')
		field(out, "resource") { if (resource == null) it.append("null") else it.append(Canonical.encode(resource)) }
		out.append(',')
		field(out, "resource_schema_url") { Canonical.writeString(it, resourceSchemaUrl) }
		out.append(',')
		field(out, "scope") { if (scope == null) it.append("null") else it.append(Canonical.encode(scope)) }
		out.append(',')
		field(out, "scope_schema_url") { Canonical.writeString(it, scopeSchemaUrl) }
	}

	private fun hash(material: IdentityMaterial, kind: SourceIdentityKind, body: (StringBuilder) -> Unit): String {
		val out = StringBuilder("{")
		field(out, "identity_version") { Canonical.writeString(it, IDENTITY_VERSION) }
		out.append(',')
		field(out, "installation_id") { Canonical.writeString(it, material.installationId) }
		out.append(',')
		field(out, "kind") { Canonical.writeString(it, kind.wire) }
		out.append(',')
		field(out, "service_name") { if (material.serviceName == null) it.append("null") else Canonical.writeString(it, material.serviceName) }
		out.append(',')
		field(out, "signal") { Canonical.writeString(it, material.signal.wire) }
		out.append(',')
		field(out, "source_time") { it.append(material.sourceTime.value) }
		out.append(',')
		field(out, "tenant_id") { Canonical.writeString(it, material.tenantId) }
		out.append(',')
		body(out)
		out.append('}')
		return Canonical.sha256Hex(out.toString())
	}

	private inline fun field(out: StringBuilder, name: String, value: (StringBuilder) -> Unit) {
		Canonical.writeString(out, name)
		out.append(':')
		value(out)
	}
}

/**
 * metric 하나에서 point 하나만 남긴 사본을 만든다. 그 사본이 point 관측의 fingerprint·series 재료다 —
 * metric 의 이름·단위·타입·temporality 는 남고 다른 point 는 빠진다. 원본은 건드리지 않는다.
 *
 * 수집 모듈과 마찬가지로 OTLP 생성 클래스에 의존하지 않고 descriptor 로 다룬다.
 */
public object MetricPoints {

	/** metric 의 데이터 종류 필드(`gauge`·`sum`·...). 설정되지 않았으면 null. */
	public fun dataField(metric: MessageOrBuilder): com.google.protobuf.Descriptors.FieldDescriptor? {
		val oneof = metric.descriptorForType.oneofs.firstOrNull { it.name == "data" } ?: return null
		return metric.getOneofFieldDescriptor(oneof)
	}

	/** point 수. 데이터가 없으면 0. */
	public fun pointCount(metric: MessageOrBuilder): Int {
		val data = dataField(metric) ?: return 0
		val body = metric.getField(data) as MessageOrBuilder
		return body.getRepeatedFieldCount(body.descriptorForType.findFieldByName("data_points"))
	}

	public fun single(metric: Message, pointIndex: Int): Message {
		val data = requireNotNull(dataField(metric)) { "데이터가 없는 metric 이다" }
		val body = metric.getField(data) as Message
		val points = body.descriptorForType.findFieldByName("data_points")
		val point = body.getRepeatedField(points, pointIndex)
		val reduced = body.toBuilder().clearField(points).addRepeatedField(points, point).build()
		return metric.toBuilder().setField(data, reduced).build()
	}
}
