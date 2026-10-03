package com.team376.pulsemetry.devseed

import java.nio.file.Files
import java.nio.file.Path
import tools.jackson.databind.JsonNode

/** 공개 개발 fixture. 비밀 원문·임의 회사·실제 사용자 이메일을 허용하지 않는다. */
internal data class CompanyOidcSeed(val company: String, val issuer: String, val clientId: String,
    val secretRef: String)

internal fun parseCompanyOidcSeed(text: String): CompanyOidcSeed {
    require(text.length <= 32_768) { "OIDC 시드 파일이 너무 큽니다." }
    val root = json.readTree(text)
    fun fields(node: JsonNode, expected: Set<String>) {
        require(node.isObject && node.properties().map { it.key }.toSet() == expected) {
            "OIDC 시드에는 정해진 공개 필드만 넣으세요. 비밀값은 허용하지 않습니다."
        }
    }
    fields(root, setOf("version", "company", "issuer", "clientId", "clientSecretRef"))
    require(root.path("version").isIntegralNumber && root.path("version").asInt() == 2)
    fun string(node: JsonNode, field: String): String {
        val value = node.path(field)
        require(value.isString && value.asString().isNotBlank())
        return value.asString()
    }
    val company = string(root, "company")
    require(company in listOf("A", "B"))
    val issuer = string(root, "issuer")
    require(Regex("https://cognito-idp\\.ap-northeast-2\\.amazonaws\\.com/ap-northeast-2_[A-Za-z0-9]+").matches(issuer))
    val clientId = string(root, "clientId")
    require(Regex("[A-Za-z0-9]{1,255}").matches(clientId))
    val secretRef = string(root, "clientSecretRef")
    require(secretRef == "config:cognito-${company.lowercase()}")
    return CompanyOidcSeed(company, issuer, clientId, secretRef)
}

internal fun loadCompanyOidcSeeds(directory: Path, scenarios: List<String>): List<CompanyOidcSeed> =
    scenarios.filter { it in listOf("A", "B") }.distinct().map { company ->
        val file = directory.resolve("company-${company.lowercase()}/cognito-seed.json")
        require(Files.isRegularFile(file) && !Files.isSymbolicLink(file) && Files.size(file) <= 32_768) {
            "$company: 공개 OIDC 시드 JSON이 없거나 유효하지 않습니다."
        }
        parseCompanyOidcSeed(Files.readString(file)).also { require(it.company == company) }
    }

/** 최초 연결만 자동 주입한다. ready 시드의 다른 인증 연결과 활성화 변경을 덮어쓰지 않는다. */
internal fun seedCompanyOidc(store: SeedStore, seeds: List<CompanyOidcSeed>): Int {
    require(seeds.map { it.company }.toSet().size == seeds.size)
    var changes = 0
    store.transaction {
        for (seed in seeds.sortedBy { it.company }) {
            val tenant = sql(id(seed.company))
            check(store.scalar("SELECT state FROM dev_seed.dashboard_datasets WHERE tenant_id=$tenant") == "ready")
            val row = store.query("""SELECT slug,oidc_issuer,oidc_client_id,oidc_client_secret_ref,
                sso_enabled,oidc_require_verified_email FROM enrollment.tenants WHERE id=$tenant FOR UPDATE""").single()
            check(row[0] == "pulsemetry-seed-${seed.company.lowercase()}")
            val current = row.subList(1, 4)
            val expected = listOf(seed.issuer, seed.clientId, seed.secretRef)
            val fresh = current.all { it == null } && row[4] == "f" && row[5] == "f"
            check(fresh || current == expected) {
                "${seed.company}: 기존 OIDC 연결이 공개 시드와 다릅니다. 자동 교체하지 않습니다."
            }
            if (!fresh) continue
            // 기존 sub의 issuer를 추측하지 않는다. 최초 회사 설정에만 미연결 회원을 허용한다.
            check(store.scalar("SELECT count(*) FROM enrollment.members WHERE tenant_id=$tenant AND oidc_subject IS NOT NULL") == "0") {
                "${seed.company}: 회사 설정 없이 연결된 회원이 있습니다. 명시적으로 확인하세요."
            }
            store.execute("""UPDATE enrollment.tenants SET oidc_issuer=${sql(seed.issuer)},oidc_client_id=${sql(seed.clientId)},
                oidc_client_secret_ref=${sql(seed.secretRef)},sso_enabled=true,oidc_require_verified_email=true WHERE id=$tenant""")
            // 이전 개발 세션이 있다면 새 신원 연결 뒤에는 다시 로그인한다.
            store.execute("UPDATE enrollment.user_sessions SET revoked_at=now() WHERE member_id IN (SELECT id FROM enrollment.members WHERE tenant_id=$tenant) AND revoked_at IS NULL")
            store.execute("UPDATE enrollment.user_authorization_codes SET used_at=now() WHERE member_id IN (SELECT id FROM enrollment.members WHERE tenant_id=$tenant) AND used_at IS NULL")
            changes++
        }
    }
    return changes
}
