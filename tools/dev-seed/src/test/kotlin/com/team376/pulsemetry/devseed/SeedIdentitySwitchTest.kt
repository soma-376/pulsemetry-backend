package com.team376.pulsemetry.devseed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SeedIdentitySwitchTest {
    private val users = listOf("A" to 0, "A" to 1, "B" to 0, "C" to 0, "C" to 1).map { (name, index) ->
        SeedIdentity(name, index, "${if (index == 0) "owner" else "admin"}@seed-${name.lowercase()}.example.test", "sub-$name-$index")
    }
    private val manifest = SeedIdentityManifest("https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_test", users)
    private fun document() = json.writeValueAsString(mapOf("version" to 1, "issuer" to manifest.issuer,
        "users" to users.map { mapOf("scenario" to it.scenario, "index" to it.index, "email" to it.email, "subject" to it.subject) }))

    @Test fun `고정 개발 회원과 HTTPS issuer만 수용한다`() {
        assertEquals(manifest, parseIdentityManifest(document()))
        for (invalid in listOf(document().replace("https://", "http://"), document().replace("owner@seed-a", "victim@seed-a"),
            document().replace("sub-A-1", "sub-A-0"), document().replace("\"index\":1", "\"index\":0"))) {
            assertFailsWith<IllegalArgumentException> { parseIdentityManifest(invalid) }
        }
    }

    private class MemoryStore(changes: List<IdentityChange>) : SeedIdentityStore {
        val links = changes.associate { it.user.memberId to it.expected }.toMutableMap()
        var writes = 0
        override fun transaction(block: () -> Unit) {
            val before = links.toMap(); val oldWrites = writes
            try { block() } catch (e: Exception) { links.clear(); links.putAll(before); writes = oldWrites; throw e }
        }
        override fun current(user: SeedIdentity) = links.getValue(user.memberId)
        override fun replace(change: IdentityChange) { writes++; links[change.user.memberId] = change.target }
    }

    @Test fun `dry run 재실행 복구는 회원을 초기화하지 않는다`() {
        val previous = users.associate { it.memberId to IdentityLink("https://previous-idp.example.test", "old-${it.subject}") }
        val changes = identityChanges(manifest, previous); val store = MemoryStore(changes)
        val snapshot = parseIdentitySnapshot(identitySnapshot(store, manifest), manifest)
        assertEquals(previous, snapshot)
        assertEquals(5, switchIdentities(store, changes, false)); assertEquals(0, store.writes)
        assertEquals(5, switchIdentities(store, changes, true)); assertEquals(5, store.writes)
        assertEquals(0, switchIdentities(store, changes, true)); assertEquals(5, store.writes)
        assertEquals(5, switchIdentities(store, identityChanges(manifest, snapshot, restore = true), true))
        assertEquals(changes.associate { it.user.memberId to it.expected }, store.links)
    }

    @Test fun `예상하지 못한 연결과 누락 연결은 부분 변경도 하지 않는다`() {
        val previous = users.associate { it.memberId to IdentityLink("https://previous-idp.example.test", "old-${it.subject}") }
        val changes = identityChanges(manifest, previous); val store = MemoryStore(changes)
        for (wrong in listOf(IdentityLink("https://other.test", "subject"), IdentityLink(null, null))) {
            store.links[users.last().memberId] = wrong
            assertFailsWith<IllegalStateException> { switchIdentities(store, changes, true) }
            assertEquals(0, store.writes)
        }
    }

    @Test fun `새 시드는 미연결만 수용하고 원본 스냅샷으로 null 복원도 가능하다`() {
        val changes = identityChanges(manifest); val store = MemoryStore(changes)
        val snapshot = parseIdentitySnapshot(identitySnapshot(store, manifest), manifest)
        assertEquals(5, switchIdentities(store, changes, true))
        assertEquals(0, switchIdentities(store, changes, true))
        assertEquals(5, switchIdentities(store, identityChanges(manifest, snapshot, restore = true), true))
        assertEquals(snapshot, store.links)
        store.links[users.last().memberId] = IdentityLink("https://unexpected.test", "other")
        val writes = store.writes
        assertFailsWith<IllegalStateException> { switchIdentities(store, changes, true) }
        assertEquals(writes, store.writes)
    }

    @Test fun `스냅샷의 중복 회원 누락 필드 불완전 연결 이메일 변조를 거부한다`() {
        val store = MemoryStore(identityChanges(manifest))
        val snapshot = identitySnapshot(store, manifest)
        for (invalid in listOf(
            snapshot.replace(users.last().memberId, users.first().memberId),
            snapshot.replace("owner@seed-a", "victim@seed-a"),
            snapshot.replace("\"issuer\" : null", "\"missing\" : null"),
            snapshot.replace("\"subject\" : null", "\"subject\" : \"partial\""),
        )) assertFailsWith<IllegalArgumentException> { parseIdentitySnapshot(invalid, manifest) }
    }

    @Test fun `연결 CLI는 명시한 형태만 허용한다`() {
        assertEquals(IdentityCommand("switch-oidc", "manifest", "backup", true),
            IdentityCommand.parse(arrayOf("switch-oidc", "cognito", "manifest", "--from", "backup", "--apply")))
        assertEquals(IdentityCommand("snapshot-oidc", "manifest", "backup", false),
            IdentityCommand.parse(arrayOf("snapshot-oidc", "manifest", "backup")))
        assertEquals(IdentityCommand("restore-oidc", "manifest", "backup", false),
            IdentityCommand.parse(arrayOf("restore-oidc", "manifest", "backup")))
        for (args in listOf(emptyArray(), arrayOf("switch-oidc"), arrayOf("switch-oidc", "other", "manifest"),
            arrayOf("restore-oidc", "manifest"), arrayOf("snapshot-oidc", "manifest", "backup", "--apply"),
            arrayOf("switch-oidc", "cognito", "manifest", "--force"))) {
            assertFailsWith<IllegalArgumentException> { IdentityCommand.parse(args) }
        }
    }
}
