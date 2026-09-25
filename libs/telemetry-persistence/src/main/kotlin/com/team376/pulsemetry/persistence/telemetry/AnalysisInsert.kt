package com.team376.pulsemetry.persistence.telemetry

/**
 * 분석 테이블의 INSERT 문. 행은 [AnalysisRowWriter] 의 JSONEachRow 이고, 컬럼 목록을 명시한다.
 *
 * ## Float64 는 서버가 문자열에서 정밀 파싱한다
 *
 * ClickHouse 24.8 의 입력 포맷은 Float64 토큰을 **가장 가까운 double 로 읽지 않는다** — 가장 짧은 왕복 표기를 보내도
 * 무작위 double 의 약 1/3 이 1 ULP 어긋난다. `precise_float_parsing` 설정도 입력 포맷에는 닿지 않는다. 그 설정이
 * 적용되는 곳은 `toFloat64(문자열)` 함수라서, Float64 컬럼은 문자열로 받아(`input()` 의 선언) `toFloat64` 로 옮긴다.
 * 나머지 컬럼은 받은 그대로 넣는다.
 *
 * `input_format_skip_unknown_fields = 0` 이라 선언에 없는 키는 조용히 버려지지 않고 요청이 거부된다(영구 오류).
 */
internal object AnalysisInsert {

	fun query(table: String, columns: List<ColumnSpec>): String {
		val names = columns.joinToString(", ") { it.name }
		val select = columns.joinToString(", ") { column ->
			when (column.baseType) {
				FLOAT64 -> "toFloat64(${column.name})"
				FLOAT64_ARRAY -> "arrayMap(x -> toFloat64(x), ${column.name})"
				else -> column.name
			}
		}
		val structure = columns.joinToString(", ") { "${it.name} ${wireType(it)}" }.replace("'", "\\'")
		return "INSERT INTO $table ($names) SELECT $select FROM input('$structure') " +
			"SETTINGS precise_float_parsing = 1, input_format_skip_unknown_fields = 0 FORMAT JSONEachRow"
	}

	/** 입력에서 받는 타입 — Float64 는 문자열이다. */
	private fun wireType(column: ColumnSpec): String = when (column.baseType) {
		FLOAT64 -> if (column.nullable) "Nullable(String)" else "String"
		FLOAT64_ARRAY -> "Array(String)"
		else -> column.type
	}

	private const val FLOAT64 = "Float64"
	private const val FLOAT64_ARRAY = "Array(Float64)"
}
