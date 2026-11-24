package com.team376.pulsemetry.persistence.telemetry

/**
 * 분석 INSERT 실패의 ClickHouse 오류 코드 해석(PROJ-341). 실패 분류(영구·일시)는 [ClickHouseHttpClient] 의 상태 규칙 그대로이고,
 * 이 객체는 Kafka 배치 경로가 **한 레코드의 입력 결함**과 공통 장애를 가를 때만 쓴다.
 *
 * ## 입력 결함 코드
 *
 * HTTP 상태만으로 입력 결함을 판정하지 않는다. ClickHouse 24.8.14.39 에서 프로젝트의 실제 분석 INSERT(`INSERT … SELECT … FROM input()
 * … FORMAT JSONEachRow`)로 두 달에 걸친 블록의 마지막 행에 결함을 넣었을 때 **요청이 쓰기 전에 실패하고 두 달 모두 0행**임을
 * 재현으로 확인한 코드만 넣는다(`InputDefectCodesEvidenceTest`). 그중 69·130·131 은 HTTP 500 으로 온다. ClickHouse 이미지를 올리면
 * 증거 시험이 먼저 통과해야 한다. 이 목록 밖의 오류는 레코드 결함으로 단정하지 않는다.
 *
 * ClickHouse 는 범위 밖 정수·일부 시각·Map 값 타입, `Nullable(Float64)` 열의 비숫자 문자열(NULL 로)을 오류 없이 바꿔 넣는다 — 그런 값은
 * 인코더([AnalysisRowWriter])가 먼저 거부한다.
 */
public object ClickHouseErrorCodes {

	public const val CANNOT_PARSE_QUOTED_STRING: Int = 26
	public const val CANNOT_PARSE_INPUT_ASSERTION_FAILED: Int = 27
	public const val ARGUMENT_OUT_OF_BOUND: Int = 69
	public const val CANNOT_PARSE_NUMBER: Int = 72
	public const val INCORRECT_DATA: Int = 117
	public const val CANNOT_READ_ARRAY_FROM_TEXT: Int = 130
	public const val TOO_LARGE_STRING_SIZE: Int = 131
	public const val MEMORY_LIMIT_EXCEEDED: Int = 241

	public val INPUT_DEFECT_CODES: Set<Int> = setOf(
		CANNOT_PARSE_QUOTED_STRING,
		CANNOT_PARSE_INPUT_ASSERTION_FAILED,
		ARGUMENT_OUT_OF_BOUND,
		CANNOT_PARSE_NUMBER,
		INCORRECT_DATA,
		CANNOT_READ_ARRAY_FROM_TEXT,
		TOO_LARGE_STRING_SIZE,
	)

	/**
	 * 코드 241 의 한도 범위 — `query`(해당 쿼리)·`user`(그 사용자의 동시 쿼리 합)·`total`(서버 전체)·`unknown`. 원인 추정의 단서일 뿐
	 * 개별 거부의 근거가 아니다. 오류 원문 대신 이 라벨만 로그·넘김 목록에 남긴다.
	 */
	public fun memoryScope(code: Int?, message: String?): String? {
		if (code != MEMORY_LIMIT_EXCEEDED) return null
		val text = message.orEmpty()
		return when {
			"(for query)" in text -> "query"
			"(for user)" in text -> "user"
			"(total)" in text -> "total"
			else -> "unknown"
		}
	}
}
