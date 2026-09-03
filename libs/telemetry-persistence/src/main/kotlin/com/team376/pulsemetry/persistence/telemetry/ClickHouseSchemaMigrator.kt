package com.team376.pulsemetry.persistence.telemetry

/**
 * `enriched_events` 스키마를 적용한다. **매 기동마다 전량이다** (ADR 0015).
 *
 * Flyway 가 ClickHouse 를 다루지 못해(허브 ADR 0004) 여기가 그 자리를 받는다. 원장 테이블도
 * 체크섬도 두지 않는 대신 **모든 문장이 멱등이어야 한다**는 규약을 진다 — 그러면 두 인스턴스가
 * 동시에 기동해도 조율이 필요 없다. ClickHouse 에는 advisory lock 이 없으므로 이 규약이
 * 분산 락을 대신하는 셈이다.
 *
 * ## 새 변경은 새 파일이다
 *
 * `V1` 을 고치지 마라. `CREATE TABLE IF NOT EXISTS` 는 이미 있는 테이블에 **아무 일도 하지
 * 않으므로**, V1 의 컬럼을 바꿔도 기존 환경에서는 조용히 무시된다. 컬럼을 더할 때는 `V2` 를
 * 만들어 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 를 쓰고 [MIGRATIONS] 에 등록한다.
 *
 * 파괴적 변경(컬럼 삭제·타입 변경·`ORDER BY` 변경)은 이 경로로 하지 않는다 — 런북이 필요하다.
 *
 * ## 조립
 *
 * 빈이 아니다(ADR 0011). **언제 부를지와 실패했을 때 어떻게 할지는 조립 앱이 정한다** —
 * 이식 원본은 다섯 번까지 재시도한 뒤 실패해도 서버를 띄웠고, 이후 적재가 503 을 돌려주게 했다.
 */
public class ClickHouseSchemaMigrator(
	private val client: ClickHouseHttpClient,
) {

	/** [MIGRATIONS] 를 순서대로 실행한다. 이미 적용된 것은 멱등이라 아무 일도 하지 않는다. */
	public fun apply() {
		for (migration in MIGRATIONS) {
			for (statement in statementsOf(read(migration))) {
				client.execute(statement)
			}
		}
	}

	private fun read(migration: String): String =
		ClickHouseSchemaMigrator::class.java.getResourceAsStream(LOCATION + migration)
			?.readBytes()?.decodeToString()
			?: error("$LOCATION$migration 을 찾지 못했다")

	/**
	 * 세미콜론으로 문장을 나눈다.
	 *
	 * ⚠️ **문자열 리터럴 안의 세미콜론을 견디지 못한다.** 이식 원본의 `apply_ddl` 과 같은
	 * 한계이고, DDL 만 담는 파일이라 지금은 닿지 않는다. 리터럴이 필요해지면 이 분해를 먼저 고친다.
	 */
	private fun statementsOf(sql: String): List<String> =
		sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }

	public companion object {
		/**
		 * 적용 순서. **클래스패스를 훑지 않는다** — 순서가 파일시스템이나 jar 항목 순서에
		 * 좌우되면 안 되고, 무엇이 적용되는지가 리뷰에 보여야 한다.
		 */
		public val MIGRATIONS: List<String> = listOf("V1__enriched_events.sql")

		public const val LOCATION: String = "/clickhouse/"
	}
}
