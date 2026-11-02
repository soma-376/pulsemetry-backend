package com.team376.pulsemetry.dashboard.analytics

data class TeamRef(val teamId: String?, val teamName: String)

data class CurrentMeta(
	val organizationId: String,
	val generatedAt: String,
	val asOf: String,
	val snapshotId: String,
	val currency: String,
	val timeZone: String,
)

const val UNASSIGNED_NAME = "미배정"
