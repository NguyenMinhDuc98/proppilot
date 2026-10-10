package dev.proppilot.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proppilot.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** What V4 lets into the tables: the check constraints and keys that guard the write actions. Rolled back after each test. */
@Transactional
class ActionsSchemaIT extends PostgresIntegrationTest {

    private static final UUID ACTION = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    JdbcTemplate jdbc;

    private long unitId;

    @BeforeEach
    void anExistingProposal() {
        insertAction(ACTION, "RECORD_PAYMENT", "PROPOSED", null);
        unitId = jdbc.queryForObject("select id from units limit 1", Long.class);
    }

    private void insertAction(UUID id, String kind, String status, String key) {
        jdbc.update("""
                insert into pending_actions (id, kind, status, args, preview, summary_en, summary_ar, created_at, expires_at,
                                             idempotency_key)
                values (?, ?, ?, '{}', '{}', 'en', 'ar', now(), now() + interval '15 minutes', ?)""", id, kind, status, key);
    }

    private void insertMaintenance(String category, String priority, UUID action) {
        jdbc.update("""
                insert into maintenance_requests (unit_id, unit_code, category, priority, description, created_at, action_id)
                values (?, 'A-101', ?, ?, 'Leaking tap', now(), ?)""", unitId, category, priority, action);
    }

    private void insertOutbox(String language, String purpose, UUID action) {
        jdbc.update("""
                insert into outbox_messages (id, lease_id, unit_code, tenant_name_en, tenant_name_ar, language, purpose,
                                             subject, body, created_at, action_id)
                select ?, id, 'A-101', 'Name', 'اسم', ?, ?, 's', 'b', now(), ? from leases limit 1""",
                UUID.randomUUID(), language, purpose, action);
    }

    @Test
    void acceptsAValidActionAndGivesTheOtherTablesTheirDefaults() {
        insertMaintenance("PLUMBING", "HIGH", ACTION);
        insertAction(UUID.fromString("00000000-0000-0000-0000-000000000002"), "SEND_TENANT_REMINDER", "APPROVED", "key-1");
        insertOutbox("ar", "RENT_REMINDER", UUID.fromString("00000000-0000-0000-0000-000000000002"));

        assertThat(jdbc.queryForObject("select status from maintenance_requests", String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select delivered from outbox_messages", Boolean.class)).isFalse();
    }

    @Test
    void keepsASnapshotOfTheSeededPayments() {
        assertThat(jdbc.queryForObject("select count(*) from payments_seed", Integer.class))
                .isEqualTo(jdbc.queryForObject("select count(*) from payments", Integer.class)).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELL_UNIT", "record_payment", ""})
    void refusesAnUnknownKind(String kind) {
        assertThatThrownBy(() -> insertAction(UUID.randomUUID(), kind, "PROPOSED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DONE", "approved", ""})
    void refusesAnUnknownStatus(String status) {
        assertThatThrownBy(() -> insertAction(UUID.randomUUID(), "RECORD_PAYMENT", status, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesTheSameIdempotencyKeyTwice() {
        insertAction(UUID.randomUUID(), "RECORD_PAYMENT", "APPROVED", "same-key");

        assertThatThrownBy(() -> insertAction(UUID.randomUUID(), "RECORD_PAYMENT", "APPROVED", "same-key"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAKeyLongerThanSixtyFourCharacters() {
        assertThatThrownBy(() -> insertAction(UUID.randomUUID(), "RECORD_PAYMENT", "APPROVED", "k".repeat(65)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAMaintenanceRequestWithAnUnknownCategory() {
        assertThatThrownBy(() -> insertMaintenance("GARDENING", "HIGH", ACTION))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAMaintenanceRequestWithAnUnknownPriority() {
        assertThatThrownBy(() -> insertMaintenance("PLUMBING", "SOON", ACTION))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAMaintenanceRequestOfAnActionThatDoesNotExist() {
        assertThatThrownBy(() -> insertMaintenance("PLUMBING", "HIGH", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anActionOpensAtMostOneMaintenanceRequest() {
        insertMaintenance("PLUMBING", "HIGH", ACTION);

        assertThatThrownBy(() -> insertMaintenance("HVAC", "LOW", ACTION))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAMaintenanceDescriptionOverFiveHundredCharacters() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into maintenance_requests (unit_id, unit_code, category, priority, description, created_at, action_id)
                values (?, 'A-101', 'OTHER', 'LOW', ?, now(), ?)""", unitId, "x".repeat(501), ACTION))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAnOutboxMessageInAnUnknownLanguage() {
        assertThatThrownBy(() -> insertOutbox("fr", "RENT_REMINDER", ACTION)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAnOutboxMessageForAnUnknownPurpose() {
        assertThatThrownBy(() -> insertOutbox("en", "SPAM", ACTION)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anActionQueuesAtMostOneMessage() {
        insertOutbox("en", "RENT_REMINDER", ACTION);

        assertThatThrownBy(() -> insertOutbox("en", "LEASE_RENEWAL", ACTION)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAnUnknownPaymentMethod() {
        assertThatThrownBy(() -> jdbc.update("update payments set method = 'BITCOIN' where id = (select min(id) from payments)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditEntriesNeedAnActorAnEventAndTheirEntity() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into audit_log (at, actor, event, entity_type, entity_id) values (now(), null, 'PROPOSED', 'T', '1')"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
