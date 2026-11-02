package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.connector.vendor.SeatConnectors
import com.team376.pulsemetry.persistence.enrollment.installation.InstallationNotifier
import com.team376.pulsemetry.persistence.enrollment.mail.InvitationMailer
import com.team376.pulsemetry.persistence.enrollment.alert.AlertRuleStore
import org.springframework.beans.factory.ObjectProvider
import com.team376.pulsemetry.security.user.UserAuthService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.ObjectMapper
import java.time.Clock

import com.team376.pulsemetry.persistence.enrollment.management.CommandJournal
import com.team376.pulsemetry.persistence.enrollment.management.OnboardingStore
import com.team376.pulsemetry.persistence.enrollment.management.VendorCatalog
import com.team376.pulsemetry.persistence.enrollment.directory.DirectoryCommandStore
import com.team376.pulsemetry.persistence.enrollment.invitation.InvitationCommandStore
import com.team376.pulsemetry.persistence.enrollment.installation.InstallationCommandStore
import com.team376.pulsemetry.persistence.enrollment.vendor.VendorCommandStore
import com.team376.pulsemetry.persistence.enrollment.operation.RetentionCleanupRequests
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import com.team376.pulsemetry.persistence.enrollment.seat.SeatLedger
import com.team376.pulsemetry.persistence.enrollment.seat.SeatControl
import com.team376.pulsemetry.enrollment.team.application.TeamCommands
import com.team376.pulsemetry.enrollment.member.application.MemberCommands
import com.team376.pulsemetry.enrollment.invitation.application.InvitationCommands
import com.team376.pulsemetry.enrollment.installation.application.InstallationCommands
import com.team376.pulsemetry.enrollment.vendor.application.VendorCommands
import com.team376.pulsemetry.enrollment.seat.application.SeatCommands
import com.team376.pulsemetry.enrollment.policy.application.PolicyCommands

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(ManagementProperties::class)
class ManagementConfig {
    @Bean
    fun alertRuleStore(jdbc: JdbcClient, manager: PlatformTransactionManager, clock: Clock) = AlertRuleStore(jdbc, manager, clock)
    @Bean
    fun managementExecutor(jdbc: JdbcClient, manager: PlatformTransactionManager, mapper: ObjectMapper, clock: Clock, properties: ManagementProperties) =
        ManagementExecutor(CommandJournal(jdbc), manager, clock, properties.responseEncryptionKey)
    @Bean
    fun managementHttp(auth: UserAuthService, executor: ManagementExecutor, mapper: ObjectMapper) = ManagementHttp(auth, executor, mapper)
    @Bean
    fun teamCommands(jdbc: JdbcClient, mapper: ObjectMapper) = TeamCommands(DirectoryCommandStore(jdbc))
    @Bean
    fun memberCommands(jdbc: JdbcClient, mapper: ObjectMapper) = MemberCommands(DirectoryCommandStore(jdbc))
    @Bean
    fun invitationCommands(jdbc: JdbcClient, mapper: ObjectMapper, invitationMail: ObjectProvider<InvitationMailer>) =
        InvitationCommands(DirectoryCommandStore(jdbc), InvitationCommandStore(jdbc), invitationMail.ifAvailable)
    @Bean
    fun installationCommands(jdbc: JdbcClient, mapper: ObjectMapper, notifier: ObjectProvider<InstallationNotifier>, clock: Clock) =
        InstallationCommands(InstallationCommandStore(jdbc), DirectoryCommandStore(jdbc), notifier.ifAvailable, clock)
    @Bean
    fun vendorCommands(jdbc: JdbcClient, mapper: ObjectMapper) = VendorCommands(VendorCommandStore(jdbc, mapper), VendorCatalog(jdbc))
    @Bean
    fun seatCommands(jdbc: JdbcClient, manager: PlatformTransactionManager, clock: Clock, mapper: ObjectMapper, connectors: ObjectProvider<SeatConnectors>) =
        SeatCommands(SeatLedger(jdbc, manager, clock), SeatControl(jdbc, manager, clock, mapper, connectors.ifAvailable != null), clock)
    @Bean
    fun onboardingStore(jdbc: JdbcClient, mapper: ObjectMapper, invitationMail: ObjectProvider<InvitationMailer>) =
        OnboardingStore(jdbc, mapper, invitationMail.ifAvailable != null)
    @Bean
    fun policyCommands(store: OnboardingStore, jdbc: JdbcClient, manager: PlatformTransactionManager,
        mapper: ObjectMapper, clock: Clock, properties: ManagementProperties) = PolicyCommands(store,
        RetentionCleanupRequests(jdbc, manager, OperationStore(jdbc, manager, clock)),
        { InitialOnboardingManifest.create(properties.onboardingOtlpEndpoint, mapper) })
}
