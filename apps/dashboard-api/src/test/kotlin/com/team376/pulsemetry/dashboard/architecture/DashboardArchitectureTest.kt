package com.team376.pulsemetry.dashboard.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** 화면 내부로의 역참조와 화면 간 구현 재사용을 막는다. 공유 값은 analytics에 둔다. */
class DashboardArchitectureTest {
    private val root = "com.team376.pulsemetry.dashboard"
    private val features = listOf("overview", "team", "member", "settings", "alert", "ingest", "operation", "catalog")
    private val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(root)

    @Test
    fun `기능은 다른 기능의 내부 구현을 참조하지 않는다`() {
        for (feature in features) {
            assertTrue(classes.any { it.packageName == "$root.$feature" }, "검사할 기능이 없다: $feature")
            noClasses().that().resideInAPackage("$root.$feature..")
                .should().dependOnClassesThat().resideInAnyPackage(*features.filter { it != feature }.map { "$root.$it.." }.toTypedArray())
                .check(classes)
        }
    }

    @Test
    fun `공유 집계는 화면 응답과 서비스에 의존하지 않는다`() {
        noClasses().that().resideInAPackage("$root.analytics..")
            .should().dependOnClassesThat().resideInAnyPackage(*features.map { "$root.$it.." }.toTypedArray()).check(classes)
    }

    @Test
    fun `기능 사이 순환은 허용하지 않는다`() {
        slices().matching("$root.(*)..").should().beFreeOfCycles().check(classes)
    }

    @Test
    fun `기능 HTTP 진입점은 기능 패키지에 둔다`() {
        assertTrue(classes.filter { it.packageName == "$root.api" }.all { it.simpleName in setOf("HealthController", "DashboardExceptionHandler") })
    }
}
