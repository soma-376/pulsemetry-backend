package com.team376.pulsemetry.telemetry.collector

/**
 * gRPC 상태 코드와 HTTP 상태의 상호 변환. 상위
 * `receiver/otlpreceiver/internal/errors.GetHTTPStatusCodeFromStatus` 와
 * `internal/statusutil.NewStatusFromMsgAndHTTPCode` 의 표를 그대로 옮긴 것이다.
 *
 * **두 표는 서로의 역함수가 아니다.** 왕복시키면 값이 달라지는 자리가 있으니
 * (503 → Unavailable → 503 은 같지만 Canceled → 503 → Unavailable 은 다르다)
 * 한쪽 표를 다른 쪽에서 유도하지 마라.
 */
internal enum class GrpcCode(val number: Int) {
	OK(0),
	CANCELLED(1),
	UNKNOWN(2),
	INVALID_ARGUMENT(3),
	DEADLINE_EXCEEDED(4),
	NOT_FOUND(5),
	ALREADY_EXISTS(6),
	PERMISSION_DENIED(7),
	RESOURCE_EXHAUSTED(8),
	FAILED_PRECONDITION(9),
	ABORTED(10),
	OUT_OF_RANGE(11),
	UNIMPLEMENTED(12),
	INTERNAL(13),
	UNAVAILABLE(14),
	DATA_LOSS(15),
	UNAUTHENTICATED(16),
	;

	/** 상위 `GetHTTPStatusCodeFromStatus`. 재시도 가능 여부가 이 표의 기준이다. */
	fun toHttpStatus(): Int = when (this) {
		// 재시도 가능
		CANCELLED, DEADLINE_EXCEEDED, ABORTED, OUT_OF_RANGE, UNAVAILABLE, DATA_LOSS -> 503
		RESOURCE_EXHAUSTED -> 429
		// 재시도 불가
		INVALID_ARGUMENT -> 400
		UNAUTHENTICATED -> 401
		PERMISSION_DENIED -> 403
		UNIMPLEMENTED -> 404
		else -> 500
	}

	companion object {
		/**
		 * 상위 `NewStatusFromMsgAndHTTPCode`. grpc 공식 매핑과 두 자리가 다른데
		 * (429 → ResourceExhausted, 400 → InvalidArgument) 상위 주석이 그것을 의도된 예외로 못박았다.
		 */
		fun ofHttpStatus(httpStatus: Int): GrpcCode = when (httpStatus) {
			400 -> INVALID_ARGUMENT
			401 -> UNAUTHENTICATED
			403 -> PERMISSION_DENIED
			404 -> UNIMPLEMENTED
			429 -> RESOURCE_EXHAUSTED
			502, 503, 504 -> UNAVAILABLE
			else -> UNKNOWN
		}
	}
}
