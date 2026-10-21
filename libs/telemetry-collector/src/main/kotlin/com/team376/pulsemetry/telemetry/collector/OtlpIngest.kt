package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.Message
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveReceipt

/**
 * 마스킹·아카이브를 마친 시그널을 받는 다음 단계. 조립 앱이 변환·보강·적재를 여기 잇는다
 * (ADR 0011 · 0013).
 *
 * [request] 는 제품 구간으로 가르기 **전의** 전체 요청이고, [receipt] 는 그 요청을 아카이브한 영수증이다
 * — 요청 기준 경로를 [ArchiveReceipt.selectorOf] 로 아카이브 문서의 selector 로 옮길 수 있다(ADR 0020 §6).
 * 아카이브가 실패하면 이 단계는 불리지 않으므로 영수증은 언제나 있다.
 *
 * 여기서 던진 예외는 재시도 가능으로 보고 **503** 이 된다 — 상태가 실리지 않은 오류의 기본이다.
 * 재시도가 무의미한 영구 오류라면 [PermanentIngestException] 을 던진다. 그래야 **400** 이 되어
 * 클라이언트가 배치를 폐기한다.
 */
public fun interface SignalConsumer {
	public fun consume(signal: Signal, request: Message, receipt: ArchiveReceipt)
}

/**
 * 재시도해도 소용없는 오류. HTTP 에서는 **400**, Kafka 에서는 재시도 없이 offset 확정이다(ADR 0066 Decision 6).
 *
 * 상태 코드를 읽는 것은 telemetryctl 데몬이고, 데몬은 **4xx 만 즉시 폐기하고 5xx 는 전부
 * 재시도한다**(허브 ADR 0006). 폐기를 만드는 코드는 4xx 뿐이다.
 *
 * **일시적 장애에 이 예외를 쓰면 배치가 즉시 버려진다.** 일시 장애는 그냥 던지면 503(Kafka 는 재시도)이 된다.
 *
 * [message] 는 HTTP 응답 본문에 실린다(기존 계약). **로그에는 싣지 않는다** — 디코더·하류의 메시지에 본문 조각이 섞일 수 있다.
 * Kafka 경로의 로그는 [OtlpPayloadException.reason] 같은 코드만 남긴다.
 */
public open class PermanentIngestException(message: String, cause: Throwable? = null) :
	RuntimeException(message, cause)
