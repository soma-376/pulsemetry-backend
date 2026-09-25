package com.team376.pulsemetry.dashboard.error

/**
 * 400 본문의 `fieldErrors[].code` (ADR 0022 §5). `field` 는 요청 파라미터·경로 변수의 이름 그대로다.
 */
enum class FieldErrorCode(val wire: String) {
	/** 필수인데 없다. */
	REQUIRED("required"),

	/** 형식이 틀렸다 — 날짜가 `YYYY-MM-DD` 가 아니거나 없는 날짜, 정수가 아님, UUID 가 아님. */
	INVALID_FORMAT("invalid_format"),

	/** 허용 목록 밖의 값 — 지원하지 않는 `timeZone`·`compare`·`sort`. */
	UNSUPPORTED_VALUE("unsupported_value"),

	/** 범위 밖 — 기간 1–366일, `limit` 1–최대, `q` 길이. */
	OUT_OF_RANGE("out_of_range"),

	/** 종료일이 시작일보다 앞이다. */
	INVALID_ORDER("invalid_order"),

	/** 해석할 수 없거나 이 요청에 묶이지 않은 cursor. */
	INVALID_CURSOR("invalid_cursor"),
}
