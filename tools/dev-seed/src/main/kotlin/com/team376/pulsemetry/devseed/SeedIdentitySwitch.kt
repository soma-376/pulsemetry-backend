package com.team376.pulsemetry.devseed

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.net.URI

/** 고정 개발 회원 5명의 연결만 표현한다. 임의 회원·SQL·접속 주소는 입력받지 않는다. */
internal data class SeedIdentity(val scenario: String, val index: Int, val email: String, val subject: String) {
    val memberId get() = id("$scenario/member/$index")
}
internal data class SeedIdentityManifest(val issuer: String, val users: List<SeedIdentity>)
internal data class IdentityLink(val issuer: String?, val subject: String?)
internal data class IdentityChange(val user: SeedIdentity, val expected: IdentityLink, val target: IdentityLink)

internal fun parseIdentityManifest(text: String): SeedIdentityManifest {
    require(text.length <= 32_768) { "신원 manifest가 너무 큽니다." }
    val root = json.readTree(text)
    require(root.path("version").asInt() == 1)
    val issuer = root.path("issuer").asString()
    require(Regex("https://cognito-idp\\.ap-northeast-2\\.amazonaws\\.com/ap-northeast-2_[A-Za-z0-9]+").matches(issuer)) {
        "개발 Cognito issuer만 허용합니다."
    }
    val expected = listOf("A" to 0, "A" to 1, "B" to 0, "C" to 0, "C" to 1)
    val nodes = root.path("users")
    require(nodes.isArray && nodes.size() == expected.size) { "A/B/C 개발 계정 5개가 필요합니다." }
    val users = (0 until nodes.size()).map { position ->
        val node = nodes[position]
        require(node.path("index").isIntegralNumber)
        val scenario = node.path("scenario").asString()
        val index = node.path("index").asInt()
        require(scenario to index in expected)
        val email = "${if (index == 0) "owner" else "admin"}@seed-${scenario.lowercase()}.example.test"
        require(node.path("email").asString() == email) { "개발 회원 이메일이 다릅니다." }
        val subject = node.path("subject").asString()
        require(Regex("[A-Za-z0-9_-]{1,255}").matches(subject)) { "잘못된 subject입니다." }
        SeedIdentity(scenario, index, email, subject)
    }
    require(users.map { it.scenario to it.index }.toSet() == expected.toSet()) { "중복 회원은 허용하지 않습니다." }
    require(users.map { it.subject }.toSet().size == users.size) { "중복 subject는 허용하지 않습니다." }
    return SeedIdentityManifest(issuer, users)
}

internal fun identityChanges(manifest: SeedIdentityManifest, previous: Map<String, IdentityLink>? = null,
    restore: Boolean = false): List<IdentityChange> {
    require(!restore || previous != null) { "복원에는 원본 스냅샷이 필요합니다." }
    require(previous == null || previous.keys == manifest.users.map { it.memberId }.toSet())
    return manifest.users.map { user ->
        val original = previous?.getValue(user.memberId) ?: IdentityLink(null, null)
        val cognito = IdentityLink(manifest.issuer, user.subject)
        if (restore) IdentityChange(user, cognito, original) else IdentityChange(user, original, cognito)
    }
}

/** 원본은 특정 IdP의 고정 ID로 추정하지 않는다. 누락·타 회원·불완전한 링크는 거부한다. */
internal fun parseIdentitySnapshot(text: String, manifest: SeedIdentityManifest): Map<String, IdentityLink> {
    require(text.length <= 32_768)
    val root = json.readTree(text)
    require(root.path("version").asInt() == 1 && root.path("kind").asString() == "seed-identity-snapshot")
    val nodes = root.path("users")
    require(nodes.isArray && nodes.size() == manifest.users.size)
    val links = (0 until nodes.size()).map { position ->
        val node = nodes[position]
        val user = manifest.users.single { it.memberId == node.path("memberId").asString() }
        require(node.path("email").asString() == user.email)
        fun nullableText(key: String): String? {
            val value = node.path(key)
            require(value.isNull || value.isString) { "스냅샷 필드가 누락되거나 문자열이 아닙니다." }
            return if (value.isNull) null else value.asString().also { require(it.isNotBlank() && it.length <= 2048) }
        }
        val issuer = nullableText("issuer"); val subject = nullableText("subject")
        require(subject == null || issuer != null)
        if (issuer != null) {
            val uri = URI(issuer)
            require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                (uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))))
        }
        user.memberId to IdentityLink(issuer, subject)
    }
    require(links.map { it.first }.toSet().size == manifest.users.size)
    return links.toMap()
}

internal fun identitySnapshot(store: SeedIdentityStore, manifest: SeedIdentityManifest): String {
    var document = ""
    store.transaction {
        document = json.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(
            "version" to 1, "kind" to "seed-identity-snapshot", "users" to manifest.users.map { user ->
                val link = store.current(user)
                mapOf("memberId" to user.memberId, "email" to user.email, "issuer" to link.issuer, "subject" to link.subject)
            }))
        parseIdentitySnapshot(document, manifest)
    }
    return document
}

internal fun requireExpectedLink(current: IdentityLink, change: IdentityChange): Boolean {
    check(current == change.expected || current == change.target ||
        (change.expected == IdentityLink(null, null) && current == IdentityLink(change.target.issuer, null))) { "예상 원본과 다른 인증 연결은 덮어쓰지 않습니다." }
    return current != change.target
}

internal interface SeedIdentityStore {
    fun transaction(block: () -> Unit)
    fun current(user: SeedIdentity): IdentityLink
    fun replace(change: IdentityChange)
}

internal fun switchIdentities(store: SeedIdentityStore, changes: List<IdentityChange>, apply: Boolean): Int {
    var count = 0
    store.transaction {
        // 모든 행을 먼저 잠그고 검사한다. 부분 적용은 허용하지 않는다.
        val pending = changes.filter { requireExpectedLink(store.current(it.user), it) }
        count = pending.size
        if (apply) pending.forEach(store::replace)
    }
    return count
}

internal class ComposeIdentityStore(private val store: SeedStore) : SeedIdentityStore {
    override fun transaction(block: () -> Unit) = store.transaction(block)
    override fun current(user: SeedIdentity): IdentityLink {
        val tenant = sql(id(user.scenario))
        check(store.scalar("SELECT state FROM dev_seed.dashboard_datasets WHERE tenant_id=$tenant") == "ready")
        check(store.scalar("SELECT slug FROM enrollment.tenants WHERE id=$tenant") == "pulsemetry-seed-${user.scenario.lowercase()}")
        val row = store.query("SELECT m.email,t.oidc_issuer,m.oidc_subject FROM enrollment.members m JOIN enrollment.tenants t ON t.id=m.tenant_id WHERE m.id=${sql(user.memberId)} AND m.tenant_id=$tenant FOR UPDATE OF t,m").single()
        check(row[0] == user.email) { "수정된 개발 회원은 덮어쓰지 않습니다." }
        return IdentityLink(row[1], row[2])
    }
    override fun replace(change: IdentityChange) {
        val member = sql(change.user.memberId)
        val tenant = sql(id(change.user.scenario))
        // 회사 issuer는 공유된다. 시드 도구가 모르는 연결된 회원이 있으면 교체하지 않는다.
        val currentIssuer = store.query("SELECT oidc_issuer FROM enrollment.tenants WHERE id=$tenant FOR UPDATE").single()[0]
        if (currentIssuer != change.target.issuer) {
            val seedMembers = (if (change.user.scenario == "B") listOf(0) else listOf(0, 1))
                .joinToString(",") { sql(id("${change.user.scenario}/member/$it")) }
            check(store.scalar("SELECT count(*) FROM enrollment.members WHERE tenant_id=$tenant AND oidc_subject IS NOT NULL AND id NOT IN ($seedMembers)") == "0") {
                "다른 회원이 연결된 회사의 issuer는 시드 도구로 교체하지 않습니다."
            }
            store.execute("UPDATE enrollment.tenants SET oidc_issuer=${sql(change.target.issuer)},sso_enabled=false WHERE id=$tenant")
        }
        store.execute("UPDATE enrollment.members SET oidc_subject=${sql(change.target.subject)} WHERE id=$member")
        store.execute("UPDATE enrollment.user_sessions SET revoked_at=now() WHERE member_id=$member AND revoked_at IS NULL")
        store.execute("UPDATE enrollment.user_authorization_codes SET used_at=now() WHERE member_id=$member AND used_at IS NULL")
    }
}

internal fun runIdentitySwitch(args: Array<String>, environment: Map<String, String>) {
    check(environment["PULSEMETRY_DEV_SEED_MODE"] == "compose") { "개발 Compose에서만 실행할 수 있습니다." }
    val request = IdentityCommand.parse(args)
    val manifest = parseIdentityManifest(readIdentityFile(request.manifest))
    val previous = request.snapshot?.takeUnless { request.command == "snapshot-oidc" }
        ?.let { parseIdentitySnapshot(readIdentityFile(it), manifest) }
    SeedStore.openCompose().use { store ->
        val adapter = ComposeIdentityStore(store)
        if (request.command == "snapshot-oidc") {
            val path = identityFilePath(request.snapshot!!)
            val document = identitySnapshot(adapter, manifest)
            Files.newByteChannel(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(document.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            println("원본 연결 스냅샷 저장 완료. DB 변경 없음. 기존 파일은 덮어쓰지 않습니다.")
        } else {
            val changes = identityChanges(manifest, previous, request.command == "restore-oidc")
            val count = switchIdentities(adapter, changes, request.apply)
            println("${if (request.apply) "적용" else "dry-run"}: $count 명의 인증 연결. 회원·역할·분석 데이터 보존. 적용 시 해당 회원은 재로그인이 필요합니다.")
        }
    }
}

internal data class IdentityCommand(val command: String, val manifest: String, val snapshot: String?, val apply: Boolean) {
    companion object {
        fun parse(args: Array<String>): IdentityCommand {
            val command = args.firstOrNull()
            if (command == "snapshot-oidc") {
                require(args.size == 3) { "snapshot-oidc <manifest> <새 스냅샷 파일>" }
                return IdentityCommand(command, args[1], args[2], false)
            }
            require(command in listOf("switch-oidc", "restore-oidc"))
            val apply = args.lastOrNull() == "--apply"
            val values = if (apply) args.dropLast(1) else args.toList()
            if (command == "restore-oidc") {
                require(values.size == 3) { "restore-oidc <manifest> <원본 스냅샷> [--apply]" }
                return IdentityCommand(command, values[1], values[2], apply)
            }
            require(values.size in listOf(3, 5) && values[1] == "cognito" &&
                (values.size == 3 || values[3] == "--from")) {
                "switch-oidc cognito <manifest> [--from <원본 스냅샷>] [--apply]"
            }
            return IdentityCommand(command!!, values[2], values.getOrNull(4), apply)
        }
    }
}

internal fun identityFilePath(value: String): Path {
    val path = Path.of(value).toAbsolutePath().normalize()
    require(path.parent == Path.of("/app/dev-auth") && !Files.isSymbolicLink(path)) { "dev-auth 바로 아래의 일반 파일만 허용합니다." }
    return path
}

private fun readIdentityFile(value: String): String {
    val path = identityFilePath(value)
    require(Files.isRegularFile(path) && Files.size(path) <= 32_768)
    return Files.readString(path)
}
