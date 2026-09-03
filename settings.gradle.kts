rootProject.name = "pulsemetry"

// 배포 단위가 되는 서버 (ADR 0002)
include(":apps:enrollment-api")

// 여러 서버가 공유하는 영속성·도메인 코드 (ADR 0002)
include(":libs:enrollment-persistence")

// 컨텍스트에 속하지 않는 횡단 모듈. 인증 계층의 자리다 (ADR 0007·0008 규칙 2)
include(":libs:security")

// 파이프라인 단계 모듈. 테이블을 소유하지 않는다 (ADR 0010)
include(":libs:telemetry-collector")
