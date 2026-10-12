package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.actions.ActionKind;
import org.springframework.stereotype.Component;

/**
 * Proposes queueing a message to a tenant. The wording always comes from a fixed template: the model chooses the
 * purpose and the language, never the text.
 */
@Component
public class SendTenantReminderTool implements ProposalTool {

    private final ActionProposer proposer;
    private final ObjectMapper json;

    SendTenantReminderTool(ActionProposer proposer, ObjectMapper json) {
        this.proposer = proposer;
        this.json = json;
    }

    @Override
    public String name() {
        return "send_tenant_reminder";
    }

    @Override
    public String description() {
        return "PROPOSE sending a rent reminder, lease renewal notice or maintenance notice to a tenant. This only "
                + "creates a proposal that a person must approve in the app, and even then the message is only stored "
                + "in an outbox, so never say it was sent. The wording always comes from a fixed template; you cannot "
                + "write or change it. A rent reminder needs the tenant to have overdue rent. Identify the tenant by "
                + "unit_code or tenant_name.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object()
                .string("unit_code", "Unit code such as A-203 (preferred, unambiguous)")
                .string("tenant_name", "Tenant name in English or Arabic (partial match)")
                .enumeration("purpose", MessageDrafter.PURPOSE_HELP, MessageDrafter.Purpose.class)
                .enumeration("language", "Language of the message; defaults to the tenant's preferred language",
                        MessageDrafter.Language.class)
                .required("purpose")
                .build();
    }

    @Override
    public ToolResult propose(JsonNode input, ToolContext context) {
        var args = new ToolArgs(input);
        var proposal = json.createObjectNode();
        args.string("unit_code").ifPresent(code -> proposal.put("unit_code", code));
        args.string("tenant_name").ifPresent(name -> proposal.put("tenant_name", name));
        args.string("purpose").ifPresent(purpose -> proposal.put("purpose", purpose));
        args.string("language").ifPresent(language -> proposal.put("language", language));
        return proposer.propose(ActionKind.SEND_TENANT_REMINDER, proposal, context);
    }
}
