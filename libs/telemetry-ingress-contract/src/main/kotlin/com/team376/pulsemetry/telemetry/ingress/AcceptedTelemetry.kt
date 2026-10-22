package com.team376.pulsemetry.telemetry.ingress

import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import java.time.Instant
import java.util.UUID

/**
 * 받은 OTLP 본문 그대로. 디코딩·압축 해제·마스킹 전이다. **Bearer 원문은 담지 않는다**(ADR 0066 Decision 4).
 *
 * [contentEncoding] 은 정규화한 값이다(`OtlpContentEncoding.normalize`). 본문 크기 상한은 Gateway(10 MiB)와
 * producer(12 MiB)가 건다 — 이 타입은 크기를 보지 않는다.
 */
public class RawOtlpPayload(
	public val signal: OtlpSignal,
	public val mediaType: OtlpMediaType,
	public val contentEncoding: String,
	public val body: ByteArray,
) {
	/** 본문을 문자열 표현에 싣지 않는다 — 로그에 객체가 찍혀도 payload 가 새지 않게 한다. */
	override fun toString(): String =
		"RawOtlpPayload(signal=$signal, mediaType=$mediaType, contentEncoding=$contentEncoding, bytes=${body.size})"
}

/**
 * Gateway 가 검증한 telemetry JWT 의 claim. **RDB 판정 전이다** — 이 값으로 바로 검증 완료 신원을 만들지 않는다.
 * Consumer 가 [credentialId](`jti`)로 자격 기록을 읽어 나머지를 대조한다(ADR 0065 Decision 6).
 */
public data class GatewayAuthContext(
	public val credentialId: UUID,
	public val tenantId: UUID,
	public val installationId: UUID,
	public val issuer: String,
	public val keyId: String,
	public val issuedAt: Instant,
	public val expiresAt: Instant,
)

/**
 * Gateway 가 접수한 요청 하나. Kafka record 하나가 된다.
 *
 * [messageId]·[acceptedAt] 은 Consumer 의 receipt ID·receivedAt 이 된다 — 재시도·재전달에도 같은 값이라
 * 분석 행의 row version 이 흔들리지 않는다(ADR 0067 Decision 3).
 */
public class AcceptedTelemetry(
	public val messageId: UUID,
	public val acceptedAt: Instant,
	public val auth: GatewayAuthContext,
	public val payload: RawOtlpPayload,
) {
	override fun toString(): String = "AcceptedTelemetry(messageId=$messageId, acceptedAt=$acceptedAt, payload=$payload)"
}
