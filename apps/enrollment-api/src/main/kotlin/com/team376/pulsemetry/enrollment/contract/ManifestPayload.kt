package com.team376.pulsemetry.enrollment.contract

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * 회사 단위 OTel 설정 manifest.
 *
 * `telemetryctl/contracts/enrollment-manifest.schema.json` 과 1:1 이다.
 *
 * **이 안에 `installation_id` 나 토큰을 넣지 마라** (PLAN.md A5).
 * 봉투([EnrollmentResponse])와 설정을 분리하는 것이 계약의 핵심이고,
 * 클라이언트가 `DisallowUnknownFields` 로 중첩 manifest 까지 검사하므로 넣는 순간 설치가 실패한다.
 * 타입으로 막아 두었으니 필드를 추가하려는 충동이 들면 A5 를 다시 읽어라.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ManifestPayload(

	/** 클라이언트의 `SupportedSchemaVersion` 은 1 이다. 이보다 크면 클라이언트가 거부한다. */
	@JsonProperty("schema_version")
	val schemaVersion: Int,

	/** `manifests.version` 으로 덮어쓴다 (PLAN.md §6.2). */
	@JsonProperty("config_revision")
	val configRevision: Int,

	@JsonProperty("otlp")
	val otlp: OtlpSettings,

	@JsonProperty("signals")
	val signals: SignalSettings,

	@JsonProperty("privacy")
	val privacy: PrivacySettings,

	@JsonProperty("repository_allowlist")
	val repositoryAllowlist: List<String>? = null,

	@JsonProperty("resource_attributes")
	val resourceAttributes: Map<String, String>? = null,
) {
	/** 저장된 manifest 의 리비전을 `manifests.version` 으로 갈아 끼운다. */
	fun withConfigRevision(revision: Int): ManifestPayload = copy(configRevision = revision)

	/**
	 * 클라이언트가 거부하지 않을 manifest 인가.
	 *
	 * 판정 기준을 Go 클라이언트의 `contract.Manifest.Validate` 와 일치시킨다 —
	 * 서버가 통과시킨 것을 클라이언트가 거부하면 사용자만 설치에 실패하고 관리자는 이유를 모른다.
	 *
	 * **`schemaVersion` 의 상한은 보지 않는다.** 요청자의 `SupportedSchemaVersion` 을 서버가 알 수 없다 —
	 * enroll 요청 본문에 그 값이 없다. 상한 판정은 클라이언트 몫이다.
	 */
	fun satisfiesContract(): Boolean = schemaVersion >= 1 && otlp.satisfiesContract()
}

/**
 * Collector 전송 설정.
 *
 * 클라이언트가 한 번 더 검증한다: [endpoint] 는 https 여야 하고(localhost 만 http 허용),
 * [protocol] 은 `http/protobuf`·`http/json`·`grpc` 뿐이다. 어기면 설치가 실패한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OtlpSettings(

	@JsonProperty("endpoint")
	val endpoint: String,

	@JsonProperty("protocol")
	val protocol: String,

	@JsonProperty("compression")
	val compression: String? = null,

	@JsonProperty("timeout_ms")
	val timeoutMs: Int? = null,
) {
	/** Go 의 `validOTLPEndpoint` · protocol switch 와 같은 판정이다. */
	fun satisfiesContract(): Boolean = hasAllowedEndpoint() && protocol in SUPPORTED_PROTOCOLS

	/**
	 * https 이거나, http 이면서 host 이름이 **정확히** `localhost` 여야 한다.
	 *
	 * `http://127.0.0.1` · `http://[::1]` · `http://localhost.evil.com` 은 전부 거부다 — Go 도 거부한다.
	 * 스킴만 소문자로 맞춘다. Go 의 `url.Parse` 가 스킴만 소문자로 바꾸고 host 는 그대로 두기 때문이다.
	 */
	private fun hasAllowedEndpoint(): Boolean {
		val parsed = try {
			URI(endpoint)
		} catch (_: URISyntaxException) {
			return false
		}
		val host = parsed.host ?: return false
		if (host.isEmpty()) return false

		return when (parsed.scheme?.lowercase(Locale.ROOT)) {
			"https" -> true
			"http" -> host == "localhost"
			else -> false
		}
	}

	private companion object {
		val SUPPORTED_PROTOCOLS = setOf("http/protobuf", "http/json", "grpc")
	}
}

data class SignalSettings(

	@JsonProperty("logs")
	val logs: Boolean,

	@JsonProperty("metrics")
	val metrics: Boolean,

	@JsonProperty("traces")
	val traces: Boolean,
)

/** 민감 정보 수집 정책. 기본값은 전부 false 다 — 서버가 명시적으로 켜야 한다. */
data class PrivacySettings(

	@JsonProperty("collect_user_prompts")
	val collectUserPrompts: Boolean = false,

	@JsonProperty("collect_assistant_responses")
	val collectAssistantResponses: Boolean = false,

	@JsonProperty("collect_tool_details")
	val collectToolDetails: Boolean = false,

	@JsonProperty("collect_tool_content")
	val collectToolContent: Boolean = false,

	@JsonProperty("collect_user_email")
	val collectUserEmail: Boolean = false,

	@JsonProperty("collect_raw_api_bodies")
	val collectRawApiBodies: Boolean = false,
)
