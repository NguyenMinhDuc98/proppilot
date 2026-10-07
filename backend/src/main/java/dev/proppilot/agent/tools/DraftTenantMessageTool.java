package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.domain.Lease;
import dev.proppilot.domain.Payment;
import dev.proppilot.domain.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** Produces a message draft for the manager to review. It never sends anything. */
@Component
public class DraftTenantMessageTool implements Tool {

    enum Purpose { RENT_REMINDER, LEASE_RENEWAL, MAINTENANCE_NOTICE }

    enum Language { EN, AR }

    private final TenantResolver resolver;
    private final PaymentRepository payments;
    private final Clock clock;
    private final ObjectMapper json;

    DraftTenantMessageTool(TenantResolver resolver, PaymentRepository payments, Clock clock, ObjectMapper json) {
        this.resolver = resolver;
        this.payments = payments;
        this.clock = clock;
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
                .enumeration("purpose", "What the message is about", "RENT_REMINDER", "LEASE_RENEWAL", "MAINTENANCE_NOTICE")
                .enumeration("language", "Language of the draft; defaults to the tenant's preferred language", "EN", "AR")
                .required("purpose")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode input) {
        var args = new ToolArgs(input);
        var purpose = args.enumValue("purpose", Purpose.class)
                .orElseThrow(() -> new ToolInputException("purpose is required"));
        var language = args.enumValue("language", Language.class);

        var resolution = resolver.resolve(args.string("tenant_name"), args.string("unit_code"));
        if (resolution instanceof TenantResolver.Resolution.Failed failed) {
            return ToolResult.error(failed.reason());
        }
        Lease lease = ((TenantResolver.Resolution.Found) resolution).lease();
        boolean arabic = language.map(l -> l == Language.AR)
                .orElse("ar".equals(lease.getTenant().getPreferredLanguage()));

        var overdue = payments.findByLeaseIdAndPaidOnIsNullAndDueDateBeforeOrderByPeriod(lease.getId(), LocalDate.now(clock));
        if (purpose == Purpose.RENT_REMINDER && overdue.isEmpty()) {
            return ToolResult.error("Unit " + lease.getUnit().getCode() + " has no overdue rent, so no reminder is needed.");
        }
        var context = new MessageTemplates.Context(
                arabic ? lease.getTenant().getNameAr() : lease.getTenant().getNameEn(),
                lease.getUnit().getCode(), lease.getUnit().getBuilding().getName(), overdue.size(),
                overdue.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                overdue.isEmpty() ? null : overdue.get(0).getDueDate(), lease.getEndDate());

        var draft = switch (purpose) {
            case RENT_REMINDER -> MessageTemplates.rentReminder(arabic, context);
            case LEASE_RENEWAL -> MessageTemplates.leaseRenewal(arabic, context);
            case MAINTENANCE_NOTICE -> MessageTemplates.maintenanceNotice(arabic, context);
        };
        var data = new Result(lease.getTenant().getNameEn(), lease.getTenant().getNameAr(), lease.getUnit().getCode(),
                arabic ? "ar" : "en", purpose.name(), draft.subject(), draft.body(), false,
                "Draft only. Nothing has been sent; the manager must review and send it.");
        return ToolResult.ok(json, data, "Drafted " + purpose.name().toLowerCase().replace('_', ' ') + " (" + data.language() + ")");
    }

    record Result(String tenantEn, String tenantAr, String unitCode, String language, String purpose,
                  String subject, String body, boolean sent, String note) {
    }
}
