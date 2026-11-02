package com.team376.pulsemetry.enrollment.management

import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

import com.team376.pulsemetry.persistence.enrollment.management.*

import com.team376.pulsemetry.enrollment.management.CommandRules.fail

/** 관리 API의 JSON 입력과 멱등 해시 정규화. */
object CommandValues {
    private val mapper = tools.jackson.module.kotlin.jacksonObjectMapper()
    fun node(value: Any): JsonNode = mapper.valueToTree(value)

    fun text(node: JsonNode, field: String, max: Int): String = node.path(field).takeIf { it.isString }?.asString()?.trim()?.takeIf { it.length in 1..max } ?: fail("invalid_request", 400, field)

    fun long(node: JsonNode, field: String): Long {
        val value = node.path(field)
        if (!value.isNumber) fail("invalid_request", 400, field)
        return try { value.asString().toBigDecimal().longValueExact() } catch (_: ArithmeticException) { fail("invalid_request", 400, field) }
    }

    fun date(node: JsonNode, field: String): LocalDate = try { LocalDate.parse(text(node, field, 10)) } catch (_: java.time.DateTimeException) { fail("invalid_request", 400, field) }

    fun array(node: JsonNode, field: String): List<JsonNode> = node.path(field).takeIf { it.isArray && it.size() in 1..100 }?.toList() ?: fail("invalid_request", 400, field)




    fun canonical(node: JsonNode): String = when { node.isObject -> node.properties().sortedBy { it.key }.joinToString(",", "{", "}") { mapper.writeValueAsString(it.key) + ":" + canonical(it.value) }; node.isArray -> node.joinToString(",", "[", "]", transform = ::canonical); else -> node.toString() }


    fun optionalText(node: JsonNode, field: String, max: Int): String? {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) return null
        return value.takeIf { it.isString }?.asString()?.trim()?.takeIf { it.length in 1..max } ?: fail("invalid_request", 400, field)
    }


    fun plannedVendors(body: JsonNode): List<String> {
        if (!body.has("plannedVendorIds")) return emptyList()
        val values = body.path("plannedVendorIds")
        if (!values.isArray || values.size() > 100) fail("invalid_request", 400, "plannedVendorIds")
        val ids = (0 until values.size()).map { index ->
            val value = values[index]
            if (!value.isString || !Regex("[A-Za-z0-9_-]{1,100}").matches(value.asString())) fail("invalid_request", 400, "plannedVendorIds")
            value.asString()
        }
        if (ids.distinct().size != ids.size) fail("invalid_request", 400, "plannedVendorIds")
        return ids.sorted()
    }

}
