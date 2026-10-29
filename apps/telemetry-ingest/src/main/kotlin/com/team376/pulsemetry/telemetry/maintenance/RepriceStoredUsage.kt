package com.team376.pulsemetry.telemetry.maintenance

import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundary
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore
import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import com.team376.pulsemetry.telemetry.maintenance.StoredUsageRepricing.Disposition
import com.team376.pulsemetry.telemetry.maintenance.StoredUsageRepricing.Planned
import com.team376.pulsemetry.telemetry.maintenance.StoredUsageRepricing.stopUnless
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.TreeMap
import java.util.TreeSet
import java.util.UUID
import kotlin.system.exitProcess

/**
 * 내부 일회성 명령. 보존한 JSONEachRow 체크포인트와 SHA-256을 명시해 저장 행을 분류하고 가격만 재평가한다.
 * 기본은 검산이다. --apply일 때만 기존 typed sink를 거쳐 재평가 행의 새 행 버전을 쓴다.
 * 재정규화가 필요한 행과 복구할 수 없는 행은 쓰지 않고 사유와 건수만 보고한다.
 * 실시간 수집·마스킹·원본 아카이브·수집 ledger·heartbeat는 호출하지 않는다.
 */
object RepriceStoredUsage {
	private const val USAGE = "input.jsonl sha256 tenant-id [--apply] [--archive-refs=<path>]"
	private val mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

	@JvmStatic
	fun main(args: Array<String>) {
		try {
			println(run(args))
		} catch (failure: StoredUsageRepricing.Stop) {
			// 중단 사유는 고정 문장이라 원문을 담지 않는다. 같은 체크포인트로 다시 실행하기 전에 사유를 보고 고친다.
			System.err.println("재평가 중단: ${failure.message}")
			exitProcess(1)
		} catch (failure: Exception) {
			// DB 오류·입력 예외는 행 원문이나 자격을 포함할 수 있어 콘솔에 노출하지 않는다.
			System.err.println("재평가 실패: ${failure.javaClass.simpleName}. 입력·접속 정보·원문은 출력하지 않았습니다.")
			exitProcess(1)
		}
	}

	internal data class Options(val input: Path, val sha256: String, val tenant: String, val apply: Boolean, val archiveRefs: Path?)

	internal fun parse(args: Array<String>): Options {
		stopUnless(args.size >= 3) { USAGE }
		val flags = args.drop(3)
		val apply = flags.count { it == "--apply" }
		val refs = flags.filter { it.startsWith("--archive-refs=") }
		stopUnless(apply <= 1 && refs.size <= 1 && apply + refs.size == flags.size) { USAGE }
		return Options(Path.of(args[0]), args[1], UUID.fromString(args[2]).toString(), apply == 1, refs.singleOrNull()?.substringAfter('=')?.let(Path::of))
	}

	private fun run(args: Array<String>): String {
		val options = parse(args)
		// 전체 계획 검산이 성공하기 전에는 DB 쓰기 객체도 만들지 않는다.
		val plan = plan(options.input, options.sha256, options.tenant)
		options.archiveRefs?.let { path ->
			Files.write(path, plan.archiveRefs, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
		}
		val applied = if (options.apply) {
			val dataSource = DriverManagerDataSource(env("PULSEMETRY_REPRICE_JDBC_URL"), env("PULSEMETRY_REPRICE_JDBC_USER"), env("PULSEMETRY_REPRICE_JDBC_PASSWORD"))
			val client = ClickHouseHttpClient(env("PULSEMETRY_REPRICE_CLICKHOUSE_URL"))
			Apply(client, TenantRetentionBoundaryStore(dataSource)::read, TelemetryEventsSink(client)::insert).run(options.input, options.tenant)
		} else null
		return plan.report(applied)
	}

	/** 파일 전체를 검산하고 분류한다. DB에 접속하지 않는다. 입력이 계약과 다르면 한 행도 쓰기 전에 중단한다. */
	internal fun plan(input: Path, sha256: String, tenant: String): Plan {
		stopUnless(digest(input) == sha256) { "입력 체크포인트 해시 불일치" }
		val plan = Plan()
		val ids = HashSet<String>()
		Files.newBufferedReader(input).useLines { lines -> lines.forEach { line ->
			val row = StoredUsageRepricing.decode(mapper.readTree(line))
			stopUnless(row.event.observation.envelope.tenantId == tenant) { "다른 tenant 의 행이 있다" }
			stopUnless(ids.add(row.event.observation.envelope.observationId)) { "체크포인트에 중복 관측이 있다" }
			plan.add(StoredUsageRepricing.classify(row))
		} }
		stopUnless(plan.rows > 0) { "입력에 행이 없다" }
		return plan
	}

	/** 분류 결과의 집계. 원문 대신 건수·사유·모델 이름·원본 참조만 남긴다. */
	internal class Plan {
		var rows: Int = 0
			private set
		val counts: MutableMap<Disposition, MutableMap<String, Int>> = Disposition.entries.associateWithTo(LinkedHashMap()) { TreeMap<String, Int>() }
		val unpricedModels: MutableMap<String, Int> = TreeMap()
		/** 재정규화 후보의 원본 참조. 원본 존재 확인은 이 목록의 메타데이터 조회로 한다. */
		val archiveRefs: MutableSet<String> = TreeSet()
		var costUsd: BigDecimal = BigDecimal.ZERO
			private set
		private var receivedFrom: EpochNanos? = null
		private var receivedTo: EpochNanos? = null

		fun add(planned: Planned) {
			rows++
			counts.getValue(planned.disposition).merge(planned.reason.wire, 1, Int::plus)
			val envelope = planned.source.event.observation.envelope
			planned.target?.event?.observation?.let { target ->
				val cost = target.costEstimatedUsd
				if (cost == null) unpricedModels.merge(envelope.model ?: "(none)", 1, Int::plus) else costUsd += cost
			}
			if (planned.disposition == Disposition.RENORMALIZE) {
				archiveRefs += envelope.archiveRef!!
				receivedFrom = listOfNotNull(receivedFrom, envelope.receivedTime).min()
				receivedTo = listOfNotNull(receivedTo, envelope.receivedTime).max()
			}
		}

		fun report(applied: Apply.Result?): String {
			val root = mapper.createObjectNode()
			root.put("rows", rows)
			root.put("normalizerRev", RowVersioning.NORMALIZER_REV.toLong())
			val dispositions = root.putObject("dispositions")
			counts.forEach { (disposition, reasons) ->
				val node = dispositions.putObject(disposition.wire)
				node.put("rows", reasons.values.sum())
				val reasonNode = node.putObject("reasons")
				reasons.forEach { (reason, count) -> reasonNode.put(reason, count) }
			}
			root.put("costUsd", costUsd.toPlainString())
			val models = root.putObject("unpricedModels")
			unpricedModels.forEach { (model, count) -> models.put(model, count) }
			val renormalize = root.putObject("renormalizeArchive")
			renormalize.put("refs", archiveRefs.size)
			renormalize.put("receivedFrom", receivedFrom?.toInstant()?.toString())
			renormalize.put("receivedTo", receivedTo?.toInstant()?.toString())
			applied?.let { result ->
				val node = root.putObject("applied")
				node.put("written", result.written)
				node.put("alreadyApplied", result.alreadyApplied)
				node.put("superseded", result.superseded)
				node.put("removed", result.removed)
				node.put("outsideBoundary", result.outsideBoundary)
			}
			return mapper.writeValueAsString(root)
		}
	}

	/**
	 * 검산을 통과한 입력에서 재평가 행만 적재한다. 배치 전체의 트랜잭션은 아니다 — 중간에 실패하면 같은 입력으로 다시 실행한다.
	 * 이미 목표 버전·해시가 같은 행은 건너뛰므로 재실행은 이어서 진행하고, 끝난 입력을 다시 돌리면 쓰기는 0건이다.
	 * 테스트는 [insert] 와 [chunkSize] 를 바꿔 중단과 재개를 재현한다.
	 */
	internal class Apply(
		private val client: ClickHouseHttpClient,
		private val boundaries: (UUID) -> TenantRetentionBoundary,
		private val insert: (List<EnrichedEvent>, RowVersioning, AnalysisWriteBoundary) -> Int,
		private val chunkSize: Int = 250,
	) {
		data class Result(val written: Int, val alreadyApplied: Int, val superseded: Int, val removed: Int, val outsideBoundary: Int)

		private data class Stored(val rowVersion: ULong, val analysisHash: String) {
			fun matches(row: StoredUsageRepricing.Row): Boolean =
				rowVersion == row.version.rowVersion && analysisHash == row.event.observation.envelope.analysisHash
		}

		fun run(input: Path, tenant: String): Result {
			var written = 0
			var alreadyApplied = 0
			var superseded = 0
			var removed = 0
			var outsideBoundary = 0
			Files.newBufferedReader(input).useLines { lines ->
				lines.map { StoredUsageRepricing.classify(StoredUsageRepricing.decode(mapper.readTree(it))) }
					.filter { it.disposition == Disposition.REPRICE }
					.chunked(chunkSize)
					.forEach { chunk ->
						val latest = stored(tenant, chunk.map { it.source.event.observation.envelope.observationId })
						val pending = chunk.filter { planned ->
							val current = latest[planned.source.event.observation.envelope.observationId]
							val target = planned.target!!
							when {
								// 보존 삭제 등으로 사라진 행은 되살리지 않는다.
								current == null -> { removed++; false }
								current.matches(target) -> { alreadyApplied++; false }
								// 내보낸 뒤 더 높은 버전이 이미 적재됐다. 이 쓰기는 교체에서 지므로 보내지 않는다.
								current.rowVersion > target.version.rowVersion -> { superseded++; false }
								else -> {
									// 운영 중 같은 관측이 바뀌었다면 오래된 백업으로 덮어쓰지 않는다.
									stopUnless(current.matches(planned.source)) { "계획 이후 관측이 변경됐다" }
									true
								}
							}
						}.map { it.target!! }
						val current = boundaries(UUID.fromString(tenant))
						val boundary = AnalysisWriteBoundary(tenant, current.deletedBefore, current.policyEpoch)
						val (admitted, outside) = pending.partition { boundary.admits(it.event.observation.envelope.sourceTime) }
						outsideBoundary += outside.size
						admitted.groupBy { it.version }.forEach { (version, batch) -> written += insert(batch.map { it.event }, version, boundary) }
					}
			}
			return Result(written, alreadyApplied, superseded, removed, outsideBoundary)
		}

		private fun stored(tenant: String, ids: List<String>): Map<String, Stored> = client.execute(
			"SELECT observation_id,row_version,analysis_hash FROM telemetry_events FINAL WHERE tenant_id={tenant:String} AND observation_id IN ({ids:Array(String)}) FORMAT JSONEachRow",
			params = mapOf("tenant" to tenant, "ids" to ids.joinToString(",", "[", "]") { "'$it'" }),
			operation = "select",
			table = "telemetry_events",
		).lineSequence().filter(String::isNotBlank).map(mapper::readTree).associate {
			it["observation_id"].asString() to Stored(it["row_version"].asString().toULong(), it["analysis_hash"].asString())
		}
	}

	private fun digest(input: Path): String {
		val hash = MessageDigest.getInstance("SHA-256")
		Files.newInputStream(input).use { source ->
			val buffer = ByteArray(65536)
			while (true) { val count = source.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
		}
		return hash.digest().joinToString("") { "%02x".format(it) }
	}

	private fun env(name: String): String = System.getenv(name) ?: throw StoredUsageRepricing.Stop("필수 접속 설정 누락: $name")
}
