package com.team376.pulsemetry.devseed

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupSeedTest {
    @Test
    fun `최초 적재만 허용하고 ready 조직은 다시 생성하지 않는다`() {
        assertTrue(shouldSeedOnStartup(null, null, "A"))
        assertFalse(shouldSeedOnStartup("ready", "pulsemetry-seed-a", "A"))
    }

    @Test
    fun `부분 실패와 조직 식별자 변경은 자동 복구하지 않는다`() {
        for (state in listOf("loading", "resetting")) {
            assertFailsWith<IllegalStateException> { shouldSeedOnStartup(state, "pulsemetry-seed-a", "A") }
        }
        for (slug in listOf(null, "customer", "pulsemetry-seed-b")) {
            assertFailsWith<IllegalStateException> { shouldSeedOnStartup("ready", slug, "A") }
        }
        assertFailsWith<IllegalStateException> { shouldSeedOnStartup(null, "pulsemetry-seed-a", "A") }
    }

}
