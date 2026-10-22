package com.team376.pulsemetry.telemetry.ingress

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Timestamp
import com.google.protobuf.UnsafeByteOperations
import com.team376.pulsemetry.telemetry.ingress.proto.AuthContext
import com.team376.pulsemetry.telemetry.ingress.proto.OtlpPayload
import com.team376.pulsemetry.telemetry.ingress.proto.Signal
import com.team376.pulsemetry.telemetry.ingress.proto.TelemetryEnvelope
import com.team376.pulsemetry.telemetry.otlp.OtlpContentEncoding
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import java.time.DateTimeException
import java.time.Instant
import java.util.UUID

/**
 * 잘못된 envelope. **영구 실패다** — 같은 record 는 다시 읽어도 같다. Consumer 는 안전한 로그를 남기고 offset 을 확정한다
 * (ADR 0066 Decision 6).
 *
 * 메시지에는 결함 코드만 싣는다. record 의 바이트·필드 값을 담지 않는다 — 원문에는 마스킹 전 데이터가 있다.
 */
public class InvalidEnvelopeException(public val defect: String) :
	RuntimeException("invalid telemetry envelope: $defect", null, false, false)

/**
 * envelope 의 인코딩·디코딩과 검증. schema version 1 만 안다.
 *
 * ## 디코딩이 거부하는 것
 *
 * - protobuf 로 읽히지 않는 바이트·null(tombstone) — `envelope_unparseable`
 * - 모르는 schema version — `envelope_version`
 * - 빠지거나 형식이 틀린 필드 — `envelope_<필드>`. UUID 는 정규 소문자 표기, 시각은 범위 안, `iat < exp`,
 *   signal·media type·Content-Encoding 은 허용 목록 안이어야 한다.
 *
 * issuer·kid 가 **맞는 값**인지는 보지 않는다. 형식만 본다 — 대조는 RDB 판정이 자격 기록과 한다.
 * 모르는 필드는 protobuf 규칙대로 무시한다(같은 판 안의 전방 호환).
 */
public object TelemetryEnvelopeCodec {

	public const val SCHEMA_VERSION: Int = 1

	private val CANONICAL_UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

	public fun encode(message: AcceptedTelemetry): ByteArray = TelemetryEnvelope.newBuilder()
		.setSchemaVersion(SCHEMA_VERSION)
		.setMessageId(message.messageId.toString())
		.setAcceptedAt(timestamp(message.acceptedAt))
		.setAuth(
			AuthContext.newBuilder()
				.setCredentialId(message.auth.credentialId.toString())
				.setTenantId(message.auth.tenantId.toString())
				.setInstallationId(message.auth.installationId.toString())
				.setIssuer(message.auth.issuer)
				.setKeyId(message.auth.keyId)
				.setIssuedAtEpochSeconds(message.auth.issuedAt.epochSecond)
				.setExpiresAtEpochSeconds(message.auth.expiresAt.epochSecond),
		)
		.setPayload(
			OtlpPayload.newBuilder()
				.setSignal(signal(message.payload.signal))
				.setContentType(message.payload.mediaType.contentType)
				.setContentEncoding(message.payload.contentEncoding)
				// 본문을 한 번 더 복사하지 않는다. 받은 배열은 발행이 끝날 때까지 고치지 않는다.
				.setBody(UnsafeByteOperations.unsafeWrap(message.payload.body)),
		)
		.build()
		.toByteArray()

	/** @throws InvalidEnvelopeException 결함 코드와 함께. */
	public fun decode(bytes: ByteArray?): AcceptedTelemetry {
		if (bytes == null) throw InvalidEnvelopeException("envelope_unparseable")
		val envelope = try {
			TelemetryEnvelope.parseFrom(bytes)
		} catch (_: InvalidProtocolBufferException) {
			throw InvalidEnvelopeException("envelope_unparseable")
		}
		if (envelope.schemaVersion != SCHEMA_VERSION) throw InvalidEnvelopeException("envelope_version")
		if (!envelope.hasAcceptedAt()) throw InvalidEnvelopeException("envelope_accepted_at")
		if (!envelope.hasAuth()) throw InvalidEnvelopeException("envelope_auth")
		if (!envelope.hasPayload()) throw InvalidEnvelopeException("envelope_payload")

		val auth = envelope.auth
		val issuedAt = epochSeconds(auth.issuedAtEpochSeconds, "envelope_issued_at")
		val expiresAt = epochSeconds(auth.expiresAtEpochSeconds, "envelope_expires_at")
		if (!expiresAt.isAfter(issuedAt)) throw InvalidEnvelopeException("envelope_expires_at")
		if (auth.issuer.isBlank()) throw InvalidEnvelopeException("envelope_issuer")
		if (auth.keyId.isBlank()) throw InvalidEnvelopeException("envelope_key_id")

		val payload = envelope.payload
		val signal = when (payload.signal) {
			Signal.SIGNAL_LOGS -> OtlpSignal.LOGS
			Signal.SIGNAL_TRACES -> OtlpSignal.TRACES
			Signal.SIGNAL_METRICS -> OtlpSignal.METRICS
			else -> throw InvalidEnvelopeException("envelope_signal")
		}
		// 정규화된 값만 받는다 — Gateway 가 쓴 그대로여야 한다.
		val mediaType = OtlpMediaType.entries.firstOrNull { it.contentType == payload.contentType }
			?: throw InvalidEnvelopeException("envelope_content_type")
		val encoding = payload.contentEncoding
		if (encoding != OtlpContentEncoding.normalize(encoding) || !OtlpContentEncoding.isSupported(encoding)) {
			throw InvalidEnvelopeException("envelope_content_encoding")
		}

		return AcceptedTelemetry(
			messageId = uuid(envelope.messageId, "envelope_message_id"),
			acceptedAt = instant(envelope.acceptedAt),
			auth = GatewayAuthContext(
				credentialId = uuid(auth.credentialId, "envelope_credential_id"),
				tenantId = uuid(auth.tenantId, "envelope_tenant_id"),
				installationId = uuid(auth.installationId, "envelope_installation_id"),
				issuer = auth.issuer,
				keyId = auth.keyId,
				issuedAt = issuedAt,
				expiresAt = expiresAt,
			),
			payload = RawOtlpPayload(
				signal = signal,
				mediaType = mediaType,
				contentEncoding = encoding,
				body = payload.body.toByteArray(),
			),
		)
	}

	private fun signal(signal: OtlpSignal): Signal = when (signal) {
		OtlpSignal.LOGS -> Signal.SIGNAL_LOGS
		OtlpSignal.TRACES -> Signal.SIGNAL_TRACES
		OtlpSignal.METRICS -> Signal.SIGNAL_METRICS
	}

	private fun timestamp(instant: Instant): Timestamp =
		Timestamp.newBuilder().setSeconds(instant.epochSecond).setNanos(instant.nano).build()

	private fun instant(timestamp: Timestamp): Instant {
		if (timestamp.nanos !in 0..999_999_999) throw InvalidEnvelopeException("envelope_accepted_at")
		return try {
			Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong())
		} catch (_: DateTimeException) {
			throw InvalidEnvelopeException("envelope_accepted_at")
		}
	}

	private fun epochSeconds(seconds: Long, defect: String): Instant {
		if (seconds <= 0) throw InvalidEnvelopeException(defect)
		return try {
			Instant.ofEpochSecond(seconds)
		} catch (_: DateTimeException) {
			throw InvalidEnvelopeException(defect)
		}
	}

	private fun uuid(value: String, defect: String): UUID {
		if (!CANONICAL_UUID.matches(value)) throw InvalidEnvelopeException(defect)
		return UUID.fromString(value)
	}
}
