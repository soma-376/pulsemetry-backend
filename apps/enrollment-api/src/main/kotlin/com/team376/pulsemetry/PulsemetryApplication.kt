package com.team376.pulsemetry

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PulsemetryApplication

fun main(args: Array<String>) {
	runApplication<PulsemetryApplication>(*args)
}
