package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Produces a message draft for the manager to review. It never sends anything. */
@Component
public class DraftTenantMessageTool implements Tool {

    private final TenantResolver resolver;
    private final MessageDrafter drafter;
    private final ObjectMapper json;

    DraftTenantMessageTool(TenantResolver resolver, MessageDrafter drafter, ObjectMapper json) {
        this.resolver = resolver;
        this.drafter = drafter;
        this.json = json;
    }

    @Override
    public String name() {
        return "draft_tenant_message";
    }

    @Override
    public String description() {
        return "Draft a polite message to a tenant (rent reminder, lease renewal or maintenance notice) in English or "
                + "Arabic. Returns a draft for the manager to review; it never sends anything. "
                + "A rent reminder needs the tenant to have overdue rent.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object()
                .string("unit_code", "Unit code such as A-203 (preferred, unambiguous)")
                .string("tenant_name", "Tenant name in English or Arabic (partial match)")
                .enumeration("purpose", MessageDrafter.PURPOSE_HELP, MessageDrafter.Purpose.class)
                .enumeration("language", "Language of the draft; defaults to the tenant's preferred language", "EN", "AR")
                .required("purpose")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode input) {
        var args = new ToolArgs(input);
        var purpose = args.enumValue("purpose", MessageDrafter.Purpose.class)
                .orElseThrow(() -> new ToolInputException("purpose is required"));
        var language = args.enumValue("language", MessageDrafter.Language.class);

        var resolution = resolver.resolve(args.string("tenant_name"), args.string("unit_code"));
        if (resolution instanceof TenantResolver.Resolution.Failed failed) {
            return failed.toolResult();
        }
        var lease = ((TenantResolver.Resolution.Found) resolution).lease();

        return switch (drafter.draft(lease, purpose, language)) {
            case MessageDrafter.Drafted.NoOverdueRent none -> ToolResult.error(none.message());
            case MessageDrafter.Drafted.Message message -> {
                var data = new Result(message.tenantEn(), message.tenantAr(), message.unitCode(),
                        message.language().name().toLowerCase(), purpose.name(), message.subject(), message.body(), false,
                        "Draft only. Nothing has been sent; the manager must review and send it.");
                yield ToolResult.ok(json, data, "Drafted " + purpose.name().toLowerCase().replace('_', ' ') + " (" + data.language() + ")");
            }
        };
    }

    record Result(String tenantEn, String tenantAr, String unitCode, String language, String purpose,
                  String subject, String body, boolean sent, String note) {
    }
}
