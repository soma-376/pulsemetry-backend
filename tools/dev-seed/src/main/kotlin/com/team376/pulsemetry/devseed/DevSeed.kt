package com.team376.pulsemetry.devseed

import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsSchemaMigrator
import org.flywaydb.core.Flyway
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.LocalDate
import java.nio.file.Path

/** Docker Compose의 초기화와 수동 관리 명령을 한 진입점에서 실행한다(ADR 0031). */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "configure-oidc") {
        runTenantOidc(args, System.getenv())
        return
    }
    if (args.firstOrNull() in listOf("switch-oidc", "snapshot-oidc", "restore-oidc")) {
        runIdentitySwitch(args, System.getenv())
        return
    }
    val request = SeedCommand.parse(args, System.getenv())
    val (command, asOf, scenarios, endpoint) = request
    fun printSummary(data: SeedData) { println(tools.jackson.databind.json.JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(data.summary())) }
    if (command == "plan") {
        scenarios.forEach { printSummary(scenario(it, asOf!!, endpoint)) }
        return
    }
    if (command == "fixture") {
        require(scenarios == listOf("A")) { "프론트 기준 fixture는 A만 지원합니다." }
        println(json.writerWithDefaultPrettyPrinter().writeValueAsString(frontendFixture(scenario("A", asOf!!, endpoint))))
        return
    }
    // JSON 누락·변조는 DB 변경 전에 검사한다. C만 선택하면 OIDC 자료는 필요 없다.
    val oidcSeeds = if (command == "init") loadCompanyOidcSeeds(Path.of("/app/oidc-seed"), scenarios) else emptyList()
    SeedStore.openCompose().use { store ->
        if (command == "init") {
            val source = DriverManagerDataSource(SeedStore.COMPOSE_JDBC_URL, "pulsemetry", "pulsemetry")
            Flyway.configure().dataSource(source)
                .locations("classpath:db/migration")
                .schemas("enrollment").defaultSchema("enrollment")
                .baselineOnMigrate(true).failOnMissingLocations(true)
                .load().migrate()
            TelemetryOpsSchemaMigrator(source).migrate()
            println("시드 manifest 수신 주소: $endpoint")
            StartupSeed.run(store, scenarios.joinToString(","), asOf.toString(), endpoint).forEach(::println)
            println("개발 OIDC 연결: ${seedCompanyOidc(store, oidcSeeds)}개 회사 최초 적용")
            prepareDashboardStore(store)
            prepareAuthKeys(Path.of("/app/dev-auth"))
            println("개발 Compose 스키마·시드 준비 완료")
            return
        }
        store.prepare()
        scenarios.forEach { name ->
            if (command == "reset") println("$name: ${store.reset(name)}") else {
                val data = scenario(name, asOf!!, endpoint)
                if (command == "apply") println("$name: ${store.apply(data)}") else {
                    store.verify(data)
                    println("$name: 검증 완료")
                }
                printSummary(data)
            }
        }
    }
}

internal data class SeedCommand(val command: String, val asOf: LocalDate?, val scenarios: List<String>, val otlpEndpoint: String = DEFAULT_OTLP_ENDPOINT) {
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
                    require(args.size in 1..2) { "인자: reset [$SCENARIO_CHOICES 중 고른 목록]" }
                    date = null
                    selection = args.getOrNull(1) ?: "A,B,C"
                }
                else -> {
                    require(args.size in 2..3) { "인자: $command YYYY-MM-DD [$SCENARIO_CHOICES 중 고른 목록]" }
                    date = LocalDate.parse(args[1])
                    selection = args.getOrNull(2) ?: "A,B,C"
                }
            }
            val scenarios = selection.split(',').map(String::trim).distinct()
            require(scenarios.all { it in SCENARIOS }) { "시나리오는 $SCENARIO_CHOICES 중 선택하세요." }
            return SeedCommand(command, date, scenarios, otlpEndpoint(environment))
        }
    }
}
