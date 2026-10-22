// 운영 계측의 포트와 Micrometer 구현. Spring 조립은 앱에서만 한다(ADR 0068).
plugins { `java-library` }
dependencies {
    api(libs.micrometer.core)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.micrometer.registry.prometheus)
}
