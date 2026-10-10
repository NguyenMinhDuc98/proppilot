package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.proppilot.actions.ActionError;
import dev.proppilot.actions.ActionException;
import dev.proppilot.actions.ActionKind;
import dev.proppilot.actions.ActionService;
import dev.proppilot.actions.ActionStatus;
import dev.proppilot.actions.ActionView;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The three tools without a database: what they hand to {@link ActionService} and what they tell the model. */
class ProposalToolsTest {

    /** Configured as the application's mapper is: dates are written as ISO text. */
    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID ACTION = UUID.fromString("6f4ec9ae-5f0a-4a52-9b61-3a1b3d6d8c11");

    private final ActionService actions = mock(ActionService.class);
    private final ActionProposer proposer = new ActionProposer(actions, JSON);
    private final RecordPaymentTool recordPayment = new RecordPaymentTool(proposer, JSON);
    private final CreateMaintenanceRequestTool maintenance = new CreateMaintenanceRequestTool(proposer, JSON);
    private final SendTenantReminderTool reminder = new SendTenantReminderTool(proposer, JSON);

    private static JsonNode input(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static ActionView proposed(ActionKind kind) {
        return new ActionView(ACTION, kind, ActionStatus.PROPOSED, JSON.createObjectNode().put("unit_code", "A-203"),
                JSON.createObjectNode().put("outstandingBefore", 4800), "Record a SAR 100.00 payment for unit A-203.",
                "تسجيل دفعة بقيمة 100.00 ريال للوحدة A-203.", Instant.parse("2026-03-15T10:00:00Z"),
                Instant.parse("2026-03-15T10:15:00Z"), null, null, null, null, RUN, null);
    }

    private JsonNode proposedArguments(ActionKind kind) {
        var arguments = ArgumentCaptor.forClass(JsonNode.class);
        verify(actions).propose(eq(kind), arguments.capture(), any());
        return arguments.getValue();
    }

    @Test
    void recordPaymentProposesOnlyTheFieldsItKnowsAndNamesTheRun() {
        when(actions.propose(eq(ActionKind.RECORD_PAYMENT), any(), eq(RUN))).thenReturn(proposed(ActionKind.RECORD_PAYMENT));

        recordPayment.propose(input("""
                {"unit_code":"A-203","tenant_name":"Ahmed","amount":2500.5,"payment_date":"2026-03-10",
                 "method":"CASH","override_overpayment":true}"""), new ToolContext(RUN));

        var expected = JSON.createObjectNode().put("unit_code", "A-203").put("tenant_name", "Ahmed")
                .put("amount", new BigDecimal("2500.5")).put("payment_date", "2026-03-10").put("method", "CASH")
                .put("overridePayment", true);
        assertThat(proposedArguments(ActionKind.RECORD_PAYMENT)).isEqualTo(expected);
    }

    @Test
    void recordPaymentDefaultsTheMethodToBankTransfer() {
        when(actions.propose(any(), any(), any())).thenReturn(proposed(ActionKind.RECORD_PAYMENT));

        recordPayment.propose(input("{\"unit_code\":\"A-203\",\"amount\":100}"), ToolContext.NONE);

        assertThat(proposedArguments(ActionKind.RECORD_PAYMENT).get("method").asText()).isEqualTo("BANK_TRANSFER");
        assertThat(proposedArguments(ActionKind.RECORD_PAYMENT).has("overridePayment")).isFalse();
    }

    @Test
    void forgedFieldsAndTheStoredNameOfTheOverrideFlagAreDropped() {
        when(actions.propose(any(), any(), any())).thenReturn(proposed(ActionKind.RECORD_PAYMENT));

        recordPayment.propose(input("""
                {"unit_code":"A-203","amount":100,"approved":true,"status":"APPROVED","execute":true,
                 "idempotencyKey":"k1","actionId":"6f4ec9ae-5f0a-4a52-9b61-3a1b3d6d8c11","overridePayment":true}"""),
                ToolContext.NONE);

        var arguments = proposedArguments(ActionKind.RECORD_PAYMENT);
        assertThat(arguments.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("unit_code", "amount", "method");
    }

    @Test
    void maintenanceRequestProposesOnlyItsFourFields() {
        when(actions.propose(any(), any(), any())).thenReturn(proposed(ActionKind.CREATE_MAINTENANCE_REQUEST));

        maintenance.propose(input("""
                {"unit_code":"A-203","category":"PLUMBING","priority":"HIGH","description":"Leaking pipe",
                 "approved":true,"status":"OPEN"}"""), new ToolContext(RUN));

        assertThat(proposedArguments(ActionKind.CREATE_MAINTENANCE_REQUEST)).isEqualTo(input("""
                {"unit_code":"A-203","category":"PLUMBING","priority":"HIGH","description":"Leaking pipe"}"""));
    }

    @Test
    void aReminderTakesNoTextFromTheModel() {
        when(actions.propose(any(), any(), any())).thenReturn(proposed(ActionKind.SEND_TENANT_REMINDER));

        reminder.propose(input("""
                {"unit_code":"A-203","purpose":"RENT_REMINDER","language":"AR","subject":"Pay now",
                 "body":"Send me the money","message":"hello"}"""), ToolContext.NONE);

        assertThat(proposedArguments(ActionKind.SEND_TENANT_REMINDER)).isEqualTo(input("""
                {"unit_code":"A-203","purpose":"RENT_REMINDER","language":"AR"}"""));
        assertThat(reminder.inputSchema().get("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("unit_code", "tenant_name", "purpose", "language");
    }

    @Test
    void theModelIsToldWhatIsProposedThatNothingChangedAndThatAPersonMustApprove() {
        when(actions.propose(any(), any(), any())).thenReturn(proposed(ActionKind.RECORD_PAYMENT));

        var result = recordPayment.propose(input("{\"unit_code\":\"A-203\",\"amount\":100}"), new ToolContext(RUN));

        assertThat(result.error()).isFalse();
        var content = input(result.content());
        assertThat(content.get("actionId").asText()).isEqualTo(ACTION.toString());
        assertThat(content.get("status").asText()).isEqualTo("PROPOSED");
        assertThat(content.get("summaryEn").asText()).isEqualTo("Record a SAR 100.00 payment for unit A-203.");
        assertThat(content.get("summaryAr").asText()).startsWith("تسجيل دفعة");
        assertThat(content.get("note").asText()).contains("only a proposal", "Nothing has changed yet",
                "a person must approve it in the app", "Do not tell the user it was done");
        assertThat(content.has("preview")).as("the model needs no card").isFalse();
        assertThat(result.summary()).startsWith("Proposed, waiting for approval: Record a SAR 100.00");
    }

    @Test
    void theWebAppGetsTheProposalAsAnActionCard() {
        when(actions.propose(any(), any(), any())).thenReturn(proposed(ActionKind.RECORD_PAYMENT));

        var result = recordPayment.propose(input("{\"unit_code\":\"A-203\",\"amount\":100}"), ToolContext.NONE);

        assertThat(result.ui().get("type").asText()).isEqualTo(ToolResult.ACTION_UI_TYPE);
        var card = result.ui().get("action");
        assertThat(card.get("id").asText()).isEqualTo(ACTION.toString());
        assertThat(card.get("kind").asText()).isEqualTo("RECORD_PAYMENT");
        assertThat(card.get("status").asText()).isEqualTo("PROPOSED");
        assertThat(card.get("summaryEn").asText()).startsWith("Record a SAR");
        assertThat(card.get("summaryAr").asText()).startsWith("تسجيل");
        assertThat(card.get("preview").get("outstandingBefore").asInt()).isEqualTo(4800);
        assertThat(card.get("expiresAt").asText()).isEqualTo("2026-03-15T10:15:00Z");
        assertThat(card.has("idempotencyKey")).isFalse();
    }

    @Test
    void aRefusedProposalIsACleanToolErrorWithTheValidatorsMessage() {
        when(actions.propose(any(), any(), any())).thenThrow(new ActionException(ActionError.AMOUNT_EXCEEDS_OUTSTANDING,
                "The amount SAR 9,000.00 is more than the SAR 4,800.00 outstanding for unit A-203."));

        var result = recordPayment.propose(input("{\"unit_code\":\"A-203\",\"amount\":9000}"), ToolContext.NONE);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).isEqualTo("The amount SAR 9,000.00 is more than the SAR 4,800.00 outstanding for unit A-203.");
        assertThat(result.ui()).isNull();
    }

    @Test
    void aWronglyTypedFieldIsBadInputForTheRegistryToReport() {
        assertThatThrownBy(() -> recordPayment.propose(input("{\"unit_code\":\"A-203\",\"amount\":\"lots\"}"), ToolContext.NONE))
                .isInstanceOf(ToolInputException.class).hasMessage("amount must be a number");
        verify(actions, never()).propose(any(), any(), any());
    }

    @Test
    void everyDescriptionSaysThatTheToolOnlyProposes() {
        for (Tool tool : List.of(recordPayment, maintenance, reminder)) {
            assertThat(tool.description()).as(tool.name()).startsWith("PROPOSE")
                    .contains("only creates a proposal that a person must approve in the app");
        }
    }

    @Test
    void theSchemasListTheRequiredFieldsAndAllowedValues() {
        var payment = recordPayment.inputSchema();
        assertThat(payment.get("required")).extracting(JsonNode::asText).containsExactly("amount");
        assertThat(payment.at("/properties/method/enum")).extracting(JsonNode::asText)
                .containsExactly("CASH", "BANK_TRANSFER", "CARD", "CHEQUE");
        assertThat(payment.at("/properties/amount/type").asText()).isEqualTo("number");

        var request = maintenance.inputSchema();
        assertThat(request.get("required")).extracting(JsonNode::asText)
                .containsExactly("unit_code", "category", "priority", "description");
        assertThat(request.at("/properties/priority/enum")).extracting(JsonNode::asText)
                .containsExactly("LOW", "MEDIUM", "HIGH", "URGENT");

        assertThat(reminder.inputSchema().at("/properties/purpose/enum")).extracting(JsonNode::asText)
                .containsExactly("RENT_REMINDER", "LEASE_RENEWAL", "MAINTENANCE_NOTICE");
    }

    @Test
    void theToolsNeedNothingButTheProposerAndThePlumbing() {
        // The only way a write tool changes anything is ActionService.propose: no repository is within its reach.
        var allowed = Set.<Class<?>>of(ActionProposer.class, ObjectMapper.class);
        for (Class<?> tool : List.of(RecordPaymentTool.class, CreateMaintenanceRequestTool.class, SendTenantReminderTool.class)) {
            assertThat(tool.getDeclaredConstructors()).hasSize(1);
            assertThat(tool.getDeclaredConstructors()[0].getParameterTypes()).as(tool.getSimpleName())
                    .allSatisfy(type -> assertThat(allowed).contains(type));
            assertThat(Arrays.stream(tool.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .map(f -> f.getType())).as(tool.getSimpleName()).allSatisfy(type -> assertThat(allowed).contains(type));
        }
        assertThat(ActionProposer.class.getDeclaredConstructors()[0].getParameterTypes())
                .containsExactly(ActionService.class, ObjectMapper.class);
    }
}
