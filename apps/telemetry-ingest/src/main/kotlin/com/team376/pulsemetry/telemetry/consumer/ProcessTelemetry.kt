package com.team376.pulsemetry.telemetry.consumer

import com.team376.pulsemetry.telemetry.collector.OtlpProcessor
import com.team376.pulsemetry.telemetry.collector.VerifiedIngestContext
import com.team376.pulsemetry.telemetry.credential.TelemetryCredentialAuthorizer
import com.team376.pulsemetry.telemetry.credential.TelemetryCredentialRejectedException
import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry

/**
 * Kafka 로 받은 접수 하나를 처리한다 — RDB 판정 → 수집 코어(ADR 0066 Decision 6 · ADR 0067 Decision 3).
 *
 * **매 시도 여기서부터 다시 돈다.** 재시도 사이에 토큰이 보안 폐기되거나 설치가 차단되면 다음 시도가 거부한다.
 * receipt ID·수신 시각은 message ID·접수 시각이다 — 시도마다 새로 만들지 않는다.
 *
 * 실패는 셋으로 나간다. 거부([TelemetryCredentialRejectedException])와 수집 코어의 영구 실패는 그대로, RDB 판정 단계의 그 밖의
 * 예외는 [TelemetryStageFailure]("authorize")로 감싼다 — 로그의 단계를 가르기 위해서다. 재시도·offset 은 listener 와 오류
 * 처리기가 소유하고 여기에는 재시도 루프가 없다. 전체를 하나의 트랜잭션으로 감싸지 않는다.
 */
class ProcessTelemetry(
	private val authorizer: TelemetryCredentialAuthorizer,
	private val processor: OtlpProcessor,
) {

	fun execute(message: AcceptedTelemetry) {
		val credential = try {
			authorizer.authorize(message.auth, message.acceptedAt)
		} catch (e: TelemetryCredentialRejectedException) {
			throw e
		} catch (e: RuntimeException) {
			throw TelemetryStageFailure("authorize", e)
		}
		processor.process(message.payload, VerifiedIngestContext(message.messageId, message.acceptedAt, credential.identity()))
	}
}

/** 처리 단계를 붙인 일시 실패. 로그에는 [stage] 와 원인 클래스 이름만 남는다. */
class TelemetryStageFailure(val stage: String, cause: Throwable) : RuntimeException(stage, cause)
