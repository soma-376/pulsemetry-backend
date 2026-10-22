package com.team376.pulsemetry.telemetry.ingress

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import com.team376.pulsemetry.telemetry.ingress.proto.TelemetryEnvelope
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** envelope v1 의 왕복과 검증(ADR 0066 Decision 4). 잘못된 record 는 결함 코드 하나로 영구 실패다. */
class TelemetryEnvelopeCodecTest {

	private val canary = "CANARY-${UUID.randomUUID()}"

	private fun accepted(
		body: ByteArray = """{"resourceLogs":[],"x":"$canary"}""".toByteArray(),
		encoding: String = "gzip",
		acceptedAt: Instant = Instant.parse("2026-10-21T01:02:03.123456789Z"),
	) = AcceptedTelemetry(
		messageId = UUID.randomUUID(),
		acceptedAt = acceptedAt,
		auth = GatewayAuthContext(
			credentialId = UUID.randomUUID(),
			tenantId = UUID.randomUUID(),
			installationId = UUID.randomUUID(),
			issuer = "pulsemetry-enrollment",
			keyId = "tk-1",
			issuedAt = Instant.parse("2026-10-21T01:00:00Z"),
			expiresAt = Instant.parse("2026-10-21T04:00:00Z"),
		),
		payload = RawOtlpPayload(OtlpSignal.LOGS, OtlpMediaType.JSON, encoding, body),
	)

	private fun envelope(message: AcceptedTelemetry = accepted()): TelemetryEnvelope.Builder =
		TelemetryEnvelope.parseFrom(TelemetryEnvelopeCodec.encode(message)).toBuilder()

	private fun assertDefect(bytes: ByteArray?, defect: String) {
		assertThatThrownBy { TelemetryEnvelopeCodec.decode(bytes) }
			.isInstanceOfSatisfying(InvalidEnvelopeException::class.java) { assertThat(it.defect).isEqualTo(defect) }
	}

	@Test
	@DisplayName("인코딩한 envelope 는 모든 필드를 그대로 되돌린다 — 접수 시각은 나노초까지")
	fun roundTrip() {
		val original = accepted()

		val decoded = TelemetryEnvelopeCodec.decode(TelemetryEnvelopeCodec.encode(original))

		assertThat(decoded.messageId).isEqualTo(original.messageId)
		assertThat(decoded.acceptedAt).isEqualTo(original.acceptedAt)
		assertThat(decoded.auth).isEqualTo(original.auth)
		assertThat(decoded.payload.signal).isEqualTo(OtlpSignal.LOGS)
		assertThat(decoded.payload.mediaType).isEqualTo(OtlpMediaType.JSON)
		assertThat(decoded.payload.contentEncoding).isEqualTo("gzip")
		assertThat(decoded.payload.body).isEqualTo(original.payload.body)
	}

	@Test
	@DisplayName("빈 본문과 무압축도 왕복한다")
	fun emptyBody() {
		val decoded = TelemetryEnvelopeCodec.decode(TelemetryEnvelopeCodec.encode(accepted(body = ByteArray(0), encoding = "")))

		assertThat(decoded.payload.body).isEmpty()
		assertThat(decoded.payload.contentEncoding).isEmpty()
	}

	@Test
	@DisplayName("envelope 에 Bearer·Authorization 을 담을 자리가 없다 — 필드 목록이 고정이다")
	fun noCredentialField() {
		val names = TelemetryEnvelope.getDescriptor().fields.map { it.name } +
			TelemetryEnvelope.getDescriptor().nestedTypes.flatMap { type -> type.fields.map { it.name } } +
			com.team376.pulsemetry.telemetry.ingress.proto.AuthContext.getDescriptor().fields.map { it.name } +
			com.team376.pulsemetry.telemetry.ingress.proto.OtlpPayload.getDescriptor().fields.map { it.name }
		assertThat(names).containsExactlyInAnyOrder(
			"schema_version", "message_id", "accepted_at", "auth", "payload",
			"credential_id", "tenant_id", "installation_id", "issuer", "key_id", "issued_at_epoch_seconds", "expires_at_epoch_seconds",
			"signal", "content_type", "content_encoding", "body",
		)
	}

	@Test
	@DisplayName("읽히지 않는 바이트·tombstone·모르는 판은 영구 실패다")
	fun unparseable() {
		assertDefect(null, "envelope_unparseable")
		assertDefect(byteArrayOf(0x0a, 0xff.toByte(), 0x01), "envelope_unparseable")
		assertDefect(ByteArray(0), "envelope_version")
		assertDefect(envelope().setSchemaVersion(2).build().toByteArray(), "envelope_version")
	}

	@Test
	@DisplayName("빠진 필드는 그 이름의 결함이다")
	fun missingFields() {
		assertDefect(envelope().clearAcceptedAt().build().toByteArray(), "envelope_accepted_at")
		assertDefect(envelope().clearAuth().build().toByteArray(), "envelope_auth")
		assertDefect(envelope().clearPayload().build().toByteArray(), "envelope_payload")
		assertDefect(envelope().clearMessageId().build().toByteArray(), "envelope_message_id")
		assertDefect(envelope().apply { authBuilder.clearIssuer() }.build().toByteArray(), "envelope_issuer")
		assertDefect(envelope().apply { authBuilder.clearKeyId() }.build().toByteArray(), "envelope_key_id")
		assertDefect(envelope().apply { authBuilder.clearIssuedAtEpochSeconds() }.build().toByteArray(), "envelope_issued_at")
	}

	@Test
	@DisplayName("UUID 는 정규 소문자 표기, 만료는 발급보다 뒤, 접수 시각 나노초는 범위 안이어야 한다")
	fun fieldFormats() {
		assertDefect(envelope().setMessageId("not-a-uuid").build().toByteArray(), "envelope_message_id")
		assertDefect(envelope().apply { authBuilder.credentialId = UUID.randomUUID().toString().uppercase() }.build().toByteArray(), "envelope_credential_id")
		assertDefect(envelope().apply { authBuilder.tenantId = "1-1-1-1-1" }.build().toByteArray(), "envelope_tenant_id")
		assertDefect(envelope().apply { authBuilder.installationId = "" }.build().toByteArray(), "envelope_installation_id")
		assertDefect(envelope().apply { authBuilder.expiresAtEpochSeconds = authBuilder.issuedAtEpochSeconds }.build().toByteArray(), "envelope_expires_at")
		assertDefect(envelope().setAcceptedAt(Timestamp.newBuilder().setSeconds(1).setNanos(-1)).build().toByteArray(), "envelope_accepted_at")
	}

	@Test
	@DisplayName("signal·media type·Content-Encoding 은 정규화된 허용 값만 받는다")
	fun payloadAllowLists() {
		assertDefect(envelope().apply { payloadBuilder.signalValue = 0 }.build().toByteArray(), "envelope_signal")
		assertDefect(envelope().apply { payloadBuilder.signalValue = 9 }.build().toByteArray(), "envelope_signal")
		assertDefect(envelope().apply { payloadBuilder.contentType = "application/json; charset=utf-8" }.build().toByteArray(), "envelope_content_type")
		assertDefect(envelope().apply { payloadBuilder.contentType = "text/csv" }.build().toByteArray(), "envelope_content_type")
		assertDefect(envelope().apply { payloadBuilder.contentEncoding = "br" }.build().toByteArray(), "envelope_content_encoding")
		assertDefect(envelope().apply { payloadBuilder.contentEncoding = "GZIP" }.build().toByteArray(), "envelope_content_encoding")
	}

	@Test
	@DisplayName("모르는 필드는 무시한다 — 같은 판 안의 전방 호환")
	fun unknownFieldsIgnored() {
		val original = accepted()
		val extra = TelemetryEnvelopeCodec.encode(original) +
			// 필드 99(length-delimited) "zz" — v1 이 모르는 필드
			byteArrayOf(0x9a.toByte(), 0x06, 0x02, 0x7a, 0x7a)

		assertThat(TelemetryEnvelopeCodec.decode(extra).messageId).isEqualTo(original.messageId)
	}

	@Test
	@DisplayName("결함 메시지와 DTO 문자열에 본문이 섞이지 않는다")
	fun noPayloadInMessages() {
		val bytes = envelope().setSchemaVersion(7).apply { payloadBuilder.body = ByteString.copyFromUtf8(canary) }.build().toByteArray()

		assertThatThrownBy { TelemetryEnvelopeCodec.decode(bytes) }.satisfies({ assertThat(it.message).doesNotContain(canary) })
		assertThat(accepted().toString()).doesNotContain(canary)
		assertThat(accepted().payload.toString()).doesNotContain(canary)
	}

	@Test
	@DisplayName("record key 는 tenant:installation 이다")
	fun recordKey() {
		val tenant = UUID.fromString("11111111-1111-1111-1111-111111111111")
		val installation = UUID.fromString("22222222-2222-2222-2222-222222222222")

		assertThat(TelemetryRecordKey.of(tenant, installation))
			.isEqualTo("11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222")
	}
}
