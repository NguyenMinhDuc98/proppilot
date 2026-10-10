package dev.proppilot.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.actions.AuditTrail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Puts the shared demo data back to how the migrations seeded it, so strangers' changes never pile up. */
@Service
public class DemoResetService {

    static final String ACTOR = "admin";

    private final JdbcTemplate jdbc;
    private final AuditTrail audit;
    private final ObjectMapper json;

    public DemoResetService(JdbcTemplate jdbc, AuditTrail audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public ResetView reset() {
        // An approval locks its action and then the payments it pays; taking the locks in that order keeps a reset
        // that runs next to an approval from deadlocking with it.
        jdbc.queryForList("select id from pending_actions for update");
        int payments = jdbc.update("""
                update payments p
                set paid_amount = s.paid_amount, paid_on = s.paid_on, method = null, recorded_action_id = null
                from payments_seed s
                where s.id = p.id
                  and (p.paid_amount <> s.paid_amount or p.paid_on is distinct from s.paid_on
                       or p.method is not null or p.recorded_action_id is not null)""");
        int requests = jdbc.update("delete from maintenance_requests");
        int messages = jdbc.update("delete from outbox_messages");
        int actions = jdbc.update("delete from pending_actions");
        int entries = jdbc.update("delete from audit_log");

        var counts = new ResetView(payments, requests, messages, actions, entries);
        audit.reset(ACTOR, json.valueToTree(counts));
        return counts;
    }
}
