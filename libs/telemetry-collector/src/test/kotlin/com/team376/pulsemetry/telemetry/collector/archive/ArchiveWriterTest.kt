package com.team376.pulsemetry.telemetry.collector.archive

import com.team376.pulsemetry.telemetry.collector.Signal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
		"UNKNOWN,     LOGS,    unknown/logs.jsonl",
		"UNKNOWN,     METRICS, unknown/metrics.jsonl",
	)
	@DisplayName("현행 file exporter 여섯과 같은 경로에 쓰고, 모르는 서비스는 unknown 구간이다")
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
	@DisplayName("쓴 줄의 실제 위치를 돌려준다 — 파일 URI 와 그 줄의 시작 바이트·길이")
	fun returnsTheActualLineLocation() {
		val writer = FileArchiveWriter(root)

		val first = writer.write(Product.CODEX, Signal.LOGS, """{"a":1}""".toByteArray())
		val second = writer.write(Product.CODEX, Signal.LOGS, """{"bb":22}""".toByteArray())

		val file = root.resolve("codex/logs.jsonl")
		assertThat(first.uri).isEqualTo(file.toAbsolutePath().normalize().toUri().toString())
		assertThat(first.byteOffset).isZero()
		assertThat(first.byteLength).isEqualTo(7)
		assertThat(second.byteOffset).isEqualTo(8) // 첫 줄 7바이트 + 개행
		val bytes = Files.readAllBytes(file)
		assertThat(String(bytes, second.byteOffset!!.toInt(), second.byteLength!!.toInt())).isEqualTo("""{"bb":22}""")
	}

	@Test
	@DisplayName("동시에 써도 각 위치가 자기 줄을 가리킨다")
	fun concurrentWritesReportTheirOwnLines() {
		val writer = FileArchiveWriter(root)
		val bodies = (0 until 64).map { """{"n":$it,"pad":"${"x".repeat(it)}"}""" }

		val locations = bodies.parallelStream()
			.map { body -> body to writer.write(Product.CLAUDE_CODE, Signal.TRACES, body.toByteArray()) }
			.toList()

		val bytes = Files.readAllBytes(root.resolve("claude_code/traces.jsonl"))
		locations.forEach { (body, location) ->
			assertThat(String(bytes, location.byteOffset!!.toInt(), location.byteLength!!.toInt())).isEqualTo(body)
		}
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
	@DisplayName("put 에 성공한 그 key 를 s3 URI 로 돌려준다 — 객체 전체가 문서라 바이트 범위가 없다")
	fun returnsTheKeyThatWasPut() {
		val s3 = CapturingS3()
		val writer = S3ArchiveWriter(s3, "raw-bucket", basePrefix = "dev", clock = clock)

		val location = writer.write(Product.UNKNOWN, Signal.LOGS, "{}".toByteArray())

		val put = s3.puts.single()
		assertThat(location.uri).isEqualTo("s3://raw-bucket/${put.first}")
		assertThat(put.first).startsWith("dev/unknown/logs/year=2026/")
		assertThat(location.byteOffset).isNull()
		assertThat(location.byteLength).isNull()
	}

	@Test
	@DisplayName("put 이 실패하면 예외다 — 위치를 돌려주지 않는다")
	fun aFailedPutReturnsNoLocation() {
		val writer = S3ArchiveWriter(NoopS3(), "b", clock = clock)

		assertThatThrownBy { writer.write(Product.CODEX, Signal.LOGS, "{}".toByteArray()) }
			.isInstanceOf(UnsupportedOperationException::class.java)
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

/** `putObject` 만 받아 key 와 바이트를 남긴다. 그 밖의 연산은 기본 구현대로 실패한다. */
internal class CapturingS3 : software.amazon.awssdk.services.s3.S3Client {
	val puts = mutableListOf<Pair<String, ByteArray>>()

	override fun serviceName(): String = "s3"
	override fun close() = Unit

	override fun putObject(
		request: software.amazon.awssdk.services.s3.model.PutObjectRequest,
		body: software.amazon.awssdk.core.sync.RequestBody,
	): software.amazon.awssdk.services.s3.model.PutObjectResponse {
		val bytes = body.contentStreamProvider().newStream().use { it.readAllBytes() }
		synchronized(puts) { puts += request.key() to bytes }
		return software.amazon.awssdk.services.s3.model.PutObjectResponse.builder().build()
	}
}
