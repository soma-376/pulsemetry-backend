package com.team376.pulsemetry.dashboard.scenario

import com.team376.pulsemetry.dashboard.api.encodeCursor
import com.team376.pulsemetry.dashboard.auth.DashboardAccess
import com.team376.pulsemetry.persistence.dashboard.DashboardSavedReports
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserIdentity
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID

@RestController
@RequestMapping("/v1")
class DashboardReports(jdbc: JdbcClient, manager: PlatformTransactionManager, private val runs: DashboardScenarioRuns,
    private val access: DashboardAccess, private val mapper: ObjectMapper) {
    private val store = DashboardSavedReports(jdbc)
    private val tx = TransactionTemplate(manager)

    @PostMapping("/scenario-runs/{id}/save")
    fun save(@PathVariable id: String, @RequestBody body: JsonNode, @AuthenticationPrincipal user: UserIdentity): ResponseEntity<*> {
        require(body.isObject && body.properties().all { it.key in setOf("name","note","time_mode") })
        fun text(key: String, max: Int, optional: Boolean = false): String? {
            val node = body[key]
            if (optional && node==null) return null
            require(node!=null && node.isString)
            val value = node.asString()
            require(value.codePointCount(0,value.length)<=max && value.none { Character.isISOControl(it) && !(key=="note" && it in "\n\r\t") })
            if (key=="name") require(value.isNotBlank())
            return value
        }
        val name = requireNotNull(text("name",100))
        val note = text("note",2000,true)
        val mode = text("time_mode",8,true) ?: "fixed"
        require(mode in setOf("fixed","relative"))
        val runId = uuid(id)
        val result = tx.execute {
            lock(runId,user)
            val run = runs.get(runId,user)
            if (run.status!="succeeded") throw UserAuthException("conflict",409)
            if (mode=="relative") require(mapper.readTree(run.params).properties().any {
                it.value.isString && Regex("^now(?:-|$)").containsMatchIn(it.value.asString())
            })
            val saved = UUID.randomUUID()
            store.save(saved,user.tenantId,runId,user.memberId,name,note,mode)
            requireNotNull(store.get(user.tenantId,saved))
        }
        return ResponseEntity.status(201).body(result)
    }

    @GetMapping("/saved-reports")
    fun list(@AuthenticationPrincipal user: UserIdentity, @RequestParam(defaultValue="50") limit: String,
        @RequestParam(required=false) cursor: String?): Map<String,Any?> {
        val size = requireNotNull(limit.toIntOrNull()); require(size in 1..500)
        if (user.role !in setOf("owner","admin")) throw UserAuthException("forbidden",403)
        val teams = access.teamIds(user).map { it.toString() }.sorted()
        val scope = mapper.writeValueAsString(listOf("saved-list-v1",user.tenantId,user.memberId,user.role,teams))
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        val after = cursor?.takeIf { it.isNotEmpty() }?.let {
            require(it.length<=2048)
            try {
                val node = mapper.readTree(Base64.getUrlDecoder().decode(it))
                require(node["scope"].asString()==fingerprint)
                Instant.parse(node["at"].asString()) to uuid(node["id"].asString())
            } catch (e: Exception) { throw IllegalArgumentException("invalid_cursor") }
        }
        val rows = store.list(user.tenantId,user.memberId,user.role=="owner",mapper.writeValueAsString(teams),after?.first,after?.second,size+1)
        val page = rows.take(size)
        val next = if (rows.size>size) encodeCursor(mapper.writeValueAsString(mapOf("scope" to fingerprint,
            "at" to page.last()["created_at"],"id" to page.last()["saved_id"]))) else null
        return mapOf("items" to page,"next_cursor" to next,"total" to null)
    }

    @DeleteMapping("/saved-reports/{id}")
    fun deleteSaved(@PathVariable id: String, @AuthenticationPrincipal user: UserIdentity): ResponseEntity<Void> {
        val savedId = uuid(id)
        tx.executeWithoutResult {
            val initial = store.get(user.tenantId,savedId) ?: throw UserAuthException("not_found",404)
            val runId = initial["run_id"] as UUID
            lock(runId,user)
            runs.get(runId,user)
            val saved = store.get(user.tenantId,savedId) ?: throw UserAuthException("not_found",404)
            val creator = (saved["created_by"] as Map<*,*>)["member_id"]
            if (user.role!="owner" && creator!=user.memberId) throw UserAuthException("forbidden",403)
            if (!store.delete(user.tenantId,savedId)) throw UserAuthException("not_found",404)
        }
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/scenario-runs/{id}")
    fun deleteRun(@PathVariable id: String, @AuthenticationPrincipal user: UserIdentity): ResponseEntity<Void> {
        val runId = uuid(id)
        tx.executeWithoutResult {
            lock(runId,user)
            val run = runs.get(runId,user)
            if (run.status in setOf("queued","running") || store.hasSaved(user.tenantId,runId)) throw UserAuthException("conflict",409)
            if (!store.deleteRun(user.tenantId,runId)) throw UserAuthException("conflict",409)
        }
        return ResponseEntity.noContent().build()
    }

    private fun lock(id: UUID, user: UserIdentity) {
        if (!store.lockRun(user.tenantId,id)) throw UserAuthException("not_found",404)
    }
    private fun uuid(value: String): UUID = UUID.fromString(value).also { require(it.toString().equals(value,ignoreCase=true)) }
}
