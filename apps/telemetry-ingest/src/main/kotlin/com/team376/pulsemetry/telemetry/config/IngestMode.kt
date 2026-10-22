package com.team376.pulsemetry.telemetry.config

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * ingest 의 수신 모드(ADR 0066 Decision 7). 환경변수 `PULSEMETRY_INGEST_MODE`(`http`·`dual`·`kafka`).
 *
 * | 모드 | OTLP/HTTP (`ptt_`·JWT) | Kafka Consumer | 쓰임 |
 * |---|---|---|---|
 * | [HTTP] | 켬 | 멈춤 | 기본. 기존 배포·복구 대상 |
 * | [DUAL] | 켬 | 켬 | 전환 검증 — ALB 를 Gateway 로 옮기기 전후 |
 * | [KAFKA] | 끔(404) | 켬 | 전환 완료. 해시 키·telemetry 공개키가 필요 없다 |
 */
enum class IngestMode(val httpEnabled: Boolean, val kafkaEnabled: Boolean) {
	HTTP(httpEnabled = true, kafkaEnabled = false),
	DUAL(httpEnabled = true, kafkaEnabled = true),
	KAFKA(httpEnabled = false, kafkaEnabled = true),
	;

	companion object {
		const val PROPERTY: String = "pulsemetry.telemetry.ingest.mode"

		/** 설정을 enum 으로 묶어 읽는다. 대소문자를 가리지 않고, 모르는 값은 바인딩 오류로 기동이 실패한다. */
		fun of(binder: Binder): IngestMode = binder.bind(PROPERTY, IngestMode::class.java).orElse(null) ?: HTTP
	}
}

/**
 * OTLP/HTTP 경로(보안 체인·해시 조회·JWT 검증·컨트롤러)를 등록할지 정한다. `kafka` 모드에서는 등록하지 않는다 —
 * 기본 닫힘 체인이 그 경로를 404 로 거부한다.
 */
class OtlpHttpEnabledCondition : Condition {
	override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
		IngestMode.of(Binder.get(context.environment)).httpEnabled
}
