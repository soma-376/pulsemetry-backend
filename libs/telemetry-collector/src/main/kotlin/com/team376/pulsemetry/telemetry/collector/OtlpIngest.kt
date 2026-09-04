package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.Message

/**
 * 수신한 OTLP 요청. 서블릿·WebFlux 어느 쪽에도 매이지 않는다 — 이 모듈은 라이브러리이고
 * HTTP 라우팅은 조립 앱이 한다(ADR 0011). 앱이 자기 요청 타입에서 이 다섯을 뽑아 넘긴다.
 */
public class OtlpHttpRequest(
	public val method: String,
	public val path: String,
	public val contentType: String?,
	public val contentEncoding: String?,
	public val body: ByteArray,
)

/** 그대로 내보낼 응답. 본문 바이트가 곧 계약이다 — [OtlpResponses] KDoc 참고. */
public class OtlpHttpResponse(
	public val status: Int,
	public val contentType: String,
	public val body: ByteArray,
)

/**
 * 마스킹·아카이브를 마친 시그널을 받는 다음 단계. 조립 앱이 변환·보강·적재를 여기 잇는다
 * (ADR 0011 · 0013).
 *
 * 여기서 던진 예외는 재시도 가능으로 보고 **503** 이 된다 — 상태가 실리지 않은 오류의 기본이다.
 * 재시도가 무의미한 영구 오류라면 [PermanentIngestException] 을 던진다. 그래야 **400** 이 되어
 * 클라이언트가 배치를 폐기한다.
 */
public fun interface SignalConsumer {
	public fun consume(signal: Signal, request: Message)
}

/**
 * 재시도해도 소용없는 오류. **400** 이 된다.
 *
 * 상태 코드를 읽는 것은 telemetryctl 데몬이고, 데몬은 **4xx 만 즉시 폐기하고 5xx 는 전부
 * 재시도한다**(허브 ADR 0006). 폐기를 만드는 코드는 4xx 뿐이다.
 *
 * **일시적 장애에 이 예외를 쓰면 배치가 즉시 버려진다.** 일시 장애는 그냥 던지면 503 이 된다.
 */
public class PermanentIngestException(message: String, cause: Throwable? = null) :
	RuntimeException(message, cause)
