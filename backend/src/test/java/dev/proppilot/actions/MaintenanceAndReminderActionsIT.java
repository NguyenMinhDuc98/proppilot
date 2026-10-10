package dev.proppilot.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

class MaintenanceAndReminderActionsIT extends ActionIntegrationTest {

    private ObjectNode maintenanceArgs() {
        return json.createObjectNode().put("unit_code", unit).put("category", "PLUMBING").put("priority", "HIGH")
                .put("description", "Water is leaking under the kitchen sink");
    }

    private ObjectNode reminderArgs(String purpose) {
        return json.createObjectNode().put("unit_code", unit).put("purpose", purpose);
    }

    private ActionError refusal(ActionKind kind, ObjectNode args) {
        return Assertions.assertThrows(ActionException.class, () -> actions.propose(kind, args, null)).error();
    }

    @Test
    void aMaintenanceRequestIsOpenedOnlyWhenTheProposalIsApproved() {
        var args = maintenanceArgs().put("unit_code", unit.toLowerCase()).put("description", "  Leak\n under   the sink\t ");

        var proposed = actions.propose(ActionKind.CREATE_MAINTENANCE_REQUEST, args, null);

        assertThat(count("maintenance_requests")).isZero();
        assertThat(proposed.args().get("unit_code").asText()).as("the unit code is normalized").isEqualTo(unit);
        assertThat(proposed.args().get("description").asText()).isEqualTo("Leak under the sink");
        assertThat(proposed.summaryEn()).contains(unit).contains("plumbing").contains("high priority");
        assertThat(proposed.summaryAr()).contains(unit).contains("سباكة").contains("عالية");
        assertThat(proposed.preview().get("status").asText()).isEqualTo("OPEN");

        var approval = actions.approve(proposed.id(), newKey(), edits(), "visitor-test");

        var request = jdbc.queryForMap("select * from maintenance_requests");
        assertThat(request).containsEntry("unit_code", unit).containsEntry("category", "PLUMBING")
                .containsEntry("priority", "HIGH").containsEntry("description", "Leak under the sink")
                .containsEntry("status", "OPEN").containsEntry("action_id", proposed.id());
        assertThat(request.get("unit_id")).isEqualTo(jdbc.queryForObject("select id from units where code = ?", Long.class, unit));
        assertThat(approval.action().result().get("requestId").asLong()).isEqualTo((Long) request.get("id"));
        var approved = audit().get(audit().size() - 1);
        assertThat(approved).containsEntry("event", "APPROVED").containsEntry("entity_type", "MAINTENANCE_REQUEST")
                .containsEntry("entity_id", String.valueOf(request.get("id"))).containsEntry("actor", "visitor-test");
        assertThat(approved.get("before")).isNull();
        assertThat(jsonOf(approved.get("after")).get("category").asText()).isEqualTo("PLUMBING");
    }

    @Test
    void rejectingAMaintenanceProposalOpensNothing() {
        var proposed = actions.propose(ActionKind.CREATE_MAINTENANCE_REQUEST, maintenanceArgs(), null);

        actions.reject(proposed.id(), null, "visitor-test");

        assertThat(count("maintenance_requests")).isZero();
    }

    @Test
    void maintenanceArgumentsAreValidated() {
        var kind = ActionKind.CREATE_MAINTENANCE_REQUEST;

        assertThat(refusal(kind, maintenanceArgs().put("unit_code", "Z-999"))).isEqualTo(ActionError.UNKNOWN_UNIT);
        assertThat(refusal(kind, maintenanceArgs().put("category", "GARDENING"))).isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(refusal(kind, maintenanceArgs().put("priority", "WHENEVER"))).isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(refusal(kind, maintenanceArgs().put("description", "leak"))).isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(refusal(kind, maintenanceArgs().put("description", "x".repeat(501)))).isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(refusal(kind, maintenanceArgs().put("description", "  \n\t \u0007 "))).isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(refusal(kind, maintenanceArgs().without("unit_code"))).isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(count("pending_actions")).isZero();
    }

    @Test
    void aDescriptionOfFiveToFiveHundredCharactersIsAccepted() {
        var kind = ActionKind.CREATE_MAINTENANCE_REQUEST;

        assertThat(actions.propose(kind, maintenanceArgs().put("description", "12345"), null).status())
                .isEqualTo(ActionStatus.PROPOSED);
        assertThat(actions.propose(kind, maintenanceArgs().put("description", "y".repeat(500)), null).status())
                .isEqualTo(ActionStatus.PROPOSED);
    }

    @Test
    void aMaintenanceRequestCanBeOpenedForAVacantUnit() {
        var vacant = jdbc.queryForObject("select code from units where status = 'VACANT' limit 1", String.class);

        var proposed = actions.propose(ActionKind.CREATE_MAINTENANCE_REQUEST, maintenanceArgs().put("unit_code", vacant), null);

        assertThat(actions.approve(proposed.id(), newKey(), edits(), "visitor-test").action().status())
                .isEqualTo(ActionStatus.APPROVED);
    }

    @Test
    void aReminderIsOnlyStoredInTheOutboxAndNeverMarkedDelivered() {
        var args = reminderArgs("RENT_REMINDER").put("language", "en");

        var proposed = actions.propose(ActionKind.SEND_TENANT_REMINDER, args, null);

        assertThat(count("outbox_messages")).isZero();
        assertThat(proposed.preview().get("subject").asText()).startsWith("Friendly reminder");
        assertThat(proposed.preview().get("body").asText()).contains("Dear Ahmed Al-Harbi").contains("4 month(s)");
        assertThat(proposed.preview().get("delivered").asBoolean()).isFalse();
        assertThat(proposed.summaryEn()).contains("rent reminder").contains("English").contains("nothing is sent");
        assertThat(proposed.summaryAr()).contains("تذكير بالإيجار").contains("الإنجليزية").contains(unit);

        var approval = actions.approve(proposed.id(), newKey(), edits(), "visitor-test");

        var message = jdbc.queryForMap("select * from outbox_messages");
        assertThat(message).containsEntry("delivered", false).containsEntry("unit_code", unit)
                .containsEntry("tenant_name_en", "Ahmed Al-Harbi").containsEntry("tenant_name_ar", "أحمد الحربي")
                .containsEntry("language", "en").containsEntry("purpose", "RENT_REMINDER")
                .containsEntry("action_id", proposed.id());
        assertThat(message.get("subject")).isEqualTo(proposed.preview().get("subject").asText());
        assertThat(message.get("body")).isEqualTo(proposed.preview().get("body").asText());
        assertThat(approval.action().result().get("delivered").asBoolean()).isFalse();
        var approved = audit().get(audit().size() - 1);
        assertThat(approved).containsEntry("event", "APPROVED").containsEntry("entity_type", "OUTBOX_MESSAGE")
                .containsEntry("entity_id", message.get("id").toString());
    }

    @Test
    void theLanguageDefaultsToTheTenantsPreferredOne() {
        var preferred = jdbc.queryForObject("select preferred_language from tenants where id = 7", String.class);
        var other = jdbc.queryForObject("""
                select u.code from leases l join units u on u.id = l.unit_id
                join tenants t on t.id = l.tenant_id where t.preferred_language <> ? limit 1""", String.class, preferred);

        var ofTenant7 = actions.propose(ActionKind.SEND_TENANT_REMINDER, reminderArgs("RENT_REMINDER"), null);
        var ofOther = actions.propose(ActionKind.SEND_TENANT_REMINDER,
                json.createObjectNode().put("unit_code", other).put("purpose", "LEASE_RENEWAL"), null);

        assertThat(ofTenant7.args().get("language").asText()).isEqualTo(preferred.toUpperCase());
        assertThat(ofOther.args().get("language").asText()).isNotEqualTo(preferred.toUpperCase());
    }

    @Test
    void aTenantCanBeNamedInsteadOfTheirUnit() {
        var args = json.createObjectNode().put("tenant_name", "أحمد الحربي").put("purpose", "LEASE_RENEWAL").put("language", "AR");

        var proposed = actions.propose(ActionKind.SEND_TENANT_REMINDER, args, null);

        assertThat(proposed.args().get("unit_code").asText()).isEqualTo(unit);
        assertThat(proposed.preview().get("subject").asText()).contains("تجديد");
    }

    @Test
    void aRentReminderNeedsOverdueRent() {
        var paidUp = jdbc.queryForObject("""
                select u.code from leases l join units u on u.id = l.unit_id
                where not exists (select 1 from payments p where p.lease_id = l.id and p.paid_on is null) limit 1""", String.class);

        var args = json.createObjectNode().put("unit_code", paidUp).put("purpose", "RENT_REMINDER");

        assertThat(refusal(ActionKind.SEND_TENANT_REMINDER, args)).isEqualTo(ActionError.NO_OVERDUE_RENT);
        assertThat(actions.propose(ActionKind.SEND_TENANT_REMINDER, args.put("purpose", "MAINTENANCE_NOTICE"), null).status())
                .isEqualTo(ActionStatus.PROPOSED);
    }

    @Test
    void reminderTargetsAreResolvedWithTheToolRules() {
        var kind = ActionKind.SEND_TENANT_REMINDER;
        var vacant = jdbc.queryForObject("select code from units where status = 'VACANT' limit 1", String.class);

        assertThat(refusal(kind, reminderArgs("RENT_REMINDER").put("unit_code", vacant))).isEqualTo(ActionError.NO_ACTIVE_LEASE);
        assertThat(refusal(kind, reminderArgs("RENT_REMINDER").put("tenant_name", "Nobody Atall"))).isEqualTo(ActionError.TENANT_UNIT_MISMATCH);
        assertThat(refusal(kind, json.createObjectNode().put("tenant_name", "Nobody Atall").put("purpose", "LEASE_RENEWAL")))
                .isEqualTo(ActionError.TENANT_NOT_FOUND);
        assertThat(refusal(kind, json.createObjectNode().put("tenant_name", "Ahmed").put("purpose", "LEASE_RENEWAL")))
                .isEqualTo(ActionError.AMBIGUOUS_TENANT);
        assertThat(refusal(kind, json.createObjectNode().put("purpose", "LEASE_RENEWAL"))).isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(refusal(kind, reminderArgs("RENT_REMINDER").put("unit_code", "oops"))).isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(refusal(kind, reminderArgs("SHOUT"))).isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(refusal(kind, json.createObjectNode().put("unit_code", unit))).isEqualTo(ActionError.INVALID_ARGUMENT);
    }

    @Test
    void aReminderForRentThatWasPaidInTheMeantimeFailsAtApproval() {
        var reminder = actions.propose(ActionKind.SEND_TENANT_REMINDER, reminderArgs("RENT_REMINDER"), null);
        var paymentInFull = proposePayment(rent.multiply(BigDecimal.valueOf(4)).toPlainString());
        actions.approve(paymentInFull.id(), newKey(), edits(), "visitor-test");

        var thrown = Assertions.assertThrows(ActionException.class,
                () -> actions.approve(reminder.id(), newKey(), edits(), "visitor-test"));

        assertThat(thrown.error()).isEqualTo(ActionError.NO_OVERDUE_RENT);
        assertThat(actions.get(reminder.id()).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(count("outbox_messages")).isZero();
    }

    @Test
    void theMessageIsWrittenFromTheFiguresOfTheMomentItIsApproved() {
        var reminder = actions.propose(ActionKind.SEND_TENANT_REMINDER, reminderArgs("RENT_REMINDER").put("language", "EN"), null);
        actions.approve(proposePayment(rent.toPlainString()).id(), newKey(), edits(), "visitor-test");

        actions.approve(reminder.id(), newKey(), edits(), "visitor-test");

        var body = jdbc.queryForObject("select body from outbox_messages", String.class);
        assertThat(body).contains("3 month(s)").contains("SAR " + rent.multiply(BigDecimal.valueOf(3)).stripTrailingZeros().toPlainString());
    }

    @Test
    void aReminderProposalCannotBeEdited() {
        var reminder = actions.propose(ActionKind.SEND_TENANT_REMINDER, reminderArgs("RENT_REMINDER"), null);

        var thrown = Assertions.assertThrows(ActionException.class,
                () -> actions.approve(reminder.id(), newKey(), edits().put("amount", 5), "visitor-test"));

        assertThat(thrown.error()).isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(actions.get(reminder.id()).status()).isEqualTo(ActionStatus.PROPOSED);
    }
}
