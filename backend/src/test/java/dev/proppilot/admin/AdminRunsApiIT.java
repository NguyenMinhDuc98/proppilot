package dev.proppilot.admin;

import static org.hamcrest.Matchers.contains;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.SqlStatementCounter;
import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.chat.ChatRun;
import dev.proppilot.chat.ChatRunRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@SpringBootTest(properties = "proppilot.admin.token=" + AdminRunsApiIT.TOKEN)
class AdminRunsApiIT extends PostgresIntegrationTest {

    static final String TOKEN = "integration-test-admin-token-1234";

    @Autowired
    MockMvc mvc;
    @Autowired
    ChatRunRepository runs;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminRunsController controller;
    @Autowired
    EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void startWithNoRuns() {
        jdbc.update("delete from chat_runs");
    }

    private void saveRun(String question, Instant at, List<String> tools) {
        var result = new AgentResult("answer", new Usage(120, 45), tools.size(), 2, AgentResult.Status.OK);
        runs.save(new ChatRun(UUID.randomUUID(), at, question, "anthropic", "claude-haiku-4-5-20251001", result, tools, 850,
                new BigDecimal("0.000345")));
    }

    @Test
    void listsTheLatestRunsNewestFirstWithEverythingAnAdminNeeds() throws Exception {
        saveRun("older question", Instant.parse("2026-10-01T08:00:00Z"), List.of());
        saveRun("newest question", Instant.parse("2026-10-02T08:00:00Z"), List.of("search_units", "get_unit_details"));

        mvc.perform(get("/api/admin/runs").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].question").value("newest question"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].status").value("OK"))
                .andExpect(jsonPath("$[0].provider").value("anthropic"))
                .andExpect(jsonPath("$[0].model").value("claude-haiku-4-5-20251001"))
                .andExpect(jsonPath("$[0].toolNames").value(contains("search_units", "get_unit_details")))
                .andExpect(jsonPath("$[0].inputTokens").value(120))
                .andExpect(jsonPath("$[0].outputTokens").value(45))
                .andExpect(jsonPath("$[0].costUsd").value(0.000345))
                .andExpect(jsonPath("$[0].latencyMs").value(850))
                .andExpect(jsonPath("$[1].question").value("older question"))
                .andExpect(jsonPath("$[1].toolNames").isEmpty());
    }

    @Test
    void returnsFiftyRunsByDefaultAndHonoursTheLimit() throws Exception {
        IntStream.range(0, 60).forEach(i -> saveRun("question " + i, Instant.parse("2026-10-01T08:00:00Z").plusSeconds(i), List.of()));

        mvc.perform(get("/api/admin/runs").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(50)))
                .andExpect(jsonPath("$[0].question").value("question 59"));
        mvc.perform(get("/api/admin/runs?limit=3").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void loadsTheToolNamesOfAllRunsWithOneQueryNoMatterHowManyRuns() {
        IntStream.range(0, 20).forEach(i -> saveRun("question " + i, Instant.parse("2026-10-01T08:00:00Z").plusSeconds(i),
                List.of("search_units", "get_payment_history")));
        var statements = new SqlStatementCounter(entityManagerFactory);

        long forThree = statements.during(() -> controller.runs(3));
        long forTwenty = statements.during(() -> controller.runs(20));

        assertThat(forThree).as("one query for the runs, one for all their tool names").isEqualTo(2);
        assertThat(forTwenty).isEqualTo(forThree);
    }

    @Test
    void rejectsALimitOutsideOneToOneHundred() throws Exception {
        mvc.perform(get("/api/admin/runs?limit=0").header("X-Admin-Token", TOKEN)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/runs?limit=101").header("X-Admin-Token", TOKEN)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/runs?limit=many").header("X-Admin-Token", TOKEN)).andExpect(status().isBadRequest());
    }

    @Test
    void refusesRequestsWithoutTheRightToken() throws Exception {
        saveRun("private question", Instant.parse("2026-10-01T08:00:00Z"), List.of());

        mvc.perform(get("/api/admin/runs")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/runs").header("X-Admin-Token", "wrong-token-wrong-token-wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/runs").header("X-Admin-Token", ""))
                .andExpect(status().isForbidden());
    }

    @Test
    void doesNotAcceptTheTokenInTheQueryString() throws Exception {
        mvc.perform(get("/api/admin/runs").queryParam("X-Admin-Token", TOKEN)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/runs").queryParam("token", TOKEN)).andExpect(status().isForbidden());
    }

    @Test
    void allowsTheFrontendToSendTheTokenHeaderAcrossOrigins() throws Exception {
        mvc.perform(options("/api/admin/runs")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-admin-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", "x-admin-token"));
    }

    @Test
    void checksTheTokenBeforeLookingAtAnyOtherParameter() throws Exception {
        mvc.perform(get("/api/admin/runs?limit=0")).andExpect(status().isForbidden());
    }
}
