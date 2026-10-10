package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.actions.ActionKind;
import dev.proppilot.domain.MaintenanceCategory;
import dev.proppilot.domain.MaintenancePriority;
import org.springframework.stereotype.Component;

/** Proposes opening a maintenance request. A person approves it before the request exists. */
@Component
public class CreateMaintenanceRequestTool implements ProposalTool {

    private final ActionProposer proposer;
    private final ObjectMapper json;

    CreateMaintenanceRequestTool(ActionProposer proposer, ObjectMapper json) {
        this.proposer = proposer;
        this.json = json;
    }

    @Override
    public String name() {
        return "create_maintenance_request";
    }

    @Override
    public String description() {
        return "PROPOSE opening a maintenance request for a unit. This only creates a proposal that a person must "
                + "approve in the app; no request exists until then, so never say it was created. Ask the user for "
                + "anything required that they did not say (unit, what is wrong, how urgent) instead of guessing.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object()
                .string("unit_code", "Unit code such as A-203")
                .enumeration("category", "What kind of problem it is", MaintenanceCategory.class)
                .enumeration("priority", "How urgent it is", MaintenancePriority.class)
                .string("description", "What is wrong, in one or two plain sentences (5 to 500 characters)")
                .required("unit_code", "category", "priority", "description")
                .build();
    }

    @Override
    public ToolResult propose(JsonNode input, ToolContext context) {
        var args = new ToolArgs(input);
        var proposal = json.createObjectNode();
        args.string("unit_code").ifPresent(code -> proposal.put("unit_code", code));
        args.string("category").ifPresent(category -> proposal.put("category", category));
        args.string("priority").ifPresent(priority -> proposal.put("priority", priority));
        args.string("description").ifPresent(description -> proposal.put("description", description));
        return proposer.propose(ActionKind.CREATE_MAINTENANCE_REQUEST, proposal, context);
    }
}
