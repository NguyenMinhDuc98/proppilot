package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.agent.ScriptedLlmClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A run with a larger model configured, end to end: which model answered and why, in the stream, the stored run and its
 * public trace, and what it was paid for.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "proppilot.llm.large-model=" + ModelRoutingIT.LARGE)
class ModelRoutingIT extends PostgresIntegrationTest {

    static final String LARGE = "claude-sonnet-4-5";
    private static final String SMALL = "claude-haiku-4-5-20251001";
    private static final AtomicInteger CLIENTS = new AtomicInteger();

    @TestConfiguration
    static class ScriptedAnthropic {
        @Bean
        @Primary
        ScriptedLlmClient scriptedLlm() {
            return new ScriptedLlmClient().servingAs("anthropic", SMALL);
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
                        .content("{\"message\":\"" + question + "\"}")
                        .with(http -> {
                            http.setRemoteAddr("10.9.0." + CLIENTS.incrementAndGet());
                            return http;
                        }))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private ChatRun recordedRun(String question) {
        return runs.findAll().stream().filter(run -> run.getQuestion().equals(question)).findFirst().orElseThrow();
    }

    private ResultActions traceOf(String question) throws Exception {
        return mvc.perform(get("/api/runs/{id}/trace", recordedRun(question).getId())).andExpect(status().isOk());
    }

    @Test
    void aComplexQuestionIsAnsweredAndPaidAtTheLargerModelsPrices() throws Exception {
        var question = "Which leases end in the next 60 days and require 90 days notice? (complex)";
        llm.thenText("Two leases.");

        var sse = chat(question);

        assertThat(sse).contains("\"model\":\"" + LARGE + "\"").contains("\"routedReason\":\"COMPLEX\"")
                .contains("\"costUsd\":0.0006");
        var run = recordedRun(question);
        assertThat(run.getModel()).isEqualTo(LARGE);
        assertThat(run.getRoutedReason()).isEqualTo("COMPLEX");
        assertThat(run.getComplexityScore()).isEqualTo(4);
        assertThat(run.getComplexityRules()).isEqualTo("SEVERAL_DATA_AREAS,WINDOW_WITH_LEASE_TERM");
        // 100 input tokens x 3.00 + 20 output tokens x 15.00 = 600 per million
        assertThat(run.getCostUsd()).isEqualByComparingTo("0.000600");
        assertThat(llm.requests).extracting("model").containsExactly(LARGE);
    }

    @Test
    void theTraceShowsWhyTheModelWasChosen() throws Exception {
        var question = "Which leases end in the next 60 days and require 90 days notice? (trace)";
        llm.thenText("Two leases.");

        chat(question);

        traceOf(question)
                .andExpect(jsonPath("$.model").value(LARGE))
                .andExpect(jsonPath("$.routedReason").value("COMPLEX"))
                .andExpect(jsonPath("$.complexity.score").value(4))
                .andExpect(jsonPath("$.complexity.rules", contains("SEVERAL_DATA_AREAS", "WINDOW_WITH_LEASE_TERM")))
                .andExpect(jsonPath("$.llmCalls[0].model").value(LARGE));
    }

    @Test
    void aSimpleQuestionIsAnsweredByTheDefaultModelAtItsPrices() throws Exception {
        var question = "Who is more than 30 days late on rent? (simple)";
        llm.thenText("Three tenants.");

        var sse = chat(question);

        assertThat(sse).contains("\"model\":\"" + SMALL + "\"").contains("\"routedReason\":\"DEFAULT\"");
        var run = recordedRun(question);
        assertThat(run.getModel()).isEqualTo(SMALL);
        assertThat(run.getComplexityScore()).isZero();
        assertThat(run.getComplexityRules()).isEmpty();
        // 100 input tokens x 1.00 + 20 output tokens x 5.00 = 200 per million
        assertThat(run.getCostUsd()).isEqualByComparingTo("0.000200");
        traceOf(question).andExpect(jsonPath("$.complexity.score").value(0))
                .andExpect(jsonPath("$.complexity.rules", hasSize(0)));
    }
}
