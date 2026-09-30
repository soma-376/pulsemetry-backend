// 벤더 좌석 커넥터 (ADR 0048). 포트(인터페이스·값 타입)와 벤더별 구현을 담는다 — 조립은 앱이 한다 (ADR 0011).
// 벤더 HTTP 가 이 모듈의 아웃바운드 기술이다. 영속성(JDBC)과 기술이 달라 모듈을 나눈다 (module-map §4).
// 라이브러리 모듈이므로 Spring Boot 플러그인을 적용하지 않는다 — 실행 가능한 산출물이 아니다. Spring 에 의존하지 않는다.
plugins {
	`java-library`
}

dependencies {
	testImplementation(libs.spring.boot.starter.test)
}
