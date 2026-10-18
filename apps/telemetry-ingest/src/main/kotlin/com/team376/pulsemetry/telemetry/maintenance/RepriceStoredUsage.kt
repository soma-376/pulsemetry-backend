package com.team376.pulsemetry.telemetry.maintenance

import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CodeburnReferencePrices
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.system.exitProcess

/**
 * 내부 일회성 명령. 보존한 JSONEachRow 체크포인트와 SHA-256을 명시해 가격만 재평가한다.
 * 기본은 검산이다. --apply일 때만 기존 typed sink를 거쳐 새 행 버전을 쓴다.
 * 실시간 수집·마스킹·원본 아카이브·수집 ledger·heartbeat는 호출하지 않는다.
 */
object RepriceStoredUsage {
	@JvmStatic
	fun main(args: Array<String>) {
		try {
			run(args)
		} catch (failure: Exception) {
			// DB 오류·입력 예외는 행 원문이나 자격을 포함할 수 있어 콘솔에 노출하지 않는다.
			System.err.println("재평가 실패: ${failure.javaClass.simpleName}. 입력·접속 정보·원문은 출력하지 않았습니다.")
			exitProcess(1)
		}
	}

	private fun run(args: Array<String>) {
		require(args.size in 3..4 && (args.size == 3 || args[3] == "--apply")) { "input.jsonl sha256 tenant-id [--apply]" }
		val input = Path.of(args[0])
		val tenant = UUID.fromString(args[2]).toString()
		val hash = MessageDigest.getInstance("SHA-256")
		Files.newInputStream(input).use { source ->
			val buffer = ByteArray(65536)
			while (true) { val count = source.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
		}
		require(hash.digest().joinToString("") { "%02x".format(it) } == args[1]) { "입력 체크포인트 해시 불일치" }
		val mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
		// 전체 계획 검산이 성공하기 전에는 DB 쓰기 객체도 만들지 않는다.
		var count = 0
		var total = BigDecimal.ZERO
		val ids = HashSet<String>()
		Files.newBufferedReader(input).useLines { lines -> lines.forEach { line ->
			val row = StoredUsageRepricing.decode(mapper.readTree(line))
			require(row.event.observation.envelope.tenantId == tenant)
			require(ids.add(row.event.observation.envelope.observationId)) { "체크포인트에 중복 관측이 있다" }
			val result = StoredUsageRepricing.reprice(row)
			total += result.event.observation.costEstimatedUsd!!
			count++
		} }
		require(count > 0)
		var written = 0
		if (args.size == 4) {
			val dataSource = DriverManagerDataSource(env("PULSEMETRY_REPRICE_JDBC_URL"), env("PULSEMETRY_REPRICE_JDBC_USER"), env("PULSEMETRY_REPRICE_JDBC_PASSWORD"))
			val boundaries = TenantRetentionBoundaryStore(dataSource)
			val client = ClickHouseHttpClient(env("PULSEMETRY_REPRICE_CLICKHOUSE_URL"))
			val sink = TelemetryEventsSink(client)
			Files.newBufferedReader(input).useLines { lines -> lines.chunked(250).forEach { chunk ->
				val source = chunk.map { StoredUsageRepricing.decode(mapper.readTree(it)) }
				val target = source.map(StoredUsageRepricing::reprice)
				// 운영 중 같은 관측이 바뀌었다면 오래된 백업으로 덮어쓰지 않는다.
				val latest = client.execute(
					"SELECT observation_id,row_version,analysis_hash FROM telemetry_events FINAL WHERE tenant_id={tenant:String} AND observation_id IN ({ids:Array(String)}) FORMAT JSONEachRow",
					params = mapOf("tenant" to tenant, "ids" to source.joinToString(",", "[", "]") { "'${it.event.observation.envelope.observationId}'" }),
				).lineSequence().filter(String::isNotBlank).map(mapper::readTree).associateBy { it["observation_id"].asString() }
				val pending = source.zip(target).filter { (old, next) ->
					val current = requireNotNull(latest[old.event.observation.envelope.observationId])
					val sameTarget = current["row_version"].asString().toULong() == next.version.rowVersion && current["analysis_hash"].asString() == next.event.observation.envelope.analysisHash
					require(sameTarget || (current["row_version"].asString().toULong() == old.version.rowVersion && current["analysis_hash"].asString() == old.event.observation.envelope.analysisHash)) { "계획 이후 관측이 변경됐다" }
					!sameTarget
				}.map { it.second }
				pending.groupBy { it.version }.forEach { (version, batch) ->
					val current = boundaries.read(UUID.fromString(tenant))
					val boundary = AnalysisWriteBoundary(tenant, current.deletedBefore, current.policyEpoch)
					require(batch.all { boundary.admits(it.event.observation.envelope.sourceTime) }) { "삭제 경계 밖의 행은 복원하지 않는다" }
					written += sink.insert(batch.map { it.event }, version, boundary)
				}
			} }
		}
		println("{\"rows\":$count,\"written\":$written,\"pricingVersion\":\"${CodeburnReferencePrices.VERSION}\",\"costUsd\":\"${total.toPlainString()}\"}")
	}

	private fun env(name: String): String = requireNotNull(System.getenv(name)) { "필수 접속 설정 누락" }
}
