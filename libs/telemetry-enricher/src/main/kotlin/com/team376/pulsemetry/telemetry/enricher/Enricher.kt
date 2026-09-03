package com.team376.pulsemetry.telemetry.enricher

import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import com.team376.pulsemetry.telemetry.enricher.provider.EnrichmentProvider

/**
 * 보강 단계의 진입점. 정규화 이벤트에 provider 주석을 붙여 [Enriched] 로 돌려준다.
 *
 * **행을 드롭하지 않는다** — 입력과 출력의 개수가 언제나 같다. provider 가 아무것도 못 해도
 * 빈 주석이 남을 뿐이다.
 *
 * ## 조립
 *
 * 이 모듈은 Spring 스테레오타입을 두지 않는다(ADR 0011). provider 목록은 조립 앱이 정해
 * 생성자로 넘긴다 — 이식 원본의 `pkgutil` 자동 발견이 그 자리를 대신한다.
 *
 * ```
 * Enricher(listOf(OrgProvider(teamMemberships), GithubProvider(), JiraProvider(), AiAnalysisProvider()))
 * ```
 *
 * 변환 단계와 잇는 배선도 앱이 한다 — 이 클래스는 어댑터의 `Normalizer` 를 부르지 않는다.
 */
public class Enricher(providers: List<EnrichmentProvider>) {

	/**
	 * `order` → `name` 순. `org` 가 `order = 0` 이라 가장 먼저 돌고, 뒤의 provider 가
	 * 그것이 해석한 [Enriched.teamIdsAsOf] 를 읽을 수 있다.
	 */
	private val providers: List<EnrichmentProvider> =
		providers.sortedWith(compareBy({ it.order }, { it.name }))

	init {
		// 이름이 겹치면 뒤엣것이 앞엣것의 주석을 덮어써 한 provider 의 산출물이 조용히 사라진다.
		// 이식 원본은 발견 결과를 이름으로 키잡은 맵에 담아 같은 일을 겪었다 — 여기서는 막는다.
		val duplicates = providers.groupBy { it.name }.filterValues { it.size > 1 }.keys
		require(duplicates.isEmpty()) { "provider 이름이 겹친다: $duplicates" }
	}

	/** provider 이름 목록. 적용 순서 그대로다. */
	public fun providerNames(): List<String> = providers.map { it.name }

	/**
	 * OTLP push 하나를 보강한다.
	 *
	 * 루프가 **이벤트 바깥·provider 안쪽**인 것이 이식 원본과 같다. `ctx` 는 호출마다 새것이라
	 * provider 의 캐시가 push 를 넘어 살아남지 않는다 — `OrgProvider` 의 조회 캐시가 그 위에 산다.
	 */
	public fun enrich(events: List<Normalized>): List<Enriched> {
		val items = events.map { Enriched(it) }
		if (items.isEmpty()) return items

		val ctx: MutableMap<String, Any?> = HashMap()
		for (item in items) {
			for (provider in providers) {
				item.annotations[provider.name] = provider.enrich(item, ctx)
			}
		}
		return items
	}
}
