package com.team376.pulsemetry.telemetry.collector.archive

/**
 * 원본 아카이브를 가르는 제품. 현행 `filter/codex` · `filter/claude_code` 가 하던 일이다.
 *
 * ## 판정은 resource 의 `service.name` 하나다
 *
 * 원본 OTTL 은 조건이 참인 레코드를 **버리는** processor 라 이렇게 쓰여 있다.
 *
 * ```yaml
 * filter/codex:
 *   error_mode: silent
 *   log_conditions: ['resource.attributes["service.name"] != "codex_cli_rs"']
 * ```
 *
 * 조건이 레코드 컨텍스트에서 평가되지만 읽는 값은 resource 속성이라 한 resource 아래 레코드의
 * 판정이 모두 같다. 그래서 이식본은 **resource 단위로** 가른다 — 결과가 같고 훨씬 단순하다.
 *
 * ## `service.name` 이 없으면 어느 아카이브에도 남지 않는다
 *
 * OTTL 의 `nil != "codex_cli_rs"` 는 참이다. `pkg/ottl/compare.go` 가 nil 과 문자열의 비교를
 * `invalidComparison` 으로 떨어뜨리고, 그 함수는 `ne` 에 대해 참을 준다. 조건이 참이면 버리므로
 * **양쪽 필터가 모두 버린다.** `error_mode: silent` 는 여기서 발동조차 하지 않는다 — 오류가 아니라
 * 정상적으로 참이 나오는 것이다. 양쪽에 남는 것으로 오해하기 쉬운 자리다.
 */
public enum class Product(
	/** resource 속성 `service.name` 의 값. 하이픈과 밑줄이 제품마다 다르다 — 원본 그대로다. */
	public val serviceName: String,
	/** 아카이브 경로의 제품 구간. 현행 `/data/<segment>/<signal>.jsonl` 과 같다. */
	public val archiveSegment: String,
) {
	CLAUDE_CODE("claude-code", "claude_code"),
	CODEX("codex_cli_rs", "codex"),
	;

	public companion object {
		private val BY_SERVICE_NAME = entries.associateBy { it.serviceName }

		/** 아는 제품이 아니면 null — 그 resource 는 어느 아카이브에도 적재되지 않는다. */
		public fun ofServiceName(serviceName: String?): Product? = BY_SERVICE_NAME[serviceName]
	}
}
