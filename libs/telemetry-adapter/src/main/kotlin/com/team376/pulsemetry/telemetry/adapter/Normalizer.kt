package com.team376.pulsemetry.telemetry.adapter

import com.google.protobuf.MessageOrBuilder
import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import com.team376.pulsemetry.telemetry.adapter.source.TelemetrySource
import com.team376.pulsemetry.telemetry.adapter.source.claudecode.ClaudeCodeSource
import com.team376.pulsemetry.telemetry.adapter.source.codex.CodexSource

/**
 * OTLP 요청 하나를 정규화 이벤트들로 바꾼다 — 이 모듈의 유일한 진입점.
 *
 * ## 어디에 붙는가
 *
 * 수집 단계(`:libs:telemetry-collector`)가 마스킹·아카이브를 마친 요청을 넘겨준다.
 * **이 모듈은 그쪽 `SignalConsumer` 를 구현하지 않는다** — 단계 모듈끼리 직접 참조하지
 * 않기로 했고(ADR 0010·0011), 그 배선은 조립 앱이 한다.
 *
 * ```
 * SignalConsumer { _, request -> enricher.accept(Normalizer.normalize(request)) }
 * ```
 *
 * ## push 를 통째로 버퍼링한다
 *
 * `call_id` 페어링이 요청 안의 이벤트 전부를 봐야 하므로 스트리밍하지 않는다. 페어링 범위가
 * **요청 하나**라는 사실이 그대로 남는다 — 승인과 실행이 다른 push 로 쪼개지면 조인이
 * 조용히 끊긴다. 구 파이프라인부터 있던 구조적 한계이고, 세션 단위 상태 저장 없이는 못 잇는다.
 */
public object Normalizer {

	/** 등록 순서가 의미를 갖는다 — 먼저 무는 소스가 이긴다. */
	private val sources: List<TelemetrySource> = listOf(ClaudeCodeSource, CodexSource)

	/**
	 * 요청 하나를 정규화한다. 이벤트가 하나도 안 나올 수 있다.
	 *
	 * @param request `ExportLogsServiceRequest` · `ExportTraceServiceRequest` ·
	 *   `ExportMetricsServiceRequest` 중 하나. 신호 종류는 안에 담긴 필드로 판별하므로
	 *   따로 넘기지 않는다.
	 */
	public fun normalize(request: MessageOrBuilder): List<Normalized> {
		val document = ProtoJson.toTree(request)
		val events = mutableListOf<Normalized>()

		for (record in OtlpReader.readAll(document)) {
			// 원본이 `str(tenant_id) if tenant_id else None` 이라 Python 의 falsy 를 따른다 —
			// 빈 문자열과 0 도 "없음"이다.
			val tenantId = record.resourceAttributes["tenant.id"]
				?.takeIf { it != "" && it != 0L && it != 0.0 && it != false }
				?.let { Stringify.of(it) }
			val context = IngestContext(
				tenantId = tenantId,
				rawRecordId = SourceRecordId.of(record.record),
				signal = record.signal,
			)

			// 첫 매치가 이긴다. 아무도 물지 않으면 제품 namespace 밖이라 조용히 버린다.
			var matched: Normalized? = null
			for (source in sources) {
				val eventName = source.match(record) ?: continue
				// 소스가 물었어도 모르는 이벤트면 null 이다 — 그것도 방출하지 않는다.
				matched = source.toEvent(record, eventName, context)
				break
			}
			matched?.let { events += it }
		}

		CallId.pair(events)
		return events
	}
}
