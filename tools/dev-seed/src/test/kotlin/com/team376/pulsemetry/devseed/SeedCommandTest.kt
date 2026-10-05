package com.team376.pulsemetry.devseed

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SeedCommandTest {
    private val environment = mapOf("PULSEMETRY_DEV_SEED_MODE" to "compose", "PULSEMETRY_LOCAL_SEED_DATE" to "2026-09-28")

    @Test fun `Compose 기본 실행은 초기화이고 날짜와 선택을 환경에서 읽는다`() {
        val command = SeedCommand.parse(emptyArray(), environment + ("PULSEMETRY_LOCAL_SEED_SCENARIOS" to "C,A,C"))
        assertEquals(SeedCommand("init", LocalDate.of(2026, 9, 28), listOf("C", "A")), command)
    }

    @Test fun `수동 검증과 초기화는 명시한 대상만 선택한다`() {
        assertEquals(SeedCommand("verify", LocalDate.of(2026, 9, 27), listOf("B")),
            SeedCommand.parse(arrayOf("verify", "2026-09-27", "B"), environment))
        assertEquals(SeedCommand("reset", null, listOf("C")), SeedCommand.parse(arrayOf("reset", "C"), environment))
    }

    @Test fun `빈 조직 D·E 는 명시할 때만 고르고 기본 초기화 목록은 A,B,C 그대로다`() {
        assertEquals(listOf("D", "E"), SeedCommand.parse(arrayOf("verify", "2026-09-27", "D,E"), environment).scenarios)
        assertEquals(listOf("A", "B", "C", "D", "E"), SeedCommand.parse(emptyArray(), environment + ("PULSEMETRY_LOCAL_SEED_SCENARIOS" to "A,B,C,D,E")).scenarios)
        assertEquals(listOf("A", "B", "C"), SeedCommand.parse(emptyArray(), environment).scenarios)
        assertEquals(listOf("A", "B", "C"), SeedCommand.parse(arrayOf("reset"), environment).scenarios)
    }

    @Test fun `시드 manifest 의 수신 주소는 환경에서 읽고 비어 있으면 4316 이며 http(s) 와 호스트가 아니면 거부한다`() {
        assertEquals("http://localhost:4316", SeedCommand.parse(emptyArray(), environment).otlpEndpoint)
        assertEquals("http://localhost:4316", SeedCommand.parse(emptyArray(), environment + ("PULSEMETRY_LOCAL_SEED_OTLP_ENDPOINT" to "  ")).otlpEndpoint)
        assertEquals("http://localhost:24316", SeedCommand.parse(arrayOf("plan", "2026-09-28", "A"), environment + ("PULSEMETRY_LOCAL_SEED_OTLP_ENDPOINT" to "http://localhost:24316/")).otlpEndpoint)
        assertEquals("https://otlp.example.test", otlpEndpoint(mapOf("PULSEMETRY_LOCAL_SEED_OTLP_ENDPOINT" to "https://otlp.example.test")))
        for (bad in listOf("localhost:4316", "ftp://localhost:4316", "http://", "not a url", "http://user:pw@localhost:4316", "http://localhost:4316/?a=1", "https://h.example.test#x")) {
            assertFailsWith<IllegalArgumentException>(bad) { SeedCommand.parse(emptyArray(), environment + ("PULSEMETRY_LOCAL_SEED_OTLP_ENDPOINT" to bad)) }
        }
    }

    @Test fun `fixture는 날짜와 A 선택을 명시한다`() {
        assertEquals(SeedCommand("fixture", LocalDate.of(2026, 9, 28), listOf("A")),
            SeedCommand.parse(arrayOf("fixture", "2026-09-28", "A"), environment))
    }

    @Test fun `고정 제공자 신원을 만드는 구 연결 명령은 거부한다`() {
        assertFailsWith<IllegalArgumentException> { SeedCommand.parse(arrayOf("link-oidc", "A,C"), environment) }
    }

    @Test fun `호스트 실행과 잘못된 명령은 DB 작업 전에 거부한다`() {
        assertFailsWith<IllegalStateException> { SeedCommand.parse(arrayOf("plan", "2026-09-28"), emptyMap()) }
        for (args in listOf(arrayOf("delete"), arrayOf("verify"), arrayOf("apply", ""),
            arrayOf("reset", "F"), arrayOf("reset", ""), arrayOf("reset", "C", "extra"),
            arrayOf("init", "2026-09-28"), arrayOf("plan", "2026-09-28", "A,"))) {
            assertFailsWith<RuntimeException> { SeedCommand.parse(args, environment) }
        }
    }

}
