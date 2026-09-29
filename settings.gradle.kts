rootProject.name = "pulsemetry"

// Docker 전용 개발 시드 도구 (ADR 0031).
include(":tools:dev-seed")

// 배포 단위가 되는 서버 (ADR 0002)
include(":apps:enrollment-api")

// 다섯 단계 모듈을 조립해 OTLP 를 받는 배포 단위 (허브 ADR 0005 · ADR 0011 · 0016)
include(":apps:telemetry-ingest")

// 분석 조회 API. 원천 스키마를 읽기만 하고 자기 캐시에만 쓴다 (ADR 0022)
include(":apps:dashboard-api")

// 조직별 보존 삭제 작업. 서버가 아닌 일회성 실행 단위다 — 분석 원본 DELETE 권한은 여기에만 있다 (ADR 0024)
include(":apps:retention-worker")

// 여러 서버가 공유하는 영속성·도메인 코드 (ADR 0002)
include(":libs:enrollment-persistence")

// 컨텍스트에 속하지 않는 횡단 모듈. 인증 계층의 자리다 (ADR 0007·0008 규칙 2)
include(":libs:security")

// 파이프라인 단계 모듈. 테이블을 소유하지 않는다 (ADR 0010)
include(":libs:telemetry-collector")
include(":libs:telemetry-adapter")
include(":libs:telemetry-enricher")

// telemetry 도메인의 테이블 쓰기 소유 모듈. 역할 모듈이라 어순이 다르다 (ADR 0008 규칙 1 · 0010)
include(":libs:telemetry-persistence")

// RDS telemetry_ops 스키마(수집 운영 기록)의 쓰기 소유 모듈. ClickHouse 와 아웃바운드 기술이 달라 나눈다 (ADR 0021)
include(":libs:telemetry-ops-persistence")
