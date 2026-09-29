package com.team376.pulsemetry.devseed

import java.time.LocalDate

/** Compose 초기화는 ready 기록을 존중한다. 내용 비교와 초기화는 수동 명령의 책임이다. */
object StartupSeed {
    /** Compose 초기화는 완료 기록을 존중하며 기존 데이터는 보존한다. */
    fun run(store: SeedStore, selection: String, date: String): List<String> {
        val selected = selection.split(',').map(String::trim).distinct()
        require(selected.isNotEmpty() && selected.all { it in listOf("A", "B", "C") }) { "시나리오는 A,B,C 중 선택하세요." }
        val asOf = if (date.isBlank()) LocalDate.now(seoul) else LocalDate.parse(date)
        store.prepare(initializeClickHouse = true)
        return selected.map { name -> "$name: ${store.ensureOnStartup(name, asOf)}" }
    }
}

/** 다른 조직을 시드로 오인하거나 실패한 부분 적재를 완료로 취급하지 않는다. */
internal fun shouldSeedOnStartup(state: String?, slug: String?, scenario: String): Boolean {
    if (state == null) {
        check(slug == null) { "$scenario: 실행 기록 없는 조직입니다. 자동으로 덮어쓰지 않습니다." }
        return true
    }
    check(state == "ready") { "$scenario: 미완료 적재($state)입니다. reset으로 복구 후 다시 실행하세요." }
    check(slug == "pulsemetry-seed-${scenario.lowercase()}") { "$scenario: 시드 조직이 없거나 식별자가 변경됐습니다." }
    return false
}
