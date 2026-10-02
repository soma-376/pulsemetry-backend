package com.team376.pulsemetry.persistence.enrollment.installation

/**
 * 설치가 **지금 집행하는** 수집 정책 판 (ADR 0043). 설정 화면의 적용 현황과 업데이트 안내의 대상 확인이 같은 식을 쓴다.
 *
 * - 설치 보고가 있으면 마지막 보고가 말한 판이다(`installation_heartbeats.applied_manifest_id` — ADR 0040). 그 조직이 모르는 판을
 *   보고했으면 없다(확인 불가).
 * - 보고가 없으면 적용 확인 기록(`installation_manifest_assignments.applied_at`) 중 가장 높은 판이다. 보고가 생기기 전에 들어온 기록이다.
 *
 * 적용 확인은 그 판을 적용한 **적이 있다**는 이력이다. 보고하는 설치에서 가장 높은 확인 판을 지금 판으로 읽으면 뒤로 돌아간 설치를 놓친다.
 */
object AppliedPolicyVersion {
    /** 설치 별칭 `i` 의 행에 붙이는 SQL 식. 값은 판 번호이거나 NULL 이다. */
    const val SQL = """CASE WHEN EXISTS (SELECT 1 FROM enrollment.installation_heartbeats h WHERE h.installation_id = i.id)
            THEN (SELECT mf.version FROM enrollment.installation_heartbeats h JOIN enrollment.manifests mf ON mf.id = h.applied_manifest_id
                  WHERE h.installation_id = i.id)
            ELSE (SELECT max(mf.version) FROM enrollment.installation_manifest_assignments a JOIN enrollment.manifests mf ON mf.id = a.manifest_id
                  WHERE a.installation_id = i.id AND a.applied_at IS NOT NULL)
        END"""

    /** 판정의 근거 — [SQL] 과 같은 분기다. 보고가 있으면 [HEARTBEAT], 없고 적용 확인 기록이 있으면 [APPLIED_CONFIRMATION], 둘 다 없으면 [NONE]. */
    const val EVIDENCE_SQL = """CASE WHEN EXISTS (SELECT 1 FROM enrollment.installation_heartbeats h WHERE h.installation_id = i.id) THEN 'heartbeat'
            WHEN EXISTS (SELECT 1 FROM enrollment.installation_manifest_assignments a WHERE a.installation_id = i.id AND a.applied_at IS NOT NULL)
            THEN 'applied_confirmation'
            ELSE 'none'
        END"""

    /** 근거가 적용 확인 기록일 때 [SQL] 이 고른 판(가장 높은 판)의 확인 시각 — 설치·판마다 기록은 하나다. 근거가 보고이거나 없으면 NULL. */
    const val CONFIRMED_AT_SQL = """CASE WHEN EXISTS (SELECT 1 FROM enrollment.installation_heartbeats h WHERE h.installation_id = i.id) THEN NULL
            ELSE (SELECT a.applied_at FROM enrollment.installation_manifest_assignments a JOIN enrollment.manifests mf ON mf.id = a.manifest_id
                  WHERE a.installation_id = i.id AND a.applied_at IS NOT NULL ORDER BY mf.version DESC LIMIT 1)
        END"""

    const val HEARTBEAT = "heartbeat"
    const val APPLIED_CONFIRMATION = "applied_confirmation"
    const val NONE = "none"
}
