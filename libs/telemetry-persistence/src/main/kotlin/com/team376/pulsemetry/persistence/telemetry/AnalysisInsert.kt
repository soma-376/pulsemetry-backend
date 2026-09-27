package com.team376.pulsemetry.persistence.telemetry

/**
 * `input()` 으로 받는 INSERT 문. 행은 [AnalysisRowWriter] 의 JSONEachRow 이고, 컬럼 목록을 명시한다. 두 분석 테이블은
 * [fenced], 수신 ledger 는 [query] 다.
 *
 * ## Float64 는 서버가 문자열에서 정밀 파싱한다
 *
 * ClickHouse 24.8 의 입력 포맷은 Float64 토큰을 **가장 가까운 double 로 읽지 않는다** — 가장 짧은 왕복 표기를 보내도
 * 무작위 double 의 약 1/3 이 1 ULP 어긋난다. `precise_float_parsing` 설정도 입력 포맷에는 닿지 않는다. 그 설정이
 * 적용되는 곳은 `toFloat64(문자열)` 함수라서, Float64 컬럼은 문자열로 받아(`input()` 의 선언) `toFloat64` 로 옮긴다.
 * 나머지 컬럼은 받은 그대로 넣는다.
 *
 * `input_format_skip_unknown_fields = 0` 이라 선언에 없는 키는 조용히 버려지지 않고 요청이 거부된다(영구 오류).
 *
 * ## 분석 테이블은 삭제 경계를 서버에서 다시 검사한다 (ADR 0024 §2)
 *
 * [fenced] 의 `WHERE` 가 `telemetry_retention_fence` 의 tenant 값보다 이른 `source_time` 을 버린다. 스칼라 서브쿼리는 쿼리가 서버에
 * 등록된 뒤 시작 때 한 번 평가된다 — 그래서 보존 작업은 fence 를 쓴 뒤 그때 실행 중이던 INSERT 만 기다리면 되고, 그 뒤에
 * 등록되는 INSERT 는 새 fence 로 판정된다. fence 가 없으면 `DateTime64` 의 하한이라 아무것도 버리지 않는다. 쓰는 쪽이 넘긴
 * 경계로 이미 거른 행이라 평소에는 버릴 것이 없다 — 이 조건은 경계가 움직이는 순간의 경쟁을 닫는다.
 *
 * `async_insert = 0` 을 고정한다. 비동기 버퍼는 반환 뒤에 쓰기를 적용해 INSERT 를 process list 밖으로 뺀다.
 *
 * `{tenant:String}` 은 요청 파라미터다([AnalysisWriteBoundary]).
 */
internal object AnalysisInsert {

	fun query(table: String, columns: List<ColumnSpec>): String = build(table, columns, where = "", settings = SETTINGS)

	/** 분석 테이블의 INSERT — [query] 에 fence 조건과 동기 INSERT 를 더한다. `{tenant:String}` 파라미터가 필요하다([send]). */
	fun fenced(table: String, columns: List<ColumnSpec>): String {
		require(columns.any { it.name == SOURCE_TIME }) { "fence 는 source_time 이 있는 분석 테이블에만 건다: $table" }
		return build(table, columns, where = "WHERE $SOURCE_TIME >= $FENCE_FLOOR ", settings = "async_insert = 0, $SETTINGS")
	}

	private fun build(table: String, columns: List<ColumnSpec>, where: String, settings: String): String {
		val names = columns.joinToString(", ") { it.name }
		val select = columns.joinToString(", ") { column ->
			when (column.baseType) {
				FLOAT64 -> "toFloat64(${column.name})"
				FLOAT64_ARRAY -> "arrayMap(x -> toFloat64(x), ${column.name})"
				else -> column.name
			}
		}
		val structure = columns.joinToString(", ") { "${it.name} ${wireType(it)}" }.replace("'", "\\'")
		return "INSERT INTO $table ($names) SELECT $select FROM input('$structure') ${where}SETTINGS $settings FORMAT JSONEachRow"
	}

	/** 이 요청 하나를 보낸다 — tenant 파라미터와 epoch 를 실은 `query_id` 로. */
	fun send(client: ClickHouseHttpClient, query: String, body: String, boundary: AnalysisWriteBoundary) {
		client.execute(query, body.toByteArray(Charsets.UTF_8), mapOf(TENANT_PARAM to boundary.tenantId), boundary.queryId())
	}

	/** 입력에서 받는 타입 — Float64 는 문자열이다. */
	private fun wireType(column: ColumnSpec): String = when (column.baseType) {
		FLOAT64 -> if (column.nullable) "Nullable(String)" else "String"
		FLOAT64_ARRAY -> "Array(String)"
		else -> column.type
	}

	const val TENANT_PARAM: String = "tenant"

	private const val SOURCE_TIME = "source_time"
	private const val SETTINGS = "precise_float_parsing = 1, input_format_skip_unknown_fields = 0"

	/**
	 * fence 도 MAX 로만 움직여 `max` 가 현재 값이다. 행이 없으면 `DateTime64` 의 하한 — 1970 년 이전 `source_time` 도 남는다.
	 * 이 식의 평가 시점은 `RetentionFenceEvidenceTest` 가 본다.
	 */
	val FENCE_FLOOR: String = "ifNull((SELECT maxOrNull(deleted_before) FROM ${RetentionFence.TABLE} WHERE tenant_id = {$TENANT_PARAM:String}), " +
		"toDateTime64('1900-01-01 00:00:00', 6, 'UTC'))"

	private const val FLOAT64 = "Float64"
	private const val FLOAT64_ARRAY = "Array(Float64)"
}
