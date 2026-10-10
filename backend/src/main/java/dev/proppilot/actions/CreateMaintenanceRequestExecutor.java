package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.tools.ToolArgs;
import dev.proppilot.agent.tools.ToolInputException;
import dev.proppilot.domain.MaintenanceCategory;
import dev.proppilot.domain.MaintenancePriority;
import dev.proppilot.domain.MaintenanceRequest;
import dev.proppilot.domain.MaintenanceRequestRepository;
import dev.proppilot.domain.Unit;
import dev.proppilot.domain.UnitRepository;
import org.springframework.stereotype.Component;

/** Opens a maintenance request for a unit. */
@Component
class CreateMaintenanceRequestExecutor implements ActionExecutor<CreateMaintenanceRequestExecutor.RequestArgs> {

    static final int MIN_DESCRIPTION_LENGTH = 5;
    static final int MAX_DESCRIPTION_LENGTH = 500;

    record RequestArgs(Unit unit, MaintenanceCategory category, MaintenancePriority priority, String description) {
    }

    private final UnitRepository units;
    private final MaintenanceRequestRepository requests;
    private final ObjectMapper json;

    CreateMaintenanceRequestExecutor(UnitRepository units, MaintenanceRequestRepository requests, ObjectMapper json) {
        this.units = units;
        this.requests = requests;
        this.json = json;
    }

    @Override
    public ActionKind kind() {
        return ActionKind.CREATE_MAINTENANCE_REQUEST;
    }

    @Override
    public RequestArgs validate(JsonNode rawArgs) {
        var in = new ToolArgs(rawArgs);
        var code = in.requireString("unit_code");
        var unit = units.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new ActionException(ActionError.UNKNOWN_UNIT, "There is no unit with code '" + code + "'."));
        var category = in.enumValue("category", MaintenanceCategory.class)
                .orElseThrow(() -> new ToolInputException("category is required"));
        var priority = in.enumValue("priority", MaintenancePriority.class)
                .orElseThrow(() -> new ToolInputException("priority is required"));
        var description = PlainText.clean(in.requireString("description"));
        if (description.length() < MIN_DESCRIPTION_LENGTH || description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new ActionException(ActionError.INVALID_ARGUMENT, "description must be between " + MIN_DESCRIPTION_LENGTH
                    + " and " + MAX_DESCRIPTION_LENGTH + " characters.");
        }
        return new RequestArgs(unit, category, priority, description);
    }

    @Override
    public JsonNode arguments(RequestArgs args) {
        var node = json.createObjectNode();
        node.put("unit_code", args.unit().getCode());
        node.put("category", args.category().name());
        node.put("priority", args.priority().name());
        node.put("description", args.description());
        return node;
    }

    @Override
    public Summary summary(RequestArgs args) {
        var code = args.unit().getCode();
        return new Summary(
                "Create a maintenance request for unit " + code + " (" + ActionLabels.category(args.category(), false)
                        + ", " + ActionLabels.priority(args.priority(), false) + " priority): \"" + args.description() + "\".",
                "إنشاء طلب صيانة للوحدة " + code + " (" + ActionLabels.category(args.category(), true) + "، أولوية "
                        + ActionLabels.priority(args.priority(), true) + "): «" + args.description() + "».");
    }

    @Override
    public JsonNode preview(RequestArgs args) {
        var node = json.createObjectNode();
        node.put("unitCode", args.unit().getCode());
        node.put("building", args.unit().getBuilding().getName());
        node.put("category", args.category().name());
        node.put("priority", args.priority().name());
        node.put("description", args.description());
        node.put("status", "OPEN");
        return node;
    }

    @Override
    public Execution execute(RequestArgs args, ExecutionContext context) {
        var saved = requests.save(new MaintenanceRequest(args.unit(), args.category(), args.priority(),
                args.description(), context.at(), context.actionId()));

        var state = json.createObjectNode();
        state.put("requestId", saved.getId());
        state.put("unitCode", saved.getUnitCode());
        state.put("category", saved.getCategory().name());
        state.put("priority", saved.getPriority().name());
        state.put("description", saved.getDescription());
        state.put("status", saved.getStatus());
        return new Execution(state, "MAINTENANCE_REQUEST", String.valueOf(saved.getId()), null, state);
    }
}
