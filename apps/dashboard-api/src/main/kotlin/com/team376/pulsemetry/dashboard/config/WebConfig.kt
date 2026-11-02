package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.request.RequestIdFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration(proxyBeanMethods = false)
class WebConfig {
    @Bean
    fun errorResponseWriter(mapper: tools.jackson.databind.ObjectMapper, properties: DashboardApiProperties) =
        com.team376.pulsemetry.dashboard.error.ErrorResponseWriter(mapper, properties.retryAfter)


	/**
	 * 요청 ID 필터를 **가장 앞**에 둔다. Spring Security 의 위임 필터(-100)보다 앞이어야 보안 체인이 쓴
	 * 거부 응답에도 `X-Request-Id` 가 실린다.
	 */
	@Bean
	fun requestIdFilter(): FilterRegistrationBean<RequestIdFilter> =
		FilterRegistrationBean(RequestIdFilter()).apply {
			order = Ordered.HIGHEST_PRECEDENCE
		}
}
