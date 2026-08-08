package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.contract.EnrollRequest
import com.team376.pulsemetry.enrollment.contract.EnrollmentResponse
import com.team376.pulsemetry.enrollment.service.EnrollmentService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 데스크탑 CLI 가 호출하는 설치 등록 엔드포인트.
 *
 * 인증이 없다. 초대 코드 자체가 자격증명이다 — 그래서 코드의 형식 검증과 원자적 소비가 전부다.
 */
@RestController
@RequestMapping("/v1")
class EnrollmentController(
	private val enrollmentService: EnrollmentService,
) {

	@PostMapping("/enroll")
	@ResponseStatus(HttpStatus.CREATED)
	fun enroll(@RequestBody request: EnrollRequest): EnrollmentResponse =
		enrollmentService.enroll(request)
}
