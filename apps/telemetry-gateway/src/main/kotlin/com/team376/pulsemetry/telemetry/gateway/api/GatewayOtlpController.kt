package com.team376.pulsemetry.telemetry.gateway.api

import com.team376.pulsemetry.telemetry.auth.TelemetryBearer
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtException
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtVerifier
import com.team376.pulsemetry.telemetry.auth.VerifiedTelemetryJwt
import com.team376.pulsemetry.telemetry.gateway.config.TelemetryGatewayProperties
import com.team376.pulsemetry.telemetry.gateway.publish.TelemetryPublishException
import com.team376.pulsemetry.telemetry.gateway.publish.TelemetryPublisher
import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.ingress.TelemetryLogEvent
import com.team376.pulsemetry.telemetry.otlp.GrpcCode
import com.team376.pulsemetry.telemetry.otlp.OtlpContentEncoding
import com.team376.pulsemetry.telemetry.otlp.OtlpHttpResponse
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpResponses
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/**
 * OTLP 세 경로의 접수(ADR 0066 Decision 2). 순서가 계약이다.
 *
 * ```
 * 1. Bearer telemetry JWT 검증          실패 401 (ingest 와 같은 본문)
 * 2. 메서드 · Content-Type · Content-Encoding   405 · 415 · 400
 * 3. 원본 본문 10 MiB                   413
 * 4. 접수 시각 확정 · 그 시각으로 토큰 시간 재검증   401
 * 5. envelope 발행 (acks=all · idempotence)   실패·기한 초과 503 + Retry-After
 * 6. broker ack 뒤 200 (OTLP 성공 본문)
 * ```
 *
 * **본문을 디코딩하지 않는다.** 깨진 gzip·protobuf·JSON 도 200 을 받고 Consumer 에서 영구 실패로 미적재된다(ADR 0066).
 * **기한은 요청 시작부터 하나다** — 본문을 읽는 데 쓴 시간도 포함한다. 데몬의 요청 제한(10초) 안에 503 이라도 돌려준다.
 *
 * ## Bearer 는 어디에도 남기지 않는다
 *
 * 인증 문맥은 검증 결과에서만 만든다. 토큰 원문을 envelope·Kafka key·header·로그에 싣지 않는다. 거부 로그는 사유 코드뿐이다.
 *
 * ## Spring 이 먼저 거부하면 안 된다
 *
 * ingest 의 `OtlpController` 와 같다 — `consumes`·`method` 를 걸지 않고 본문을 서블릿 스트림에서 직접 읽는다. 405·415 는
 * 상위 문자열로 이 클래스가 낸다. 다만 **인증이 먼저다** — 인증되지 않은 요청에는 메서드·형식을 알려 주지 않는다.
 */
@RestController
class GatewayOtlpController(
	private val verifier: TelemetryJwtVerifier,
	private val publisher: TelemetryPublisher,
	private val writer: GatewayResponseWriter,
	private val clock: Clock,
	properties: TelemetryGatewayProperties,
) {

	private val maxRequestBytes = properties.telemetry.gateway.maxRequestBytes
	private val requestDeadline = properties.telemetry.gateway.requestDeadline
	private val topic = properties.telemetry.kafka.topic

	@RequestMapping(path = ["/v1/logs", "/v1/traces", "/v1/metrics"])
	fun ingest(request: HttpServletRequest, response: HttpServletResponse) {
		val startedAt = clock.instant()
		val deadline = startedAt.plus(requestDeadline)

		// 1. 인증. 서명·용도·형식·시간(요청 시작 시각 기준).
		val token = try {
			verifier.verify(TelemetryBearer.extract(request.getHeader(HttpHeaders.AUTHORIZATION)), startedAt)
		} catch (e: TelemetryJwtException) {
			return reject(response, "authenticate", e.reason.code, OtlpResponses.unauthorized())
		}

		// 2. 메서드·형식. 경로는 매핑이 세 개뿐이라 언제나 시그널이다.
		if (!request.method.equals("POST", ignoreCase = true)) {
			return reject(response, "request", "method_not_allowed", OtlpResponses.methodNotAllowed())
		}
		val signal = OtlpSignal.ofPath(request.requestURI)
			?: return reject(response, "request", "path_unknown", OtlpResponses.notFound())
		val mediaType = OtlpMediaType.ofContentType(request.getHeader(HttpHeaders.CONTENT_TYPE))
			?: return reject(response, "request", "unsupported_media_type", OtlpResponses.unsupportedMediaType())
		val encoding = OtlpContentEncoding.normalize(request.getHeader(HttpHeaders.CONTENT_ENCODING))
		if (!OtlpContentEncoding.isSupported(encoding)) {
			return reject(
				response, "request", "unsupported_content_encoding",
				OtlpResponses.statusResponse(mediaType, 400, GrpcCode.INVALID_ARGUMENT, OtlpContentEncoding.unsupportedMessage(encoding)),
			)
		}

		// 3. 본문 상한. 압축을 풀기 전 바이트다.
		val body = readBody(request) ?: return reject(response, "request", "payload_too_large", OtlpResponses.payloadTooLarge())

		// 4. 접수 시각. 10 MiB 를 읽는 사이에 만료될 수 있으므로 이 시각으로 다시 본다.
		val acceptedAt = clock.instant()
		try {
			TelemetryJwtVerifier.checkTime(token, acceptedAt)
		} catch (e: TelemetryJwtException) {
			return reject(response, "authenticate", e.reason.code, OtlpResponses.unauthorized())
		}

		// 5·6. 발행하고 ack 를 받은 뒤에만 200 이다.
		val message = AcceptedTelemetry(
			messageId = UUID.randomUUID(),
			acceptedAt = acceptedAt,
			auth = authContext(token),
			payload = RawOtlpPayload(signal = signal, mediaType = mediaType, contentEncoding = encoding, body = body),
		)
		try {
			publisher.publish(message, deadline)
		} catch (e: TelemetryPublishException) {
			log.warn(
				TelemetryLogEvent.PUBLISH_FAILED.format(
					"messageId" to message.messageId, "topic" to topic, "partition" to null, "offset" to null,
					"stage" to "publish", "reason" to e.reason, "attempt" to 1, "cause" to e.causeType,
				),
			)
			return writer.write(response, OtlpResponses.statusResponse(mediaType, 503, GrpcCode.UNAVAILABLE, UNAVAILABLE_MESSAGE))
		}
		writer.write(response, OtlpResponses.successResponse(mediaType))
	}

	private fun authContext(token: VerifiedTelemetryJwt) = GatewayAuthContext(
		credentialId = token.credentialId,
		tenantId = token.tenantId,
		installationId = token.installationId,
		issuer = token.issuer,
		keyId = token.keyId,
		issuedAt = token.issuedAt,
		expiresAt = token.expiresAt,
	)

	/** 거부 로그는 단계·사유·상태뿐이다. 토큰·본문·헤더 값을 싣지 않는다. */
	private fun reject(response: HttpServletResponse, stage: String, reason: String, result: OtlpHttpResponse) {
		log.info(TelemetryLogEvent.GATEWAY_REJECTED.format("stage" to stage, "reason" to reason, "status" to result.status))
		writer.write(response, result)
	}

	/**
	 * 압축을 풀기 **전** 바이트에 상한을 건다. 넘으면 `null` 이다. 상한 + 1 만큼 읽어 "넘쳤는지"를 판정한다 —
	 * 상한만 읽으면 딱 맞는 요청과 구분되지 않는다.
	 */
	private fun readBody(request: HttpServletRequest): ByteArray? {
		if (request.contentLengthLong > maxRequestBytes) return null
		val read = request.inputStream.readNBytes(Math.toIntExact(maxRequestBytes + 1))
		return if (read.size > maxRequestBytes) null else read
	}

	private companion object {
		val log = LoggerFactory.getLogger(GatewayOtlpController::class.java)

		/** 503 본문의 메시지. Kafka 예외 문구를 내보내지 않는다. */
		const val UNAVAILABLE_MESSAGE = "telemetry ingress unavailable"
	}
}
