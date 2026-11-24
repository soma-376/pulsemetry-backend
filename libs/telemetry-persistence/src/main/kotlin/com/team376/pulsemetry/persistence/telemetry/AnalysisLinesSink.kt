package com.team376.pulsemetry.persistence.telemetry

/**
 * 인코딩을 마친 분석 행 줄을 INSERT 하나로 보내는 자리(PROJ-341). 두 분석 sink 가 구현한다.
 *
 * Kafka 배치 경로는 레코드마다 [TelemetryEventsSink.encode]·[TelemetryMetricPointsSink.encode] 로 줄을 만들어 원본 레코드에
 * 귀속시킨 뒤, tenant·테이블마다 묶어 이것으로 보낸다. 줄을 어떻게 나눠 보내는지는 부르는 쪽의 몫이고, 서버의 fence 검사·
 * `async_insert = 0`·실패 분류는 [TelemetryEventsSink.insert] 와 같다.
 */
public interface AnalysisLinesSink {

	/** 대상 분석 테이블 이름. */
	public val table: String

	/** [lines] 를 그 순서대로 INSERT 하나로 보낸다. 빈 목록이면 요청을 보내지 않는다. 줄은 모두 [boundary] 의 tenant 것이어야 한다. */
	public fun send(lines: List<String>, boundary: AnalysisWriteBoundary)

	public companion object {
		/** INSERT 본문 — 줄마다 줄바꿈 하나. 배치 경로가 요청 크기를 잴 때도 이 규칙을 쓴다. */
		public fun body(lines: List<String>): String = lines.joinToString(separator = "\n", postfix = "\n")

		/** [line] 하나가 본문에서 차지하는 UTF-8 바이트(줄바꿈 포함). 전송할 때와 같은 인코더로 잰다. */
		public fun bodyBytes(line: String): Long = line.toByteArray(Charsets.UTF_8).size + 1L
	}
}
