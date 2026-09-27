package com.team376.pulsemetry.telemetry.enricher.provider

import com.team376.pulsemetry.telemetry.adapter.observation.ObservationEnvelope

/**
 * 외부 맥락(GitHub·Jira·AI 분석)을 하나의 인터페이스로 통일한 SPI.
 *
 * **패키지 이름이 `provider` 인 것은 어형 규칙이다** — 인터페이스와 그 구현만 모인 패키지는
 * 인터페이스 이름의 소문자형을 쓴다(ADR 0010 · `docs/module-map.md` 3절). 모듈 이름
 * `enricher` 와 다른 것이 정상이다.
 *
 * ## 계약
 *
 * - [annotate] 는 주석 맵을 돌려준다. 아무것도 안 하면 빈 맵이다. **행을 드롭하지 않는다.**
 * - 산출물은 `enrichment_json` 의 자기 이름 항목으로만 적재된다. **공통 컬럼으로 승격하지 않는다** — 승격 예외는
 *   조직 보강의 `member_id`·`team_id_as_of`·`team_ids_as_of` 셋뿐이고(ADR 0020 §5 · ADR 0017 규칙 7), 그것은 이 SPI 가
 *   아니라 `ObservationEnricher` 가 직접 채운다. 늘리려면 ADR 0017 을 개정한다.
 * - 새 provider 는 파일 하나를 더하고 조립 앱의 목록에 넣으면 끝이다. 코어는 안 바뀐다.
 */
public interface EnrichmentProvider {

	/** 주석 맵의 키가 되는 이름. `ObservationEnricher` 안에서 유일해야 하고 `org` 일 수 없다. */
	public val name: String

	/** 작을수록 먼저 돈다. 같으면 [name] 순이다. */
	public val order: Int
		get() = DEFAULT_ORDER

	/**
	 * 관측 하나의 주석. 결과가 `enrichment_json` 의 자기 이름 항목이 된다.
	 *
	 * 기본은 빈 맵이다 — 스텁 셋은 이 기본으로 빈 항목을 쓴다(ADR 0017 규칙 8). [ctx] 는 **push 하나 안에서만** 공유되는
	 * 작업 공간이다. provider 사이의 조회 결과 재사용이 여기 산다. 키는 provider 가 자기 것임을 알 수 있게 짓는다.
	 */
	public fun annotate(envelope: ObservationEnvelope, ctx: MutableMap<String, Any?>): Map<String, Any?> = emptyMap()

	public companion object {
		public const val DEFAULT_ORDER: Int = 100
	}
}
