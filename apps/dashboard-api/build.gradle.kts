plugins { alias(libs.plugins.spring.boot) }
dependencies {
    implementation(project(":libs:telemetry-persistence"))
    implementation("org.flywaydb:flyway-core")
    implementation(project(":libs:dashboard-persistence"))
    implementation(project(":libs:security"))
    implementation(project(":libs:enrollment-persistence"))
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jackson.module.kotlin)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(testFixtures(project(":libs:enrollment-persistence")))
}
tasks.jar { enabled = false }
