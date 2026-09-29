package com.team376.pulsemetry.devseed

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DevAuthKeysTest {
    @TempDir lateinit var directory: Path

    @Test fun `키는 서명에 사용할 수 있고 재실행해도 기존 키를 보존한다`() {
        prepareAuthKeys(directory)
        val before = listOf("private.pem", "public.pem", "response-key.txt", "local-auth.properties")
            .associateWith { Files.readString(directory.resolve(it)) }
        fun der(name: String) = Base64.getDecoder().decode(before.getValue(name).lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString(""))
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(der("private.pem")))
        val publicKey = factory.generatePublic(X509EncodedKeySpec(der("public.pem")))
        val message = "local-auth".toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(privateKey); update(message); sign() }
        assertTrue(Signature.getInstance("SHA256withRSA").run { initVerify(publicKey); update(message); verify(signature) })
        val properties = Properties().apply { before.getValue("local-auth.properties").reader().use(::load) }
        assertEquals(before.getValue("response-key.txt"), properties.getProperty("pulsemetry.management.response-encryption-key"))
        assertEquals(32, Base64.getDecoder().decode(before.getValue("response-key.txt")).size)
        prepareAuthKeys(directory)
        before.forEach { (name, content) -> assertEquals(content, Files.readString(directory.resolve(name))) }
    }

    @Test fun `일부 키만 남아 있으면 자동 교체하지 않는다`() {
        Files.writeString(directory.resolve("private.pem"), "existing")
        assertFailsWith<IllegalStateException> { prepareAuthKeys(directory) }
        assertEquals("existing", Files.readString(directory.resolve("private.pem")))
        assertTrue(!Files.exists(directory.resolve("public.pem")))
    }
}
