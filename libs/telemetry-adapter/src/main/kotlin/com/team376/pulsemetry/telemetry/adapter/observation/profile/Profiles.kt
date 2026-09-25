package com.team376.pulsemetry.telemetry.adapter.observation.profile

import com.google.protobuf.Message
import com.google.protobuf.MessageOrBuilder
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.MetadataAllowlist
import com.team376.pulsemetry.telemetry.adapter.observation.MetricPointObservation
import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.TypedAttribute
import com.team376.pulsemetry.telemetry.adapter.observation.TypedValue
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.SemanticsProfile

/**
 * 제품·producer 버전 범위 하나의 매핑 규칙 묶음(ADR 0020 §4·부록 A). 벤더별 구현은 이 인터페이스를 채운다.
 *
 * 이름이 알려졌다는 사실과 그 버전의 wire 형식·토큰 포함관계가 검증됐다는 사실은 다르다 — 검증은
 * [SemanticsProfile] 과 ADR 0020 부록 C 가 기록한다. 신호별 규칙이 없는(null) 신호는 generic 경로로 간다.
 */
public interface ProductProfile {
	public val product: Product

	/** 이 규칙이 적용되는 producer(`service.version`) 버전. */
	public val versions: VersionSet

	/** 규칙의 버전. `mapping_version` 이다 — 규칙을 바꾸면 올린다(관측 ID 는 바뀌지 않는다). */
	public val mappingVersion: String

	public val logs: LogProfile?
	public val spans: SpanProfile?
	public val metrics: MetricProfile?
}

public interface LogProfile {
	public val allowlist: MetadataAllowlist

	/**
	 * 레코드의 의미 이름. 제품마다 자리가 다르다(속성 `event.name`·확인된 body 경로 등). 최상위 `eventName` 이 소스
	 * 위치일 때 그것을 의미 이름으로 돌려주지 않는다. 없으면 null — `event_type = diagnostic` 이다.
	 */
	public fun semanticName(view: LogRecordView): String?

	/** registry 에 있는 이름이면 매핑한 결과, 없으면 null — generic(`vendor.unknown`)이다. */
	public fun map(name: String, view: LogRecordView, base: EventObservation): MappedEvent?
}

public interface SpanProfile {
	public val allowlist: MetadataAllowlist

	/** 허용 목록 안인가. 밖이면 행을 만들지 않고 제외 수만 센다. */
	public fun allows(view: SpanView): Boolean

	public fun map(view: SpanView, base: EventObservation): MappedEvent
}

public interface MetricProfile {
	public val allowlist: MetadataAllowlist

	/** registry 에 있는 metric 이면 매핑한 결과, 없으면 null — generic(`vendor.metric`)이다. */
	public fun map(view: MetricPointView, base: MetricPointObservation): MetricPointObservation?
}

/** 매핑 결과. [semantics] 는 사용량 관측의 토큰 의미 프로파일, [nativeIdentity] 는 검증된 고유 ID 가 있을 때. */
public class MappedEvent(
	public val observation: EventObservation,
	public val semantics: SemanticsProfile? = null,
	public val nativeIdentity: NativeIdentity? = null,
)

public data class NativeIdentity(val namespace: String, val id: String)

/** producer 버전 집합. 검증한 버전을 나열하는 것이 기본이다 — 범위 추론은 하지 않는다. */
public fun interface VersionSet {
	public fun contains(version: String?): Boolean

	public companion object {
		public fun exactly(vararg versions: String): VersionSet {
			val set = versions.toSet()
			return VersionSet { it != null && it in set }
		}

		public val ANY: VersionSet = VersionSet { true }
	}
}

/** 제품별 프로파일 목록. 같은 제품에서 버전이 겹치는 두 프로파일이 있으면 앞의 것이 쓰인다. */
public class ProfileRegistry(private val profiles: List<ProductProfile>) {
	public fun find(product: Product, version: String?): ProductProfile? =
		profiles.firstOrNull { it.product == product && it.versions.contains(version) }

	public companion object {
		public val EMPTY: ProfileRegistry = ProfileRegistry(emptyList())
	}
}

/** 로그 레코드 하나와 그 문맥. [record] 는 원본 protobuf 다. */
public class LogRecordView(
	public val record: MessageOrBuilder,
	public val attributes: List<TypedAttribute>,
	public val body: TypedValue,
	public val resourceAttributes: List<TypedAttribute>,
	public val scopeAttributes: List<TypedAttribute>,
	public val scopeName: String?,
)

public class SpanView(
	public val span: MessageOrBuilder,
	public val name: String,
	public val attributes: List<TypedAttribute>,
	public val resourceAttributes: List<TypedAttribute>,
	public val scopeAttributes: List<TypedAttribute>,
	public val scopeName: String?,
)

public class MetricPointView(
	public val metric: Message,
	public val name: String,
	public val point: MessageOrBuilder,
	public val pointAttributes: List<TypedAttribute>,
	public val resourceAttributes: List<TypedAttribute>,
	public val scopeAttributes: List<TypedAttribute>,
	public val scopeName: String?,
)
