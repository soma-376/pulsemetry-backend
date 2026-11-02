package com.team376.pulsemetry.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Files
import java.io.File
import kotlin.test.assertTrue

/** 각 라이브러리 테스트에서 그 모듈의 main 바이트코드만 읽는다. fixture·테스트 클래스는 대상이 아니다. */
class LibraryArchitectureTest {
    private val classes = ClassFileImporter().importPaths(System.getProperty("pulsemetry.architecture.main")
        .split(File.pathSeparator).map(Path::of).filter(Files::isDirectory))

    @Test fun `실제 main 클래스가 검사 대상이다`() {
        assertTrue(classes.any(), "검사 대상이 비어 있다")
    }

    @Test fun `라이브러리는 실행 앱 구현을 참조하지 않는다`() {
        val apps = System.getProperty("pulsemetry.architecture.apps").split(",").toTypedArray()
        assertTrue(apps.isNotEmpty())
        noClasses().should().dependOnClassesThat().resideInAnyPackage(*apps).check(classes)
    }

    @Test fun `라이브러리는 스캔으로 자동 등록되거나 빈을 조립하지 않는다`() {
        noClasses().should().beAnnotatedWith("org.springframework.stereotype.Component").check(classes)
        noClasses().should().beMetaAnnotatedWith("org.springframework.stereotype.Component").check(classes)
        noMethods().should().beAnnotatedWith("org.springframework.context.annotation.Bean").check(classes)
    }

    @Test fun `공유 모델과 도메인은 전송 영속성 및 프레임워크를 모른다`() {
        val namespace = when (System.getProperty("pulsemetry.architecture.module")) {
            ":libs:telemetry-model" -> "com.team376.pulsemetry.telemetry.model.."
            ":libs:enrollment-domain" -> "com.team376.pulsemetry.domain.enrollment.."
            else -> return
        }
        noClasses().should().dependOnClassesThat().resideOutsideOfPackages(namespace, "java..", "kotlin..", "org.jetbrains.annotations..").check(classes)
    }
}
