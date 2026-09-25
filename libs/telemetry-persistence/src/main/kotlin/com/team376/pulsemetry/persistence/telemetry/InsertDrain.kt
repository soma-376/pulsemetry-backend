package com.team376.pulsemetry.persistence.telemetry

import java.time.Duration

/**
 * fence 를 쓴 뒤, 그 순간 서버에서 실행 중이던 INSERT 가 모두 끝나기를 기다린다(ADR 0024 §4 의 3).
 *
 * **근거는 서버의 process list 다.** fence INSERT 가 반환된 뒤에 [running] 을 읽으면, 그 목록에 없는 INSERT 는 이미 끝났거나(그 파트는
 * 뒤의 DELETE 가 덮는다) 나중에 등록되어 새 fence 를 읽는다 — 등록이 fence 서브쿼리 평가보다 먼저이고 평가는 시작 때 한 번이다
 * (`RetentionFenceEvidenceTest`). 그래서 목록에 있던 것만 기다리면 된다. 테이블·태그를 가리지 않고 모든 INSERT 를 모은다 — 태그를
 * 빠뜨린 쓰기도 놓치지 않는다.
 *
 * 요청 timeout 이나 취소 신호를 근거로 쓰지 않는다. 대기 상한 안에 목록이 비지 않으면 false 이고, 호출자는 완료를 선언하지 않는다.
 *
 * 단일 ClickHouse 서버를 전제한다(ADR 0024 Context). 빈이 아니다(ADR 0011).
 */
public class InsertDrain(
	private val client: ClickHouseHttpClient,
	private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {

	/** 지금 실행 중인 INSERT 의 `query_id`. */
	public fun running(): Set<String> =
		client.execute("SELECT query_id FROM system.processes WHERE query_kind = 'Insert' FORMAT TSVRaw")
			.lines().filter { it.isNotBlank() }.toSet()

	/** [queryIds] 가 모두 목록에서 빠지면 true. [timeout] 이 지나도 남아 있으면 false. 빈 집합이면 조회 없이 true. */
	public fun awaitFinished(queryIds: Set<String>, timeout: Duration, pollInterval: Duration): Boolean {
		require(!timeout.isNegative && !pollInterval.isNegative && !pollInterval.isZero) { "대기 상한·간격이 잘못됐다: $timeout · $pollInterval" }
		if (queryIds.isEmpty()) return true
		val deadline = System.nanoTime() + timeout.toNanos()
		while (true) {
			if (stillRunning(queryIds) == 0L) return true
			if (System.nanoTime() - deadline >= 0) return false
			sleep(pollInterval)
		}
	}

	private fun stillRunning(queryIds: Set<String>): Long =
		client.execute(
			"SELECT count() FROM system.processes WHERE has({ids:Array(String)}, query_id) FORMAT TSV",
			params = mapOf("ids" to queryIds.joinToString(",", "[", "]") { "'" + it.replace("\\", "\\\\").replace("'", "\\'") + "'" }),
		).trim().toLong()
}
