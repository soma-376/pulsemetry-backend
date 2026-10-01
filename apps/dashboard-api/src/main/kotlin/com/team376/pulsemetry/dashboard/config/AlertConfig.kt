package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.alert.AlertEvaluationJob
import com.team376.pulsemetry.dashboard.alert.AlertEvaluator
import com.team376.pulsemetry.dashboard.alert.AlertService
import com.team376.pulsemetry.dashboard.alert.AlertStore
import com.team376.pulsemetry.dashboard.analytics.AnalyticsFrames
import com.team376.pulsemetry.dashboard.analytics.CurrentStateTokens
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator
import com.team376.pulsemetry.dashboard.organization.OrganizationReader
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * 알림 평가와 조회의 조립 (ADR 0051 §5·§6). 평가 기록은 캐시 계정으로 자기 스키마에 쓰고, 규칙·목록·분석 행·확인은 원천 계정으로 읽는다.
 */
@Configuration(proxyBeanMethods = false)
class AlertConfig {

	@Bean
	fun alertStore(@Qualifier(CacheStoreConfig.CACHE_DATA_SOURCE) cacheDataSource: HikariDataSource, mapper: ObjectMapper): AlertStore =
		AlertStore(JdbcClient.create(cacheDataSource), mapper)

	@Bean
	fun alertEvaluator(
		properties: DashboardApiProperties,
		source: JdbcClient,
		reader: ClickHouseSourceReader,
		store: AlertStore,
		frames: AnalyticsFrames,
		aggregator: UsageAggregator,
		organizations: OrganizationReader,
		boundaries: RetentionBoundaryReader,
		clock: Clock,
	): AlertEvaluator = AlertEvaluator(source, reader, store, frames, aggregator, organizations, boundaries,
		// 관측이 다 도착했다고 보는 대기는 기간 완전성과 같다(ADR 0042).
		properties.completeness.settleAfter, properties.alerts.lease, clock)

	@Bean
	fun alertEvaluationJob(evaluator: AlertEvaluator): AlertEvaluationJob = AlertEvaluationJob(evaluator)

	@Bean
	fun alertService(source: JdbcClient, store: AlertStore, tokens: CurrentStateTokens, codec: PageCursorCodec, clock: Clock): AlertService =
		AlertService(source, store, tokens, codec, clock)
}
