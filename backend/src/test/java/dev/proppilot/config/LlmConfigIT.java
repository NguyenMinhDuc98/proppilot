package dev.proppilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.agent.llm.AnthropicLlmClient;
import dev.proppilot.agent.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Boots the app as it runs against real Claude (with a dummy key) and checks the documented defaults. */
@SpringBootTest(properties = {"proppilot.llm.provider=anthropic", "proppilot.llm.api-key=test-key"})
class LlmConfigIT extends PostgresIntegrationTest {

    @Autowired
    LlmClient llm;

    @Autowired
    LlmProperties llmProps;

    @Autowired
    AgentProperties agentProps;

    @Test
    void wiresTheAnthropicClientWithTheDocumentedDefaults() {
        assertThat(llm).isInstanceOf(AnthropicLlmClient.class);
        assertThat(llmProps.maxTokens()).isEqualTo(2048);
        assertThat(llmProps.maxRetries()).isEqualTo(3);
        assertThat(llmProps.retryBaseDelayMs()).isEqualTo(1000);
        assertThat(agentProps.runTimeoutSeconds()).isEqualTo(90);
    }
}
