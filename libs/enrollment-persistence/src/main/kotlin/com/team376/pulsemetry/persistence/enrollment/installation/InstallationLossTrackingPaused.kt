package com.team376.pulsemetry.persistence.enrollment.installation

/** 구 writer 전환을 위해 추적이 중단되었다. heartbeat 전체를 재시도해야 한다. */
class InstallationLossTrackingPaused : RuntimeException("손실 추적이 중단되어 있다")
