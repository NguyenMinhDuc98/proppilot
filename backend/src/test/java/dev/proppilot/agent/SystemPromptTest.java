package dev.proppilot.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SystemPromptTest {

    private final String prompt = SystemPrompt.forDate(LocalDate.of(2026, 3, 15));

    @Test
    void forbidsMentioningInternalToolNamesInsteadOfAskingForASourcesLine() {
        assertThat(prompt).contains("Never mention internal tool or function names").doesNotContain("Data:");
    }

    @Test
    void doesNotNameAnyToolItself() {
        assertThat(prompt).doesNotContainPattern("[a-z]+_[a-z_]+");
    }
}
