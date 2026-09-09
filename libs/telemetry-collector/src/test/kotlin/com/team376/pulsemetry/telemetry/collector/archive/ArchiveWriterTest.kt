package com.team376.pulsemetry.telemetry.collector.archive

import com.team376.pulsemetry.telemetry.collector.Signal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FileArchiveWriterTest {

	@TempDir
	lateinit var root: Path

	@ParameterizedTest(name = "{0} / {1} -> {2}")
	@CsvSource(
		"CLAUDE_CODE, LOGS,    claude_code/logs.jsonl",
		"CLAUDE_CODE, TRACES,  claude_code/traces.jsonl",
		"CLAUDE_CODE, METRICS, claude_code/metrics.jsonl",
		"CODEX,       LOGS,    codex/logs.jsonl",
		"CODEX,       TRACES,  codex/traces.jsonl",
		"CODEX,       METRICS, codex/metrics.jsonl",
	)
	@DisplayName("현행 file exporter 여섯과 같은 경로에 쓴다")
	fun writesToTheSamePathsAsUpstream(product: Product, signal: Signal, expected: String) {
		FileArchiveWriter(root).write(product, signal, """{"resourceLogs":[]}""".toByteArray())

		assertThat(root.resolve(expected)).exists()
	}

	@Test
	@DisplayName("한 줄에 문서 하나다 — append 이고 자르지 않는다")
	fun appendsOneDocumentPerLine() {
		val writer = FileArchiveWriter(root)

		writer.write(Product.CODEX, Signal.LOGS, """{"a":1}""".toByteArray())
		writer.write(Product.CODEX, Signal.LOGS, """{"b":2}""".toByteArray())

		assertThat(Files.readAllLines(root.resolve("codex/logs.jsonl")))
			.containsExactly("""{"a":1}""", """{"b":2}""")
	}

	@Test
	@DisplayName("부모 디렉터리를 만든다 — 현행 설정의 create_directory: true 다")
	fun createsParentDirectories() {
		val nested = root.resolve("does/not/exist/yet")

		FileArchiveWriter(nested).write(Product.CODEX, Signal.LOGS, "{}".toByteArray())

		assertThat(nested.resolve("codex/logs.jsonl")).exists()
	}
}

class S3ArchiveWriterTest {

	private val clock = Clock.fixed(Instant.parse("2026-09-03T07:04:00Z"), ZoneOffset.UTC)

	@Test
	@DisplayName("키 배치가 상위 awss3 exporter 의 기본 파티션 형식과 같다")
	fun keyFollowsUpstreamPartitionFormat() {
		val writer = S3ArchiveWriter(s3 = NoopS3(), bucket = "b", basePrefix = "raw", clock = clock)

		assertThat(writer.key(Product.CLAUDE_CODE, Signal.LOGS))
			.matches("""raw/claude_code/logs/year=2026/month=09/day=03/hour=07/minute=04/logs_[0-9a-f-]{36}\.json""")
	}

	@Test
	@DisplayName("prefix 가 비면 앞에 슬래시를 남기지 않는다")
	fun omitsEmptyPrefix() {
		val writer = S3ArchiveWriter(s3 = NoopS3(), bucket = "b", clock = clock)

		assertThat(writer.key(Product.CODEX, Signal.METRICS)).startsWith("codex/metrics/year=2026/")
	}

	@Test
	@DisplayName("파티션 시각은 UTC 다 — 태스크 타임존에 따라 키가 흔들리면 재처리가 범위를 못 잡는다")
	fun partitionsInUtc() {
		// 같은 순간을 다른 존의 Clock 으로 봐도 키가 같아야 한다.
		val seoul = Clock.fixed(Instant.parse("2026-09-03T07:04:00Z"), ZoneOffset.ofHours(9))
		val utc = S3ArchiveWriter(NoopS3(), "b", clock = clock).key(Product.CODEX, Signal.LOGS)
		val kst = S3ArchiveWriter(NoopS3(), "b", clock = seoul).key(Product.CODEX, Signal.LOGS)

		assertThat(utc.substringBeforeLast('/')).isEqualTo(kst.substringBeforeLast('/'))
	}

	@Test
	@DisplayName("객체 이름이 매번 다르다 — 같은 분에 여러 건이 와도 덮어쓰지 않는다")
	fun keysAreUniqueWithinAMinute() {
		val writer = S3ArchiveWriter(NoopS3(), "b", clock = clock)

		val keys = (1..50).map { writer.key(Product.CODEX, Signal.LOGS) }.toSet()

		assertThat(keys).hasSize(50)
	}
}

/**
 * 키 배치만 보는 테스트라 실제 호출이 없다. `S3Client` 는 연산마다 기본 구현을 갖는 인터페이스여서
 * 이 둘만 채우면 된다 — LocalStack 컨테이너를 띄울 이유가 없다.
 */
private class NoopS3 : software.amazon.awssdk.services.s3.S3Client {
	override fun serviceName(): String = "s3"
	override fun close() = Unit
}
