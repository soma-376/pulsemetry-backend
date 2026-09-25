package com.team376.pulsemetry.dashboard.store

/**
 * 저장소(원천·캐시)를 지금 읽거나 쓸 수 없다 — 연결 실패, 제한 시간, 서버 쪽 일시 장애, 계정 인증 실패.
 * 응답은 503 `unavailable` 이다(ADR 0022 §5). 메시지는 로그에만 남는다.
 */
open class StoreUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 조회가 상한(시간·행·바이트·메모리)을 넘었다. **잘라 낸 결과를 내지 않는다** — 부분 결과를 정상 총액처럼 보이면 안 된다.
 * 응답은 [StoreUnavailableException] 과 같은 503 이고, 한도 초과임은 로그로 구별한다.
 */
class StoreLimitExceededException(message: String) : StoreUnavailableException(message)

/**
 * 저장소가 문장을 거부했다 — 문법·식별자·타입 오류, 읽기 전용 위반처럼 다시 보내도 같은 결과인 것. 요청자의 잘못이 아니라
 * 이 앱의 결함이므로 응답은 500 `internal_error` 다.
 */
class StoreQueryRejectedException(message: String) : RuntimeException(message)
