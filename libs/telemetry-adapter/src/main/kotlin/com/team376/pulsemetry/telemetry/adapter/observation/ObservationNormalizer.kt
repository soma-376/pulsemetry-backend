package com.team376.pulsemetry.telemetry.adapter.observation

import com.google.protobuf.Message
import com.google.protobuf.MessageOrBuilder
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogRecordView
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MappedEvent
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricPointView
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductIdentity
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductRegistry
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanView
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.SemanticsProfile

/** 한 요청을 정규화한 결과. 관측은 아직 hash 가 봉인되지 않았다 — [ObservationPipeline] 이 마친다. */
public class NormalizationResult(
	public val events: List<NormalizedEvent>,
	public val metricPoints: List<MetricPointObservation>,
	public val stats: NormalizationStats,
)

/** 이벤트 관측과, 사용량 관측이면 그 토큰 의미 프로파일. */
public class NormalizedEvent(
	public val observation: EventObservation,
	public val semantics: SemanticsProfile?,
)

/**
 * 정규화 계약 2판의 진입점(ADR 0020). OTLP 요청 하나를 관측으로 바꾼다.
 *
 * 외부 I/O·가격 조회·신호 간 조인·요청 사이의 상태가 없다 — 같은 입력과 같은 [ProfileRegistry] 는 언제나 같은
 * 결과다(ADR 0017 규칙 5 유지). 벤더 의미는 [ProductProfile] 이 채우고, 이 클래스는 벤더와 무관한 일을 한다:
 *
 * 1. `service.name` 정확 일치로 제품·표면을 정하고([ProductRegistry]), 관측 이름의 제품 접두사와 충돌하면
 *    `product = unknown`·`mapping_status = ambiguous` 로 둔다.
 * 2. `source_time` 을 고르고 못 얻으면 행을 만들지 않고 사유를 센다([SourceTimes]).
 * 3. 봉투(관측 ID·출처·시간·archive·allowlist metadata·attrs)를 채운다.
 * 4. 프로파일이 없거나 이름이 registry 밖이면 generic 이다 — 로그는 `diagnostic`/`vendor.unknown`, 메트릭은
 *    `vendor.metric`, **스팬은 허용 목록 밖이면 행을 만들지 않는다**. generic 의 `usage_role` 은 `none` 이다.
 *
 * 개별 관측의 미지원·측정 오류는 generic 행이나 거부 수로 남고 요청 전체를 실패시키지 않는다(ADR 0020 §4).
 */
public class ObservationNormalizer(
	private val profiles: ProfileRegistry = ProfileRegistry.EMPTY,
) {

	public fun normalize(request: Message, context: ObservationContext): NormalizationResult {
		val signal = requireNotNull(Otlp.signalOf(request)) { "OTLP export 요청이 아니다: ${request.descriptorForType.fullName}" }
		val run = Run(context, NormalizationStats())
		Otlp.resourcesOf(request, signal).forEachIndexed { r, resourceX ->
			val resource = Resource.of(resourceX)
			val profile = profiles.find(resource.identity.product, resource.serviceVersion)
			Otlp.scopesOf(resourceX, signal).forEachIndexed { s, scopeX ->
				val scope = Scope.of(scopeX)
				Otlp.recordsOf(scopeX, signal).forEachIndexed { k, record ->
					when (signal) {
						ObservationSignal.LOG -> run.log(resource, profile, scope, record as Message, listOf(r, s, k))
						ObservationSignal.SPAN -> run.span(resource, profile, scope, record as Message, listOf(r, s, k))
						ObservationSignal.METRIC -> run.metric(resource, profile, scope, record as Message, listOf(r, s, k))
					}
				}
			}
		}
		return NormalizationResult(run.events, run.points, run.stats)
	}

	/** resource 하나의 읽은 값. */
	private class Resource(
		val message: MessageOrBuilder?,
		val schemaUrl: String,
		val attributes: List<TypedAttribute>,
		val serviceName: String?,
		val serviceVersion: String?,
		val serviceInstanceId: String?,
		val identity: ProductIdentity,
		val flags: Set<QualityFlag>,
	) {
		companion object {
			fun of(resourceX: MessageOrBuilder): Resource {
				val message = Otlp.message(resourceX, "resource")
				val attributes = Otlp.attributes(message)
				val service = AttributeRead.of(attributes, SERVICE_NAME)
				// service.name 이 서로 다른 값으로 중복되면 제품을 정할 근거가 없다.
				val serviceName = ((service as? AttributeRead.Present)?.value as? TypedValue.Str)?.value
				return Resource(
					message = message,
					schemaUrl = Otlp.string(resourceX, "schema_url"),
					attributes = attributes,
					serviceName = serviceName,
					serviceVersion = attributes.singleString("service.version"),
					serviceInstanceId = attributes.singleString("service.instance.id"),
					identity = ProductRegistry.resolve(serviceName),
					flags = if (service == AttributeRead.Conflict) setOf(QualityFlag.AMBIGUOUS_ATTRIBUTE) else emptySet(),
				)
			}
		}
	}

	private class Scope(
		val message: MessageOrBuilder?,
		val schemaUrl: String,
		val name: String?,
		val version: String?,
		val attributes: List<TypedAttribute>,
	) {
		companion object {
			fun of(scopeX: MessageOrBuilder): Scope {
				val message = Otlp.message(scopeX, "scope")
				return Scope(
					message = message,
					schemaUrl = Otlp.string(scopeX, "schema_url"),
					name = Otlp.string(message, "name").ifEmpty { null },
					version = Otlp.string(message, "version").ifEmpty { null },
					attributes = Otlp.attributes(message),
				)
			}
		}
	}

	/** 한 번의 정규화. 결과를 모은다. */
	private inner class Run(val context: ObservationContext, val stats: NormalizationStats) {
		val events = mutableListOf<NormalizedEvent>()
		val points = mutableListOf<MetricPointObservation>()

		fun log(resource: Resource, profile: ProductProfile?, scope: Scope, record: Message, path: List<Int>) {
			stats.recordReceived()
			val attributes = Otlp.attributes(record)
			val view = LogRecordView(
				record = record,
				attributes = attributes,
				body = Otlp.message(record, "body")?.let { Otlp.anyValue(it) } ?: TypedValue.Empty,
				resourceAttributes = resource.attributes,
				scopeAttributes = scope.attributes,
				scopeName = scope.name,
			)
			val logs = profile?.logs
			val name = if (logs != null) logs.semanticName(view) else attributes.singleString(EVENT_NAME)
			val conflict = ProductRegistry.conflicts(resource.identity.product, name)

			val selection = stats.record(
				SourceTimes.forLog(OtlpTime.ofBits(Otlp.long(record, "time_unix_nano")), OtlpTime.ofBits(Otlp.long(record, "observed_time_unix_nano"))),
			) as? SourceTimeSelection.Selected ?: return

			val mapper = if (conflict) null else logs
			val allowlist = mapper?.allowlist ?: GENERIC_ALLOWLIST
			val metadata = allowlist.filter(resource.attributes, scope.attributes, attributes)
			val relation = relationIds(record, spanIdField = "span_id", parentField = null)
			val envelope = envelope(
				resource, scope, record, path, selection, if (conflict) ProductRegistry.UNKNOWN else resource.identity,
				mappingStatus = if (conflict) MappingStatus.AMBIGUOUS else MappingStatus.GENERIC,
				originalName = name,
				mappingVersion = mapper?.let { profile?.mappingVersion } ?: GENERIC_MAPPING_VERSION,
				metadata = metadata,
				extraFlags = relation.flags,
				signal = ObservationSignal.LOG,
			)
			val base = EventObservation(
				envelope = envelope,
				eventType = if (name == null) EventType.DIAGNOSTIC else EventType.VENDOR_UNKNOWN,
				traceId = relation.traceId,
				spanId = relation.spanId,
				severityNumber = Otlp.enumNumber(record, "severity_number").takeIf { it in 1..UINT8_MAX },
			)
			val mapped = if (name != null) mapper?.map(name, view, base) else null
			accept(mapped, base, resource, selection, ObservationSignal.LOG, providerEvidence(mapped, mapper?.providerEvidenceKeys, attributes))
		}

		fun span(resource: Resource, profile: ProductProfile?, scope: Scope, record: Message, path: List<Int>) {
			stats.recordReceived()
			val name = Otlp.string(record, "name")
			val view = SpanView(record, name, Otlp.attributes(record), resource.attributes, scope.attributes, scope.name)
			val spans = profile?.spans
			// 허용 목록은 시각보다 먼저 본다 — 밖의 스팬은 시각 범위에 섞이지 않는다.
			if (profile == null || spans == null || ProductRegistry.conflicts(resource.identity.product, name) || !spans.allows(view)) {
				stats.recordExcludedSpan(name)
				return
			}
			val selection = stats.record(SourceTimes.forSpan(OtlpTime.ofBits(Otlp.long(record, "start_time_unix_nano"))))
				as? SourceTimeSelection.Selected ?: return

			val metadata = spans.allowlist.filter(resource.attributes, scope.attributes, view.attributes)
			val relation = relationIds(record, spanIdField = "span_id", parentField = "parent_span_id")
			val end = OtlpTime.optional(OtlpTime.ofBits(Otlp.long(record, "end_time_unix_nano")))
			val duration = end?.let { it.value - selection.sourceTime.value }
			val envelope = envelope(
				resource, scope, record, path, selection, resource.identity,
				mappingStatus = MappingStatus.GENERIC,
				originalName = name,
				mappingVersion = profile.mappingVersion,
				metadata = metadata,
				extraFlags = relation.flags + if (duration != null && duration < 0) setOf(QualityFlag.INVALID_MEASUREMENT) else emptySet(),
				signal = ObservationSignal.SPAN,
			)
			val status = Otlp.message(record, "status")
			val base = EventObservation(
				envelope = envelope,
				eventType = EventType.DIAGNOSTIC,
				endTime = end,
				durationNs = duration?.takeIf { it >= 0 },
				traceId = relation.traceId,
				spanId = relation.spanId,
				parentSpanId = relation.parentSpanId,
				spanKind = SPAN_KINDS.getOrElse(Otlp.enumNumber(record, "kind")) { SpanKind.UNSPECIFIED },
				spanStatusCode = STATUS_CODES.getOrElse(status?.let { Otlp.enumNumber(it, "code") } ?: 0) { SpanStatusCode.UNSET },
			)
			accept(spans.map(view, base), base, resource, selection, ObservationSignal.SPAN, emptySet())
		}

		fun metric(resource: Resource, profile: ProductProfile?, scope: Scope, metric: Message, path: List<Int>) {
			val name = Otlp.string(metric, "name")
			val conflict = ProductRegistry.conflicts(resource.identity.product, name)
			val mapper = if (conflict) null else profile?.metrics
			for (index in 0 until MetricPoints.pointCount(metric)) {
				stats.recordReceived()
				val body = MetricPointBody.read(metric, index)
				val selection = stats.record(SourceTimes.forMetricPoint(OtlpTime.ofBits(Otlp.long(body.point, "time_unix_nano"))))
					as? SourceTimeSelection.Selected ?: continue

				val pointAttributes = Otlp.attributes(body.point)
				val exemplars = Otlp.repeated(body.point, "exemplars").map { Otlp.attributes(it, "filtered_attributes") }
				val allowlist = mapper?.allowlist ?: GENERIC_ALLOWLIST
				val metadata = allowlist.filter(resource.attributes, scope.attributes, pointAttributes, exemplars)
				val single = MetricPoints.single(metric, index)
				val series = SeriesIds.of(
					context.tenantId, context.installationId, resource.message, resource.schemaUrl, scope.message,
					scope.schemaUrl, metric, index,
				)
				val envelope = envelope(
					resource, scope, single, path + index, selection, if (conflict) ProductRegistry.UNKNOWN else resource.identity,
					mappingStatus = if (conflict) MappingStatus.AMBIGUOUS else MappingStatus.GENERIC,
					originalName = name,
					mappingVersion = mapper?.let { profile?.mappingVersion } ?: GENERIC_MAPPING_VERSION,
					metadata = metadata,
					extraFlags = body.flags,
					signal = ObservationSignal.METRIC,
				)
				val base = body.toObservation(envelope, name, Otlp.string(metric, "description"), Otlp.string(metric, "unit"), series)
				val view = MetricPointView(metric, name, body.point, pointAttributes, resource.attributes, scope.attributes, scope.name)
				points += mapper?.map(view, base) ?: base
			}
		}

		/** 매핑 결과(없으면 generic)를 확정한다. 검증된 고유 ID 가 있으면 관측 ID 를 그것으로 바꾼다. */
		private fun accept(
			mapped: MappedEvent?,
			base: EventObservation,
			resource: Resource,
			selection: SourceTimeSelection.Selected,
			signal: ObservationSignal,
			flags: Set<QualityFlag>,
		) {
			var observation = (mapped?.observation ?: base).withFlags(*flags.toTypedArray())
			mapped?.nativeIdentity?.let { native ->
				val id = ObservationIds.native(material(resource, selection, signal), native.namespace, native.id)
				observation = observation.copy(
					envelope = observation.envelope.copy(
						observationId = id,
						sourceIdentityKind = SourceIdentityKind.NATIVE_ID,
						sourceIdentityNamespace = native.namespace,
						nativeObservationId = native.id,
					),
				)
			}
			events += NormalizedEvent(observation, mapped?.semantics)
		}

		/**
		 * 사용량 관측의 공급자 근거(ADR 0020 §4). 검증된 키의 문자열 값이 정확히 하나로 모이면 근거가 있다 — 값은
		 * allowlist metadata 에 이미 남아 있다. 없거나 서로 다르면 `provider_unresolved`. `product` 로 채우지 않는다.
		 */
		private fun providerEvidence(mapped: MappedEvent?, keys: List<String>?, attributes: List<TypedAttribute>): Set<QualityFlag> {
			if (mapped?.observation?.eventType != EventType.MODEL_RESPONSE_USAGE) return emptySet()
			val values = attributes.filter { it.key in keys.orEmpty() }.map { it.value }.toSet()
			val resolved = values.size == 1 && (values.single() as? TypedValue.Str)?.value?.isNotEmpty() == true
			return if (resolved) emptySet() else setOf(QualityFlag.PROVIDER_UNRESOLVED)
		}

		private fun material(resource: Resource, selection: SourceTimeSelection.Selected, signal: ObservationSignal) =
			IdentityMaterial(context.tenantId, context.installationId, signal, resource.serviceName, selection.sourceTime)

		private fun envelope(
			resource: Resource,
			scope: Scope,
			record: MessageOrBuilder,
			path: List<Int>,
			selection: SourceTimeSelection.Selected,
			identity: ProductIdentity,
			mappingStatus: MappingStatus,
			originalName: String?,
			mappingVersion: String,
			metadata: TypedMetadata,
			extraFlags: Set<QualityFlag>,
			signal: ObservationSignal,
		): ObservationEnvelope {
			val pointer = context.archive?.locate(path)
			val attrs = ScalarAttrs.derive(metadata.record)
			val flags = buildSet {
				if (pointer == null) add(QualityFlag.ARCHIVE_RECEIPT_MISSING)
				addAll(resource.flags)
				addAll(attrs.flags)
				addAll(extraFlags)
			}
			return ObservationEnvelope(
				tenantId = context.tenantId,
				installationId = context.installationId,
				observationId = ObservationIds.fingerprint(
					material(resource, selection, signal), resource.message, resource.schemaUrl, scope.message, scope.schemaUrl, record,
				),
				analysisHash = AnalysisHash.PLACEHOLDER,
				identityVersion = ObservationIds.IDENTITY_VERSION,
				sourceIdentityKind = SourceIdentityKind.FINGERPRINT,
				mappingStatus = mappingStatus,
				qualityFlags = flags.sortedBy { it.ordinal },
				sourceTime = selection.sourceTime,
				sourceTimeOrigin = selection.origin,
				observedTime = selection.observedTime,
				receivedTime = context.receivedTime,
				signal = signal,
				product = identity.product,
				surface = identity.surface,
				productVersion = resource.serviceVersion.takeIf { identity.product != Product.UNKNOWN },
				serviceName = resource.serviceName,
				serviceVersion = resource.serviceVersion,
				serviceInstanceId = resource.serviceInstanceId,
				scopeName = scope.name,
				scopeVersion = scope.version,
				resourceSchemaUrl = resource.schemaUrl.ifEmpty { null },
				scopeSchemaUrl = scope.schemaUrl.ifEmpty { null },
				originalName = originalName,
				mappingVersion = mappingVersion,
				usageRole = UsageRole.NONE,
				usageScope = UsageScope.UNKNOWN,
				workloadKind = WorkloadKind.UNKNOWN,
				archiveRef = pointer?.ref,
				archiveSelector = pointer?.selector,
				maskingVersion = context.maskingVersion,
				metadataJson = metadata.toJson(),
				attrs = attrs.attrs,
			)
		}
	}

	private class Relation(val traceId: String?, val spanId: String?, val parentSpanId: String?, val flags: Set<QualityFlag>)

	/** trace/span ID 는 유효한 원본 hex 만 남긴다. 있는데 형식이 틀리면 null + `invalid_relation_id`. */
	private fun relationIds(record: MessageOrBuilder, spanIdField: String, parentField: String?): Relation {
		val trace = Otlp.hexId(Otlp.bytes(record, "trace_id"), TRACE_ID_BYTES)
		val span = Otlp.hexId(Otlp.bytes(record, spanIdField), SPAN_ID_BYTES)
		val parent = parentField?.let { Otlp.hexId(Otlp.bytes(record, it), SPAN_ID_BYTES) } ?: Otlp.HexId.Absent
		val invalid = listOf(trace, span, parent).any { it == Otlp.HexId.Invalid }
		return Relation(
			traceId = (trace as? Otlp.HexId.Present)?.hex,
			spanId = (span as? Otlp.HexId.Present)?.hex,
			parentSpanId = (parent as? Otlp.HexId.Present)?.hex,
			flags = if (invalid) setOf(QualityFlag.INVALID_RELATION_ID) else emptySet(),
		)
	}

	public companion object {
		private const val SERVICE_NAME = "service.name"
		private const val EVENT_NAME = "event.name"
		private const val TRACE_ID_BYTES = 16
		private const val SPAN_ID_BYTES = 8

		/** generic 경로의 규칙 버전. */
		public const val GENERIC_MAPPING_VERSION: String = "generic-v1"

		/**
		 * 프로파일이 없을 때의 allowlist. 제품을 모르면 어떤 속성이 안전한지 모르므로 이름과 서비스 식별만 남긴다.
		 */
		public val GENERIC_ALLOWLIST: MetadataAllowlist = MetadataAllowlist(
			version = "generic-v1",
			resource = setOf(SERVICE_NAME, "service.version"),
			record = setOf(EVENT_NAME),
		)

		private val SPAN_KINDS = listOf(
			SpanKind.UNSPECIFIED, SpanKind.INTERNAL, SpanKind.SERVER, SpanKind.CLIENT, SpanKind.PRODUCER, SpanKind.CONSUMER,
		)
		private val STATUS_CODES = listOf(SpanStatusCode.UNSET, SpanStatusCode.OK, SpanStatusCode.ERROR)
	}
}
