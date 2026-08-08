package com.team376.pulsemetry.enrollment.support

import com.team376.pulsemetry.enrollment.contract.ManifestPayload
import com.team376.pulsemetry.enrollment.contract.OtlpSettings
import com.team376.pulsemetry.enrollment.contract.PrivacySettings
import com.team376.pulsemetry.enrollment.contract.SignalSettings

/**
 * 계약을 만족하는 최소 manifest.
 *
 * 클라이언트가 한 번 더 검증하므로 여기서부터 맞춰 둔다:
 * `otlp.endpoint` 는 https(또는 http://localhost), `otlp.protocol` 은 세 값 중 하나,
 * `schema_version` 은 1 이하 (`SupportedSchemaVersion = 1`).
 */
object ContractFixtures {

	fun manifest(configRevision: Int = 1): ManifestPayload = ManifestPayload(
		schemaVersion = 1,
		configRevision = configRevision,
		otlp = OtlpSettings(
			endpoint = "https://otlp.pulsemetry.example.com",
			protocol = "http/protobuf",
			compression = "gzip",
			timeoutMs = 10_000,
		),
		signals = SignalSettings(logs = true, metrics = true, traces = true),
		privacy = PrivacySettings(),
	)

	/** 선택 필드를 전부 생략한 형태. Go 의 `omitempty` 와 같은 결과여야 한다. */
	fun minimalManifest(): ManifestPayload = ManifestPayload(
		schemaVersion = 1,
		configRevision = 0,
		otlp = OtlpSettings(endpoint = "https://collector.example.com", protocol = "grpc"),
		signals = SignalSettings(logs = false, metrics = true, traces = false),
		privacy = PrivacySettings(),
	)
}
