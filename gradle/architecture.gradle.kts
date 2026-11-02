import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

// 테스트·fixture 간선은 main 배포 의존과 별개다. build/check에서 실제 해결된 runtime도 함께 검사한다 (ADR 0075).
val verifyModuleBoundaries = tasks.register("verifyModuleBoundaries") {
    group = "verification"
    description = "모듈 방향·순환·파이프라인 격리와 라이브러리 자동설정 유입을 검사한다."
    doLast {
        val modules = rootProject.subprojects.filter { it.buildFile.exists() }
        val edges = modules.associate { module ->
            module.path to listOf("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly")
                .flatMap { name -> module.configurations.findByName(name)?.allDependencies?.withType(ProjectDependency::class.java)?.map { it.path }.orEmpty() }.toSet()
        }
        val stages = setOf(":libs:telemetry-collector", ":libs:telemetry-adapter", ":libs:telemetry-enricher", ":libs:telemetry-persistence")
        for ((source, targets) in edges) for (target in targets) {
            check(!target.startsWith(":apps:")) { "앱을 의존 대상으로 사용할 수 없다: $source → $target" }
            check(source !in stages || target !in stages) { "단계 실행 간선을 앱 조립으로 옮겨야 한다: $source → $target" }
        }
        fun visit(path: String, stack: List<String>) {
            check(path !in stack) { "모듈 순환: ${(stack + path).joinToString(" → ")}" }
            edges[path].orEmpty().forEach { visit(it, stack + path) }
        }
        edges.keys.forEach { visit(it, emptyList()) }

        val isolated = setOf(":libs:telemetry-model", ":libs:enrollment-domain")
        for (module in modules.filter { it.path.startsWith(":libs:") }) {
            val components = module.configurations.getByName("runtimeClasspath").incoming.resolutionResult.allComponents
            val coordinates = components.mapNotNull { it.moduleVersion }
            val compileCoordinates = module.configurations.getByName("compileClasspath").incoming.resolutionResult.allComponents.mapNotNull { it.moduleVersion }
            val starters = (coordinates + compileCoordinates).distinct().filter { it.group == "org.springframework.boot" && it.name.startsWith("spring-boot-starter") }
            check(starters.isEmpty()) { "라이브러리 main에 Boot starter가 유입됐다: ${module.path}: $starters" }
            if (module.path in isolated) {
                check(edges.getValue(module.path).isEmpty()) { "순수 공유 모듈의 project 의존: ${module.path}" }
                val frameworks = coordinates.filter { it.group !in setOf("com.team376", "org.jetbrains.kotlin", "org.jetbrains") }
                check(frameworks.isEmpty()) { "순수 공유 모듈의 프레임워크 의존: ${module.path}: $frameworks" }
            }
            if (module.path == ":libs:telemetry-persistence" || module.path == ":libs:telemetry-model") {
                val implementations = components.mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }
                    .filter { it in setOf(":libs:telemetry-enricher", ":libs:telemetry-adapter", ":libs:enrollment-persistence") }
                val persistence = coordinates.filter { it.group.startsWith("org.hibernate") || it.group.startsWith("org.flywaydb") ||
                    it.group == "jakarta.persistence" || it.name.contains("data-jpa") || it.name.contains("hibernate") }
                check(implementations.isEmpty() && persistence.isEmpty()) { "관측 값/적재에 실행·JPA·Flyway가 유입됐다: ${module.path}: $implementations $persistence" }
            }
        }
        logger.lifecycle("모듈 경계 검증 완료: ${modules.size}개 모듈, main 의존·runtimeClasspath")
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyModuleBoundaries) }
}
