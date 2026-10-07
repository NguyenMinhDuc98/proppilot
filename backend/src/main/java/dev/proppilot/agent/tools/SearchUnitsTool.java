package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.domain.UnitStatus;
import dev.proppilot.units.UnitFilter;
import dev.proppilot.units.UnitService;
import dev.proppilot.units.UnitView;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SearchUnitsTool implements Tool {

    private static final int LIMIT = 15;

    private final UnitService units;
    private final ObjectMapper json;

    public SearchUnitsTool(UnitService units, ObjectMapper json) {
        this.units = units;
        this.json = json;
    }

    @Override
    public String name() {
        return "search_units";
    }

    @Override
    public String description() {
        return "Search rental units. All filters are optional and combined with AND. "
                + "Returns the total number of matches and up to " + LIMIT + " units.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object()
                .string("city", "City name in English or Arabic, e.g. Riyadh, Jeddah, Dammam")
                .string("building", "Building code, a single letter A-F")
                .enumeration("status", "Unit status", "VACANT", "OCCUPIED", "MAINTENANCE")
                .integer("bedrooms", "Exact number of bedrooms (1-4)")
                .number("min_rent", "Minimum monthly rent in SAR")
                .number("max_rent", "Maximum monthly rent in SAR")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode input) {
        var args = new ToolArgs(input);
        var filter = new UnitFilter(
                args.string("city").orElse(null),
                args.string("building").orElse(null),
                args.enumValue("status", UnitStatus.class).orElse(null),
                args.integer("bedrooms", 0, 10).orElse(null),
                args.decimal("min_rent").orElse(null),
                args.decimal("max_rent").orElse(null));

        var page = units.search(filter, 0, LIMIT);
        List<UnitView> shown = page.getContent();
        var data = new Result(page.getTotalElements(), shown.size(), shown);
        return ToolResult.ok(json, data, data.total() + " units found");
    }

    record Result(long total, int shown, List<UnitView> units) {
    }
}
