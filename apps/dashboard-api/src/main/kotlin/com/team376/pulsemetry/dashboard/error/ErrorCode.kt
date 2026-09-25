package com.team376.pulsemetry.dashboard.error

import org.springframework.http.HttpStatus

/**
 * 오류 본문의 코드와 상태 (ADR 0022 §5). 코드를 더하는 것은 ADR 의 목록을 늘리는 일이다.
 *
 * [message] 는 서버가 쓴 고정 문장이다. 예외 메시지나 요청 원문을 싣지 않는다 — 화면은 코드로 문구를 고르고,
 * 이 문장은 사람이 응답을 읽을 때의 설명이다.
 */
enum class ErrorCode(
	val status: HttpStatus,
	val code: String,
	val message: String,
) {
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request", "요청 형식이 올바르지 않습니다."),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated", "로그인이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden", "이 정보를 볼 권한이 없습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "not_found", "요청한 대상을 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "이 주소에서는 쓸 수 없는 요청 방식입니다."),
	SNAPSHOT_EXPIRED(HttpStatus.CONFLICT, "snapshot_expired", "조회 기준이 만료됐습니다. 첫 페이지부터 다시 조회하세요."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "서버 오류가 발생했습니다."),
	UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "일시적으로 조회할 수 없습니다. 잠시 뒤 다시 시도하세요."),
}
