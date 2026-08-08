package com.team376.pulsemetry.enrollment.contract

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

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
)

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
