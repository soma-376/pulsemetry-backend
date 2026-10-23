package com.team376.pulsemetry.telemetry.gateway

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures
import com.team376.pulsemetry.telemetry.gateway.support.AbstractGatewayIntegrationTest
import com.team376.pulsemetry.telemetry.gateway.support.GatewayTestKafka
import com.team376.pulsemetry.telemetry.ingress.TelemetryEnvelopeCodec
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpResponses
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import java.util.zip.GZIPOutputStream
import javax.sql.DataSource

/**
 * Gateway 의 접수 계약(ADR 0066 Decision 2)을 실제 Kafka 위에서 본다. DB 는 없다.
 */
@ExtendWith(OutputCaptureExtension::class)
class GatewayAcceptanceTest : AbstractGatewayIntegrationTest() {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var context: ApplicationContext

	@Autowired
	private lateinit var registry: MeterRegistry

	private val http: HttpClient = HttpClient.newHttpClient()

	private fun token(
		tenantId: UUID = UUID.randomUUID(),
		installationId: UUID = UUID.randomUUID(),
		credentialId: UUID = UUID.randomUUID(),
		issuedAt: Instant = Instant.now(),
	): String = GatewayTestKafka.KEY.issuer().issue(credentialId, tenantId, installationId, issuedAt).token

	private fun post(
		token: String?,
		body: ByteArray,
		path: String = "/v1/logs",
		contentType: String = "application/json",
		contentEncoding: String? = null,
		method: String = "POST",
	): HttpResponse<ByteArray> {
		val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
			.header("Content-Type", contentType)
			.method(method, HttpRequest.BodyPublishers.ofByteArray(body))
		token?.let { builder.header("Authorization", "Bearer $it") }
		contentEncoding?.let { builder.header("Content-Encoding", it) }
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
	}

	/**
	 * 공유 컨텍스트의 누적 counter 합. 테스트는 전후 차이만 본다. 거부·발행 counter 는 응답을 쓰기 전에 오르므로 응답 직후에 읽어도 된다.
	 * 요청 결과 counter 는 응답을 쓴 뒤에 오르므로 여기서 보지 않는다(`GatewayOtlpControllerTest`).
	 */
	private fun count(name: String, vararg tags: String): Double = registry.find(name).tags(*tags).counters().sumOf { it.count() }

	private fun recordsFor(tenantId: UUID, minimum: Int = 1): List<ConsumerRecord<String, ByteArray>> =
		GatewayTestKafka.records(GatewayTestKafka.shared.bootstrapServers, minimum).filter { it.key().startsWith("$tenantId:") }

	@Test
	@DisplayName("DB 없이 뜬다 — DataSource 빈도 JPA·PostgreSQL·S3 클래스도 없다")
	fun startsWithoutDatabase() {
		assertThat(context.getBeanNamesForType(DataSource::class.java)).isEmpty()
		listOf(
			"org.postgresql.Driver",
			"jakarta.persistence.EntityManager",
			"org.flywaydb.core.Flyway",
			"software.amazon.awssdk.services.s3.S3Client",
			"com.team376.pulsemetry.security.TelemetryTokenHasher",
			"com.team376.pulsemetry.telemetry.collector.OtlpIngestProcessor",
		).forEach { name ->
			assertThat(runCatching { Class.forName(name) }.isFailure).describedAs(name).isTrue()
		}
		val health = http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/healthz")).GET().build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertThat(health.statusCode()).isEqualTo(200)
		assertThat(health.body()).isEqualTo("""{"status":"ok"}""")
	}

	@Test
	@DisplayName("유효한 JWT 는 broker ack 뒤 200 이고 envelope 에 검증된 claim 과 원문이 실린다 — Bearer 는 어디에도 없다")
	fun acceptsAndPublishes() {
		val tenantId = UUID.randomUUID()
		val installationId = UUID.randomUUID()
		val credentialId = UUID.randomUUID()
		val raw = token(tenantId, installationId, credentialId)
		val canary = "CANARY-${UUID.randomUUID()}"
		val body = """{"resourceLogs":[],"x":"$canary"}""".toByteArray()
		val before = Instant.now()
		val acknowledgedBefore = count("pulsemetry.gateway.publish", "signal", "logs", "outcome", "acknowledged", "reason", "none")
		val bytesBefore = registry.get("pulsemetry.gateway.request.bytes").tags("signal", "logs").summary().count()

		val response = post(raw, gzip(body), contentType = "application/json; charset=utf-8", contentEncoding = " GZIP ")

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(String(response.body())).isEqualTo("""{"partialSuccess":{}}""")
		assertThat(count("pulsemetry.gateway.publish", "signal", "logs", "outcome", "acknowledged", "reason", "none") - acknowledgedBefore).isEqualTo(1.0)
		assertThat(registry.get("pulsemetry.gateway.request.bytes").tags("signal", "logs").summary().count() - bytesBefore).isEqualTo(1)

		val record = recordsFor(tenantId).single()
		assertThat(record.key()).isEqualTo("$tenantId:$installationId")
		// 플랫폼 trace 를 끈 기본 설정이라 header 가 없다. 켜면 traceparent 하나뿐이다(ADR 0069, TracingEnabledTest).
		assertThat(record.headers().toArray()).isEmpty()
		val envelope = TelemetryEnvelopeCodec.decode(record.value())
		assertThat(envelope.auth.credentialId).isEqualTo(credentialId)
		assertThat(envelope.auth.tenantId).isEqualTo(tenantId)
		assertThat(envelope.auth.installationId).isEqualTo(installationId)
		assertThat(envelope.auth.keyId).isEqualTo(GatewayTestKafka.KID)
		assertThat(envelope.acceptedAt).isBetween(before, Instant.now())
		assertThat(envelope.payload.signal).isEqualTo(OtlpSignal.LOGS)
		assertThat(envelope.payload.mediaType).isEqualTo(OtlpMediaType.JSON)
		assertThat(envelope.payload.contentEncoding).isEqualTo("gzip")
		// 압축을 풀지 않은 원문 그대로다.
		assertThat(envelope.payload.body).isEqualTo(gzip(body))
		// 토큰 원문은 key·값 어디에도 없다.
		assertThat(String(record.value(), Charsets.ISO_8859_1)).doesNotContain(raw).doesNotContain(raw.substringAfterLast('.'))
	}

	@Test
	@DisplayName("protobuf 로 받으면 protobuf 성공 본문(0a 00)이다 — 깨진 본문도 디코딩하지 않으므로 200 이다")
	fun protobufAndUndecodedBody() {
		val tenantId = UUID.randomUUID()
		val response = post(token(tenantId), byteArrayOf(0x0a, 0xff.toByte(), 0x01), contentType = "application/x-protobuf")

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/x-protobuf")
		assertThat(response.body()).containsExactly(0x0a, 0x00)
		// ADR 0066 — 깨진 protobuf 도 접수된다. Consumer 가 영구 실패로 확정한다.
		assertThat(recordsFor(tenantId)).hasSize(1)
	}

	@Test
	@DisplayName("401 은 사유와 무관하게 같은 본문이고 record 를 남기지 않는다 — 사유는 로그에만 있다")
	fun unauthorizedVariants(output: CapturedOutput) {
		val tenantId = UUID.randomUUID()
		val key = GatewayTestKafka.KEY
		val userJwt = TelemetryJwtFixtures.sign(
			JWSHeader.Builder(JWSAlgorithm.RS256).keyID(GatewayTestKafka.KID).type(JOSEObjectType.JWT).build(),
			TelemetryJwtFixtures.validClaims(tenantId = tenantId).audience("pulsemetry").build(),
			key.privateKey,
		)
		val stranger = TelemetryJwtFixtures.signingKey(GatewayTestKafka.KID)
		val expired = token(tenantId, issuedAt = Instant.now().minus(4, ChronoUnit.HOURS))
		val forged = TelemetryJwtFixtures.sign(
			TelemetryJwtFixtures.validHeader(GatewayTestKafka.KID).build(),
			TelemetryJwtFixtures.validClaims(tenantId = tenantId).build(),
			stranger.privateKey,
		)
		val unknownKid = TelemetryJwtFixtures.sign(
			TelemetryJwtFixtures.validHeader("tk-unknown").build(),
			TelemetryJwtFixtures.validClaims(tenantId = tenantId).build(),
			key.privateKey,
		)
		val tooLong = TelemetryJwtFixtures.sign(
			TelemetryJwtFixtures.validHeader(GatewayTestKafka.KID).build(),
			TelemetryJwtFixtures.validClaims(tenantId = tenantId).expirationTime(Date.from(Instant.now().plus(5, ChronoUnit.HOURS))).build(),
			key.privateKey,
		)

		val cases = mapOf(
			null to "missing_bearer",
			"ptt_" + "a".repeat(43) to "jwt_malformed",
			userJwt to "jwt_type",
			expired to "jwt_expired",
			forged to "jwt_signature",
			unknownKid to "jwt_kid_unknown",
			tooLong to "jwt_lifetime",
		)
		val publishedBefore = count("pulsemetry.gateway.publish")
		cases.forEach { (raw, reason) ->
			val reasonBefore = count("pulsemetry.gateway.rejections", "stage", "authenticate", "reason", reason)
			val response = post(raw, """{"resourceLogs":[]}""".toByteArray())
			assertThat(count("pulsemetry.gateway.rejections", "stage", "authenticate", "reason", reason) - reasonBefore).describedAs(reason).isEqualTo(1.0)
			assertThat(response.statusCode()).describedAs(reason).isEqualTo(401)
			assertThat(String(response.body())).isEqualTo(OtlpResponses.UNAUTHORIZED_BODY)
			assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty()
			assertThat(output.all).contains("event=telemetry_gateway_rejected stage=authenticate reason=$reason status=401")
		}
		// 인증 전에는 메서드·형식을 알려 주지 않는다.
		assertThat(post(null, ByteArray(0), method = "GET").statusCode()).isEqualTo(401)
		assertThat(recordsFor(tenantId, minimum = 0)).isEmpty()
		// 거부는 발행을 시도하지 않는다.
		assertThat(count("pulsemetry.gateway.publish")).isEqualTo(publishedBefore)
	}

	@Test
	@DisplayName("인증 뒤 405·415·400(encoding) 은 ingest 와 같은 문자열이다")
	fun requestShapeRejections() {
		val raw = token()
		val publishedBefore = count("pulsemetry.gateway.publish")
		val methodBefore = count("pulsemetry.gateway.rejections", "stage", "request", "reason", "method_not_allowed")
		val mediaBefore = count("pulsemetry.gateway.rejections", "stage", "request", "reason", "unsupported_media_type")

		val notPost = post(raw, ByteArray(0), method = "PUT")
		assertThat(notPost.statusCode()).isEqualTo(405)
		assertThat(String(notPost.body())).isEqualTo("405 method not allowed, supported: [POST]")

		val csv = post(raw, "a,b".toByteArray(), contentType = "text/csv")
		assertThat(csv.statusCode()).isEqualTo(415)
		assertThat(String(csv.body())).isEqualTo("415 unsupported media type, supported: [application/json, application/x-protobuf]")

		val brotli = post(raw, "{}".toByteArray(), contentEncoding = "br")
		assertThat(brotli.statusCode()).isEqualTo(400)
		assertThat(String(brotli.body())).isEqualTo("""{"code":3,"message":"unsupported Content-Encoding: br"}""")

		assertThat(count("pulsemetry.gateway.rejections", "stage", "request", "reason", "method_not_allowed") - methodBefore).isEqualTo(1.0)
		assertThat(count("pulsemetry.gateway.rejections", "stage", "request", "reason", "unsupported_media_type") - mediaBefore).isEqualTo(1.0)
		assertThat(count("pulsemetry.gateway.publish")).isEqualTo(publishedBefore)
	}

	@Test
	@DisplayName("계약 밖 경로는 404 text/plain 이다")
	fun unknownPath() {
		val response = post(token(), "{}".toByteArray(), path = "/v1/profiles")

		assertThat(response.statusCode()).isEqualTo(404)
		assertThat(String(response.body())).isEqualTo("404 not found")
	}

	@Test
	@DisplayName("본문 10 MiB 는 받고 1바이트라도 넘으면 413 이다 — 10 MiB envelope 가 12 MiB record 상한 안에서 발행된다")
	fun bodyLimitBoundary() {
		val tenantId = UUID.randomUUID()
		val limit = 10 * 1024 * 1024
		val exact = ByteArray(limit) { (it % 251).toByte() }
		val tooLargeBefore = count("pulsemetry.gateway.rejections", "stage", "request", "reason", "payload_too_large")
		val publishedBefore = count("pulsemetry.gateway.publish")

		val accepted = post(token(tenantId), exact, contentType = "application/x-protobuf")
		assertThat(accepted.statusCode()).isEqualTo(200)
		val record = recordsFor(tenantId).single()
		assertThat(TelemetryEnvelopeCodec.decode(record.value()).payload.body).isEqualTo(exact)
		assertThat(record.serializedValueSize()).isGreaterThan(limit).isLessThan(12 * 1024 * 1024)

		val over = post(token(tenantId), ByteArray(limit + 1), contentType = "application/x-protobuf")
		assertThat(over.statusCode()).isEqualTo(413)
		assertThat(String(over.body())).isEqualTo("413 request entity too large")
		assertThat(recordsFor(tenantId)).hasSize(1)
		assertThat(count("pulsemetry.gateway.rejections", "stage", "request", "reason", "payload_too_large") - tooLargeBefore).isEqualTo(1.0)
		// 상한 안의 한 건만 발행했다.
		assertThat(count("pulsemetry.gateway.publish") - publishedBefore).isEqualTo(1.0)
	}

	@Test
	@DisplayName("토큰·본문의 카나리아 문자열이 로그에 남지 않는다")
	fun canaryNeverLogged(output: CapturedOutput) {
		val canary = "CANARYGW" + UUID.randomUUID().toString().replace("-", "")
		post("$canary.$canary.$canary", "{\"$canary\":1}".toByteArray())
		post("ptt_$canary", "{}".toByteArray())
		val good = token()
		post(good, """{"resourceLogs":[],"secret":"$canary"}""".toByteArray())
		post(good, "{}".toByteArray(), contentEncoding = canary)

		assertThat(output.all).doesNotContain(canary).doesNotContain(good)
		assertThat(registry.meters.flatMap { it.id.tags }.joinToString()).doesNotContain(canary).doesNotContain(good)
	}

	private fun gzip(body: ByteArray): ByteArray =
		ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(body) } }.toByteArray()
}
