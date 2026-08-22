package com.team376.pulsemetry.enrollment.error

/**
 * 사용자에게 그대로 보여줄 수 있는 실패.
 *
 * [message] 는 CLI 가 터미널에 출력하는 문장이므로 스택트레이스나 내부 식별자를 담지 마라.
 * 예상 못 한 실패는 이 예외로 감싸지 말고 그대로 터뜨려 500 이 되게 둔다 — 계약에 없는 상황을
 * 그럴듯한 4xx 로 위장하면 원인을 못 찾는다.
 */
class EnrollmentException(
	val errorCode: EnrollmentErrorCode,
	override val message: String,
) : RuntimeException(message) {

	companion object {

		fun missingCode() = EnrollmentException(
			EnrollmentErrorCode.INVALID_REQUEST,
			"초대 코드가 없습니다. 관리자에게 받은 설치 명령을 그대로 붙여넣었는지 확인하세요.",
		)

		fun malformedCode() = EnrollmentException(
			EnrollmentErrorCode.INVALID_REQUEST,
			"초대 코드 형식이 올바르지 않습니다. XXXX-XXXX-XXXX 형태인지 확인하고, " +
				"맞다면 관리자에게 새 코드를 요청하세요.",
		)

		fun unsupportedPlatform() = EnrollmentException(
			EnrollmentErrorCode.INVALID_REQUEST,
			"지원하지 않는 운영체제입니다. Windows·macOS·Linux 에서만 설치할 수 있습니다.",
		)

		fun invalidEmail() = EnrollmentException(
			EnrollmentErrorCode.INVALID_REQUEST,
			"이메일이 비어 있습니다. 초대할 사용자의 이메일을 지정하세요.",
		)

		fun invalidExpiry() = EnrollmentException(
			EnrollmentErrorCode.INVALID_REQUEST,
			"만료 시간은 1시간 이상이어야 합니다.",
		)

		fun malformedBody() = EnrollmentException(
			EnrollmentErrorCode.INVALID_REQUEST,
			"요청 형식이 올바르지 않습니다. CLI 를 최신 버전으로 업데이트한 뒤 다시 시도하세요.",
		)

		fun invitationNotFound() = EnrollmentException(
			EnrollmentErrorCode.INVITATION_NOT_FOUND,
			"초대 코드를 찾을 수 없습니다. 관리자에게 새 코드를 요청하세요.",
		)

		fun invitationUsed() = EnrollmentException(
			EnrollmentErrorCode.INVITATION_USED,
			"이미 사용된 초대 코드입니다. 코드는 한 번만 쓸 수 있으니 관리자에게 새 코드를 요청하세요.",
		)

		fun invitationRevoked() = EnrollmentException(
			EnrollmentErrorCode.INVITATION_REVOKED,
			"폐기된 초대 코드입니다. 관리자에게 새 코드를 요청하세요.",
		)

		fun invitationExpired() = EnrollmentException(
			EnrollmentErrorCode.INVITATION_EXPIRED,
			"초대 코드가 만료되었습니다. 관리자에게 새 코드를 요청하세요.",
		)

		fun manifestNotConfigured() = EnrollmentException(
			EnrollmentErrorCode.MANIFEST_NOT_CONFIGURED,
			"회사의 수집 설정이 아직 준비되지 않았습니다. 관리자에게 문의하세요.",
		)

		fun unauthorized() = EnrollmentException(
			EnrollmentErrorCode.UNAUTHORIZED,
			"인증에 실패했습니다. 설치를 다시 진행하거나 관리자에게 문의하세요.",
		)

		fun forbidden() = EnrollmentException(
			EnrollmentErrorCode.FORBIDDEN,
			"이 작업을 수행할 권한이 없습니다.",
		)

		fun installationRevoked() = EnrollmentException(
			EnrollmentErrorCode.INSTALLATION_REVOKED,
			"이 설치는 폐기되었습니다. 관리자에게 새 초대 코드를 요청하세요.",
		)

		fun notFound() = EnrollmentException(
			EnrollmentErrorCode.NOT_FOUND,
			"요청한 주소를 찾을 수 없습니다. CLI 를 최신 버전으로 업데이트한 뒤 다시 시도하세요.",
		)

		fun methodNotAllowed() = EnrollmentException(
			EnrollmentErrorCode.METHOD_NOT_ALLOWED,
			"이 주소에서는 쓸 수 없는 요청 방식입니다. CLI 를 최신 버전으로 업데이트한 뒤 다시 시도하세요.",
		)
	}
}
