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
 * 제품 하나의 이벤트 계약(ADR 0074). producer 버전·surface는 출처이며 선택 조건이 아니다.
 *
 * 제품 식별과 각 이벤트의 wire 형식·토큰 포함관계 검증은 별개다 — 계약 근거는
 * [SemanticsProfile] 과 ADR 0020 부록 C 가 기록한다. 신호별 규칙이 없는(null) 신호는 generic 경로로 간다.
 */
public interface ProductProfile {
	public val product: Product

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

	/**
	 * 사용량 관측의 공급자 근거가 실리는 레코드 속성 키(ADR 0020 §4). 검증된 위치만 적는다 — 없으면 빈 목록이고,
	 * 그 제품의 사용량 관측은 전부 `provider_unresolved` 다. 키는 [allowlist] 의 레코드 목록에 있어야 한다
	 * (근거가 metadata 에 남아야 조회 계층이 해석한다).
	 */
	public val providerEvidenceKeys: List<String> get() = emptyList()

	/**
	 * 최상위 `eventName` 을 `metadata_json` 에 남기는가. 그 필드의 값이 무엇인지(예: producer 소스 위치) 검증한
	 * 프로파일만 켠다 — 모르는 제품의 `eventName` 은 무엇이 들었는지 알 수 없다.
	 */
	public val keepsEventName: Boolean get() = false
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

/** 제품당 하나의 계약만 등록한다. 중복을 거부해 등록 순서에 따른 가림을 막는다(ADR 0074). */
public class ProfileRegistry(profiles: List<ProductProfile>) {
	private val byProduct = profiles.associateBy { it.product }

	init {
		require(byProduct.size == profiles.size) { "제품별 프로파일은 하나만 등록할 수 있다" }
		require(Product.UNKNOWN !in byProduct) { "모르는 제품에는 프로파일을 등록할 수 없다" }
		for (profile in profiles) {
			val logs = profile.logs ?: continue
			val missing = logs.providerEvidenceKeys.filterNot { it in logs.allowlist.record }
			require(missing.isEmpty()) { "${profile.product.wire} 의 공급자 근거 키가 allowlist 에 없다: $missing" }
		}
	}

	public fun find(product: Product): ProductProfile? = byProduct[product]

	public companion object {
		public val EMPTY: ProfileRegistry = ProfileRegistry(emptyList())
	}
}

/** 로그 레코드 하나와 그 문맥. [record] 는 원본 protobuf 다. [eventName] 은 최상위 `eventName` 필드(없으면 빈 문자열)다. */
public class LogRecordView(
	public val record: MessageOrBuilder,
	public val attributes: List<TypedAttribute>,
	public val body: TypedValue,
	public val eventName: String,
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
