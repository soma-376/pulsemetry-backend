// 데스크탑 CLI 전용 인증(enrollment) 서버. 실행 가능한 배포 산출물이다.
plugins {
	alias(libs.plugins.spring.boot)
}

dependencies {
	implementation(project(":libs:enrollment-persistence"))
	implementation(libs.spring.boot.starter.webmvc)
	implementation(libs.jackson.module.kotlin)

	testImplementation(libs.spring.boot.starter.webmvc.test)
}
