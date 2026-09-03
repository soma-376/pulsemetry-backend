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
 * 마스킹·아카이브를 마친 시그널을 받는 다음 단계. 지금은 구현이 없다 —
 * 변환 단계(`:libs:telemetry-adapter`)가 PROJ-103 에서 이 자리에 붙는다.
 *
 * 여기서 던진 예외는 재시도 가능으로 보고 **503** 이 된다. 상위도 상태가 실리지 않은 오류의
 * 기본을 `Unavailable` 로 두고 permanent 만 `Internal` 로 올린다. 영구 오류라 재시도가 무의미하면
 * [PermanentIngestException] 을 던진다 — 그래야 **500** 이 되어 배치가 폐기된다.
 */
public fun interface SignalConsumer {
	public fun consume(signal: Signal, request: Message)
}

/**
 * 재시도해도 소용없는 오류. 상위 `consumererror.IsPermanent` 에 대응한다.
 *
 * 현행 계약이 RDS·ClickHouse 장애를 503 으로 돌려보내 collector 가 재시도하게 하고 있으므로
 * (허브 `contracts/telemetry-ingest.md`), **일시적 장애에 이 예외를 쓰면 배치가 버려진다.**
 */
public class PermanentIngestException(message: String, cause: Throwable? = null) :
	RuntimeException(message, cause)
