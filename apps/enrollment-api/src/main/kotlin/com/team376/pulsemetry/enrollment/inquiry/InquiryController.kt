package com.team376.pulsemetry.enrollment.inquiry

import com.team376.pulsemetry.enrollment.error.FilterErrorResponse
import com.team376.pulsemetry.persistence.enrollment.inquiry.InquiryException
import com.team376.pulsemetry.persistence.enrollment.mail.InquiryNotifier
import org.springframework.beans.factory.ObjectProvider
import com.team376.pulsemetry.persistence.enrollment.inquiry.InquiryLimits
import com.team376.pulsemetry.persistence.enrollment.inquiry.InquiryReceipt
import com.team376.pulsemetry.persistence.enrollment.inquiry.InquiryStore
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration

/** 문의 접수 설정. 켰을 때 운영 수치와 허용 출처는 기본값이 없는 필수값이다. */
@ConfigurationProperties("pulsemetry.inquiries")
class InquiryProperties {
    var enabled: Boolean = false
    /** 같은 회사·이메일의 재전송을 같은 접수로 보는 시간 */
    var duplicateWindow: Duration? = null
    var rateLimit: RateLimit = RateLimit()
    /** 문의 폼을 띄우는 프론트 출처 */
    var allowedOrigins: List<String> = emptyList()
    /** 접수된 문의를 알릴 담당자 주소. 메일 기능을 같이 켰을 때 필요하다 */
    var notificationRecipient: String = ""

    class RateLimit {
        /** 출처 하나가 [window] 안에 보낼 수 있는 요청 수 */
        var requests: Int? = null
        var window: Duration? = null
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.inquiries", name = ["enabled"], havingValue = "true")
class InquiryConfig {
    @Bean
    fun inquiryStore(jdbc: JdbcClient, manager: PlatformTransactionManager, clock: Clock, properties: InquiryProperties,
        notifier: ObjectProvider<InquiryNotifier>): InquiryStore {
        require(properties.allowedOrigins.any { it.isNotBlank() }) { "pulsemetry.inquiries.allowed-origins 가 비어 있다" }
        return InquiryStore(jdbc, manager, clock, InquiryLimits(
            requireNotNull(properties.duplicateWindow) { "pulsemetry.inquiries.duplicate-window 가 비어 있다" },
            requireNotNull(properties.rateLimit.requests) { "pulsemetry.inquiries.rate-limit.requests 가 비어 있다" },
            requireNotNull(properties.rateLimit.window) { "pulsemetry.inquiries.rate-limit.window 가 비어 있다" }), notifier.ifAvailable)
    }
}

/** 공개 접수. 인증이 없고 조직·계정·초대를 만들지 않는다. */
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.inquiries", name = ["enabled"], havingValue = "true")
class InquiryController(private val store: InquiryStore) {
    @PostMapping("/api/v1/inquiries")
    fun receive(@RequestBody body: InquiryRequest, request: HttpServletRequest): ResponseEntity<InquiryReceipt> =
        ResponseEntity.status(201).header("Cache-Control", "no-store").body(store.receive(body.company, body.email, request.remoteAddr))
}

class InquiryRequest(val company: String?, val email: String?)

internal object InquiryErrorBody {
    private val messages = mapOf(
        "company" to "회사명을 1자 이상 100자 이하로 입력하세요.",
        "email" to "회사 이메일 형식을 확인하세요.",
        "rate_limited" to "문의 요청이 너무 많습니다. 잠시 후 다시 시도하세요.",
        "inquiry_unavailable" to "문의를 접수하지 못했습니다. 잠시 후 다시 시도하세요.",
    )
    fun message(code: String, field: String? = null) = messages[field] ?: messages[code] ?: "문의 요청 형식이 올바르지 않습니다."
}

/** 문의 경로의 오류도 공개 경로의 두 필드 본문이다. 문장은 CLI 가 아니라 문의 폼의 사용자에게 보인다. */
@Order(-10)
@RestControllerAdvice(assignableTypes = [InquiryController::class])
class InquiryErrors {
    @ExceptionHandler(InquiryException::class)
    fun inquiry(e: InquiryException) = error(e.status, e.code, e.field, e.retryAfter)
    @ExceptionHandler(HttpMessageNotReadableException::class, HttpMediaTypeNotSupportedException::class)
    fun malformed() = error(400, "invalid_request")
    @ExceptionHandler(DataAccessException::class, TransactionException::class)
    fun unavailable() = error(503, "inquiry_unavailable", retry = 1)
    private fun error(status: Int, code: String, field: String? = null, retry: Long? = null): ResponseEntity<Map<String, String>> {
        val builder = ResponseEntity.status(status).header("Cache-Control", "no-store")
        retry?.let { builder.header("Retry-After", it.toString()) }
        return builder.body(mapOf("error" to code, "message" to InquiryErrorBody.message(code, field)))
    }
}

/** 본문을 읽기 전에 출처별 한도를 센다. 빈으로 노출하지 않는다 — 보안 체인이 문의 경로에만 건다. */
class InquiryRequestFilter(private val store: InquiryStore) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = request.servletPath != "/api/v1/inquiries" || request.method == "OPTIONS"
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            store.limit(request.remoteAddr)
        } catch (e: InquiryException) {
            return write(response, e.status, e.code, e.retryAfter)
        } catch (_: RuntimeException) {
            // 드라이버 예외의 원문을 응답·로그에 싣지 않는다.
            return write(response, 503, "inquiry_unavailable", 1)
        }
        chain.doFilter(request, response)
    }
    private fun write(response: HttpServletResponse, status: Int, code: String, retry: Long?) {
        response.setHeader("Cache-Control", "no-store")
        FilterErrorResponse.write(response, status, code, InquiryErrorBody.message(code), retry)
    }
}
