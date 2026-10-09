package dev.proppilot.info;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.agent.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InfoControllerTest {

    private MockMvc mvcFor(String provider, String model) {
        var llm = mock(LlmClient.class);
        when(llm.provider()).thenReturn(provider);
        when(llm.model()).thenReturn(model);
        return MockMvcBuilders.standaloneSetup(new InfoController(llm)).build();
    }

    @Test
    void reportsTheOfflineAssistant() throws Exception {
        mvcFor("offline", "rule-based").perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"mode":"offline","provider":"offline","model":"rule-based"}""", true));
    }

    @Test
    void reportsClaudeWithTheConfiguredModelAndNothingElse() throws Exception {
        mvcFor("anthropic", "claude-haiku-4-5-20251001").perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"mode":"claude","provider":"anthropic","model":"claude-haiku-4-5-20251001"}""", true));
    }
}
