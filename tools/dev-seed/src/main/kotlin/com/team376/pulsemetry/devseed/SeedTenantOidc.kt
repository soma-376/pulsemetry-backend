package com.team376.pulsemetry.devseed

import java.nio.file.Files
import java.util.Properties

/** Cognito 설정 파일의 공개 값만 회사에 이관한다. 비밀 원문을 출력하거나 DB에 쓰지 않는다. */
internal class SeedTenantOidc(val issuer: String, val clientId: String)

internal fun parseTenantOidc(properties: Properties): SeedTenantOidc {
    val issuer = properties.getProperty("PULSEMETRY_COGNITO_ISSUER_URI", "").trim()
    val clientId = properties.getProperty("PULSEMETRY_COGNITO_CLIENT_ID", "").trim()
    require(Regex("https://cognito-idp\\.ap-northeast-2\\.amazonaws\\.com/ap-northeast-2_[A-Za-z0-9]+").matches(issuer))
    require(clientId.matches(Regex("[A-Za-z0-9]{1,255}")))
    return SeedTenantOidc(issuer, clientId)
}

internal fun configureTenantOidc(store: SeedStore, configuration: SeedTenantOidc, apply: Boolean): Int {
    var count = 0
    store.transaction {
        for (name in listOf("A", "B", "C")) {
            val tenant = sql(id(name))
            check(store.scalar("SELECT state FROM dev_seed.dashboard_datasets WHERE tenant_id=$tenant") == "ready")
            val row = store.query("""SELECT slug,oidc_issuer,oidc_client_id,oidc_client_secret_ref,
                sso_enabled,oidc_require_verified_email FROM enrollment.tenants WHERE id=$tenant FOR UPDATE""").single()
            check(row[0] == "pulsemetry-seed-${name.lowercase()}")
            check(row[1] == null || row[1] == configuration.issuer) { "다른 issuer는 먼저 명시적으로 신원을 전환하세요." }
            check(row[2] == null || row[2] == configuration.clientId) { "기존 client ID는 자동 교체하지 않습니다." }
            check(row[3] == null || row[3] == "config:cognito") { "기존 비밀 참조는 자동 교체하지 않습니다." }
            if (row.drop(1) != listOf(configuration.issuer, configuration.clientId, "config:cognito", "t", "t")) {
                count++
                if (apply) store.execute("""UPDATE enrollment.tenants SET oidc_issuer=${sql(configuration.issuer)},
                    oidc_client_id=${sql(configuration.clientId)},oidc_client_secret_ref='config:cognito',
                    sso_enabled=true,oidc_require_verified_email=true WHERE id=$tenant""")
            }
        }
    }
    return count
}

internal fun runTenantOidc(args: Array<String>, environment: Map<String, String>) {
    check(environment["PULSEMETRY_DEV_SEED_MODE"] == "compose")
    require(args.size in 3..4 && args[1] == "cognito" && (args.size == 3 || args[3] == "--apply")) {
        "configure-oidc cognito /app/dev-auth/cognito.properties [--apply]"
    }
    val path = identityFilePath(args[2])
    require(Files.isRegularFile(path) && Files.size(path) <= 32_768)
    val properties = Properties().apply { Files.newBufferedReader(path).use(::load) }
    val configuration = parseTenantOidc(properties)
    val apply = args.size == 4
    SeedStore.openCompose().use { store ->
        val count = configureTenantOidc(store, configuration, apply)
        println("${if (apply) "적용" else "dry-run"}: $count 개 회사의 OIDC 설정. 회원 sub·비밀 원문은 변경하지 않습니다.")
    }
}
