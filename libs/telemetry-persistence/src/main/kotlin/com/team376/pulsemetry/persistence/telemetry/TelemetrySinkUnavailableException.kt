package com.team376.pulsemetry.persistence.telemetry

/**
 * ClickHouse 에 닿지 못했거나 ClickHouse 가 지금은 못 받겠다고 답했다 — **일시 장애**다.
 * 조립 앱이 **503** 과 `Retry-After` 로 매핑한다.
 *
 * ## 무엇이 여기 오는가
 *
 * 연결 실패 · 타임아웃 · 인터럽트, 그리고 응답 상태 중 **5xx · 429 · 408** 이다.
 * 그 밖의 4xx 는 [TelemetrySinkRejectedException] 이고 400 이 된다.
 *
 * ## 짝
 *
 * 보강 단계의 짝은 `:libs:telemetry-enricher` 의 `EnrichmentUnavailableException` 이다.
 * 이식 원본은 두 단계가 예외 한 벌(`BackendUnavailable`)을 공유했지만 여기서는 모듈이 갈리므로
 * 각자 자기 계약을 갖는다. **둘을 함께 503 으로 묶는 것은 앱의 몫이다.**
 *
 * 근거는 허브 ADR 0006 이다. 그 ADR 없이 분류를 넓히지 마라.
 */
public class TelemetrySinkUnavailableException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)
