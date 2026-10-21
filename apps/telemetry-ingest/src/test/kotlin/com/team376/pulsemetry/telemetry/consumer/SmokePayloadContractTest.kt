package com.team376.pulsemetry.telemetry.consumer

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.IdentityMaterial
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationIds
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationSignal
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.InstrumentationScope
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.resource.v1.Resource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Python smoke의 한정 canonicalizer와 JVM id-v1 구현을 같은 golden 원문으로 교차 검증한다. */
class SmokePayloadContractTest {
    @Test
    fun expectedFingerprintMatchesJvm() {
        val body = javaClass.getResourceAsStream("/observability-smoke-log.json")!!.readAllBytes()
        assertThat(expectedId(body, "19100000-0000-4000-8000-000000000002", "19100000-0000-4000-8000-000000000003"))
            .isEqualTo("6c79c7e2e80060cd25fb6121f08cbe2334ab07894521a15e1b82c73ff372e391")
    }

    companion object {
        fun expectedId(body: ByteArray, tenant: String, installation: String): String {
            val json = JsonMapper.builder().build().readTree(body).path("resourceLogs").path(0)
            fun attributes(node: JsonNode): List<KeyValue> = buildList {
                for (attr in node.path("attributes")) {
                    val value = attr.path("value")
                    val builder = AnyValue.newBuilder()
                    if (value.has("doubleValue")) builder.doubleValue = value.path("doubleValue").asDouble()
                    else if (value.has("intValue")) builder.intValue = value.path("intValue").asText().toLong()
                    else builder.stringValue = value.path("stringValue").asText()
                    add(KeyValue.newBuilder().setKey(attr.path("key").asText()).setValue(builder).build())
                }
            }
            val resource = Resource.newBuilder().addAllAttributes(attributes(json.path("resource"))).apply {
                for ((key, value) in listOf("tenant.id" to tenant, "developer.installation_id" to installation))
                    addAttributes(KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)))
            }.build()
            val scopeJson = json.path("scopeLogs").path(0).path("scope")
            val scope = InstrumentationScope.newBuilder().setName(scopeJson.path("name").asText()).setVersion(scopeJson.path("version").asText()).build()
            val recordJson = json.path("scopeLogs").path(0).path("logRecords").path(0)
            val record = LogRecord.newBuilder().setTimeUnixNano(recordJson.path("timeUnixNano").asText().toLong())
                .setObservedTimeUnixNano(recordJson.path("observedTimeUnixNano").asText().toLong())
                .setBody(AnyValue.newBuilder().setStringValue(recordJson.path("body").path("stringValue").asText()))
                .addAllAttributes(attributes(recordJson)).build()
            return ObservationIds.fingerprint(IdentityMaterial(tenant, installation, ObservationSignal.LOG, "claude-code", EpochNanos(record.timeUnixNano)),
                resource, "", scope, "", record)
        }
    }
}
