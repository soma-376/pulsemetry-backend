plugins { `java-library` }
dependencies {
    api("org.springframework:spring-jdbc")
    implementation(libs.jackson.module.kotlin)
}
