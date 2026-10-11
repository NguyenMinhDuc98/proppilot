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

/** What V7 added: the columns of a run and the tables of its calls. Rolled back after each test. */
@Transactional
class TracesSchemaIT extends PostgresIntegrationTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void aRunWrittenTheWayV1ToV6Wrote() {
        jdbc.update("""
                insert into chat_runs (id, created_at, question, provider, model, input_tokens, output_tokens, tool_calls,
                                       iterations, latency_ms, cost_usd, status)
                values (?, now(), 'old question', 'anthropic', 'claude-haiku-4-5-20251001', 100, 20, 1, 2, 800, 0.0003, 'OK')""",
                RUN);
    }

    /** column name to "type, nullable, default" for one table. */
    private Map<String, String> columns(String table) {
        var rows = jdbc.queryForList("""
                select column_name, data_type, character_maximum_length, is_nullable, column_default
                from information_schema.columns where table_name = ?""", table);
        var described = new TreeMap<String, String>();
        for (var row : rows) {
            var length = row.get("character_maximum_length");
            var nullable = "YES".equals(row.get("is_nullable")) ? "null" : "not null";
            var fallback = row.get("column_default") == null ? "" : ", default " + row.get("column_default");
            described.put((String) row.get("column_name"),
                    row.get("data_type") + (length == null ? "" : "(" + length + ")") + ", " + nullable + fallback);
        }
        return described;
    }

    private void insertLlmCall(UUID run, int seq, String outcome) {
        jdbc.update("""
                insert into run_llm_calls (run_id, seq, model, input_tokens, output_tokens, latency_ms, outcome, started_at)
                values (?, ?, 'claude-haiku-4-5-20251001', 10, 5, 120, ?, now())""", run, seq, outcome);
    }

    private void insertToolCall(UUID run, int seq) {
        jdbc.update("""
                insert into run_tool_calls (run_id, seq, tool_name, args, summary, error, latency_ms)
                values (?, ?, 'search_units', '{}', '3 units', false, 12)""", run, seq);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where run_id = ?", Integer.class, RUN);
    }

    @Test
    void anOldRunKeepsItsFiguresAndGetsTheDefaultsOfTheNewColumns() {
        var run = jdbc.queryForMap("select * from chat_runs where id = ?", RUN);

        assertThat(run).containsEntry("input_tokens", 100).containsEntry("cache_read_tokens", 0)
                .containsEntry("cache_write_tokens", 0).containsEntry("guardrail_violations", 0)
                .containsEntry("routed_reason", null).containsEntry("guardrail_status", null);
    }

    @Test
    void chatRunsGainsTheCacheRoutingAndGuardrailColumns() {
        var columns = columns("chat_runs");

        assertThat(columns)
                .containsEntry("cache_read_tokens", "integer, not null, default 0")
                .containsEntry("cache_write_tokens", "integer, not null, default 0")
                .containsEntry("routed_reason", "character varying(24), null")
                .containsEntry("guardrail_status", "character varying(24), null")
                .containsEntry("guardrail_violations", "integer, not null, default 0");
    }

    @Test
    void theInputTokenColumnSaysThatItCountsUncachedTokensOnly() {
        var comment = jdbc.queryForObject("""
                select col_description('chat_runs'::regclass, attnum) from pg_attribute
                where attrelid = 'chat_runs'::regclass and attname = 'input_tokens'""", String.class);

        assertThat(comment).startsWith("Uncached input tokens only");
    }

    @Test
    void runLlmCallsHasTheColumnsOfAModelCall() {
        assertThat(columns("run_llm_calls")).containsOnly(
                Map.entry("run_id", "uuid, not null"),
                Map.entry("seq", "integer, not null"),
                Map.entry("model", "character varying(64), not null"),
                Map.entry("input_tokens", "integer, not null"),
                Map.entry("output_tokens", "integer, not null"),
                Map.entry("cache_read_tokens", "integer, not null, default 0"),
                Map.entry("cache_write_tokens", "integer, not null, default 0"),
                Map.entry("latency_ms", "bigint, not null"),
                Map.entry("stop_reason", "character varying(24), null"),
                Map.entry("outcome", "character varying(16), not null"),
                Map.entry("error_code", "character varying(32), null"),
                Map.entry("started_at", "timestamp with time zone, not null"));
    }

    @Test
    void runToolCallsHasTheColumnsOfAToolExecution() {
        assertThat(columns("run_tool_calls")).containsOnly(
                Map.entry("run_id", "uuid, not null"),
                Map.entry("seq", "integer, not null"),
                Map.entry("tool_name", "character varying(64), not null"),
                Map.entry("args", "text, not null"),
                Map.entry("summary", "text, not null"),
                Map.entry("error", "boolean, not null"),
                Map.entry("latency_ms", "bigint, not null"));
    }

    @Test
    void theToolNameTableThatTheStatisticsReadIsStillThere() {
        assertThat(columns("chat_run_tools")).containsKeys("run_id", "tool_name");
    }

    @Test
    void aRunHasOneCallPerSequenceNumber() {
        insertLlmCall(RUN, 1, "OK");
        insertToolCall(RUN, 1);

        assertThatThrownBy(() -> insertLlmCall(RUN, 1, "OK")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aToolCallRowKeepsItsPrimaryKeyApartFromTheModelCalls() {
        insertToolCall(RUN, 1);

        assertThatThrownBy(() -> insertToolCall(RUN, 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aModelCallOfARunThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> insertLlmCall(UUID.randomUUID(), 1, "OK"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aToolCallOfARunThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> insertToolCall(UUID.randomUUID(), 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anOutcomeOtherThanOkOrErrorIsRefused() {
        assertThatThrownBy(() -> insertLlmCall(RUN, 1, "MAYBE")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingARunDeletesItsCalls() {
        insertLlmCall(RUN, 1, "OK");
        insertLlmCall(RUN, 2, "ERROR");
        insertToolCall(RUN, 1);

        jdbc.update("delete from chat_runs where id = ?", RUN);

        assertThat(count("run_llm_calls")).isZero();
        assertThat(count("run_tool_calls")).isZero();
    }
}
