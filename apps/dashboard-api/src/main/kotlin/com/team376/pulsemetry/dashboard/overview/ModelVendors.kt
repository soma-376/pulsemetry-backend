package com.team376.pulsemetry.dashboard.overview

/** 표시용 모델 제작사. 공급자·계약·가격 판정에는 사용하지 않는다. */
object ModelVendors {
    data class Vendor(val id: String, val name: String)
    private val vendors = mapOf("openai" to Vendor("openai", "OpenAI"), "anthropic" to Vendor("anthropic", "Anthropic"))
    private val catalog: Map<String, Vendor> = requireNotNull(javaClass.getResourceAsStream("/catalog/model-vendors.tsv"))
        .bufferedReader().useLines { lines ->
            val entries = lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val fields = line.split('\t')
                require(fields.size == 2 && fields[0].isNotBlank()) { "모델 제작사 카탈로그 행이 잘못됐다" }
                fields[0] to requireNotNull(vendors[fields[1]]) { "미등록 제작사다" }
            }.toList()
            require(entries.isNotEmpty() && entries.map { it.first }.distinct().size == entries.size) { "빈 카탈로그 또는 중복 모델이다" }
            entries.toMap()
        }

    fun resolve(modelId: String): Vendor? {
        // 원본 이름(공급자 접두사를 포함한 별칭도 가능)을 먼저 확인한다.
        catalog[modelId]?.let { return it }
        val parts = modelId.split('/')
        val encoded = when {
            parts.size == 3 && parts[0] == "unknown" -> parts[2]
            parts.size == 2 && parts[0] in vendors -> parts[1]
            else -> return null
        }
        if (encoded == "~") return null
        // ModelResolution.escape의 역변환. URLDecoder는 '+'를 공백으로 바꾸므로 쓰지 않는다.
        val name = encoded.replace("%2F", "/").replace("%7E", "~").replace("%25", "%")
        return catalog[name]
    }
}
