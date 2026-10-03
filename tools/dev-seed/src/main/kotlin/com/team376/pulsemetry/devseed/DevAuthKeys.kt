package com.team376.pulsemetry.devseed

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64

/** Compose가 호스트에 키를 준비한다. 기존 키는 보존하고 비밀은 출력하지 않는다. */
internal fun prepareAuthKeys(directory: Path) {
    Files.createDirectories(directory)
    val privateKey = directory.resolve("private.pem")
    val publicKey = directory.resolve("public.pem")
    if (!Files.exists(privateKey) && !Files.exists(publicKey)) {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fun pem(label: String, bytes: ByteArray) = "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes) + "\n-----END $label-----\n"
        Files.writeString(privateKey, pem("PRIVATE KEY", pair.private.encoded), StandardOpenOption.CREATE_NEW)
        Files.writeString(publicKey, pem("PUBLIC KEY", pair.public.encoded), StandardOpenOption.CREATE_NEW)
    }
    check(Files.exists(privateKey) && Files.exists(publicKey)) { "키 파일이 일부만 있습니다. 기존 키를 확인하세요." }
    // 기존 키 파일을 그대로 사용해 이전 개발 환경의 암호화 응답과 대기 중인 메일 본문도 계속 읽는다.
    fun aesKey(name: String, label: String): String {
        val file = directory.resolve(name)
        if (!Files.exists(file)) Files.writeString(file,
            Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)), StandardOpenOption.CREATE_NEW)
        return Files.readString(file).trim().also { check(Base64.getDecoder().decode(it).size == 32) { "$label 암호화 키는 Base64 32바이트여야 합니다." } }
    }
    val responseKey = aesKey("response-key.txt", "응답")
    // 메일 키는 나중에 생겼다. 기존 디렉터리에는 이 실행에서 새로 만든다.
    val mailKey = aesKey("mail-key.txt", "메일")
    Files.writeString(directory.resolve("local-auth.properties"),
        "pulsemetry.management.response-encryption-key=$responseKey\npulsemetry.mail.encryption-key=$mailKey\n")
    println("개발용 인증 키 준비 완료")
}
