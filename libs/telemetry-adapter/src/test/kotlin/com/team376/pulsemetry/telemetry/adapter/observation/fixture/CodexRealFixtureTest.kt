package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * `otlp-v2/codex/real/` — Codex 실캡처를 익명화한 추출본. 출처·선택 기준·치환 규칙은 그 디렉터리의 README 가 적는다.
 *
 * 모든 문서가 OTLP 로 읽히고(모르는 필드 없음), 레코드·point 가 빠짐없이 옮겨지며, Codex 프로파일로 태운 결과가
 * 공통 불변식을 지키는지를 본다([RealFixtureChecks]). 기대값 대조는 [CodexFixtureTest] 가 한다.
 */
class CodexRealFixtureTest {

	@TestFactory
	@DisplayName("codex/real 추출본 — 모든 문서가 읽히고 레코드가 빠짐없이 옮겨지며 공통 불변식을 지킨다")
	fun everyDocumentParses(): List<DynamicTest> =
		RealFixtureChecks.everyDocumentParses(FixtureSuite(ProfileRegistry(listOf(CodexProfile))), "codex/real")
}
