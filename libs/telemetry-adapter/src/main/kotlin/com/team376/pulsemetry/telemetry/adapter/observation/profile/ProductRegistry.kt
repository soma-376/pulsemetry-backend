package com.team376.pulsemetry.telemetry.adapter.observation.profile

import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.Surface

/** 서비스 별칭으로 정한 제품과 표면. */
public data class ProductIdentity(val product: Product, val surface: Surface)

/**
 * resource `service.name` 의 **정확 일치** 별칭 registry(ADR 0020 부록 A.1). substring·대소문자 무시 판별을 하지 않는다.
 *
 * 관측 이름의 제품 접두사([namePrefixes])는 제품을 **정하는** 근거가 아니다 — 서비스로 정한 제품과 **충돌하는지**
 * 확인하는 데만 쓴다. 충돌하면 `product = unknown`, `mapping_status = ambiguous` 다. 서비스를 모르는데 이름에
 * 접두사가 있다고 제품을 추정하지 않는다.
 */
public object ProductRegistry {

	private val ALIASES: Map<String, ProductIdentity> = mapOf(
		"codex_cli_rs" to ProductIdentity(Product.CODEX, Surface.CLI),
		"Codex Desktop" to ProductIdentity(Product.CODEX, Surface.DESKTOP),
		"codex-app-server" to ProductIdentity(Product.CODEX, Surface.APP_SERVER),
		// Claude Code 의 표면은 검증된 근거가 생기기 전까지 unknown 이다(ADR 0020 부록 A.1).
		"claude-code" to ProductIdentity(Product.CLAUDE_CODE, Surface.UNKNOWN),
	)

	/** 제품별 관측 이름 접두사. 충돌 판정에만 쓴다. */
	private val NAME_PREFIXES: Map<Product, String> = mapOf(
		Product.CLAUDE_CODE to "claude_code.",
		Product.CODEX to "codex.",
	)

	public val UNKNOWN: ProductIdentity = ProductIdentity(Product.UNKNOWN, Surface.UNKNOWN)

	public fun resolve(serviceName: String?): ProductIdentity = serviceName?.let { ALIASES[it] } ?: UNKNOWN

	/**
	 * [name] 이 [product] 가 아닌 다른 제품의 접두사로 시작하면 true. 이름이 없거나 어느 접두사에도 걸리지 않으면
	 * 충돌이 아니다.
	 */
	public fun conflicts(product: Product, name: String?): Boolean {
		if (name == null || product == Product.UNKNOWN) return false
		return NAME_PREFIXES.any { (other, prefix) -> other != product && name.startsWith(prefix) }
	}

	public fun namePrefix(product: Product): String? = NAME_PREFIXES[product]
}
