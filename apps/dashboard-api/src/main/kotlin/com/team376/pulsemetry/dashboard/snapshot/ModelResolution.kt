package com.team376.pulsemetry.dashboard.snapshot

/**
 * 공급자를 분류하지 않는 모델 식별자 규칙(허브 ADR 0032).
 * 기존 model-id-v1의 ID를 유지하여 조회·집계 키를 바꾸지 않는다.
 * unknown 접두어와 snapshot provider NULL은 저장 호환용이며 화면 분류가 아니다.
 */
class ModelResolution private constructor(val version: String) {
	data class Resolved(val modelId: String)

	fun resolve(product: String, model: String?): Resolved =
		Resolved("unknown/${escape(product)}/${model?.let(::escape) ?: MISSING}")

	/** 기존 snapshot DDL의 provider 슬롯만 NULL로 채운다. 원본 메타데이터를 해석하지 않는다. */
	fun sql(): Sql = Sql(
		provider = "CAST(NULL AS Nullable(String))",
		modelId = "concat('unknown/', ${escapeSql("product")}, '/', if(isNull(model), '$MISSING', ${escapeSql("assumeNotNull(model)")}))",
	)

	data class Sql(val provider: String, val modelId: String)

	companion object {
		val NONE = ModelResolution(version = "model-id-v1")
		private const val MISSING = "~"
		fun escape(value: String): String = value.replace("%", "%25").replace("/", "%2F").replace("~", "%7E")
		private fun escapeSql(expression: String): String =
			"replaceAll(replaceAll(replaceAll($expression, '%', '%25'), '/', '%2F'), '~', '%7E')"
	}
}
