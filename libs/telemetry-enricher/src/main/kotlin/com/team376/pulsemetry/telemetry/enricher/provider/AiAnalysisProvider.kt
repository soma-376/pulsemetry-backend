package com.team376.pulsemetry.telemetry.enricher.provider

/**
 * `ai_analysis` provider — **no-op 스텁**이다. 실연동은 이 이식의 범위가 아니다.
 *
 * 지우지 마라. **등록된 모든 provider 가 항상 항목을 쓰므로**(ADR 0017 규칙 8), 이 셋이 있어야
 * `enrichment_json` 이 `{"ai_analysis":{},"github":{},"jira":{},"org":{...}}` 가 된다. 빼면 저장되는 값이 달라진다.
 * 주석은 [EnrichmentProvider.annotate] 의 기본(빈 맵)이다.
 *
 * 실구현이 붙을 때 산출물을 컬럼으로 승격하려면 ADR 0017 을 먼저 개정한다.
 */
public class AiAnalysisProvider : EnrichmentProvider {

	override val name: String = "ai_analysis"
}
