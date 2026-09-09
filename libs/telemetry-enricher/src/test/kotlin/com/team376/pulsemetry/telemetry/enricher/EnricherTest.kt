package com.team376.pulsemetry.telemetry.enricher

import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import com.team376.pulsemetry.telemetry.enricher.provider.AiAnalysisProvider
import com.team376.pulsemetry.telemetry.enricher.provider.EnrichmentProvider
import com.team376.pulsemetry.telemetry.enricher.provider.GithubProvider
import com.team376.pulsemetry.telemetry.enricher.provider.JiraProvider
import com.team376.pulsemetry.telemetry.enricher.support.GoldenEvents
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 보강 단계의 구조 계약 — 행 수 보존, 주석 키 집합, 적용 순서, `ctx` 수명.
 *
 * `enrichment_json` 에 실리는 **값**이 현행 파이프라인과 같은지가 여기 걸려 있다. 구 registry 는
 * 발견된 모든 provider 에 대해 항상 항목을 쓰므로, no-op 스텁이 빠지면 저장되는 값이 달라진다.
 */
class EnricherTest {

	private val events: List<Normalized> = GoldenEvents.events(GoldenEvents.CLAUDE_CODE_LOGS)

	/** `org` 를 대신해 순서만 확인하는 provider. RDS 없이 도는 테스트를 위해 둔다. */
	private class RecordingProvider(
		override val name: String,
		override val order: Int = EnrichmentProvider.DEFAULT_ORDER,
		val seenContexts: MutableList<MutableMap<String, Any?>> = mutableListOf(),
	) : EnrichmentProvider {
		override fun enrich(item: Enriched, ctx: MutableMap<String, Any?>): Map<String, Any?> {
			seenContexts += ctx
			return mapOf("seen" to true)
		}
	}

	@Test
	@DisplayName("행을 드롭하지 않는다 — 입력과 출력 개수가 같고 순서도 같다")
	fun rowCountAndOrderArePreserved() {
		val enriched = Enricher(listOf(GithubProvider())).enrich(events)

		assertThat(enriched).hasSameSizeAs(events)
		assertThat(enriched.map { it.event }).containsExactlyElementsOf(events)
	}

	@Test
	@DisplayName("no-op 스텁까지 포함해 모든 provider 가 주석 항목을 남긴다")
	fun everyProviderLeavesAnEntry() {
		val providers = listOf(GithubProvider(), JiraProvider(), AiAnalysisProvider())

		val enriched = Enricher(providers).enrich(events)

		// 현행 파이프라인의 enrichment_json 이 {"ai_analysis":{},"github":{},"jira":{},"org":{...}} 인
		// 근거다. 스텁을 빼면 이 키들이 사라져 저장되는 값이 달라진다.
		assertThat(enriched).allSatisfy { item ->
			assertThat(item.annotations).containsOnlyKeys("ai_analysis", "github", "jira")
			assertThat(item.annotations.values).allSatisfy { assertThat(it as Map<*, *>).isEmpty() }
		}
	}

	@Test
	@DisplayName("order 가 작은 provider 가 먼저 돈다 — 같으면 이름 순이다")
	fun providersRunInOrderThenName() {
		val enricher = Enricher(
			listOf(
				RecordingProvider(name = "bbb"),
				RecordingProvider(name = "aaa"),
				RecordingProvider(name = "zzz", order = 0),
			),
		)

		assertThat(enricher.providerNames()).containsExactly("zzz", "aaa", "bbb")
	}

	@Test
	@DisplayName("ctx 는 push 하나에서만 공유된다 — 호출이 달라지면 새것이다")
	fun contextIsScopedToOnePush() {
		val recording = RecordingProvider(name = "recording")
		val enricher = Enricher(listOf(recording))

		enricher.enrich(events)
		val firstPush = recording.seenContexts.toList()
		recording.seenContexts.clear()
		enricher.enrich(events)
		val secondPush = recording.seenContexts.toList()

		// 빈 맵끼리는 equals 가 참이라 동일성으로 본다 — 여기서 묻는 것은 값이 아니라 인스턴스다.
		assertThat(firstPush).hasSameSizeAs(events).allMatch { it === firstPush.first() }
		assertThat(secondPush).hasSameSizeAs(events).allMatch { it === secondPush.first() }
		assertThat(secondPush.first()).isNotSameAs(firstPush.first())
	}

	@Test
	@DisplayName("빈 입력에는 provider 를 부르지 않는다")
	fun emptyInputShortCircuits() {
		val recording = RecordingProvider(name = "recording")

		assertThat(Enricher(listOf(recording)).enrich(emptyList())).isEmpty()
		assertThat(recording.seenContexts).isEmpty()
	}

	@Test
	@DisplayName("provider 이름이 겹치면 조립 시점에 막는다 — 주석이 조용히 덮이지 않게")
	fun duplicateProviderNamesAreRejected() {
		assertThatThrownBy { Enricher(listOf(GithubProvider(), GithubProvider())) }
			.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining("github")
	}
}
