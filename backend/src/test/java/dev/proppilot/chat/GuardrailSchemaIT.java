package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proppilot.PostgresIntegrationTest;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** What V9 added for the grounding guardrail: the table of a run's findings and the statuses a run may have. */
@Transactional
class GuardrailSchemaIT extends PostgresIntegrationTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void aRun() {
        jdbc.update("""
                insert into chat_runs (id, created_at, question, provider, model, input_tokens, output_tokens, tool_calls,
                                       iterations, latency_ms, cost_usd, status)
                values (?, now(), 'question', 'anthropic', 'claude-haiku-4-5-20251001', 100, 20, 1, 2, 800, 0.0003, 'OK')""",
                RUN);
    }

    private void insertFinding(UUID run, int seq, String kind, String claim) {
        jdbc.update("insert into run_guardrail_findings (run_id, seq, kind, claim) values (?, ?, ?, ?)", run, seq, kind,
                claim);
    }

    private void setStatus(String status) {
        jdbc.update("update chat_runs set guardrail_status = ? where id = ?", status, RUN);
    }

    private int findings() {
        return jdbc.queryForObject("select count(*) from run_guardrail_findings where run_id = ?", Integer.class, RUN);
    }

    @Test
    void aFindingHasItsRunItsPlaceItsKindAndItsClaim() {
        var described = new TreeMap<String, String>();
        for (var row : jdbc.queryForList("""
                select column_name, data_type, character_maximum_length, is_nullable
                from information_schema.columns where table_name = 'run_guardrail_findings'""")) {
            var length = row.get("character_maximum_length");
            described.put((String) row.get("column_name"), row.get("data_type") + (length == null ? "" : "(" + length + ")")
                    + ", " + ("YES".equals(row.get("is_nullable")) ? "null" : "not null"));
        }

        assertThat(described).containsOnly(
                Map.entry("run_id", "uuid, not null"),
                Map.entry("seq", "integer, not null"),
                Map.entry("kind", "character varying(16), not null"),
                Map.entry("claim", "character varying(40), not null"));
    }

    @Test
    void aRunHasOneFindingPerPlaceAndEveryKindOfClaimIsAccepted() {
        insertFinding(RUN, 1, "NUMBER", "4,950");
        insertFinding(RUN, 2, "UNIT_CODE", "A-204");
        insertFinding(RUN, 3, "TENANT_NAME", "Ahmed Al-Harbi");
        insertFinding(RUN, 4, "CITATION", "LSE-A-204#4");

        assertThat(findings()).isEqualTo(4);
        assertThatThrownBy(() -> insertFinding(RUN, 1, "NUMBER", "5,000")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aKindThatTheCheckerDoesNotHaveIsRefused() {
        assertThatThrownBy(() -> insertFinding(RUN, 1, "SENTIMENT", "bad")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aClaimLongerThanTheCutIsRefused() {
        insertFinding(RUN, 1, "CITATION", "x".repeat(40));

        assertThatThrownBy(() -> insertFinding(RUN, 2, "CITATION", "x".repeat(41)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFindingOfARunThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> insertFinding(UUID.randomUUID(), 1, "NUMBER", "1,000"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingARunDeletesItsFindings() {
        insertFinding(RUN, 1, "NUMBER", "4,950");
        insertFinding(RUN, 2, "NUMBER", "5,000");

        jdbc.update("delete from chat_runs where id = ?", RUN);

        assertThat(findings()).isZero();
    }

    @Test
    void aRunHasNoStatusOrOneOfTheFourTheGuardrailGives() {
        for (var status : new String[] {"OK", "FLAGGED", "RETRIED_OK", "RETRIED_FLAGGED"}) {
            setStatus(status);
        }

        jdbc.update("update chat_runs set guardrail_status = null where id = ?", RUN);
    }

    @Test
    void aStatusOtherThanTheFourIsRefused() {
        assertThatThrownBy(() -> setStatus("WARNED")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
