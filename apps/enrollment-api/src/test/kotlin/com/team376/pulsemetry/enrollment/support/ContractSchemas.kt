package com.team376.pulsemetry.enrollment.support

import com.networknt.schema.Error
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.nio.file.Files
import java.nio.file.Path

/**
 * `telemetryctl/contracts` 의 스키마 파일들을 계약 테스트의 오라클로 쓴다.
 * (KDoc 안에 `contracts` 다음에 별표를 붙이면 Kotlin 이 중첩 주석으로 읽어 컴파일이 깨진다.)
 *
 * 파일을 backend 로 복사하지 않고 **원본을 직접 읽는다.** 복사본을 두면 언젠가 갈라지고,
 * 그때 통과하는 건 계약이 아니라 우리가 믿고 싶은 계약이 된다 (PLAN.md R3).
 * 경로는 Gradle 이 `pulsemetry.contracts.dir` 시스템 프로퍼티로 넘겨 준다.
 *
 * 스키마의 `$id` 는 `https://get.your-service.com/contracts/...` 인데, 그 주소로 나가지 않도록
 * 두 파일의 내용을 `$id` 에 직접 등록한다. 그러면 envelope 의 상대 `$ref` 도 로컬에서 풀린다.
 */
object ContractSchemas {

	private const val BASE_IRI = "https://get.your-service.com/contracts/"

	const val ENVELOPE_ID: String = BASE_IRI + "enrollment-envelope.schema.json"
	const val MANIFEST_ID: String = BASE_IRI + "enrollment-manifest.schema.json"

	private val contractsDir: Path by lazy {
		val configured = System.getProperty("pulsemetry.contracts.dir")
			?: error("pulsemetry.contracts.dir 시스템 프로퍼티가 없다. build.gradle.kts 의 test 설정을 확인하라.")
		Path.of(configured).also {
			check(Files.isDirectory(it)) { "계약 디렉터리를 찾을 수 없다: $it" }
		}
	}

	private val registry: SchemaRegistry by lazy {
		SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
			builder.schemas(
				mapOf(
					ENVELOPE_ID to read("enrollment-envelope.schema.json"),
					MANIFEST_ID to read("enrollment-manifest.schema.json"),
				),
			)
		}
	}

	private fun read(fileName: String): String =
		Files.readString(contractsDir.resolve(fileName))

	/** `POST /v1/enroll` 성공 응답 봉투. */
	fun enrollmentSchema(): Schema = registry.getSchema(SchemaLocation.of("$ENVELOPE_ID#/\$defs/enrollment"))

	/** `POST /v1/enroll` 요청 본문. */
	fun enrollRequestSchema(): Schema = registry.getSchema(SchemaLocation.of("$ENVELOPE_ID#/\$defs/enroll_request"))

	/** `POST /v1/installations/telemetry-token` 응답. */
	fun telemetryTokenResponseSchema(): Schema =
		registry.getSchema(SchemaLocation.of("$ENVELOPE_ID#/\$defs/telemetry_token_response"))

	/** 봉투 안에 실리는 순수 설정 manifest. */
	fun manifestSchema(): Schema = registry.getSchema(SchemaLocation.of(MANIFEST_ID))

	fun validate(schema: Schema, json: String): List<Error> = schema.validate(json, InputFormat.JSON)

	/** 실패 메시지를 사람이 읽을 수 있게 모은다. */
	fun describe(errors: List<Error>): String =
		errors.joinToString(separator = "\n") { "  - ${it.instanceLocation}: ${it.message}" }
}
