package com.team376.pulsemetry.telemetry.enricher.provider

import com.team376.pulsemetry.telemetry.enricher.Enriched

/**
 * `github` provider — **no-op 스텁**이다. 실연동은 이 이식의 범위가 아니다.
 *
 * 지우지 마라. 이식 원본의 registry 가 **발견된 모든 provider 에 대해 항상 항목을 쓰므로**,
 * 이 셋이 있어야 `enrichment_json` 이 현행 파이프라인과 같은 값이 된다 —
 * `{"ai_analysis":{},"github":{},"jira":{},"org":{...}}`. 빼면 저장되는 값이 달라진다.
 *
 * 실구현이 붙을 때 산출물을 컬럼으로 승격하려면 ADR 0017 을 먼저 개정한다.
 */
public class GithubProvider : EnrichmentProvider {

	override val name: String = "github"

	override fun enrich(item: Enriched, ctx: MutableMap<String, Any?>): Map<String, Any?> = emptyMap()
}
