package dev.proppilot.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GuardrailModeTest {

    @Test
    void readsTheThreeModesInAnyCase() {
        assertThat(GuardrailMode.parse("off")).isEqualTo(GuardrailMode.OFF);
        assertThat(GuardrailMode.parse("WARN")).isEqualTo(GuardrailMode.WARN);
        assertThat(GuardrailMode.parse(" Retry ")).isEqualTo(GuardrailMode.RETRY);
    }

    @Test
    void aBlankOrMissingSettingIsWarn() {
        assertThat(GuardrailMode.parse(null)).isEqualTo(GuardrailMode.WARN);
        assertThat(GuardrailMode.parse("")).isEqualTo(GuardrailMode.WARN);
        assertThat(GuardrailMode.parse("   ")).isEqualTo(GuardrailMode.WARN);
    }

    @Test
    void anythingElseIsRefusedWithTheNameOfTheSetting() {
        for (var setting : new String[] {"strict", "on", "true", "warn,retry", "0"}) {
            assertThatThrownBy(() -> GuardrailMode.parse(setting))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GUARDRAIL_MODE must be off, warn or retry");
        }
    }
}
