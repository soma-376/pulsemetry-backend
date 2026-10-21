package com.team376.pulsemetry.telemetry.support

import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures
import java.nio.file.Files
import java.nio.file.Path

/**
 * ingest 테스트 컨텍스트가 신뢰하는 telemetry JWT 키 하나. 기반 클래스가 공개키 경로를 설정으로 넘기고,
 * [IngestTestData] 가 같은 키로 서명한다. JVM 하나에 한 번 만든다 — 컨텍스트 캐시를 쪼개지 않는다.
 */
object IngestJwtKeys {

	const val KID: String = "tk-ingest"

	val KEY: TelemetryJwtFixtures.SigningKey = TelemetryJwtFixtures.signingKey(KID)

	val PUBLIC_PEM: Path = KEY.writePem(Files.createTempDirectory("ingest-telemetry-jwt")).second
}
