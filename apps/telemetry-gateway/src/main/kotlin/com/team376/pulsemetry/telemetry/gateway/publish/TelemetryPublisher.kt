package com.team376.pulsemetry.telemetry.gateway.publish

import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import java.time.Instant

/** broker 가 확인한 record 의 위치. 로그와 테스트가 쓴다. */
class PublishedRecord(val topic: String, val partition: Int, val offset: Long)

/**
 * 접수한 요청 하나를 Kafka 에 발행한다(ADR 0066 Decision 2·3).
 *
 * **정상 반환은 broker ack 다**(`acks=all`) — `send()` 를 부른 것만으로 돌아오지 않는다. 실패와 기한 초과는
 * [TelemetryPublishException] 이다. 기한이 지난 뒤 실제로 발행됐는지는 알 수 없다 — 호출자는 503 을 내고 재전송 중복을 받아들인다.
 */
fun interface TelemetryPublisher {
	fun publish(message: AcceptedTelemetry, deadline: Instant): PublishedRecord
}

/**
 * 발행 실패. [reason] 은 로그용 코드다(`deadline_exceeded`·`delivery_timeout`·`metadata_timeout`·`record_too_large`·
 * `not_enough_replicas`·`authorization_failed`·`producer_closed`·`interrupted`·`broker_error`).
 *
 * 메시지에는 코드만 싣는다 — Kafka 예외 메시지는 topic·partition 외에 무엇을 담을지 보장되지 않는다. 원인은 클래스 이름만 [causeType] 에 남긴다.
 */
class TelemetryPublishException(val reason: String, val causeType: String = "-") :
	RuntimeException("telemetry publish failed: $reason", null, false, false)
