package com.team376.pulsemetry.enrollment.installation.domain

/** 활성 설치·구성원 중 현재 정책보다 이전 판이거나 적용 판을 모르는 설치만 안내한다(ADR 0043). */
object InstallationNotices {
    fun eligible(installationStatus: String, memberStatus: String, appliedVersion: Long?, expectedVersion: Long): Boolean =
        installationStatus == "active" && memberStatus == "active" && (appliedVersion == null || appliedVersion < expectedVersion)
}
