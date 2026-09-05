package com.team376.pulsemetry.telemetry.collector.masking

import java.util.regex.Pattern

/**
 * `redaction/secrets` 의 `blocked_values` 열넷. 이식 원본은
 * `infra/config/otel-collector.yaml`(배포본)의 `processors.redaction/secrets` 다.
 * dev 쪽 `ai-telemetry-pipeline/otel-collector-config.yaml` 과 이 블록은 글자까지 같다.
 *
 * ## 순서가 사양이다
 *
 * [SecretMasker] 가 이 목록을 **선언 순서대로** 적용한다. 운영 버전 `v0.157.0` 의 Go 구현은
 * 정규식을 `map[string]*regexp.Regexp` 에 담고 그 맵을 순회하며 값을 누적 변형했다 — Go 의 맵
 * 순회는 무작위라 **정규식이 겹치면 같은 입력이 실행마다 다른 출력을 냈다.** 실측하면 이렇다.
 *
 * ```
 * 입력  "token=sk-abcdefghij1234567890"
 * 출력  "token=****"   2547 / 4000 회
 * 출력  "****"         1453 / 4000 회
 * ```
 *
 * 상위도 이것을 결함으로 보고 `v0.157.0` **이후** 맵을 슬라이스로 바꾸며 주석을 달았다 —
 * *"preserving the order in which they are listed in the configuration"*.
 * 그래서 이 이식본은 v0.157.0 의 무작위 순서가 아니라 **상위가 고친 뒤의 동작**을 따른다.
 * 비결정적 동작은 애초에 golden 으로 고정할 수 없다. `SecretMaskerTest` 가 위 겹침 케이스를
 * 선언 순서 기대값으로 박아 둔다. **목록의 순서를 바꾸면 출력이 바뀐다.**
 *
 * ## Go RE2 와 java.util.regex 의 방언 차이 — 보정 두 곳
 *
 * 열넷을 프로브 스물둘에 대해 Go 와 Java 로 각각 돌려 매치 구간을 대조했다. 차이는 둘뿐이었다.
 *
 * 1. **`\s` 에 수직탭(U+000B)이 들어가는가.** Go RE2 의 `\s` 는 `[\t\n\f\r ]` 이고 Java 의 `\s` 는
 *    `[ \t\n\x0B\f\r]` 다. 그대로 두면 Java 가 수직탭으로 띄운 값을 더 마스킹한다.
 *    그래서 `\s` 를 **`[\t\n\f\r ]` 로 펴서** 적는다(#12 · #13 · #14).
 *    `[\s\S]`(#11)는 손대지 않는다 — 합집합이라 양쪽 모두 "아무 문자"다.
 * 2. **`(?i)` 의 케이스 폴딩 범위.** Go 는 유니코드를 인식해 U+017F(긴 s)를 `s` 와 같게 보고,
 *    Java 는 [Pattern.UNICODE_CASE] 없이는 ASCII 만 접는다. 그래서 전부 그 플래그로 컴파일한다.
 *
 * 두 보정을 넣으면 프로브 스물둘이 Go 와 **완전히 일치**한다(`MaskingRulesTest`).
 *
 * 매치 위치의 숫자는 다를 수 있다 — Go 는 바이트, Java 는 UTF-16 오프셋이라 그렇고, 구간은 같다.
 */
internal object MaskingRules {

	/** 상위에 `hash_function` 이 없으므로 매치는 이 고정 문자열로 바뀐다. */
	const val MASK: String = "****"

	/** Go RE2 의 `\s`. Java 의 `\s` 와 달리 수직탭을 포함하지 않는다. */
	private const val S = "[\\t\\n\\f\\r ]"

	/** 부정 문자류 안에서 쓰는 같은 집합. `[^:/?#\s]` → `[^:/?#\t\n\f\r ]`. */
	private const val NS = "\\t\\n\\f\\r "

	/**
	 * 원본 YAML 의 주석과 순서를 그대로 유지한다. **정렬하지 마라** — 순서가 동작이다.
	 */
	val BLOCKED_VALUES: List<Pattern> = listOf(
		// --- Provider API keys / tokens ---
		"sk-ant-[A-Za-z0-9\\-_]{20,}",                                   // Anthropic
		"sk-[A-Za-z0-9]{20,}",                                           // OpenAI 계열
		"ghp_[A-Za-z0-9]{36}",                                           // GitHub PAT (classic)
		"github_pat_[A-Za-z0-9_]{22,}",                                  // GitHub PAT (fine-grained)
		"gh[orsu]_[A-Za-z0-9]{36}",                                      // GitHub oauth/refresh/server/user
		"glpat-[A-Za-z0-9\\-_]{20}",                                     // GitLab PAT
		"xox[baprs]-[A-Za-z0-9\\-]{10,}",                                // Slack
		"AIza[0-9A-Za-z\\-_]{35}",                                       // Google API key
		"AKIA[0-9A-Z]{16}",                                              // AWS Access Key ID
		// --- Structured tokens ---
		"eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+",     // JWT
		"-----BEGIN[\\s\\S]*?PRIVATE KEY-----[\\s\\S]*?-----END[\\s\\S]*?-----", // PEM private key
		// --- Bearer auth ---
		"(?i)bearer$S+[A-Za-z0-9._\\-]{10,}",
		// --- DB/URL 자격증명 (scheme://user:pass@) ---
		"[a-zA-Z][a-zA-Z0-9+.\\-]*://[^:/?#$NS]+:[^@/?#$NS]+@",
		// --- key=value / key: value 형태 시크릿 (키 이름 포함 매칭) ---
		"(?i)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|client[_-]?secret|auth[_-]?token)" +
			"[\"']?$S*[:=]$S*[\"']?[^$NS\"',}]{6,}",
	).map { Pattern.compile(it, Pattern.UNICODE_CASE) }
}
