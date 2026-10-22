package com.team376.pulsemetry.telemetry.collector

import com.team376.pulsemetry.telemetry.collector.archive.ArchiveReceipt
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.ArchivedObject
import com.team376.pulsemetry.telemetry.collector.archive.Product
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * 전송 독립 코어를 HTTP 없이 부른다(ADR 0067). Kafka Consumer 가 쓰는 방식 그대로다 — 영수증 값은 문맥에서만 오고,
 * 실패는 영구(사유 코드)와 일시로만 갈린다.
 */
class OtlpIngestProcessorTest {

	private val written = mutableListOf<Product>()
	private val receipts = mutableListOf<ArchiveReceipt>()
	private var archiveFailure: RuntimeException? = null

	private val processor = OtlpIngestProcessor(
		archive = object : ArchiveWriter {
			override fun write(product: Product, signal: Signal, body: ByteArray): ArchivedObject {
				archiveFailure?.let { throw it }
				written += product
				return ArchivedObject("memory://${written.size}")
			}
		},
		next = { _, _, receipt -> receipts += receipt },
	)

	private val context = VerifiedIngestContext(
		receiptId = UUID.fromString("0f000000-0000-4000-8000-000000000001"),
		receivedAt = Instant.parse("2026-10-21T01:02:03.123456789Z"),
		identity = AuthorizedTelemetryIdentity(UUID.randomUUID(), UUID.randomUUID()),
	)

	private val log = """
		{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"claude-code"}}]},
		 "scopeLogs":[{"logRecords":[{"body":{"stringValue":"hi"}}]}]}]}
	""".trimIndent().toByteArray()

	private fun payload(body: ByteArray = log, encoding: String = "", mediaType: OtlpMediaType = OtlpMediaType.JSON) =
		RawOtlpPayload(OtlpSignal.LOGS, mediaType, encoding, body)

	@Test
	@DisplayName("영수증의 ID·수신 시각·신원은 문맥에서만 온다 — 다시 처리해도 같다")
	fun receiptComesFromContext() {
		processor.process(payload(), context)
		processor.process(payload(), context)

		assertThat(receipts).hasSize(2)
		receipts.forEach { receipt ->
			assertThat(receipt.receiptId).isEqualTo(context.receiptId.toString())
			assertThat(receipt.receivedAt).isEqualTo(context.receivedAt)
			assertThat(receipt.tenantId).isEqualTo(context.identity.tenantId.toString())
			assertThat(receipt.installationId).isEqualTo(context.identity.installationId.toString())
		}
	}

	@Test
	@DisplayName("gzip 본문을 풀어 처리한다 — Gateway 가 넘긴 원문 그대로다")
	fun gzipPayload() {
		val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(log) } }.toByteArray()

		processor.process(payload(body = gz, encoding = "gzip"), context)

		assertThat(written).containsExactly(Product.CLAUDE_CODE)
	}

	@Test
	@DisplayName("빈 요청은 아카이브도 하류도 부르지 않고 정상 반환한다")
	fun emptyRequest() {
		processor.process(payload(body = """{"resourceLogs":[]}""".toByteArray()), context)

		assertThat(written).isEmpty()
		assertThat(receipts).isEmpty()
	}

	@Test
	@DisplayName("압축 해제·디코딩 실패는 사유 코드가 있는 영구 실패이고 아카이브에 닿지 않는다")
	fun payloadFailuresArePermanent() {
		val cases = mapOf(
			payload(encoding = "br") to "unsupported_content_encoding",
			payload(body = "not gzip".toByteArray(), encoding = "gzip") to "decompress_failed",
			payload(body = "not json".toByteArray()) to "decode_failed",
			payload(body = byteArrayOf(0x0a, 0xff.toByte(), 0x01), mediaType = OtlpMediaType.PROTOBUF) to "decode_failed",
		)
		cases.forEach { (payload, reason) ->
			assertThatThrownBy { processor.process(payload, context) }
				.isInstanceOfSatisfying(OtlpPayloadException::class.java) { assertThat(it.reason).isEqualTo(reason) }
		}
		assertThat(written).isEmpty()
		assertThat(receipts).isEmpty()
	}

	@Test
	@DisplayName("압축 해제 상한을 넘으면 body_too_large 영구 실패다")
	fun decompressedLimit() {
		val small = OtlpIngestProcessor(
			archive = object : ArchiveWriter {
				override fun write(product: Product, signal: Signal, body: ByteArray) = ArchivedObject("memory://x")
			},
			next = { _, _, _ -> },
			maxDecompressedBytes = 16,
		)

		// 상한은 압축을 풀 때만 건다 — 무압축 본문의 상한은 HTTP 앞단(10 MiB)이 이미 걸었다.
		val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(log) } }.toByteArray()
		assertThatThrownBy { small.process(payload(body = gz, encoding = "gzip"), context) }
			.isInstanceOfSatisfying(OtlpPayloadException::class.java) { assertThat(it.reason).isEqualTo("body_too_large") }
	}

	@Test
	@DisplayName("아카이브 실패는 영구 실패가 아니다 — 하류를 부르지 않고 그대로 올린다")
	fun archiveFailureIsTransient() {
		archiveFailure = IllegalStateException("s3 unreachable")

		assertThatThrownBy { processor.process(payload(), context) }
			.isInstanceOf(IllegalStateException::class.java)
			.isNotInstanceOf(PermanentIngestException::class.java)
		assertThat(receipts).isEmpty()
	}
}
