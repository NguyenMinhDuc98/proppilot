package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.ScriptedLlmClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** How a chat run ends when the model misbehaves, end to end through HTTP, SSE and the database. */
@AutoConfigureMockMvc
class ChatRunOutcomesIT extends PostgresIntegrationTest {

    @TestConfiguration
    static class ScriptedLlmConfig {
        @Bean
        @Primary
        ScriptedLlmClient scriptedLlm() {
            return new ScriptedLlmClient();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ScriptedLlmClient llm;

    @Autowired
    ChatRunRepository runs;

    @BeforeEach
    void resetScript() {
        llm.reset();
    }

    private String chat(String question) throws Exception {
        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private ChatRun recordedRun(String question) {
        return runs.findAll().stream().filter(run -> run.getQuestion().equals(question)).findFirst().orElseThrow();
    }

    @Test
    void providerFailuresReachTheBrowserAsACodeWithoutProviderText() throws Exception {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529: upstream-secret");

        var sse = chat("outcome: provider failure");

        assertThat(sse).contains("event:error").contains("\"code\":\"llm_overloaded\"")
                .contains("event:done").contains("\"status\":\"ERROR\"")
                .doesNotContain("upstream-secret");
        assertThat(recordedRun("outcome: provider failure").getStatus()).isEqualTo(AgentResult.Status.ERROR);
    }
}
