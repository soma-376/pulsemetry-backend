package com.team376.pulsemetry.telemetry.enricher.support

import com.team376.pulsemetry.telemetry.adapter.model.Client
import com.team376.pulsemetry.telemetry.adapter.model.Envelope
import com.team376.pulsemetry.telemetry.adapter.model.Identity
import com.team376.pulsemetry.telemetry.adapter.model.Ingest
import com.team376.pulsemetry.telemetry.adapter.model.LogKind
import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedLog
import com.team376.pulsemetry.telemetry.adapter.model.Prompt
import com.team376.pulsemetry.telemetry.adapter.model.Surface
import java.time.Instant
import java.util.UUID

/**
 * `installation_id` 와 시각을 지정해 이벤트를 만든다. **테스트 전용이다.**
 *
 * golden fixture 의 `installation_id` 는 고정 문자열이라 DB 의 행과 맞출 수 없고,
 * 시각도 골라 쓸 수 없다. 실측 이벤트가 필요한 자리에는 [GoldenEvents] 를 쓰고, 특정 값이
 * 필요한 자리에는 이쪽을 쓴다 — as-of 경계와 `ts` 절사가 그런 자리다.
 */
public object TestEvents {

	public fun log(
		installationId: UUID?,
		at: Instant,
		tenantId: String? = "acme",
	): Normalized = logWithRawInstallationId(installationId?.toString(), at, tenantId)

	/** 신뢰 키가 UUID 가 아닌 경우까지 짓기 위한 갈래. */
	public fun logWithRawInstallationId(
		installationId: String?,
		at: Instant,
		tenantId: String? = "acme",
	): Normalized = NormalizedLog(
		envelope = Envelope(
			identity = Identity(
				tenantId = tenantId,
				memberId = "alice@acme.test",
				installationId = installationId,
			),
			client = Client(product = "claude_code", surface = Surface.CLI, version = "1.2.3"),
			timestamp = epochSeconds(at),
			sessionId = "sess-test-0001",
			ingest = Ingest(adapterVersion = 3),
			recordId = "idem-${UUID.randomUUID().toString().take(16).replace("-", "")}",
		),
		type = LogKind.USER_PROMPT,
		payload = Prompt(length = 42),
		sequence = 1,
	)

	/** 어댑터가 봉투에 담는 표기 — epoch 초에 소수부가 붙는다. */
	public fun epochSeconds(at: Instant): Double = at.epochSecond + at.nano / 1_000_000_000.0
}
