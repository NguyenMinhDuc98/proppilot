package dev.proppilot.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.actions.ActionKind;
import dev.proppilot.actions.ActionService;
import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.LlmCallRecord;
import dev.proppilot.agent.ToolCallRecord;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.chat.ChatRun;
import dev.proppilot.chat.ChatRunRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Tenant 7 is the seeded non-payer whose last four invoices are unpaid. */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "proppilot.admin.token=" + DemoResetIT.TOKEN,
        "proppilot.actions.max-approvals-per-day=1000"})
class DemoResetIT extends PostgresIntegrationTest {

    static final String TOKEN = "integration-test-admin-token-1234";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ActionService actions;
    @Autowired
    DemoResetService demo;
    @Autowired
    ObjectMapper json;
    @Autowired
    ChatRunRepository runs;

    private String unit;
    private BigDecimal rent;

    @BeforeEach
    void startFromTheSeed() {
        demo.reset();
        unit = jdbc.queryForObject("select u.code from leases l join units u on u.id = l.unit_id where l.tenant_id = 7", String.class);
        rent = jdbc.queryForObject("select monthly_rent from leases where tenant_id = 7", BigDecimal.class);
    }

    @AfterEach
    void leaveTheSeedBehind() {
        demo.reset();
    }

    private List<Map<String, Object>> payments() {
        return jdbc.queryForList("select * from payments order by id");
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private UUID propose(ActionKind kind, com.fasterxml.jackson.databind.node.ObjectNode args) {
        return actions.propose(kind, args, null).id();
    }

    private void approve(UUID id) {
        actions.approve(id, UUID.randomUUID().toString(), json.createObjectNode(), "visitor-test");
    }

    @Test
    void putsPaymentsBackExactlyAndEmptiesEverythingTheActionsWrote() throws Exception {
        var seeded = payments();
        approve(propose(ActionKind.RECORD_PAYMENT, json.createObjectNode().put("unit_code", unit)
                .put("amount", rent.multiply(BigDecimal.TWO).add(new BigDecimal("100.50"))).put("method", "BANK_TRANSFER")));
        approve(propose(ActionKind.CREATE_MAINTENANCE_REQUEST, json.createObjectNode().put("unit_code", unit)
                .put("category", "HVAC").put("priority", "URGENT").put("description", "No cooling in the living room")));
        approve(propose(ActionKind.SEND_TENANT_REMINDER, json.createObjectNode().put("unit_code", unit)
                .put("purpose", "RENT_REMINDER")));
        actions.reject(propose(ActionKind.RECORD_PAYMENT, json.createObjectNode().put("unit_code", unit)
                .put("amount", 10).put("method", "CASH")), "no", "visitor-test");
        propose(ActionKind.RECORD_PAYMENT, json.createObjectNode().put("unit_code", unit).put("amount", 20).put("method", "CASH"));
        assertThat(payments()).isNotEqualTo(seeded);
        var auditBefore = count("audit_log");

        mvc.perform(post("/api/admin/reset").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsRestored").value(3))
                .andExpect(jsonPath("$.maintenanceRequestsDeleted").value(1))
                .andExpect(jsonPath("$.outboxMessagesDeleted").value(1))
                .andExpect(jsonPath("$.actionsDeleted").value(5))
                .andExpect(jsonPath("$.auditEntriesDeleted").value(auditBefore));

        assertThat(payments()).isEqualTo(seeded);
        assertThat(count("maintenance_requests")).isZero();
        assertThat(count("outbox_messages")).isZero();
        assertThat(count("pending_actions")).isZero();
        var log = jdbc.queryForList("select event, actor, entity_type, entity_id, after from audit_log");
        assertThat(log).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("event", "RESET").containsEntry("actor", "admin").containsEntry("entity_type", "DEMO");
            assertThat(row.get("after").toString()).contains("\"paymentsRestored\": 3");
        });
    }

    @Test
    void theSnapshotTakenByTheMigrationIsTheSeededState() {
        assertThat(count("payments_seed")).isEqualTo(count("payments"));
        assertThat(jdbc.queryForObject("""
                select count(*) from payments p join payments_seed s on s.id = p.id
                where p.paid_amount = s.paid_amount and p.paid_on is not distinct from s.paid_on
                  and p.method is null and p.recorded_action_id is null""", Integer.class)).isEqualTo(count("payments"));
    }

    @Test
    void leavesTheStoredRunsAndTheirTracesAlone() {
        var at = Instant.parse("2026-10-01T08:00:00Z");
        var call = new LlmCallRecord(1, "claude-haiku-4-5-20251001", 100, 20, 0, 0, 500, StopReason.END_TURN,
                LlmCallRecord.Outcome.OK, null, at);
        var tool = new ToolCallRecord(1, "search_units", "{}", "3 units", false, 12, at);
        var result = new AgentResult("answer", new Usage(100, 20), 1, 1, AgentResult.Status.OK, null, List.of(call),
                List.of(tool));
        var id = UUID.randomUUID();
        runs.save(new ChatRun(id, at, "a question", "anthropic", "claude-haiku-4-5-20251001", result, List.of("search_units"),
                500, new BigDecimal("0.0003")));

        demo.reset();

        try {
            assertThat(count("chat_runs where id = '" + id + "'")).isEqualTo(1);
            assertThat(count("run_llm_calls where run_id = '" + id + "'")).isEqualTo(1);
            assertThat(count("run_tool_calls where run_id = '" + id + "'")).isEqualTo(1);
        } finally {
            jdbc.update("delete from chat_runs where id = ?", id);
        }
    }

    @Test
    void aSecondResetFindsNothingLeftToRestore() throws Exception {
        approve(propose(ActionKind.RECORD_PAYMENT, json.createObjectNode().put("unit_code", unit)
                .put("amount", 100).put("method", "CASH")));
        mvc.perform(post("/api/admin/reset").header("X-Admin-Token", TOKEN)).andExpect(status().isOk());

        mvc.perform(post("/api/admin/reset").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsRestored").value(0))
                .andExpect(jsonPath("$.actionsDeleted").value(0))
                .andExpect(jsonPath("$.auditEntriesDeleted").value(1));
    }

    @Test
    void refusesACallerWithoutTheAdminTokenAndChangesNothing() throws Exception {
        approve(propose(ActionKind.RECORD_PAYMENT, json.createObjectNode().put("unit_code", unit)
                .put("amount", 100).put("method", "CASH")));
        var changed = payments();

        mvc.perform(post("/api/admin/reset")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/reset").header("X-Admin-Token", "wrong-token-wrong-token-wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/reset").queryParam("X-Admin-Token", TOKEN)).andExpect(status().isForbidden());

        assertThat(payments()).isEqualTo(changed);
        assertThat(count("pending_actions")).isEqualTo(1);
    }
}
