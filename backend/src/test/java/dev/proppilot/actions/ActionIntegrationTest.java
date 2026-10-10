package dev.proppilot.actions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.admin.DemoResetService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base for tests that approve actions against the real seeded database. The shared data is put back to the seed before
 * and after every test, with the same reset the public demo uses every night.
 *
 * <p>Tenant 7 is the seeded "chronic" non-payer: the invoices of the last four months are unpaid. The caps of the
 * public demo are lifted here; {@link ActionLimitsIT} covers them.
 */
@SpringBootTest(properties = {"proppilot.actions.decisions-per-minute=1000", "proppilot.actions.max-approvals-per-day=1000"})
abstract class ActionIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    protected ActionService actions;
    @Autowired
    protected DemoResetService demo;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected Clock clock;
    @Autowired
    protected DataSource dataSource;

    protected String unit;
    protected BigDecimal rent;

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

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected ObjectNode paymentArgs(String amount) {
        return json.createObjectNode().put("unit_code", unit).put("amount", new BigDecimal(amount)).put("method", "CASH");
    }

    protected ActionView proposePayment(String amount) {
        return actions.propose(ActionKind.RECORD_PAYMENT, paymentArgs(amount), null);
    }

    protected ObjectNode edits() {
        return json.createObjectNode();
    }

    /** Every invoice of the lease, oldest first. */
    protected List<Map<String, Object>> invoicesOfTenant7() {
        return jdbc.queryForList("""
                select p.id, p.period, p.amount, p.paid_amount, p.paid_on, p.method, p.recorded_action_id
                from payments p join leases l on l.id = p.lease_id
                where l.tenant_id = 7 order by p.period""");
    }

    protected BigDecimal outstandingOfTenant7() {
        return jdbc.queryForObject("""
                select coalesce(sum(p.amount - p.paid_amount), 0)
                from payments p join leases l on l.id = p.lease_id
                where l.tenant_id = 7 and p.paid_on is null and p.due_date <= ?""", BigDecimal.class, today());
    }

    /** The audit log, oldest first. */
    protected List<Map<String, Object>> audit() {
        return jdbc.queryForList("select event, actor, action_id, entity_type, entity_id, before, after, note from audit_log order by id");
    }

    protected List<String> auditEvents() {
        return audit().stream().map(row -> (String) row.get("event")).toList();
    }

    /** A jsonb column read through JdbcTemplate arrives as a PGobject; this is its content as a tree. */
    protected JsonNode jsonOf(Object column) {
        try {
            return json.readTree(column.toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    protected Map<String, Object> pendingActionRow(UUID id) {
        return jdbc.queryForMap("select * from pending_actions where id = ?", id);
    }

    protected int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    protected LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Blocks until some statement waits for a lock held by another transaction, so a test can release it then. */
    protected void waitUntilAStatementWaitsForALock() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock'", Integer.class);
            if (waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no statement ever waited for a lock");
    }
}
