package com.team376.pulsemetry.retention

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator
import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsSchemaMigrator
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 보존 작업 테스트의 저장소 — JVM 하나에 PostgreSQL·ClickHouse 하나씩. 스키마는 운영에서 다른 앱이 적용하는 것을 여기서 적용한다
 * (`telemetry_ops` 는 enrollment-api, ClickHouse 는 ingest).
 *
 * 분석 행은 필요한 컬럼만 SQL 로 넣는다 — 이 테스트가 보는 것은 tenant·`source_time`·`observation_id`·`row_version` 이다.
 */
object RetentionTestStores {

	private const val HTTP_PORT = 8123

	val postgres: PostgreSQLContainer by lazy { PostgreSQLContainer("postgres:16-alpine").also { it.start() } }

	val clickhouse: GenericContainer<*> by lazy {
		GenericContainer("clickhouse/clickhouse-server:24.8-alpine")
			.withExposedPorts(HTTP_PORT)
			// 이게 없으면 entrypoint 가 default 유저를 루프백 전용으로 잠근다 (infra ADR-0019).
			.withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
			.waitingFor(Wait.forHttp("/ping").forPort(HTTP_PORT).forStatusCode(200))
			.also { it.start() }
	}

	val dataSource: PGSimpleDataSource by lazy {
		PGSimpleDataSource().apply {
			setURL(postgres.jdbcUrl)
			user = postgres.username
			password = postgres.password
		}.also {
			Flyway.configure().dataSource(it).schemas("enrollment").defaultSchema("enrollment")
				.locations("classpath:db/migration").load().migrate()
			TelemetryOpsSchemaMigrator(it).migrate()
		}
	}

	val clickHouseUrl: String get() = "http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}"

	val client: ClickHouseHttpClient by lazy { ClickHouseHttpClient(clickHouseUrl).also { ClickHouseSchemaMigrator(it).apply() } }

	/** 테스트마다 비운다. */
	fun clear() {
		for (table in listOf("telemetry_events", "telemetry_metric_points", "telemetry_ingest_ledger", "telemetry_retention_fence")) {
			client.execute("TRUNCATE TABLE $table")
		}
		dataSource.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE TABLE telemetry_ops.tenant_retention_boundary, telemetry_ops.retention_operations, telemetry_ops.tenant_ingest_summary",
				)
			}
		}
	}

	data class Row(val tenant: UUID, val id: String, val sourceTime: Instant, val rowVersion: Long = 1)

	fun observationId(seed: Int): String = "%064d".format(seed)

	/**
	 * 한 INSERT 로 넣는다 — 월 파티션마다 파트가 하나라 merge 가 revision 을 합치지 않는다. `optimize_on_insert = 0` 은 같은 INSERT 안의
	 * 같은 키를 합치지 않게 한다(revision 둘을 남기려고).
	 */
	fun insert(table: String, rows: List<Row>) {
		val body = rows.joinToString("") { json(it) }
		client.execute(
			"INSERT INTO $table (tenant_id, installation_id, observation_id, row_version, source_time) " +
				"SETTINGS optimize_on_insert = 0 FORMAT JSONEachRow",
			body.toByteArray(StandardCharsets.UTF_8),
		)
	}

	fun json(row: Row): String =
		"""{"tenant_id":"${row.tenant}","installation_id":"i","observation_id":"${row.id}","row_version":${row.rowVersion},"source_time":"${time(row.sourceTime)}"}""" + "\n"

	/** 물리 행 수(FINAL 없이 — revision 까지). [before] 가 있으면 그보다 이른 `source_time` 만. */
	fun rows(table: String, tenant: UUID, before: Instant? = null): Long = client.execute(
		"SELECT count() FROM $table WHERE tenant_id = {t:String}" +
			(if (before != null) " AND source_time < {b:DateTime64(9, 'UTC')}" else "") + " FORMAT TSV",
		params = mapOf("t" to tenant.toString()) + (before?.let { mapOf("b" to time(it)) } ?: emptyMap()),
	).trim().toLong()

	fun ids(table: String, tenant: UUID): List<String> = client.execute(
		"SELECT DISTINCT observation_id FROM $table WHERE tenant_id = {t:String} ORDER BY observation_id FORMAT TSV",
		params = mapOf("t" to tenant.toString()),
	).lines().filter { it.isNotBlank() }

	fun insertLedger(tenant: UUID, receivedAt: Instant) {
		client.execute(
			"INSERT INTO telemetry_ingest_ledger (tenant_id, installation_id, received_time, receipt_id, signal, product, record_count, rejected_count, masking_version) " +
				"SELECT {t:String}, 'i', {r:DateTime64(9, 'UTC')}, {id:String}, 'log', 'claude_code', 1, 0, 'm'",
			params = mapOf("t" to tenant.toString(), "r" to time(receivedAt), "id" to UUID.randomUUID().toString()),
		)
	}

	fun time(value: Instant): String = TIME.format(value)

	private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC)

	/**
	 * 구 경계를 읽고 등록된 채로 본문을 다 보내지 않은 **구 writer** 의 INSERT — 분석 INSERT 와 같은 fence 조건을 건다. 서버에 등록되도록
	 * `max_query_size` 를 줄이고 첫 조각을 그보다 크게 보낸다(ADR 0024 Context 의 측정 2).
	 */
	class StaleWriter(tenant: UUID, queryId: String) : AutoCloseable {
		private val socket = Socket(clickhouse.host, clickhouse.getMappedPort(HTTP_PORT)).apply { soTimeout = 30_000 }
		private val out = socket.getOutputStream()

		init {
			val query = "INSERT INTO telemetry_events (tenant_id, installation_id, observation_id, row_version, source_time) " +
				"SELECT tenant_id, installation_id, observation_id, row_version, source_time " +
				"FROM input('tenant_id String, installation_id String, observation_id String, row_version UInt64, source_time DateTime64(9, \\'UTC\\')') " +
				"WHERE source_time >= ifNull((SELECT maxOrNull(deleted_before) FROM telemetry_retention_fence WHERE tenant_id = {tenant:String}), " +
				"toDateTime64('1900-01-01 00:00:00', 6, 'UTC')) SETTINGS async_insert = 0 FORMAT JSONEachRow"
			val target = "/?" + mapOf("query" to query, "param_tenant" to tenant.toString(), "query_id" to queryId, "max_query_size" to "1024")
				.entries.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
			out.write("POST $target HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
			out.flush()
		}

		fun send(rows: List<Row>) {
			val bytes = rows.joinToString("") { json(it) }.toByteArray(StandardCharsets.UTF_8)
			out.write("${Integer.toHexString(bytes.size)}\r\n".toByteArray(StandardCharsets.US_ASCII))
			out.write(bytes)
			out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
			out.flush()
		}

		/** 본문을 끝내고 응답 상태 코드를 돌려준다. */
		fun finish(): Int {
			out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
			out.flush()
			return socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine().split(' ')[1].toInt()
		}

		override fun close() = socket.close()
	}

	fun registered(queryId: String): Boolean {
		val deadline = System.nanoTime() + 10_000_000_000L
		while (System.nanoTime() < deadline) {
			val found = client.execute(
				"SELECT count() FROM system.processes WHERE query_id = {id:String} FORMAT TSV",
				params = mapOf("id" to queryId),
			).trim()
			if (found == "1") return true
			Thread.sleep(20)
		}
		return false
	}
}
