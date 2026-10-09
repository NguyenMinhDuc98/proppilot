package dev.proppilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminPropertiesTest {

    @Test
    void isDisabledWithoutAToken() {
        assertThat(new AdminProperties(null).enabled()).isFalse();
        assertThat(new AdminProperties("").enabled()).isFalse();
        assertThat(new AdminProperties("  \t ").enabled()).isFalse();
    }

    @Test
    void ignoresWhitespaceAroundTheToken() {
        var props = new AdminProperties("  secret-secret-secret-secret-1 \n");

        assertThat(props.enabled()).isTrue();
        assertThat(props.token()).isEqualTo("secret-secret-secret-secret-1");
    }

    @Test
    void keepsTheTokenOutOfItsStringForm() {
        assertThat(new AdminProperties("secret-secret-secret-secret-1").toString()).doesNotContain("secret");
    }
}
