package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.store.ClickHouseParam

/**
 * 사용량 행의 공급자와 모델 ID 를 정하는 규칙 한 판 (ADR 0023 §1 — build 때 한 번 정해 `snapshot_usage` 에 고정한다).
 *
 * ## 공급자
 *
 * **`product` 로 공급자를 추정하지 않는다**(ADR 0020 §4 — `codex` 라는 사실은 OpenAI 의 근거가 아니다). 공급자는 검증된 적용 범위
 * [ProviderScope] — 제품 · producer(`service_name`) · producer 판 — 안의 행에만 붙는다. 범위 밖이면 미확인(`null`)이다.
 * 분석 테이블에는 공급자 열이 없고 행에 실린 공급자 근거도 아직 없어, 운영의 기본 판 [NONE] 은 범위가 비어 있다 — 모든 행이 미확인이다.
 *
 * ## 모델 ID
 *
 * - 공급자가 있으면 `<provider>/<canonical>`. canonical 은 그 공급자의 별칭 표로 원래 모델명을 옮긴 것이고, 표에 없으면 원래 이름이다.
 * - 미확인이면 `unknown/<product>/<원래 모델명>`.
 * - 성분마다 `%`·`/`·`~` 를 퍼센트 인코딩하고 모델명이 없으면 `~` 다 — 서로 다른 입력이 같은 ID 가 되지 않는다.
 *
 * 분석 원본의 `model` 은 바꾸지 않는다. 모델별 합계와 순위는 **이 ID 로 먼저 모은 뒤** 매긴다.
 */
class ModelResolution(
	val version: String,
	private val providerScopes: List<ProviderScope>,
	/** 공급자 → (원래 모델명 → canonical). */
	private val aliases: Map<String, Map<String, String>>,
) {

	/** 이 범위 안의 사용량 행은 [provider] 의 것이다. 범위는 원천 근거(소스·공식 문서)와 저장소 fixture 로 검증된 것만 둔다. */
	data class ProviderScope(
		val product: String,
		val serviceName: String,
		val productVersions: Set<String>,
		val provider: String,
	) {
		init {
			require(productVersions.isNotEmpty()) { "공급자 범위에 producer 판이 없다: $product/$serviceName" }
		}
	}

	data class Resolved(val provider: String?, val modelId: String)

	init {
		// 같은 판이 두 공급자에 걸리면 근거가 충돌한다 — 그런 표는 만들지 않는다.
		val keys = providerScopes.flatMap { scope -> scope.productVersions.map { Triple(scope.product, scope.serviceName, it) } }
		require(keys.size == keys.toSet().size) { "공급자 범위가 겹친다: $providerScopes" }
		require(aliases.keys.all { provider -> providerScopes.any { it.provider == provider } }) { "범위 없는 공급자의 별칭 표가 있다: ${aliases.keys}" }
	}

	fun resolve(product: String, serviceName: String?, productVersion: String?, model: String?): Resolved {
		val provider = providerScopes.firstOrNull {
			it.product == product && it.serviceName == serviceName && productVersion in it.productVersions
		}?.provider
		val modelId = if (provider == null) {
			"$UNKNOWN/${escape(product)}/${model?.let(::escape) ?: MISSING}"
		} else {
			"${escape(provider)}/${model?.let { escape(aliases[provider]?.get(it) ?: it) } ?: MISSING}"
		}
		return Resolved(provider, modelId)
	}

	/**
	 * 같은 규칙의 ClickHouse 식. 열 `product`·`service_name`·`product_version`·`model` 을 읽는다. 쓰는 값은 [params] 에 더한다.
	 * 공급자 식을 `provider` 로 이름 붙인 뒤 모델 ID 식이 그것을 읽는다.
	 */
	fun sql(params: MutableMap<String, ClickHouseParam>): Sql {
		val provider = if (providerScopes.isEmpty()) {
			"CAST(NULL AS Nullable(String))"
		} else {
			val branches = providerScopes.mapIndexed { index, scope ->
				params["rp${index}_product"] = ClickHouseParam.string(scope.product)
				params["rp${index}_service"] = ClickHouseParam.string(scope.serviceName)
				params["rp${index}_versions"] = ClickHouseParam.stringArray(scope.productVersions.sorted())
				params["rp${index}_provider"] = ClickHouseParam.string(scope.provider)
				"product = {rp${index}_product:String} AND coalesce(service_name, '') = {rp${index}_service:String} " +
					"AND has({rp${index}_versions:Array(String)}, coalesce(product_version, '')), " +
					"CAST({rp${index}_provider:String} AS Nullable(String))"
			}
			"multiIf(${branches.joinToString(", ")}, CAST(NULL AS Nullable(String)))"
		}
		val canonical = if (aliases.isEmpty()) {
			"assumeNotNull(model)"
		} else {
			val branches = aliases.entries.sortedBy { it.key }.mapIndexed { index, (name, table) ->
				val entries = table.entries.sortedBy { it.key }
				params["ra${index}_provider"] = ClickHouseParam.string(name)
				params["ra${index}_from"] = ClickHouseParam.stringArray(entries.map { it.key })
				params["ra${index}_to"] = ClickHouseParam.stringArray(entries.map { it.value })
				"provider = {ra${index}_provider:String}, " +
					"transform(assumeNotNull(model), {ra${index}_from:Array(String)}, {ra${index}_to:Array(String)}, assumeNotNull(model))"
			}
			"multiIf(${branches.joinToString(", ")}, assumeNotNull(model))"
		}
		val modelId = "if(isNull(provider), " +
			"concat('$UNKNOWN/', ${escapeSql("product")}, '/', if(isNull(model), '$MISSING', ${escapeSql("assumeNotNull(model)")})), " +
			"concat(${escapeSql("assumeNotNull(provider)")}, '/', if(isNull(model), '$MISSING', ${escapeSql(canonical)})))"
		return Sql(provider = provider, modelId = modelId)
	}

	data class Sql(val provider: String, val modelId: String)

	companion object {
		/** 운영의 기본 판 — 검증된 공급자 범위가 없다. 규칙이나 표를 바꾸면 판을 올린다. */
		val NONE = ModelResolution(version = "model-id-v1", providerScopes = emptyList(), aliases = emptyMap())

		private const val UNKNOWN = "unknown"
		private const val MISSING = "~"

		fun escape(value: String): String = value.replace("%", "%25").replace("/", "%2F").replace("~", "%7E")

		private fun escapeSql(expression: String): String =
			"replaceAll(replaceAll(replaceAll($expression, '%', '%25'), '/', '%2F'), '~', '%7E')"
	}
}
