// enrollment 스키마의 영속성 계층. 애플리케이션 모듈들이 공유한다. (ADR 0002)
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다.

dependencies {
	// JPA 엔티티·리포지토리와 Flyway 마이그레이션은 P1·P2 에서 추가한다.
}
