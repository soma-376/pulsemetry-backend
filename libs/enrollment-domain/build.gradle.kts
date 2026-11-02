// 명령과 조회가 공유하는 순수 상태·전이·계약 값. HTTP·SQL·Spring을 포함하지 않는다 (ADR 0075).
plugins {
    `java-library`
}

dependencies {
    testImplementation("org.assertj:assertj-core")
}
