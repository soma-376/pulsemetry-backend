rootProject.name = "pulsemetry"

// 배포 단위가 되는 서버 (ADR 0002)
include(":apps:enrollment-api")

// 여러 서버가 공유하는 영속성·도메인 코드 (ADR 0002)
include(":libs:enrollment-persistence")
