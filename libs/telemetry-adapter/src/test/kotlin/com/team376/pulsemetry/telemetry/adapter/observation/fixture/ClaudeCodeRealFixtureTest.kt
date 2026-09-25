package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * `otlp-v2/claude_code/real/` — Claude Code(와 미등록 서비스 하나)의 실캡처를 익명화한 추출본. 출처·선택 기준·치환 규칙은
 * 그 디렉터리의 README 가 적는다. 아직 Claude Code 프로파일이 없어 generic 경로로 태운 결과의 불변식을 본다.
 */
class ClaudeCodeRealFixtureTest {

	@TestFactory
	@DisplayName("claude_code/real 추출본 — 모든 문서가 읽히고 레코드가 빠짐없이 옮겨지며 공통 불변식을 지킨다")
	fun everyDocumentParses(): List<DynamicTest> = RealFixtureChecks.everyDocumentParses(FixtureSuite(), "claude_code/real")
}
