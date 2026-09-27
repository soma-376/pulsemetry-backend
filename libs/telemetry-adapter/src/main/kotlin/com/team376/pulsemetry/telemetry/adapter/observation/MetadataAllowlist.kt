package com.team376.pulsemetry.telemetry.adapter.observation

/**
 * 분석 저장소에 들어갈 수 있는 속성 이름의 목록. **버전이 있는 이름별 allowlist** 다(ADR 0020 §1).
 *
 * 제품·프로파일마다 하나씩 두고, 목록을 바꾸면 [version] 을 올린다. 등록되지 않은 키는 `metadata_json` 에
 * 복사하지 않는다 — 미등록 필드는 원본 아카이브에만 남는다.
 *
 * ## 본문류 이름은 올릴 수 없다
 *
 * 본문·프롬프트·도구 arguments/output·명령문·자유 형식 오류 메시지·절대 경로·자격증명은 어떤 목록에도
 * 들어갈 수 없다. 생성자가 [isForbidden] 으로 막는다 — 정책은 프로파일이 정하지만, 실수로 본문 키를
 * 올리는 변경은 컴파일된 목록이 로드되는 순간 실패한다. 이 검사는 안전망이지 정책의 전부가 아니다.
 */
public class MetadataAllowlist(
	public val version: String,
	public val resource: Set<String> = emptySet(),
	public val scope: Set<String> = emptySet(),
	public val record: Set<String> = emptySet(),
	public val exemplar: Set<String> = emptySet(),
) {
	init {
		require(version.isNotBlank()) { "allowlist 에는 버전이 있다" }
		val forbidden = (resource + scope + record + exemplar).filter { isForbidden(it) }
		require(forbidden.isEmpty()) { "본문류 속성은 allowlist 에 올릴 수 없다(ADR 0020 §1): $forbidden" }
	}

	/** 등록된 키만 남긴다. 순서와 중복은 그대로다. */
	public fun filter(
		resource: List<TypedAttribute>,
		scope: List<TypedAttribute>,
		record: List<TypedAttribute>,
		exemplars: List<List<TypedAttribute>> = emptyList(),
	): TypedMetadata = TypedMetadata(
		resource = resource.filter { it.key in this.resource },
		scope = scope.filter { it.key in this.scope },
		record = record.filter { it.key in this.record },
		exemplars = exemplars.map { attributes -> attributes.filter { it.key in exemplar } }.filter { it.isNotEmpty() },
	)

	public companion object {
		/**
		 * 이 이름으로 끝나는 키(`.` 으로 나눈 마지막 조각, 대소문자 무시)는 올릴 수 없다.
		 * `prompt.id` 같은 식별자는 마지막 조각이 `id` 라 통과하고, `error.message`·`tool.arguments` 는 막힌다.
		 */
		private val FORBIDDEN_LAST_SEGMENTS: Set<String> = setOf(
			// 본문·프롬프트·응답
			"prompt", "body", "content", "contents", "text", "completion", "response_body", "request_body",
			// 도구 입력·출력
			"arguments", "args", "input", "output", "tool_parameters", "tool_input", "tool_output",
			// 명령문
			"command", "cmd", "commandline", "command_line",
			// 자유 형식 오류
			"message", "stacktrace", "stack",
			// 경로
			"cwd", "path", "file_path", "filepath", "filename", "file",
			// 자격증명
			"password", "secret", "token", "api_key", "apikey", "access_key", "secret_key", "private_key",
			"authorization", "cookie", "credential", "credentials",
		)

		/** 마지막 조각만으로는 잡히지 않는 전체 키. */
		private val FORBIDDEN_KEYS: Set<String> = setOf("url.full", "http.url", "url.query", "db.statement", "db.query.text")

		public fun isForbidden(key: String): Boolean {
			val lower = key.lowercase()
			return lower in FORBIDDEN_KEYS || lower.substringAfterLast('.') in FORBIDDEN_LAST_SEGMENTS
		}
	}
}

/**
 * `attrs` 파생(ADR 0020 §1). allowlist 를 통과한 **레코드 경계**의 스칼라 속성(string·bool·int·double)을
 * 문자열로 편다. bytes·array·kvlist 와 빈 값은 싣지 않는다.
 *
 * 같은 키가 **같은 타입·같은 값**으로 여러 번 오면 하나로 싣는다. 값이나 타입이 다르면 그 키를 빼고
 * `ambiguous_attribute` 를 남긴다 — 어느 쪽을 골라도 근거 없는 선택이다. 원형의 중복은 `metadata_json` 에
 * 남는다. resource·scope 는 싣지 않는다 — 경계가 다른 같은 이름을 한 맵에 섞지 않는다.
 */
public object ScalarAttrs {

	public class Derived(
		public val attrs: Map<String, String>,
		public val flags: Set<QualityFlag>,
	)

	public fun derive(record: List<TypedAttribute>): Derived {
		val byKey = LinkedHashMap<String, MutableSet<TypedValue>>()
		for (attribute in record) {
			if (!isScalar(attribute.value)) continue
			byKey.getOrPut(attribute.key) { LinkedHashSet() } += attribute.value
		}
		val attrs = sortedMapOf<String, String>()
		var ambiguous = false
		for ((key, values) in byKey) {
			if (values.size == 1) attrs[key] = render(values.single()) else ambiguous = true
		}
		return Derived(attrs, if (ambiguous) setOf(QualityFlag.AMBIGUOUS_ATTRIBUTE) else emptySet())
	}

	private fun isScalar(value: TypedValue): Boolean =
		value is TypedValue.Str || value is TypedValue.Bool || value is TypedValue.Int || value is TypedValue.Double

	/** 정수는 10진 그대로, double 은 JVM 표준 표기다. 문자열로 편 값끼리의 비교는 타입을 잃는다 — 필터 용도다. */
	private fun render(value: TypedValue): String = when (value) {
		is TypedValue.Str -> value.value
		is TypedValue.Bool -> value.value.toString()
		is TypedValue.Int -> value.value.toString()
		is TypedValue.Double -> value.value.toString()
		else -> error("스칼라가 아니다: $value")
	}
}
