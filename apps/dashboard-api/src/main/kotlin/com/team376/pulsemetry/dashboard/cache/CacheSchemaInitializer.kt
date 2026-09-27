package com.team376.pulsemetry.dashboard.cache

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean

/**
 * 기동 때 두 캐시 스키마를 적용한다(ADR 0023 §3). **실패하면 기동이 실패한다** — 이 앱은 캐시 없이 할 수 있는 일이 없다.
 * RDS 를 먼저 한다. ClickHouse 쪽은 멱등이라 순서가 결과를 바꾸지 않는다.
 */
class CacheSchemaInitializer(
	private val rds: RdsCacheSchema,
	private val clickHouse: ClickHouseCacheSchema,
) : InitializingBean {

	private val log = LoggerFactory.getLogger(CacheSchemaInitializer::class.java)

	override fun afterPropertiesSet() {
		val executed = rds.migrate()
		clickHouse.apply()
		log.info("dashboard_cache 스키마 적용 — RDS 마이그레이션 {}건, ClickHouse 문장 {}개", executed, ClickHouseCacheSchema.statements().size)
	}
}
