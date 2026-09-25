package com.team376.pulsemetry.telemetry.collector.archive

/**
 * 원본 아카이브를 가르는 제품 구간. 판정은 resource 의 `service.name` **정확 일치** 하나다.
 *
 * ## 이식 원본보다 넓다 — 모르는 서비스도 버리지 않는다 (ADR 0020 §6)
 *
 * 이식 원본(`filter/codex` · `filter/claude_code`)은 `claude-code` 와 `codex_cli_rs` 두 값만 남기고
 * 나머지 resource 를 **어느 아카이브에도 쓰지 않았다**. 그런데 실수신에서 Codex 는 `codex_cli_rs` 가
 * 아니라 `codex-app-server` · `Codex Desktop` 으로 온다 — 원본이 그대로 버려졌다. 아카이브는 재처리의
 * 복구 원천이므로 모르는 서비스와 `service.name` 이 없는 resource 도 [UNKNOWN] 구간에 남긴다.
 *
 * - 별칭은 정확 일치다. substring·대소문자 무시 판별을 하지 않는다 — 비슷한 이름의 다른 서비스를
 *   한 제품으로 섞으면 구간의 의미가 흐려진다. 새 별칭은 이 표에 더한다.
 * - 이 구간은 **아카이브 경로**다. 분석 행의 `product`·`surface` 는 정규화 단계의 registry 가 따로
 *   정한다(ADR 0020 부록 A.1).
 */
public enum class Product(
	/** 아카이브 경로의 제품 구간. 현행 `/data/<segment>/<signal>.jsonl` 과 같다. */
	public val archiveSegment: String,
	/** 이 구간으로 가는 `service.name` 값들. 하이픈·밑줄·공백이 원본 그대로다. */
	public val serviceNames: Set<String>,
) {
	CLAUDE_CODE("claude_code", setOf("claude-code")),
	CODEX("codex", setOf("codex_cli_rs", "codex-app-server", "Codex Desktop")),

	/** 별칭 표에 없는 서비스와 `service.name` 이 없거나 문자열이 아닌 resource. */
	UNKNOWN("unknown", emptySet()),
	;

	public companion object {
		private val BY_SERVICE_NAME: Map<String, Product> =
			entries.flatMap { product -> product.serviceNames.map { it to product } }.toMap()

		/** 정확 일치하는 제품. 없으면 [UNKNOWN] — 그 resource 도 아카이브된다. */
		public fun ofServiceName(serviceName: String?): Product = BY_SERVICE_NAME[serviceName] ?: UNKNOWN
	}
}
