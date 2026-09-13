package com.team376.pulsemetry.enrollment.auth

import com.fasterxml.jackson.annotation.JsonProperty
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserAuthService
import com.team376.pulsemetry.security.user.UserTokens
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/auth")
@ConditionalOnProperty(prefix = "pulsemetry.user-auth", name = ["enabled"], havingValue = "true")
class UserAuthController(private val auth: UserAuthService) {
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@RequestBody request: SignupRequest) {
        val code = InvitationCode.normalize(request.code) ?: throw UserAuthException("invalid_request", 400)
        auth.signup(Sha256.hex(code), request.email, request.password)
    }
    @PostMapping("/login")
    fun login(@RequestBody r: LoginRequest) = TokenResponse.from(auth.login(r.tenantId, r.email, r.password))
    @PostMapping("/cli/authorize")
    fun authorize(@RequestBody r: AuthorizeRequest) = CallbackResponse(auth.authorize(r.tenantId, r.email, r.password,
        r.redirectUri, r.state, r.codeChallenge, r.codeChallengeMethod))
    @PostMapping("/cli/token")
    fun exchange(@RequestBody r: CodeRequest) = TokenResponse.from(auth.exchange(r.code, r.redirectUri, r.codeVerifier))
    @PostMapping("/refresh")
    fun refresh(@RequestBody r: RefreshRequest) = TokenResponse.from(auth.refresh(r.refreshToken))
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@RequestBody r: RefreshRequest) = auth.logout(r.refreshToken)
}

// 비밀을 가진 DTO는 data class가 아니다. 자동 toString으로 원문을 출력하지 않는다.
class SignupRequest(val code: String, val email: String, val password: String)
class LoginRequest(@JsonProperty("tenant_id") val tenantId: UUID, val email: String, val password: String)
class AuthorizeRequest(@JsonProperty("tenant_id") val tenantId: UUID, val email: String, val password: String,
    @JsonProperty("redirect_uri") val redirectUri: String, val state: String,
    @JsonProperty("code_challenge") val codeChallenge: String,
    @JsonProperty("code_challenge_method") val codeChallengeMethod: String)
class CodeRequest(val code: String, @JsonProperty("redirect_uri") val redirectUri: String,
    @JsonProperty("code_verifier") val codeVerifier: String)
class RefreshRequest(@JsonProperty("refresh_token") val refreshToken: String)
class CallbackResponse(@JsonProperty("callback_url") val callbackUrl: String)
class TokenResponse(@JsonProperty("access_token") val accessToken: String,
    @JsonProperty("refresh_token") val refreshToken: String,
    @JsonProperty("token_type") val tokenType: String,
    @JsonProperty("expires_in") val expiresIn: Long) {
    companion object {
        fun from(tokens: UserTokens) = TokenResponse(tokens.accessToken, tokens.refreshToken, "Bearer", tokens.expiresIn)
    }
}
