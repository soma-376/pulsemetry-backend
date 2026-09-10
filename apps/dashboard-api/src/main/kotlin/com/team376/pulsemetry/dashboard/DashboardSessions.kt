package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.persistence.telemetry.DashboardClickHouseReader
import com.team376.pulsemetry.persistence.telemetry.DashboardReadException
import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Base64

/** ADR 0019의 개인 활동 재구성 경계: 모든 페이지에서 owner·감사를 재검증한다. */
@RestController
@RequestMapping("/v1/sessions")
class DashboardSessions(private val jdbc: JdbcClient, private val reader: DashboardClickHouseReader,
    private val mapper: ObjectMapper, private val access: DashboardAccess, private val clock: Clock) {
    private val utc = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    @GetMapping("/{sessionId}/events")
    fun events(@AuthenticationPrincipal user: UserIdentity, @PathVariable sessionId: String,
        @RequestHeader(value="X-Audit-Reason",required=false) audit: String?,
        @RequestParam(defaultValue="session_id") lookup: String,
        @RequestParam(defaultValue="now-7d") from: String, @RequestParam(defaultValue="now") to: String,
        @RequestParam(defaultValue="50") limit: Int, @RequestParam(required=false) cursor: String?): Map<String,Any?> {
        val deadline = System.nanoTime()+Duration.ofSeconds(30).toNanos()
        require(sessionId.length in 1..500 && sessionId.none { it.isISOControl() } && sessionId!="(unknown)")
        require(lookup in setOf("session_id","request_id","call_id","installation_id") && limit in 1..500)
        access.personal(user,audit,"session_events","$lookup:$sessionId")
        val context = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(listOf(user.tenantId.toString(),lookup,sessionId,from,to)))
            .joinToString("") { "%02x".format(it) }
        val saved = cursor?.takeIf { it.isNotEmpty() }?.let {
            try {
                require(it.length<=4096)
                val node = mapper.readTree(Base64.getUrlDecoder().decode(it))
                require(node.isArray && node.size()==9 && node.toList().all { field -> field.isString })
                node.toList().map { field -> field.asString() }.also { fields -> require(fields[0]==context) }
            } catch (_: Exception) { throw IllegalArgumentException("invalid_cursor") }
        }
        val timezone = jdbc.sql("SELECT timezone FROM enrollment.tenants WHERE id=:tenant")
            .param("tenant",user.tenantId).query(String::class.java).single()
        val time = DashboardTime(clock.instant(),ZoneId.of(timezone))
        val start = saved?.let { Instant.parse(it[1]) } ?: time.resolve(from)
        val end = saved?.let { Instant.parse(it[2]) } ?: time.resolve(to)
        require(start<end)
        if (Duration.between(start,end)>Duration.ofDays(366)) throw DashboardReadException("query_too_wide",422)
        val parameters = mutableMapOf("tenant" to user.tenantId.toString(),"from" to boundary(start),"to" to boundary(end),"key" to sessionId)
        val base = "FROM enriched_events FINAL WHERE tenant_id={tenant:String} AND ts>={from:DateTime} AND ts<{to:DateTime}"
        val session = "JSONExtractString(raw_json,'envelope','session_id')"
        val search = when (lookup) {
            "session_id" -> session
            "request_id" -> "JSONExtractString(raw_json,'payload','request_id')"
            "call_id" -> "JSONExtractString(raw_json,'call_id')"
            else -> "installation_id"
        }
        fun read(sql: String): List<JsonNode> {
            val remaining = deadline-System.nanoTime()
            if (remaining<=0) throw DashboardReadException("query_timeout",503)
            return mapper.readTree(reader.query(sql,parameters,Duration.ofNanos(remaining)))["data"].toList()
        }
        val matches = read("SELECT DISTINCT installation_id,product,$session AS sid $base AND $search={key:String} AND $session NOT IN ('','(unknown)') ORDER BY installation_id,product,sid LIMIT 2")
        if (matches.isEmpty()) throw DashboardReadException("session_not_found",404)
        if (matches.size>1) throw DashboardReadException("ambiguous_session",422)
        val match = matches.single()
        val identity = listOf(match["sid"].asString(),match["installation_id"].asString(),match["product"].asString())
        require(saved==null || saved.subList(3,6)==identity)
        parameters += mapOf("session" to identity[0],"installation" to identity[1],"product" to identity[2])
        val selected = "$base AND $session={session:String} AND installation_id={installation:String} AND product={product:String}"
        val summary = read("""SELECT count() AS total,toUnixTimestamp(min(ts)) AS started,toUnixTimestamp(max(ts)) AS ended,
            argMin(team_ids_as_of,tuple(ts,JSONExtractInt(raw_json,'sequence'),event_id)) AS teams,
            argMin(JSONExtractString(raw_json,'envelope','client','version'),tuple(ts,JSONExtractInt(raw_json,'sequence'),event_id)) AS version
            $selected""").single()
        parameters += mapOf("first" to if (saved==null) "1" else "0", "after_ts" to (saved?.get(6)?.toLongOrNull() ?: 0).toString(),
            "after_sequence" to (saved?.get(7)?.toLongOrNull() ?: 0).toString(),"after_id" to (saved?.get(8) ?: ""),"limit" to (limit+1).toString())
        require(saved==null || (saved[6].toLongOrNull()!=null && saved[7].toLongOrNull()!=null && saved[8].length in 1..500))
        val rows = read("""SELECT event_id,toUnixTimestamp(ts) AS timestamp,signal,raw_json,JSONExtractInt(raw_json,'sequence') AS seq
            $selected AND ({first:UInt8}=1 OR tuple(toInt64(toUnixTimestamp(ts)),JSONExtractInt(raw_json,'sequence'),event_id)>
                tuple({after_ts:Int64},{after_sequence:Int64},{after_id:String}))
            ORDER BY ts,seq,event_id LIMIT {limit:UInt32}""")
        val page = rows.take(limit)
        val items = page.map { row ->
            val raw = mapper.readTree(row["raw_json"].asString())
            val metric = row["signal"].asString()=="metric"
            fun optional(key: String): JsonNode? = raw[key]?.takeUnless { it.isNull }
            mapOf("event_id" to row["event_id"].asString(),"ts" to Instant.ofEpochSecond(row["timestamp"].asLong()).toString(),
                "signal" to row["signal"].asString(),"type" to if (metric) raw["point"]?.get("name") else raw["type"],
                "turn_id" to optional("turn_id"),"call_id" to optional("call_id"),"span_id" to optional("span_id"),
                "parent_id" to optional("parent_id"),"sequence" to optional("sequence"),
                "call_id_inferred" to (raw["envelope"]?.get("_ingest")?.get("call_id_inferred")?.takeIf { it.isBoolean }?.asBoolean() ?: false),
                "payload" to (raw[if (metric) "point" else "payload"]?.takeUnless { it.isNull } ?: mapper.createObjectNode()))
        }
        val next = if (rows.size>limit) page.last().let { row -> Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(
            listOf(context,start.toString(),end.toString())+identity+listOf(row["timestamp"].asLong().toString(),row["seq"].asLong().toString(),row["event_id"].asString()))) } else null
        return mapOf("session" to mapOf("session_id" to identity[0],"installation_id" to identity[1],"product" to identity[2],
            "client_version" to summary["version"].asString().ifEmpty { null },"team_ids" to summary["teams"],
            "started_at" to Instant.ofEpochSecond(summary["started"].asLong()).toString(),
            "ended_at" to Instant.ofEpochSecond(summary["ended"].asLong()).toString(),"event_count" to summary["total"].asLong()),
            "items" to items,"next_cursor" to next,"total" to summary["total"].asLong())
    }
    private fun boundary(at: Instant) = utc.format(if (at.nano==0) at else at.plusSeconds(1).minusNanos(at.nano.toLong()))
}
