package com.team376.pulsemetry.telemetry.maintenance

import com.team376.pulsemetry.telemetry.adapter.observation.*
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import java.math.BigDecimal
import java.time.Instant

/** 재평가 테스트가 쓰는 저장 사용량 행. 값은 Codeburn 판 단가로 손계산할 수 있게 고른다. */
internal object StoredUsageSamples {
	const val TENANT: String = "00000000-0000-0000-0000-000000000001"

	fun row(
		tenant: String = TENANT,
		id: Char = 'a',
		model: String = "codex-auto-review",
		rev: UInt = 8u,
		ingestSeq: UInt = 123u,
		semanticsProfile: String? = "codex-responses-inclusive-v1",
		archiveRef: String? = "file:///private/original",
		sourceTime: Instant = Instant.parse("2026-09-13T12:34:56.123456789Z"),
	): StoredUsageRepricing.Row {
		val observation = EventObservation(
			envelope = ObservationEnvelope(
				tenantId = tenant, installationId = "install",
				observationId = id.toString().repeat(64), analysisHash = AnalysisHash.PLACEHOLDER,
				identityVersion = ObservationIds.IDENTITY_VERSION, sourceIdentityKind = SourceIdentityKind.FINGERPRINT,
				mappingStatus = MappingStatus.MAPPED, sourceTime = EpochNanos.of(sourceTime),
				sourceTimeOrigin = SourceTimeOrigin.OTLP_EVENT, receivedTime = EpochNanos.of(Instant.parse("2026-10-16T01:02:03.987654321Z")),
				signal = ObservationSignal.LOG, product = Product.CODEX, surface = Surface.CLI,
				mappingVersion = "test", usageRole = UsageRole.PRIMARY, usageScope = UsageScope.RESPONSE, workloadKind = WorkloadKind.MAIN,
				model = model, maskingVersion = "mask-v2", metadataJson = TypedMetadata.EMPTY.toJson(),
				archiveRef = archiveRef, archiveSelector = archiveRef?.let { "bytes=0+100;path=0/0/0" }, sessionId = "session",
			),
			eventType = EventType.MODEL_RESPONSE_USAGE, tokensInput = 150, tokensInputUncached = 100,
			tokensOutput = 20, tokensCacheRead = 40, tokensCacheCreate = 10, tokensTotalDerived = 170,
			tokensTotalReported = 9_007_199_254_740_993L, costReportedUsd = BigDecimal("12345678901234567890123456.123456789012"), success = true,
			inputSemantics = InputSemantics.INCLUSIVE_CACHE, outputSemantics = OutputSemantics.INCLUSIVE_REASONING_TOOL,
			semanticsProfile = semanticsProfile,
		)
		return StoredUsageRepricing.Row(
			EnrichedEvent(AnalysisHash.seal(observation), OrgAttribution("member", "team", listOf("team"), "{}", "org-v1")),
			RowVersioning.of(rev, ingestSeq),
		)
	}
}
