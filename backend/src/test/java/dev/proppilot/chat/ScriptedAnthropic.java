package dev.proppilot.chat;

import dev.proppilot.agent.ScriptedLlmClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the offline assistant with a scripted model that plays Anthropic's default model. */
@TestConfiguration
public class ScriptedAnthropic {

    public static final String SMALL = "claude-haiku-4-5-20251001";

    @Bean
    @Primary
    ScriptedLlmClient scriptedLlm() {
        return new ScriptedLlmClient().servingAs("anthropic", SMALL);
    }
}
