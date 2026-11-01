package com.team376.pulsemetry.enrollment.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.library.dependencies.SliceAssignment
import com.tngtech.archunit.library.dependencies.SliceIdentifier
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class EnrollmentArchitectureTest {
    private val root = "com.team376.pulsemetry.enrollment"
    private val features = listOf("team", "member", "invitation", "installation", "policy", "vendor", "seat")
    private val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(root)

    @Test fun `기능끼리 내부 구현을 참조하지 않는다`() {
        for (feature in features) {
            assertTrue(classes.any { it.packageName.startsWith("$root.$feature.") }, "검사할 기능이 없다: $feature")
            noClasses().that().resideInAPackage("$root.$feature..")
                .should().dependOnClassesThat().resideInAnyPackage(*features.filter { it != feature }
                    .flatMap { listOf("$root.$it.api..", "$root.$it.application..", "$root.$it.domain..") }.toTypedArray()).check(classes)
        }
    }

    @Test fun `실행 책임은 HTTP와 SQL 구현을 참조하지 않는다`() {
        noClasses().that().resideInAPackage("$root..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "jakarta.servlet..", "org.springframework.web..", "org.springframework.http..",
                "org.springframework.jdbc..", "java.sql..", "jakarta.persistence..").check(classes)
    }

    @Test fun `기능 사이 공개 참조에도 순환이 없다`() {
        slices().assignedFrom(object : SliceAssignment {
            override fun getDescription() = "Enrollment 기능"
            override fun getIdentifierOf(javaClass: JavaClass): SliceIdentifier {
                val feature = javaClass.packageName.removePrefix("$root.").substringBefore('.')
                return if (feature in features) SliceIdentifier.of(feature) else SliceIdentifier.ignore()
            }
        }).should().beFreeOfCycles().check(classes)
    }

    @Test fun `도메인 규칙은 전송과 영속성과 프레임워크를 모른다`() {
        noClasses().that().resideInAPackage("$root..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..application..", "..persistence..", "org.springframework..",
                "jakarta..", "tools.jackson..", "com.fasterxml.jackson..").check(classes)
    }

    @Test fun `공통 명령 실행 관문은 기능과 전송 계층을 모른다`() {
        noClasses().that().haveSimpleName("ManagementExecutor").should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.servlet..", "org.springframework.web..", *features.map { "$root.$it.." }.toTypedArray()).check(classes)
    }
}
