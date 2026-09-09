package com.team376.pulsemetry.telemetry.collector

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * 상태 매핑 표의 전사. 상위
 * `receiver/otlpreceiver/internal/errors.GetHTTPStatusCodeFromStatus` 와
 * `internal/statusutil.NewStatusFromMsgAndHTTPCode` 가 원본이다.
 */
internal class GrpcCodeTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(
		// 재시도 가능
		"CANCELLED,          503",
		"DEADLINE_EXCEEDED,  503",
		"ABORTED,            503",
		"OUT_OF_RANGE,       503",
		"UNAVAILABLE,        503",
		"DATA_LOSS,          503",
		"RESOURCE_EXHAUSTED, 429",
		// 재시도 불가
		"INVALID_ARGUMENT,   400",
		"UNAUTHENTICATED,    401",
		"PERMISSION_DENIED,  403",
		"UNIMPLEMENTED,      404",
		// 나머지는 전부 500
		"INTERNAL,           500",
		"UNKNOWN,            500",
		"NOT_FOUND,          500",
		"ALREADY_EXISTS,     500",
		"FAILED_PRECONDITION,500",
		"OK,                 500",
	)
	@DisplayName("gRPC 코드에서 HTTP 상태로")
	fun mapsGrpcToHttp(code: GrpcCode, expected: Int) {
		assertThat(code.toHttpStatus()).isEqualTo(expected)
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(
		"400, INVALID_ARGUMENT",
		"401, UNAUTHENTICATED",
		"403, PERMISSION_DENIED",
		"404, UNIMPLEMENTED",
		"429, RESOURCE_EXHAUSTED",
		"502, UNAVAILABLE",
		"503, UNAVAILABLE",
		"504, UNAVAILABLE",
		"418, UNKNOWN",
		"500, UNKNOWN",
	)
	@DisplayName("HTTP 상태에서 gRPC 코드로 — 429·400 은 grpc 공식 매핑과 다른 의도된 예외다")
	fun mapsHttpToGrpc(httpStatus: Int, expected: GrpcCode) {
		assertThat(GrpcCode.ofHttpStatus(httpStatus)).isEqualTo(expected)
	}

	@Test
	@DisplayName("두 표는 서로의 역함수가 아니다 — 한쪽에서 다른 쪽을 유도하지 마라")
	fun theTwoTablesAreNotInverses() {
		// 500 은 UNKNOWN 으로 갔다가 다시 500 으로 돌아오지만,
		assertThat(GrpcCode.ofHttpStatus(500).toHttpStatus()).isEqualTo(500)
		// CANCELLED 는 503 으로 갔다가 UNAVAILABLE 로 돌아온다.
		assertThat(GrpcCode.ofHttpStatus(GrpcCode.CANCELLED.toHttpStatus()))
			.isEqualTo(GrpcCode.UNAVAILABLE)
			.isNotEqualTo(GrpcCode.CANCELLED)
	}

	@Test
	@DisplayName("코드 번호가 gRPC 규격 그대로다 — 와이어에 나가는 값이다")
	fun numbersMatchTheGrpcSpec() {
		assertThat(GrpcCode.entries.map { it.number }).isEqualTo((0..16).toList())
	}
}
