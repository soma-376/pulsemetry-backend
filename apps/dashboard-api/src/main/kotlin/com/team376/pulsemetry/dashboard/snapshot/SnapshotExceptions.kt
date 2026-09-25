package com.team376.pulsemetry.dashboard.snapshot

/**
 * snapshot 을 지금 만들 수 없다 — 동시 build 한도, 공개 CAS 실패, 복사 검증 실패. 원천 장애가 아니지만 요청자가 할 일은 같다(잠시 뒤 다시).
 * 응답은 503 `unavailable` + `Retry-After` 다. 생성 중·실패의 HTTP 표현은 프론트와 합의 전이다 — 이것이 합의 전 기본값이다.
 * [reason] 은 로그와 manifest 의 `failure_reason` 에만 남는다.
 */
class SnapshotUnavailableException(val reason: String) : RuntimeException(reason)
