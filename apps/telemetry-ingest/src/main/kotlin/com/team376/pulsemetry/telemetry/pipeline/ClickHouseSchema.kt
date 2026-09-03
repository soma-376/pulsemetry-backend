package com.team376.pulsemetry.telemetry.pipeline

import com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator
import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import java.time.Duration

/**
 * 스키마가 적용됐는지를 들고 있다가 적재 직전에 확인한다.
 *
 * ## 왜 기동 실패를 견디는가 (ADR 0015 · 0016)
 *
 * 이식 원본은 다섯 번까지 재시도한 뒤 실패해도 서버를 띄웠다. 그 정책을 그대로 둔다 —
 * **아카이브가 다음 단계보다 앞이므로**, ClickHouse 가 죽어 있어도 앱이 살아 있으면 원본은
 * 외부 저장소에 남는다. 기동을 막으면 그 경로까지 함께 죽어 유실이 오히려 커진다.
 *
 * ## 왜 이 클래스가 따로 있는가
 *
 * 기동 실패를 허용하는 대가로 **적용 전 적재를 막는 것**이 이 클래스의 존재 이유다.
 * 테이블이 없는 채로 `INSERT` 가 가면 ClickHouse 가 `UNKNOWN_TABLE` 을 **404** 로 돌려주고,
 * 새 계약(허브 ADR 0006)에서 404 는 영구 오류라 데몬이 **첫 시도에 배치를 버린다.**
 * 적용 전의 실패는 일시 장애여야 하므로, 적재 전에 한 번 더 적용을 시도하고 실패하면
 * [TelemetrySinkUnavailableException] 이 그대로 나가 503 이 된다.
 *
 * 정상 상태의 비용은 `volatile` 읽기 하나다.
 */
class ClickHouseSchema(
	private val migrator: ClickHouseSchemaMigrator,
	private val startupAttempts: Int,
	private val startupBackoff: Duration,
) : InitializingBean {

	private val log = LoggerFactory.getLogger(javaClass)

	@Volatile
	private var applied: Boolean = false

	private val lock = Any()

	/**
	 * 기동 경로. 웹 서버가 요청을 받기 전에 끝난다.
	 *
	 * 재시도는 **일시 장애에만** 건다. DDL 자체가 거부되는 것은 우리 코드의 결함이라 다시 걸어도
	 * 같고, 기다리는 만큼 기동만 늦어진다.
	 */
	override fun afterPropertiesSet() {
		repeat(startupAttempts) { attempt ->
			try {
				apply()
				log.info("clickhouse 스키마를 적용했다")
				return
			} catch (exception: TelemetrySinkUnavailableException) {
				log.warn(
					"clickhouse 스키마 미적용 ({}) — 재시도 {}/{}",
					exception.message,
					attempt + 1,
					startupAttempts,
				)
				sleepBeforeRetry()
			} catch (exception: RuntimeException) {
				log.error("clickhouse 스키마 적용이 거부됐다 — 재시도하지 않는다", exception)
				return
			}
		}
		log.warn("clickhouse 스키마를 적용하지 못한 채 기동한다. 아카이브는 계속 돌고 적재는 503 이다.")
	}

	/**
	 * 적재 직전 관문. 아직이면 한 번 더 시도하고, 실패하면 예외가 그대로 나가 503 이 된다.
	 */
	fun ensureApplied() {
		if (applied) return
		synchronized(lock) {
			if (applied) return
			apply()
		}
	}

	private fun apply() {
		migrator.apply()
		applied = true
	}

	private fun sleepBeforeRetry() {
		try {
			Thread.sleep(startupBackoff.toMillis())
		} catch (exception: InterruptedException) {
			// 기동 중 인터럽트는 종료 신호다. 상태를 복구하고 재시도를 멈춘다.
			Thread.currentThread().interrupt()
			throw IllegalStateException("clickhouse 스키마 적용 대기 중 중단됐다", exception)
		}
	}
}
