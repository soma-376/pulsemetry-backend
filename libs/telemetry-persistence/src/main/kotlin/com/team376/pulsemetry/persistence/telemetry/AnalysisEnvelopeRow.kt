package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.ObservationEnvelope
import com.team376.pulsemetry.telemetry.adapter.observation.OrgAttribution
import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning

/**
 * 두 분석 테이블이 공유하는 봉투 49컬럼(ADR 0020 §1). 세 조각을 DDL 순서로 엮는다 — 정규화 몫 42([ObservationEnvelope]),
 * 조직 보강 5([OrgAttribution]), 적재 2([RowVersioning]).
 */
internal object AnalysisEnvelopeRow {

	fun write(w: AnalysisRowWriter, e: ObservationEnvelope, org: OrgAttribution, versioning: RowVersioning) {
		w.lowCardinality("tenant_id", e.tenantId)
		w.string("installation_id", e.installationId)
		w.hex64("observation_id", e.observationId)
		w.uint64("row_version", versioning.rowVersion)
		w.uint32("normalizer_rev", versioning.normalizerRev)
		w.hex64("analysis_hash", e.analysisHash)
		w.uint16("schema_version", e.schemaVersion)
		w.string("identity_version", e.identityVersion)
		w.lowCardinality("source_identity_kind", e.sourceIdentityKind)
		w.string("source_identity_namespace", e.sourceIdentityNamespace)
		w.string("native_observation_id", e.nativeObservationId)
		w.lowCardinality("record_status", e.recordStatus)
		w.string("exclusion_reason", e.exclusionReason)
		w.lowCardinality("mapping_status", e.mappingStatus)
		w.lowCardinalityArray("quality_flags", e.qualityFlags)
		w.time("source_time", e.sourceTime)
		w.lowCardinality("source_time_origin", e.sourceTimeOrigin)
		w.time("event_time", e.eventTime)
		w.time("observed_time", e.observedTime)
		w.time("received_time", e.receivedTime)
		w.lowCardinality("signal", e.signal)
		w.lowCardinality("product", e.product)
		w.lowCardinality("surface", e.surface)
		w.string("product_version", e.productVersion)
		w.string("service_name", e.serviceName)
		w.string("service_version", e.serviceVersion)
		w.string("service_instance_id", e.serviceInstanceId)
		w.string("scope_name", e.scopeName)
		w.string("scope_version", e.scopeVersion)
		w.string("resource_schema_url", e.resourceSchemaUrl)
		w.string("scope_schema_url", e.scopeSchemaUrl)
		w.string("original_name", e.originalName)
		w.string("mapping_version", e.mappingVersion)
		w.string("enrichment_version", org.enrichmentVersion)
		w.lowCardinality("usage_role", e.usageRole)
		w.lowCardinality("usage_scope", e.usageScope)
		w.lowCardinality("workload_kind", e.workloadKind)
		w.string("session_id", e.sessionId)
		w.string("session_id_namespace", e.sessionIdNamespace)
		w.string("model", e.model)
		w.string("member_id", org.memberId)
		w.string("team_id_as_of", org.teamIdAsOf)
		w.stringArray("team_ids_as_of", org.teamIdsAsOf)
		w.string("archive_ref", e.archiveRef)
		w.string("archive_selector", e.archiveSelector)
		w.string("masking_version", e.maskingVersion)
		w.string("metadata_json", e.metadataJson)
		w.stringMap("attrs", e.attrs)
		w.string("enrichment_json", org.enrichmentJson)
	}
}
