package com.team376.pulsemetry.dashboard.cache

/**
 * ClickHouse `dashboard_cache` 의 DDL 을 적용한다 — **매 기동마다 전량이다**(ADR 0015 의 규약 · ADR 0023 §1·§3).
 *
 * 원장 테이블도 체크섬도 두지 않는 대신 모든 문장이 멱등이어야 한다. 배포된 파일을 고치지 않고 다음 번호 파일을 [MIGRATIONS] 에 더한다 —
 * `CREATE … IF NOT EXISTS` 는 이미 있는 테이블·뷰에 아무 일도 하지 않으므로 고친 내용은 기존 환경에서 조용히 무시된다.
 * 파괴적 변경(열 삭제·타입·정렬 키·뷰 정의 변경)은 이 경로로 하지 않는다. 캐시라서 테이블을 새 이름으로 만들고 옛것을 버리는 편이 쉽다.
 */
class ClickHouseCacheSchema(
	private val client: ClickHouseCacheClient,
) {

	fun apply() {
		for (statement in statements()) {
			client.execute(statement)
		}
	}

	companion object {
		/** 적용 순서. 클래스패스를 훑지 않는다 — 무엇이 적용되는지가 리뷰에 보여야 한다. */
		val MIGRATIONS: List<String> = listOf(
			"V1__dashboard_cache_snapshot.sql",
		)

		const val LOCATION: String = "/clickhouse/dashboard-cache/"

		fun statements(): List<String> = MIGRATIONS.flatMap { split(read(it)) }

		private fun read(migration: String): String =
			ClickHouseCacheSchema::class.java.getResourceAsStream(LOCATION + migration)
				?.readBytes()?.decodeToString()
				?: error("$LOCATION$migration 을 찾지 못했다")

		/**
		 * `--` 주석 줄을 벗긴 뒤 세미콜론으로 나눈다. 적재 모듈의 `ClickHouseSchemaMigrator` 와 같은 분해다 —
		 * **문자열 리터럴 안의 세미콜론과 `--` 는 견디지 못한다.** DDL 파일에 그런 리터럴을 쓰지 않는다.
		 */
		private fun split(sql: String): List<String> =
			sql.lineSequence()
				.filterNot { it.trimStart().startsWith("--") }
				.joinToString("\n")
				.split(';')
				.map { it.trim() }
				.filter { it.isNotEmpty() }
	}
}
