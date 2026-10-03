package com.team376.pulsemetry.dashboard.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 수집 상태 판정 규칙 (ADR 0041). 기대값은 ADR 의 규칙에서 쓴다 — 창 15분, 지연 5분, 중단 3시간.
 */
class IngestJudgementTest {

	private val now: Instant = Instant.parse("2026-09-30T03:00:00Z")
	private val thresholds = IngestThresholds(Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofHours(3))

	private fun minutes(n: Long): Duration = Duration.ofMinutes(n)

	/** 수집 중이라고 보고한 설치. [age] 는 보고가 얼마나 오래됐는지, 나머지 시간은 그 보고 시각 기준이다. */
	private fun collecting(
		age: Duration = minutes(1),
		deliveredAgo: Duration? = Duration.ofSeconds(30),
		receivingFor: Duration = Duration.ofHours(8),
		backlogFor: Duration? = null,
		recentLoss: Boolean = false,
		member: UUID = UUID.randomUUID(),
		memberActive: Boolean = true,
	): InstallationState {
		val received = now.minus(age)
		return InstallationState(UUID.randomUUID(), member, memberActive, InstallationState.Report(
			receivedAt = received, local = true, forwarding = true, receivingSince = received.minus(receivingFor),
			lastDeliveredAt = deliveredAgo?.let { received.minus(it) }, pendingSince = backlogFor?.let { received.minus(it) }, recentLoss = recentLoss,
		))
	}

	private fun notCollecting(local: Boolean, forwarding: Boolean, receiving: Boolean, age: Duration = minutes(1), member: UUID = UUID.randomUUID()): InstallationState {
		val received = now.minus(age)
		return InstallationState(UUID.randomUUID(), member, true, InstallationState.Report(
			receivedAt = received, local = local, forwarding = forwarding, receivingSince = if (receiving) received.minus(minutes(30)) else null,
			lastDeliveredAt = null, pendingSince = null, recentLoss = false,
		))
	}

	private fun unreported(member: UUID = UUID.randomUUID()) = InstallationState(UUID.randomUUID(), member, true, null)

	private fun judge(vararg installations: InstallationState, receipts: Boolean = true, observed: Set<UUID>? = emptySet()) =
		IngestJudgement.judge(receipts, installations.toList(), observed, now, thresholds)

	private fun status(vararg installations: InstallationState) = judge(*installations).let { it.status to it.reason }

	@Test
	@DisplayName("수신 이력이 없는 조직은 수신 대기다 — 설치 보고가 무엇을 말하든 같다")
	fun noReceiptsIsEmpty() {
		assertThat(judge(receipts = false).let { it.status to it.reason }).isEqualTo("empty" to null)
		val stalled = collecting(recentLoss = true, deliveredAgo = Duration.ofHours(5))
		assertThat(judge(stalled, receipts = false).let { it.status to it.reason }).isEqualTo("empty" to null)
		assertThat(judge(collecting(), receipts = false).status).isEqualTo("empty")
	}

	@Test
	@DisplayName("설치 보고라는 근거가 없으면 확인 불가다 — 수신 이력만으로 정상을 말하지 않는다")
	fun withoutReportsIsUnknown() {
		// 설치가 없다.
		assertThat(status()).isEqualTo("unknown" to "source_not_available")
		// 설치는 있지만 보고한 적이 없다(보고하지 않는 구버전 데몬).
		assertThat(status(unreported(), unreported())).isEqualTo("unknown" to "source_not_available")
		// 읽지 못했다. 보고가 없는 것과 같은 상태지만 세는 값은 모두 null 이다.
		val unreadable = IngestJudgement.judge(true, null, emptySet(), now, thresholds)
		assertThat(unreadable.status to unreadable.reason).isEqualTo("unknown" to "source_not_available")
		assertThat(listOf(unreadable.activeInstallations, unreadable.coverageTargetMembers, unreadable.coverageObservedMembers, unreadable.coverageRatio)).containsOnlyNulls()
	}

	@Test
	@DisplayName("지금 보고하는 수집 중 설치가 잃지도 밀리지도 않으면 정상이다")
	fun healthy() {
		assertThat(status(collecting())).isEqualTo("healthy" to null)
		// 한동안 보낼 것이 없었던 설치도 정상이다. 마지막 전달이 오래됐다는 것만으로는 문제가 아니다.
		assertThat(status(collecting(deliveredAgo = Duration.ofHours(7)))).isEqualTo("healthy" to null)
		// 아직 한 번도 전달한 적 없는 새 설치도 같다.
		assertThat(status(collecting(deliveredAgo = null, receivingFor = minutes(2)))).isEqualTo("healthy" to null)
		// 보고 순간에 마침 전송 중이던 것(대기를 이번 보고에서 처음 봤다)은 지연이 아니다.
		assertThat(status(collecting(deliveredAgo = Duration.ofHours(7), backlogFor = Duration.ZERO))).isEqualTo("healthy" to null)
	}

	@Test
	@DisplayName("창의 경계 — 창 시작 시각에 받은 보고까지가 지금의 근거다")
	fun windowBoundary() {
		val atEdge = judge(collecting(age = minutes(15)))
		assertThat(atEdge.status to atEdge.activeInstallations).isEqualTo("healthy" to 1L)
		val justOutside = judge(collecting(age = minutes(15).plusNanos(1)))
		assertThat(justOutside.status to justOutside.reason).isEqualTo("unknown" to "installations_silent")
		assertThat(justOutside.activeInstallations).isEqualTo(0L)
	}

	@Test
	@DisplayName("전달 대기가 지연 기준보다 오래 이어지면 지연이다")
	fun persistentBacklogIsDelayed() {
		assertThat(status(collecting(backlogFor = minutes(5)))).isEqualTo("healthy" to null)
		assertThat(status(collecting(backlogFor = minutes(5).plusSeconds(1)))).isEqualTo("delayed" to "delivery_delayed")
		// 대기만으로는 중단이라고 하지 않는다. 잃고 있다는 근거가 없다.
		assertThat(status(collecting(backlogFor = Duration.ofHours(6), deliveredAgo = Duration.ofHours(6)))).isEqualTo("delayed" to "delivery_delayed")
	}

	@Test
	@DisplayName("잃고 있으면 지연이고, 중단 기준 넘게 전달 성공이 없으면 중단이다")
	fun lossIsDelayedUntilNothingGetsThrough() {
		// 잃었지만 방금도 전달됐다.
		assertThat(status(collecting(recentLoss = true, deliveredAgo = Duration.ofSeconds(30)))).isEqualTo("delayed" to "delivery_delayed")
		// 경계: 정확히 중단 기준만큼은 아직 지연이다.
		assertThat(status(collecting(recentLoss = true, deliveredAgo = Duration.ofHours(3)))).isEqualTo("delayed" to "delivery_delayed")
		assertThat(status(collecting(recentLoss = true, deliveredAgo = Duration.ofHours(3).plusSeconds(1)))).isEqualTo("down" to "delivery_stalled")
		// 전달에 성공한 적이 없으면 듣기 시작한 때부터 센다.
		assertThat(status(collecting(recentLoss = true, deliveredAgo = null, receivingFor = Duration.ofHours(1)))).isEqualTo("delayed" to "delivery_delayed")
		assertThat(status(collecting(recentLoss = true, deliveredAgo = null, receivingFor = Duration.ofHours(4)))).isEqualTo("down" to "delivery_stalled")
	}

	@Test
	@DisplayName("중단은 판정 대상 전부가 멈췄을 때만이다 — 일부만 멈추면 지연이다")
	fun downNeedsEveryJudgedInstallation() {
		val stalled = { collecting(recentLoss = true, deliveredAgo = Duration.ofHours(5)) }
		assertThat(status(stalled(), stalled())).isEqualTo("down" to "delivery_stalled")
		assertThat(status(stalled(), collecting())).isEqualTo("delayed" to "delivery_delayed")
		assertThat(status(collecting(), collecting(backlogFor = minutes(30)), collecting())).isEqualTo("delayed" to "delivery_delayed")
	}

	@Test
	@DisplayName("수집 비활성 설치는 판정 대상이 아니다 — 데몬이 전달 결과를 보지 못한다")
	fun nonCollectingInstallationsAreNotJudged() {
		val direct = notCollecting(local = false, forwarding = false, receiving = false)
		val forwardingOff = notCollecting(local = true, forwarding = false, receiving = true)
		val receiverOff = notCollecting(local = true, forwarding = true, receiving = false)
		// 이들만 있으면 판정할 근거가 없다. 살아 있는 설치로는 센다.
		val only = judge(direct, forwardingOff, receiverOff)
		assertThat(only.status to only.reason).isEqualTo("unknown" to "source_not_available")
		assertThat(only.activeInstallations).isEqualTo(3L)
		// 분모에서 빠진다: 멈춘 수집 설치 하나와 함께 있으면 "전부 멈췄다"가 된다.
		val stalled = collecting(recentLoss = true, deliveredAgo = Duration.ofHours(5))
		assertThat(status(stalled, direct, forwardingOff, receiverOff)).isEqualTo("down" to "delivery_stalled")
		assertThat(status(collecting(), direct, forwardingOff, receiverOff)).isEqualTo("healthy" to null)
	}

	@Test
	@DisplayName("수집 중이던 설치가 조용해지면 확인 불가이고, 중단 기준을 넘기면 중단이다")
	fun silentInstallations() {
		assertThat(status(collecting(age = minutes(16)))).isEqualTo("unknown" to "installations_silent")
		assertThat(status(collecting(age = Duration.ofHours(3)))).isEqualTo("unknown" to "installations_silent")
		assertThat(status(collecting(age = Duration.ofHours(3).plusSeconds(1)))).isEqualTo("down" to "installations_silent")
		// 가장 최근 보고가 기준이다. 하나라도 중단 기준 안에 보고했으면 중단이 아니다.
		assertThat(status(collecting(age = Duration.ofDays(9)), collecting(age = Duration.ofHours(2)))).isEqualTo("unknown" to "installations_silent")
		// 조용해진 설치의 옛 보고가 말한 손실로 지금을 판정하지 않는다.
		assertThat(status(collecting(age = Duration.ofHours(2), recentLoss = true, deliveredAgo = Duration.ofHours(9)))).isEqualTo("unknown" to "installations_silent")
		// 지금 보고하는 설치가 하나라도 있으면 그것으로 판정한다.
		assertThat(status(collecting(age = Duration.ofDays(9)), collecting())).isEqualTo("healthy" to null)
		// 수집 중이었던 적이 없는 설치의 침묵은 아무 말도 하지 않는다.
		assertThat(status(notCollecting(local = false, forwarding = false, receiving = false, age = Duration.ofDays(9)))).isEqualTo("unknown" to "source_not_available")
	}

	@Test
	@DisplayName("활성 설치 수는 창 안에 보고한 설치 수이고, 보고한 적이 없으면 0 이 아니라 null 이다")
	fun activeInstallations() {
		assertThat(judge().activeInstallations).isNull()
		assertThat(judge(unreported(), unreported()).activeInstallations).isNull()
		assertThat(judge(collecting(age = Duration.ofDays(2))).activeInstallations).isEqualTo(0L)
		val mixed = judge(collecting(), collecting(age = minutes(14)), collecting(age = minutes(40)), unreported(),
			notCollecting(local = false, forwarding = false, receiving = false))
		assertThat(mixed.activeInstallations).isEqualTo(3L)
	}

	@Test
	@DisplayName("커버리지 — 회사로 가는 경로가 켜져 있다고 보고한 구성원 가운데 창 안에 수신이 확인된 비율")
	fun coverage() {
		val alice = UUID.randomUUID()
		val aliceLaptop = collecting(member = alice)
		val aliceDesktop = collecting(member = alice, age = Duration.ofDays(3)) // 조용해졌어도 대상이다.
		val bob = collecting()
		val carolDirect = notCollecting(local = false, forwarding = false, receiving = false)
		val daveForwardingOff = notCollecting(local = true, forwarding = false, receiving = true)
		val erinReceiverOff = notCollecting(local = true, forwarding = true, receiving = false)
		val frankUnreported = unreported()
		val suspended = collecting(memberActive = false)
		val all = arrayOf(aliceLaptop, aliceDesktop, bob, carolDirect, daveForwardingOff, erinReceiverOff, frankUnreported, suspended)

		// 분모: alice·bob·carol. 경로가 꺼졌다고 보고한 설치(dave·erin), 보고한 적 없는 설치(frank), 활성이 아닌 구성원은 뺀다.
		val none = judge(*all, observed = emptySet())
		assertThat(Triple(none.coverageTargetMembers, none.coverageObservedMembers, none.coverageRatio)).isEqualTo(Triple(3L, 0L, 0.0))

		// 분자: 대상 구성원 중 수신이 확인된 사람. 같은 사람의 두 설치는 한 명이고, 대상이 아닌 설치의 수신은 세지 않는다.
		val some = judge(*all, observed = setOf(aliceLaptop.installationId, aliceDesktop.installationId, carolDirect.installationId,
			frankUnreported.installationId, daveForwardingOff.installationId, suspended.installationId, UUID.randomUUID()))
		assertThat(some.coverageTargetMembers to some.coverageObservedMembers).isEqualTo(3L to 2L)
		assertThat(some.coverageRatio).isEqualTo(2.0 / 3.0)

		val everyone = judge(*all, observed = setOf(aliceDesktop.installationId, bob.installationId, carolDirect.installationId))
		assertThat(everyone.coverageRatio).isEqualTo(1.0)
	}

	@Test
	@DisplayName("커버리지의 분모가 0 이거나 수신을 읽지 못하면 비율은 0 이 아니라 null 이다")
	fun coverageWithoutBasis() {
		val noTargets = judge(unreported(), notCollecting(local = true, forwarding = false, receiving = true), observed = setOf(UUID.randomUUID()))
		assertThat(Triple(noTargets.coverageTargetMembers, noTargets.coverageObservedMembers, noTargets.coverageRatio)).isEqualTo(Triple(0L, 0L, null))

		val unreadable = judge(collecting(), observed = null)
		assertThat(Triple(unreadable.coverageTargetMembers, unreadable.coverageObservedMembers, unreadable.coverageRatio)).isEqualTo(Triple(1L, null, null))
		// 수신을 읽지 못해도 상태는 설치 보고로 판정한다.
		assertThat(unreadable.status).isEqualTo("healthy")
	}
}
