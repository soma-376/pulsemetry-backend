package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.authorization.AdminOnlyDashboardAuthorizer
import com.team376.pulsemetry.dashboard.authorization.DashboardAuthorizer
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
class QueryConfig {

	/** 합의 전 기본 정책 — 조직 관리자만(ADR 0022 §3). RBAC 합의 뒤 이 빈을 바꾼다. */
	@Bean
	fun dashboardAuthorizer(): DashboardAuthorizer = AdminOnlyDashboardAuthorizer()

	@Bean
	fun pageCursorCodec(mapper: ObjectMapper): PageCursorCodec = PageCursorCodec(mapper)
}
