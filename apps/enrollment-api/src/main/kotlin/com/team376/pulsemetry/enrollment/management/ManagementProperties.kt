package com.team376.pulsemetry.enrollment.management

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("pulsemetry.management")
class ManagementProperties {
    var enabled = false
    var responseEncryptionKey = ""
    var onboardingOtlpEndpoint = ""
    /** 초대 메일의 수락 링크가 가리키는 프론트 주소. 메일 기능을 같이 켰을 때 필요하다 */
    var invitationAcceptUrl = ""
    /** 초대 코드로 OS별 설치 명령을 안내하는 공개 페이지. SSO 로그인 주소와 별개다. */
    var installationPageUrl = ""
}
