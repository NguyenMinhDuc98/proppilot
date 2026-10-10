package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.tools.MessageDrafter;
import dev.proppilot.agent.tools.MessageDrafter.Drafted;
import dev.proppilot.agent.tools.MessageDrafter.Language;
import dev.proppilot.agent.tools.MessageDrafter.Purpose;
import dev.proppilot.agent.tools.ToolArgs;
import dev.proppilot.agent.tools.ToolInputException;
import dev.proppilot.domain.Lease;
import dev.proppilot.domain.OutboxMessage;
import dev.proppilot.domain.OutboxMessageRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Queues a message to a tenant in the outbox. Nothing is ever delivered: the row stays {@code delivered = false}. */
@Component
class SendTenantReminderExecutor implements ActionExecutor<SendTenantReminderExecutor.ReminderArgs> {

    record ReminderArgs(Lease lease, Purpose purpose, Language language) {

        String unitCode() {
            return lease.getUnit().getCode();
        }
    }

    private final LeaseTargets targets;
    private final MessageDrafter drafter;
    private final OutboxMessageRepository outbox;
    private final ObjectMapper json;

    SendTenantReminderExecutor(LeaseTargets targets, MessageDrafter drafter, OutboxMessageRepository outbox,
                               ObjectMapper json) {
        this.targets = targets;
        this.drafter = drafter;
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public ActionKind kind() {
        return ActionKind.SEND_TENANT_REMINDER;
    }

    @Override
    public ReminderArgs validate(JsonNode rawArgs) {
        var in = new ToolArgs(rawArgs);
        var purpose = in.enumValue("purpose", Purpose.class).orElseThrow(() -> new ToolInputException("purpose is required"));
        var lease = targets.resolve(in);
        var language = in.enumValue("language", Language.class)
                .orElse("ar".equals(lease.getTenant().getPreferredLanguage()) ? Language.AR : Language.EN);
        var args = new ReminderArgs(lease, purpose, language);
        draft(args);
        return args;
    }

    private Drafted.Message draft(ReminderArgs args) {
        return switch (drafter.draft(args.lease(), args.purpose(), Optional.of(args.language()))) {
            case Drafted.NoOverdueRent none -> throw new ActionException(ActionError.NO_OVERDUE_RENT, none.message());
            case Drafted.Message message -> message;
        };
    }

    @Override
    public JsonNode arguments(ReminderArgs args) {
        var node = json.createObjectNode();
        node.put("unit_code", args.unitCode());
        node.put("purpose", args.purpose().name());
        node.put("language", args.language().name());
        return node;
    }

    @Override
    public Summary summary(ReminderArgs args) {
        var tenant = args.lease().getTenant();
        return new Summary(
                "Queue a " + ActionLabels.purpose(args.purpose(), false) + " in " + ActionLabels.language(args.language(), false)
                        + " for " + tenant.getNameEn() + " (unit " + args.unitCode() + "). It is stored in the outbox; "
                        + "nothing is sent.",
                "إضافة رسالة " + ActionLabels.purpose(args.purpose(), true) + " باللغة "
                        + ActionLabels.language(args.language(), true) + " إلى " + tenant.getNameAr() + " (الوحدة "
                        + args.unitCode() + "). تُحفظ الرسالة في صندوق الصادر ولا يُرسل أي شيء.");
    }

    @Override
    public JsonNode preview(ReminderArgs args) {
        var message = draft(args);
        var node = json.createObjectNode();
        node.put("unitCode", message.unitCode());
        node.put("tenantEn", message.tenantEn());
        node.put("tenantAr", message.tenantAr());
        node.put("purpose", message.purpose().name());
        node.put("language", message.language().name());
        node.put("subject", message.subject());
        node.put("body", message.body());
        node.put("delivered", false);
        return node;
    }

    @Override
    public Execution execute(ReminderArgs args, ExecutionContext context) {
        // Written again from today's figures: the amount owed may have changed since the proposal was made.
        var message = draft(args);
        var saved = outbox.save(new OutboxMessage(UUID.randomUUID(), args.lease(), message.language().name().toLowerCase(),
                message.purpose().name(), message.subject(), message.body(), context.at(), context.actionId()));

        var state = json.createObjectNode();
        state.put("messageId", saved.getId().toString());
        state.put("unitCode", saved.getUnitCode());
        state.put("tenantEn", saved.getTenantNameEn());
        state.put("tenantAr", saved.getTenantNameAr());
        state.put("purpose", saved.getPurpose());
        state.put("language", saved.getLanguage());
        state.put("subject", saved.getSubject());
        state.put("body", saved.getBody());
        state.put("delivered", saved.isDelivered());
        return new Execution(state, "OUTBOX_MESSAGE", saved.getId().toString(), null, state);
    }
}
