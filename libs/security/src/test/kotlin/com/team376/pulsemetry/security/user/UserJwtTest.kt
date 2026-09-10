package com.team376.pulsemetry.security.user

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

class UserJwtTest {
    private val now=Instant.parse("2026-09-09T00:00:00Z")
    private val clock=Clock.fixed(now,ZoneOffset.UTC)
    private val identity=UserIdentity(UUID.randomUUID(),UUID.randomUUID(),"member",UUID.randomUUID(),3)
    private fun jwt(kid:String="old", pair:KeyPair=old, keys:Map<String,RSAPublicKey> = mapOf("old" to old.public as RSAPublicKey)) =
        UserJwt("https://auth.test","pulsemetry",kid,pair.private as RSAPrivateKey,keys,clock)
    @Test fun `구 공개키를 선배포한 뒤 서명키 전환 중 양쪽 토큰을 검증한다`() {
        val keys=mapOf("old" to old.public as RSAPublicKey,"new" to fresh.public as RSAPublicKey)
        val oldToken=jwt().issue(identity)
        val rotating=jwt("new",fresh,keys)
        val newToken=rotating.issue(identity)
        assertThat(rotating.verify(oldToken)).isEqualTo(identity)
        assertThat(rotating.verify(newToken)).isEqualTo(identity)
        val retired=jwt("new",fresh,mapOf("new" to fresh.public as RSAPublicKey))
        assertThatThrownBy { retired.verify(oldToken) }.isInstanceOf(UserAuthException::class.java)
        assertThat(retired.verify(newToken)).isEqualTo(identity)
    }
    @Test fun `issuer audience 만료 미래 발급 누락 클레임과 다른 알고리즘을 거부한다`() {
        val original=SignedJWT.parse(jwt().issue(identity)).jwtClaimsSet
        val invalids=listOf(
            JWTClaimsSet.Builder(original).issuer("https://evil.test").build(),
            JWTClaimsSet.Builder(original).audience("different").build(),
            JWTClaimsSet.Builder(original).expirationTime(Date.from(now.minusSeconds(30))).build(),
            JWTClaimsSet.Builder(original).issueTime(Date.from(now.plusSeconds(31))).build(),
            JWTClaimsSet.Builder(original).claim("sid",null).build(),
            JWTClaimsSet.Builder(original).claim("manifest_revision",null).build(),
            JWTClaimsSet.Builder(original).claim("role","superuser").build(),
            JWTClaimsSet.Builder(original).expirationTime(Date.from(now.plusSeconds(301))).build(),
        )
        for (claims in invalids) assertThatThrownBy { jwt().verify(sign(claims)) }.isInstanceOf(UserAuthException::class.java)
        assertThatThrownBy { jwt().verify(sign(original,algorithm=JWSAlgorithm.RS512)) }.isInstanceOf(UserAuthException::class.java)
        assertThatThrownBy { jwt().verify(sign(original,kid="missing")) }.isInstanceOf(UserAuthException::class.java)
        assertThatThrownBy { jwt().verify(sign(original,key=fresh)) }.isInstanceOf(UserAuthException::class.java)
        assertThatThrownBy { jwt().verify("not-a-jwt") }.isInstanceOf(UserAuthException::class.java)
    }
    @Test fun `만료의 30초 시계 오차 경계와 키 불일치를 검사한다`() {
        val raw=jwt().issue(identity)
        val before=UserJwt("https://auth.test","pulsemetry","old",old.private as RSAPrivateKey,
            mapOf("old" to old.public as RSAPublicKey),Clock.fixed(now.plusSeconds(329),ZoneOffset.UTC))
        assertThat(before.verify(raw)).isEqualTo(identity)
        val after=UserJwt("https://auth.test","pulsemetry","old",old.private as RSAPrivateKey,
            mapOf("old" to old.public as RSAPublicKey),Clock.fixed(now.plusSeconds(330),ZoneOffset.UTC))
        assertThatThrownBy { after.verify(raw) }.isInstanceOf(UserAuthException::class.java)
        assertThatThrownBy { jwt(keys=mapOf("old" to fresh.public as RSAPublicKey)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
    @Test fun `웹 토큰은 manifest 없이 8시간이고 CLI와 교차 사용되지 않는다`() {
        fun web(at: Instant = now) = UserJwt("https://auth.test", "pulsemetry-dashboard", "old",
            old.private as RSAPrivateKey, mapOf("old" to old.public as RSAPublicKey), Clock.fixed(at, ZoneOffset.UTC), "web")
        val person = identity.copy(role = "owner", revision = null, sessionKind = "web")
        val token = web().issue(person)
        assertThat(web().lifetime.seconds).isEqualTo(28800)
        assertThat(web(now.plusSeconds(28799)).verify(token)).isEqualTo(person)
        assertThatThrownBy { web(now.plusSeconds(28830)).verify(token) }.isInstanceOf(UserAuthException::class.java)
        assertThatThrownBy { jwt().verify(token) }.isInstanceOf(UserAuthException::class.java)
        assertThatThrownBy { web().verify(jwt().issue(identity)) }.isInstanceOf(UserAuthException::class.java)
        assertThatThrownBy { web().issue(identity) }.isInstanceOf(IllegalArgumentException::class.java)
    }
    private fun sign(claims:JWTClaimsSet,algorithm:JWSAlgorithm=JWSAlgorithm.RS256,kid:String="old",key:KeyPair=old) =
        SignedJWT(JWSHeader.Builder(algorithm).keyID(kid).type(JOSEObjectType.JWT).build(),claims)
            .also { it.sign(RSASSASigner(key.private as RSAPrivateKey)) }.serialize()
    companion object {
        private val old=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val fresh=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
