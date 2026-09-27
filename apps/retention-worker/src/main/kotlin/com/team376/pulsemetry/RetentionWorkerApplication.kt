package com.team376.pulsemetry

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

/**
 * 조직별 보존 삭제 작업(ADR 0024 §5). 서버가 아니다 — 명령 하나(`--tenant --retention-months --as-of`)를 실행하고 종료 코드로 끝난다.
 * 종료 코드의 뜻은 `RetentionCommandRunner` 가 담는다.
 *
 * 다른 앱처럼 루트 패키지에 둔다(모듈 지도 3절). 앱끼리는 의존하지 않으므로 메인 클래스들이 한 클래스패스에 오르지 않는다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class RetentionWorkerApplication

fun main(args: Array<String>) {
	exitProcess(SpringApplication.exit(runApplication<RetentionWorkerApplication>(*args)))
}
