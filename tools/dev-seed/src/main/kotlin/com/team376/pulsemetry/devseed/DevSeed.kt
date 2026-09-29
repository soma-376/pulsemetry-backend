package com.team376.pulsemetry.devseed

import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsSchemaMigrator
import org.flywaydb.core.Flyway
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.LocalDate
import java.nio.file.Path

/** Docker Compose의 초기화와 수동 관리 명령을 한 진입점에서 실행한다(ADR 0031). */
fun main(args: Array<String>) {
    val request = SeedCommand.parse(args, System.getenv())
    val (command, asOf, scenarios) = request
    fun printSummary(data: SeedData) { println(tools.jackson.databind.json.JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(data.summary())) }
    if (command == "plan") {
        scenarios.forEach { printSummary(scenario(it, asOf!!)) }
        return
    }
    if (command == "fixture") {
        require(scenarios == listOf("A")) { "프론트 기준 fixture는 A만 지원합니다." }
        println(json.writerWithDefaultPrettyPrinter().writeValueAsString(frontendFixture(scenario("A", asOf!!))))
        return
    }
    SeedStore.openCompose().use { store ->
        if (command == "init") {
            val source = DriverManagerDataSource(SeedStore.COMPOSE_JDBC_URL, "pulsemetry", "pulsemetry")
            Flyway.configure().dataSource(source)
                .locations("classpath:db/migration")
                .schemas("enrollment").defaultSchema("enrollment")
                .baselineOnMigrate(true).failOnMissingLocations(true)
                .load().migrate()
            TelemetryOpsSchemaMigrator(source).migrate()
            StartupSeed.run(store, scenarios.joinToString(","), asOf.toString()).forEach(::println)
            prepareDashboardStore(store)
            prepareAuthKeys(Path.of("/app/dev-auth"))
            println("개발 Compose 스키마·시드 준비 완료")
            return
        }
        store.prepare()
        scenarios.forEach { name ->
            if (command == "reset") println("$name: ${store.reset(name)}") else {
                val data = scenario(name, asOf!!)
                if (command == "apply") println("$name: ${store.apply(data)}") else {
                    store.verify(data)
                    println("$name: 검증 완료")
                }
                printSummary(data)
            }
        }
    }
}

internal data class SeedCommand(val command: String, val asOf: LocalDate?, val scenarios: List<String>) {
    companion object {
        fun parse(args: Array<String>, environment: Map<String, String>): SeedCommand {
            check(environment["PULSEMETRY_DEV_SEED_MODE"] == "compose") { "개발 Compose에서만 실행할 수 있습니다." }
            val command = args.firstOrNull() ?: "init"
            require(command in listOf("init", "plan", "fixture", "apply", "verify", "reset")) { "명령: init|plan|fixture|apply|verify|reset" }
            val selection: String
            val date: LocalDate?
            when (command) {
                "init" -> {
                    require(args.size <= 1) { "init의 날짜·시나리오는 Compose 환경변수로 지정하세요." }
                    date = environment["PULSEMETRY_LOCAL_SEED_DATE"].takeUnless { it.isNullOrBlank() }
                        ?.let(LocalDate::parse) ?: LocalDate.now(seoul)
                    selection = environment["PULSEMETRY_LOCAL_SEED_SCENARIOS"] ?: "A,B,C"
                }
                "reset" -> {
                    require(args.size in 1..2) { "인자: reset [A,B,C]" }
                    date = null
                    selection = args.getOrNull(1) ?: "A,B,C"
                }
                else -> {
                    require(args.size in 2..3) { "인자: $command YYYY-MM-DD [A,B,C]" }
                    date = LocalDate.parse(args[1])
                    selection = args.getOrNull(2) ?: "A,B,C"
                }
            }
            val scenarios = selection.split(',').map(String::trim).distinct()
            require(scenarios.all { it in listOf("A", "B", "C") }) { "시나리오는 A,B,C 중 선택하세요." }
            return SeedCommand(command, date, scenarios)
        }
    }
}
