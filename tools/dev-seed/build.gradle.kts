plugins {
    application
}

dependencies {
    implementation(project(":libs:enrollment-persistence"))
    implementation(project(":libs:telemetry-ops-persistence"))
    implementation(project(":libs:telemetry-persistence"))
    implementation(libs.flyway.core)
    implementation(libs.jackson.module.kotlin)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}

application {
    mainClass.set("com.team376.pulsemetry.devseed.DevSeedKt")
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.test {
    // 180일 시드 두 벌을 비교하는 재현성 검사도 전체 필드를 유지한다.
    maxHeapSize = "2g"
}
