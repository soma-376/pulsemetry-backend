package com.team376.pulsemetry.telemetry.pipeline

import com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator
import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock

/**
 * 스키마가 적용됐는지를 들고 있다가 적재 직전에 확인한다.
 *
 * ## 왜 기동 실패를 견디는가 (ADR 0015 · 0016)
 *
 * 다섯 번까지 재시도한 뒤 실패해도 서버를 띄운다 — **아카이브가 다음 단계보다 앞이므로**,
 * ClickHouse 가 죽어 있어도 앱이 살아 있으면 원본은 외부 저장소에 남는다. 기동을 막으면 그 경로까지
 * 함께 죽어 유실이 오히려 커진다.
 *
 * ## 왜 이 클래스가 따로 있는가
 *
 * 기동 실패를 허용하는 대가로 **적용 전 적재를 막는 것**이 이 클래스의 존재 이유다.
 * 테이블이 없는 채로 `INSERT` 가 가면 ClickHouse 가 `UNKNOWN_TABLE` 을 **404** 로 돌려주고,
 * 허브 ADR 0006 에서 404 는 영구 오류라 데몬이 **첫 시도에 배치를 버린다.**
 * 적용 전의 실패는 일시 장애여야 하므로, 적재 전에 한 번 더 적용을 시도하고 실패하면
 * [TelemetrySinkUnavailableException] 이 그대로 나가 503 이 된다.
 *
 * ## 요청 스레드를 줄 세우지 않는다
 *
 * 적용 시도는 **한 번에 한 스레드**만 한다. 나머지는 기다리지 않고 바로 503 을 낸다 — ClickHouse 가
 * 죽어 있는 동안 모든 요청이 락 뒤에서 30초 타임아웃을 차례로 기다리면 서블릿 풀이 마르고, 데몬은
 * 503 대신 전송 오류를 본다. 실패한 뒤에는 [startupBackoff] 만큼 재시도를 쉰다. DDL 이 거부된
 * 경우(우리 코드의 결함)도 같은 쿨다운을 탄다 — 배치는 클라이언트 잘못이 아니므로 400 으로
 * 버리지 않고, 매 요청마다 같은 DDL 을 던지지도 않는다.
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

	/**
	 * 이 시각(`System.nanoTime`)까지는 다시 시도하지 않는다. `nanoTime` 은 차이로만 비교해야 하므로
	 * 초기값도 "지금" 이다 — `Long.MIN_VALUE` 같은 감시값은 뺄셈에서 오버플로한다.
	 */
	@Volatile
	private var retryNotBefore: Long = System.nanoTime()

	private val lock = ReentrantLock()

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
				log.error("clickhouse 스키마 적용이 거부됐다 — 기동에서는 재시도하지 않는다", exception)
				return
			}
		}
		log.warn("clickhouse 스키마를 적용하지 못한 채 기동한다. 아카이브는 계속 돌고 적재는 503 이다.")
	}

	/**
	 * 적재 직전 관문. 아직이면 한 스레드만 다시 시도하고, 실패하면 [TelemetrySinkUnavailableException]
	 * 이 나가 503 이 된다.
	 */
	fun ensureApplied() {
		if (applied) return
		if (System.nanoTime() - retryNotBefore < 0) {
			throw TelemetrySinkUnavailableException("clickhouse 스키마 미적용 — 재시도 대기 중")
		}
		if (!lock.tryLock()) {
			throw TelemetrySinkUnavailableException("clickhouse 스키마 미적용 — 다른 요청이 적용 중")
		}
		try {
			if (applied) return
			try {
				apply()
			} catch (exception: TelemetrySinkUnavailableException) {
				retryNotBefore = System.nanoTime() + startupBackoff.toNanos()
				throw exception
			} catch (exception: RuntimeException) {
				retryNotBefore = System.nanoTime() + startupBackoff.toNanos()
				log.error("clickhouse 스키마 적용이 거부됐다 — {} 뒤에 다시 본다", startupBackoff, exception)
				throw TelemetrySinkUnavailableException(
					"clickhouse 스키마 적용이 거부됐다: ${exception.message}",
					exception,
				)
			}
		} finally {
			lock.unlock()
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
