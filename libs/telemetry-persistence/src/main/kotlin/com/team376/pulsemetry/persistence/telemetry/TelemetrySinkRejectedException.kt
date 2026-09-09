package com.team376.pulsemetry.persistence.telemetry

/**
 * ClickHouse 가 요청 자체를 거부했다 — **영구 오류**다. 조립 앱이 **400** 으로 매핑한다.
 *
 * 구문 오류(400) · 인증 실패(401·403) · 없는 테이블(404) 처럼 같은 배치를 다시 보내도 같은 답이
 * 오는 응답이 여기 온다. 과부하 계열(`429`)과 타이밍 계열(`408`)은 여기가 아니라
 * [TelemetrySinkUnavailableException] 이다.
 *
 * 근거는 허브 ADR 0006 이다. 그 ADR 없이 분류를 넓히지 마라 — 넓히면 스키마 불일치가
 * 다시 재시도로 맴돌고 영원히 드러나지 않는다.
 */
public class TelemetrySinkRejectedException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)
