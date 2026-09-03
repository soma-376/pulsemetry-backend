package com.team376.pulsemetry.telemetry.enricher

/**
 * 보강이 읽는 외부 백엔드(RDS)에 닿지 못했다 — **일시 장애**다.
 *
 * 조립 앱이 이것을 **503** 과 `Retry-After` 로 매핑한다. 영구 오류(400)로 돌려보내면 데몬이
 * 배치를 즉시 폐기하므로, 다시 보내면 나을 수 있는 실패에는 쓰지 않는다(허브 ADR 0006).
 *
 * **분류를 넓히지 마라.** 스키마 드리프트 같은 영구 오류까지 이 타입으로 감싸면 재시도
 * 큐가 막힌다 — 이식 원본이 `psycopg.OperationalError` 만 잡고 `ProgrammingError` 는
 * 그대로 전파한 이유다.
 *
 * 적재 단계의 짝은 `:libs:telemetry-persistence` 의 `TelemetrySinkUnavailableException` 이고,
 * 그쪽의 영구 오류 짝은 `TelemetrySinkRejectedException` 이다. 이식 원본은 두 단계가 예외 한 벌
 * (`BackendUnavailable`)을 공유했지만 여기서는 모듈이 갈리므로 각자 자기 계약을 갖는다.
 * **일시 장애 둘을 함께 503 으로 묶는 것은 앱의 몫이다.**
 */
public class EnrichmentUnavailableException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)
