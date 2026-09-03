package com.team376.pulsemetry.telemetry.adapter.source

import com.team376.pulsemetry.telemetry.adapter.IngestContext
import com.team376.pulsemetry.telemetry.adapter.OtlpRecord
import com.team376.pulsemetry.telemetry.adapter.model.Normalized

/**
 * 벤더 하나를 정규화 스키마에 잇는 SPI.
 *
 * `Normalizer` 가 등록 순서대로 [match] 를 물어 **첫 매치를 쓴다.** 그래서 목록 순서가
 * 의미를 갖는다.
 *
 * 이식 원본은 파이썬 모듈 덕타이핑이었다 — `match`/`to_event` 와 세 상수를 가진 모듈이면
 * 소스였다. 여기서는 인터페이스로 세운다.
 */
internal interface TelemetrySource {

	/**
	 * 이 레코드가 우리 것이면 **정규 이벤트 이름**을, 아니면 null 을 낸다.
	 *
	 * 이름이 어디서 나오는지는 벤더마다 다르다 — claude_code 는 리더가 준 이름을 그대로 보고,
	 * codex 는 로그일 때만 `event.name` 속성을 본다.
	 *
	 * 제품 namespace 접두사와 어댑터 버전은 각 소스의 `*Common` 이 상수로 갖는다. 진단 계층을
	 * 이식하지 않아 소비자가 없으므로 이 인터페이스에는 두지 않는다.
	 */
	fun match(record: OtlpRecord): String?

	/**
	 * 이벤트를 만든다. 이 소스가 모르는 이벤트면 null — 진단은 하되 방출하지 않는다.
	 *
	 * @param eventName [match] 가 돌려준 값이다. 리더가 준 [OtlpRecord.name] 이 아니다.
	 */
	fun toEvent(record: OtlpRecord, eventName: String, context: IngestContext): Normalized?
}
