package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.Message
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource

/**
 * 인증 계층이 검증한 신원. 문자열 둘뿐이다.
 *
 * 이 모듈은 `:libs:security` 를 알지 않는다 — 토큰도 principal 타입도 여기 나타나지 않고,
 * 조립 앱이 값만 꺼내 넘긴다(ADR 0011 · 0016).
 */
public class StampedIdentity(
	public val tenantId: String?,
	public val installationId: String?,
)

/**
 * 지금 처리 중인 요청의 신뢰 신원을 돌려준다. 없으면 `null` 이다.
 *
 * 조립 앱이 `SecurityContextHolder` 에서 꺼내 구현한다. 기본 구현은 아무것도 심지 않는다 —
 * 인증을 세우지 않은 테스트가 그 자리를 쓴다.
 */
public fun interface IdentitySource {
	public fun current(): StampedIdentity?
}

/**
 * 검증된 신원을 OTLP **리소스 속성**으로 승격한다.
 *
 * ## 왜 필요한가
 *
 * 변환 단계는 신원을 리소스 속성에서 읽는다 — `tenant.id` 와 `developer.installation_id` 다.
 * 그 값을 심는 것이 이 코드이고, 심지 않으면 `enriched_events` 의 `tenant_id`·`installation_id`
 * 가 빈 문자열이 되고 팀 소속 조회가 아예 일어나지 않는다. **기동도 되고 행도 쌓이므로
 * 빠졌을 때 조용하다.**
 *
 * 이식 원본은 리시버가 auth-proxy 의 `x-pulsemetry-*` 헤더를 같은 두 속성으로 승격했다
 * (`otlp_receiver._stamp_identity`). 헤더 전파는 허브 ADR 0005 가 폐기했지만 **승격 자체는
 * 남아야 한다** — 사라진 것은 운반 수단이지 신원이 파이프라인에 들어가는 경로가 아니다.
 *
 * ## 규칙 — 원본과 같다
 *
 * - **검증된 값이 자기신고를 이긴다.** 클라이언트가 같은 키를 보냈어도 덮어쓴다(신뢰 경계).
 * - **빈 값은 건너뛴다.** 없는 신원으로 있는 값을 지우지 않는다.
 * - 요청 안의 **모든** resource 블록에 적용한다.
 */
internal object IdentityStamper {

	private const val TENANT_ID = "tenant.id"
	private const val INSTALLATION_ID = "developer.installation_id"

	/** [identity] 를 [builder] 의 모든 resource 에 upsert 한다. 심을 값이 없으면 아무 일도 하지 않는다. */
	fun stamp(builder: Message.Builder, identity: StampedIdentity) {
		val stamps = buildMap {
			identity.tenantId?.takeIf { it.isNotEmpty() }?.let { put(TENANT_ID, it) }
			identity.installationId?.takeIf { it.isNotEmpty() }?.let { put(INSTALLATION_ID, it) }
		}
		if (stamps.isEmpty()) return

		for (resource in resourcesOf(builder)) {
			upsert(resource, stamps)
		}
	}

	private fun resourcesOf(builder: Message.Builder): List<Resource.Builder> = when (builder) {
		is ExportLogsServiceRequest.Builder -> builder.resourceLogsBuilderList.map { it.resourceBuilder }
		is ExportTraceServiceRequest.Builder -> builder.resourceSpansBuilderList.map { it.resourceBuilder }
		is ExportMetricsServiceRequest.Builder -> builder.resourceMetricsBuilderList.map { it.resourceBuilder }
		else -> error("모르는 요청 타입이다: ${builder.descriptorForType.fullName}")
	}

	private fun upsert(resource: Resource.Builder, stamps: Map<String, String>) {
		val remaining = stamps.toMutableMap()
		for (attribute in resource.attributesBuilderList) {
			val value = remaining.remove(attribute.key) ?: continue
			attribute.value = stringValue(value)
		}
		for ((key, value) in remaining) {
			resource.addAttributes(
				KeyValue.newBuilder().setKey(key).setValue(stringValue(value)).build(),
			)
		}
	}

	private fun stringValue(value: String): AnyValue =
		AnyValue.newBuilder().setStringValue(value).build()
}
