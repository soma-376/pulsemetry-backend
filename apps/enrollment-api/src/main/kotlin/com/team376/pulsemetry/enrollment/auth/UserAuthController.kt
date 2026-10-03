package com.team376.pulsemetry.enrollment.auth

import com.fasterxml.jackson.annotation.JsonProperty
import com.team376.pulsemetry.security.user.AuthRateLimiter
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserAuthService
import com.team376.pulsemetry.security.user.UserTokens
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/auth")
@ConditionalOnProperty(prefix = "pulsemetry.user-auth", name = ["enabled"], havingValue = "true")
class UserAuthController(private val auth: UserAuthService, private val limiter: AuthRateLimiter) {
    // 폐기 경로는 본문을 파싱하지 않는다. 비밀번호 DTO/로그를 남기지 않는다.
    @PostMapping("/signup", "/login", "/cli/authorize")
    fun passwordAuthenticationRemoved(): Nothing = throw UserAuthException("auth_method_removed", 410)
    @PostMapping("/token", "/cli/token")
    fun exchange(@RequestBody r: CodeRequest) = TokenResponse.from(auth.exchange(r.code, r.redirectUri, r.codeVerifier))
    // RT 를 가진 요청은 RT 를 소비하기 전에 세션 단위로 센다(ADR 0052).
    @PostMapping("/refresh")
    fun refresh(@RequestBody r: RefreshRequest, request: HttpServletRequest): TokenResponse {
        limiter.refreshToken(r.refreshToken, request.remoteAddr)
        return TokenResponse.from(auth.refresh(r.refreshToken))
    }
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@RequestBody r: RefreshRequest, request: HttpServletRequest) {
        limiter.refreshToken(r.refreshToken, request.remoteAddr)
        auth.logout(r.refreshToken)
    }
}

// 비밀을 가진 DTO는 data class가 아니다. 자동 toString으로 원문을 출력하지 않는다.
class CodeRequest(val code: String, @JsonProperty("redirect_uri") val redirectUri: String,
    @JsonProperty("code_verifier") val codeVerifier: String)
class RefreshRequest(@JsonProperty("refresh_token") val refreshToken: String)
class TokenResponse(@JsonProperty("access_token") val accessToken: String,
    @JsonProperty("refresh_token") val refreshToken: String,
    @JsonProperty("token_type") val tokenType: String,
    @JsonProperty("expires_in") val expiresIn: Long) {
    companion object {
        fun from(tokens: UserTokens) = TokenResponse(tokens.accessToken, tokens.refreshToken, "Bearer", tokens.expiresIn)
    }
}
