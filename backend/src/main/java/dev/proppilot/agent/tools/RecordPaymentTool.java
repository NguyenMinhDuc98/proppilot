package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.actions.ActionKind;
import dev.proppilot.domain.PaymentMethod;
import org.springframework.stereotype.Component;

/** Proposes recording a rent payment. A person approves it before any invoice changes. */
@Component
public class RecordPaymentTool implements ProposalTool {

    private final ActionProposer proposer;
    private final ObjectMapper json;

    RecordPaymentTool(ActionProposer proposer, ObjectMapper json) {
        this.proposer = proposer;
        this.json = json;
    }

    @Override
    public String name() {
        return "record_payment";
    }

    @Override
    public String description() {
        return "PROPOSE recording a rent payment received from a tenant. This only creates a proposal that a person "
                + "must approve in the app; nothing is recorded until then, so never say it was recorded. The money is "
                + "applied to the unit's oldest unpaid invoices first. Identify the tenant by unit_code or tenant_name. "
                + "Ask the user for the amount if it was not given. Set override_overpayment only when the user "
                + "explicitly asks to record more than is owed.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object()
                .string("unit_code", "Unit code such as A-203 (preferred, unambiguous)")
                .string("tenant_name", "Tenant name in English or Arabic (partial match)")
                .number("amount", "Amount received in SAR: more than 0, at most 2 decimal places")
                .string("payment_date", "Date the money was received, like 2026-03-15; defaults to today")
                .enumeration("method", "How it was paid; defaults to BANK_TRANSFER", PaymentMethod.class)
                .bool("override_overpayment", "Allow an amount above what is outstanding (default false)")
                .required("amount")
                .build();
    }

    @Override
    public ToolResult propose(JsonNode input, ToolContext context) {
        var args = new ToolArgs(input);
        var proposal = json.createObjectNode();
        args.string("unit_code").ifPresent(code -> proposal.put("unit_code", code));
        args.string("tenant_name").ifPresent(name -> proposal.put("tenant_name", name));
        args.signedDecimal("amount").ifPresent(amount -> proposal.put("amount", amount));
        args.string("payment_date").ifPresent(date -> proposal.put("payment_date", date));
        proposal.put("method", args.string("method").orElse(PaymentMethod.BANK_TRANSFER.name()));
        // The stored proposal calls the flag overridePayment, the name the approver may edit it under.
        args.bool("override_overpayment").ifPresent(allowed -> proposal.put("overridePayment", allowed));
        return proposer.propose(ActionKind.RECORD_PAYMENT, proposal, context);
    }
}
