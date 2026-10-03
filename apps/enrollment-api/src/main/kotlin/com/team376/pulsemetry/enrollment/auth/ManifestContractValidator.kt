package com.team376.pulsemetry.enrollment.auth

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion

/** build 때 telemetryctl 원본에서 가져온 계약. DTO 기본값이 누락 필드를 숨기기 전에 원문을 검증한다. */
class ManifestContractValidator {
    private val id = "https://get.your-service.com/contracts/enrollment-manifest.schema.json"
    private val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
        val resource = requireNotNull(javaClass.getResourceAsStream("/contracts/enrollment-manifest.schema.json")) {
            "배포물에 manifest 계약이 없다"
        }
        builder.schemas(mapOf(id to resource.bufferedReader().use { it.readText() }))
    }.getSchema(SchemaLocation.of(id))

    fun valid(raw: String): Boolean = schema.validate(raw, InputFormat.JSON).isEmpty()
}
