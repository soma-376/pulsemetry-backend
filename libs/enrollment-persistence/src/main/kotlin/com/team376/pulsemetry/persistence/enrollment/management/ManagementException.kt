package com.team376.pulsemetry.persistence.enrollment.management

/** 관리 명령 거절. detail에는 비밀을 싣지 않는다. */
class ManagementException(val code: String, val status: Int, val field: String? = null, val detail: Any? = null) : RuntimeException(code)
