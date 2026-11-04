package com.team376.pulsemetry.dashboard.overview

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ModelVendorsTest {
    @Test fun `제작사는 명시적 모델 카탈로그로만 확인한다`() {
        assertThat(ModelVendors.resolve("unknown/claude_code/claude-sonnet-5-5")).isEqualTo(ModelVendors.Vendor("anthropic", "Anthropic"))
        assertThat(ModelVendors.resolve("unknown/codex/gpt-6.1-sol")?.id).isEqualTo("openai")
        assertThat(ModelVendors.resolve("openai/gpt-6-astra")?.id).isEqualTo("openai")
        assertThat(ModelVendors.resolve("anthropic/claude-haiku-5-5")?.id).isEqualTo("anthropic")
        assertThat(ModelVendors.resolve("unknown/codex/anthropic%2Fclaude-fable-5")?.id).isEqualTo("anthropic")
    }
    @Test fun `공급자 제품 이름과 환산 별칭만으로 제작사를 추론하지 않는다`() {
        for (id in listOf("unknown/claude_code/custom", "openai/custom", "unknown/codex/codex-auto-review", "gpt-unregistered", "unknown/codex/~", "unknown/codex/%invalid")) {
            assertThat(ModelVendors.resolve(id)).describedAs(id).isNull()
        }
    }
}
