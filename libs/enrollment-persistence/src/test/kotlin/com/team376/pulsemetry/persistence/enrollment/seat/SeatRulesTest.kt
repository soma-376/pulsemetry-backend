package com.team376.pulsemetry.persistence.enrollment.seat

import com.team376.pulsemetry.persistence.enrollment.seat.SeatState.ASSIGNED
import com.team376.pulsemetry.persistence.enrollment.seat.SeatState.PENDING_ASSIGNMENT
import com.team376.pulsemetry.persistence.enrollment.seat.SeatState.PENDING_RELEASE
import com.team376.pulsemetry.persistence.enrollment.seat.SeatState.RELEASED
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * ADR 0048 의 표 셋을 그대로 옮긴 사례 — §2 전이 표, §3 우선순위 표, §7 동기화 상태. 그리고 §6 자격증명 암호화의 규칙.
 * 기대값은 ADR 의 표에서 쓴다(구현을 돌린 출력이 아니다).
 */
class SeatRulesTest {

	private val states = listOf(null, ASSIGNED, PENDING_ASSIGNMENT, PENDING_RELEASE, RELEASED)

	private fun allowed(source: SeatSource): Set<Pair<SeatState?, SeatState>> =
		states.flatMap { from -> SeatState.entries.map { from to it } }.filter { (from, to) -> from != to && SeatTransitions.allowed(source, from, to) }.toSet()

	@Test
	fun `전이 표 — 수동·CSV 는 새 배정·재배정·해제만, 벤더 제어와 관리자 조치 확인은 표의 칸만`() {
		val manual = setOf(null to ASSIGNED, RELEASED to ASSIGNED, ASSIGNED to RELEASED)
		assertThat(allowed(SeatSource.MANUAL)).isEqualTo(manual)
		assertThat(allowed(SeatSource.CSV)).isEqualTo(manual)
		assertThat(allowed(SeatSource.VENDOR_CONTROL)).isEqualTo(setOf(
			ASSIGNED to PENDING_RELEASE, ASSIGNED to RELEASED, PENDING_RELEASE to ASSIGNED, PENDING_RELEASE to RELEASED,
			RELEASED to PENDING_ASSIGNMENT, RELEASED to ASSIGNED, PENDING_ASSIGNMENT to ASSIGNED,
		))
		assertThat(allowed(SeatSource.ADMIN_ACTION)).isEqualTo(setOf(ASSIGNED to RELEASED, PENDING_RELEASE to RELEASED, RELEASED to ASSIGNED))
	}

	@Test
	fun `전이 표 — 동기화는 벤더가 보고한 보유 상태로 무엇에서든 가고 목록에 없는 보유 좌석만 해제한다`() {
		val holding = listOf(ASSIGNED, PENDING_ASSIGNMENT, PENDING_RELEASE)
		val expected = states.flatMap { from -> holding.map { from to it } }.filter { it.first != it.second }.toSet() +
			holding.map { it to RELEASED }
		assertThat(allowed(SeatSource.CONNECTOR)).isEqualTo(expected)
		assertThat(SeatTransitions.allowed(SeatSource.CONNECTOR, null, RELEASED)).isFalse()
		assertThat(SeatState.entries.filter { it.holds }).containsExactlyElementsOf(holding)
	}

	@Test
	fun `우선순위 표 — 연결이 있으면 커넥터, 커넥터 플랜인데 연결이 없으면 수동 임시, 커넥터 없는 플랜은 수동`() {
		// 1행: 커넥터가 없는 플랜
		assertThat(SeatAuthority.of(connectorPlan = false, activeConnection = false)).isEqualTo(SeatAuthority(SeatAuthority.Authority.MANUAL, provisional = false))
		// 2행: 커넥터가 있는 플랜, 활성 연결 없음
		assertThat(SeatAuthority.of(connectorPlan = true, activeConnection = false)).isEqualTo(SeatAuthority(SeatAuthority.Authority.MANUAL, provisional = true))
		// 3·4행: 활성 연결 있음(동기화 실패 중이어도 권위는 커넥터다)
		assertThat(SeatAuthority.of(connectorPlan = true, activeConnection = true)).isEqualTo(SeatAuthority(SeatAuthority.Authority.CONNECTOR, provisional = false))
		assertThat(listOf(false to false, true to false, true to true).map { (plan, connected) -> SeatAuthority.of(plan, connected).allowsManualAssignment })
			.containsExactly(true, true, false)
	}

	@Test
	fun `동기화 상태 — 성공 없음은 pending, 마지막 시도가 실패면 failing, 실패 중이거나 기준보다 오래된 성공 값은 낡음`() {
		val at = Instant.parse("2026-10-01T00:00:00Z")
		assertThat(SyncStatus.of(null, null)).isEqualTo(SyncStatus.PENDING)
		assertThat(SyncStatus.of(null, at)).isEqualTo(SyncStatus.FAILING)
		assertThat(SyncStatus.of(at, at.minusSeconds(60))).isEqualTo(SyncStatus.SUCCEEDED)
		assertThat(SyncStatus.of(at, at.plusSeconds(60))).isEqualTo(SyncStatus.FAILING)
		val day = Duration.ofDays(1)
		assertThat(SyncStatus.stale(null, at, at, day)).describedAs("값이 없으면 낡음이 아니라 없음").isFalse()
		assertThat(SyncStatus.stale(at, at.plusSeconds(1), at.plusSeconds(2), day)).isTrue()
		assertThat(SyncStatus.stale(at, null, at.plus(day), day)).isFalse()
		assertThat(SyncStatus.stale(at, null, at.plus(day).plusSeconds(1), day)).isTrue()
	}

	private fun key(byte: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { byte.toByte() })

	@Test
	fun `자격증명 암호문은 평문을 담지 않고 다른 행으로 옮기면 풀리지 않으며 옛 키로 만든 것은 회전 뒤에도 풀린다`() {
		val secret = "fake-vendor-credential-" + "q".repeat(40)
		val old = CredentialCipher(mapOf("k1" to key(1)), "k1")
		val sealed = old.seal(secret, "vendor-connection/a/b/c")
		assertThat(sealed.keyId).isEqualTo("k1")
		assertThat(sealed.ciphertext).doesNotContain(secret).doesNotContain(Base64.getEncoder().encodeToString(secret.toByteArray()))
		assertThat(old.seal(secret, "vendor-connection/a/b/c").ciphertext).describedAs("매번 새 nonce").isNotEqualTo(sealed.ciphertext)
		assertThat(old.open("k1", sealed.ciphertext, "vendor-connection/a/b/c")).isEqualTo(secret)
		assertThatThrownBy { old.open("k1", sealed.ciphertext, "vendor-connection/a/b/other") }.isInstanceOf(javax.crypto.AEADBadTagException::class.java)

		// 회전: 새 키가 현재 키, 옛 키는 복호화에만 쓴다.
		val rotated = CredentialCipher(mapOf("k1" to key(1), "k2" to key(2)), "k2")
		assertThat(rotated.open("k1", sealed.ciphertext, "vendor-connection/a/b/c")).isEqualTo(secret)
		assertThat(rotated.seal(secret, "x").keyId).isEqualTo("k2")
		// 옛 키를 너무 일찍 빼면 평문으로 떨어지지 않고 실패한다.
		assertThatThrownBy { CredentialCipher(mapOf("k2" to key(2)), "k2").open("k1", sealed.ciphertext, "vendor-connection/a/b/c") }
			.isInstanceOf(CredentialKeyUnavailable::class.java).hasMessage("credential_key_unavailable")
		assertThat(rotated.toString()).doesNotContain(key(1)).doesNotContain(key(2))
	}

	@Test
	fun `키 설정이 잘못되면 조립이 실패한다`() {
		assertThatThrownBy { CredentialCipher(emptyMap(), "k1") }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { CredentialCipher(mapOf("k1" to Base64.getEncoder().encodeToString(ByteArray(16))), "k1") }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { CredentialCipher(mapOf("k1" to key(1)), "k2") }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { CredentialCipher(mapOf("k 1" to key(1)), "k 1") }.isInstanceOf(IllegalArgumentException::class.java)
	}
}
