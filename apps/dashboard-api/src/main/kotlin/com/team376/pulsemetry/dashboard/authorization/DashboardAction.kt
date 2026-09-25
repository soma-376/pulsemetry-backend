package com.team376.pulsemetry.dashboard.authorization

/**
 * 인가가 판정하는 행위. 화면 요청서의 조회 묶음마다 하나다.
 *
 * - [ORGANIZATION_ANALYTICS] — 개요(조직 전체 합계).
 * - [TEAM_ANALYTICS] — 팀 분석 목록·팀 상세.
 * - [INDIVIDUAL_USAGE] — 팀 사용자 표처럼 구성원 개인의 사용량.
 * - [MEMBER_DIRECTORY] — 구성원 로스터·미배정·회수 후보·팀 선택지.
 * - [ORGANIZATION_SETTINGS] — 설정·벤더·설치 현황.
 */
enum class DashboardAction {
	ORGANIZATION_ANALYTICS,
	TEAM_ANALYTICS,
	INDIVIDUAL_USAGE,
	MEMBER_DIRECTORY,
	ORGANIZATION_SETTINGS,
}
