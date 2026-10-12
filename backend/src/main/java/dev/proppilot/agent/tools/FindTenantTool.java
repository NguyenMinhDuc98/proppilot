package dev.proppilot.agent.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Resolves a name to the tenants it stands for, before anything is done with one of them. It is the same search the
 * other tools run when they are given a {@code tenant_name} (see {@link TenantResolver#candidates}), but it returns
 * every match and leaves the choice to the user. That is what a first name needs: the other tools refuse it, and a
 * model that tries to avoid the refusal by looking in a list of tenants first picks the one that is in the list.
 *
 * <p>The result says nothing about rent or payments, so there is no reason to choose between candidates.
 */
@Component
public class FindTenantTool implements Tool {

    /** The most tenants one call returns; {@code count} says how many matched. */
    static final int MAX_SHOWN = 10;
    private static final int MAX_NAME_LENGTH = 100;

    private final TenantResolver resolver;
    private final ObjectMapper json;

    FindTenantTool(TenantResolver resolver, ObjectMapper json) {
        this.resolver = resolver;
        this.json = json;
    }

    @Override
    public String name() {
        return "find_tenant";
    }

    @Override
    public String description() {
        return "Find the tenants whose name contains a text, in English or Arabic, and return each one's name in both "
                + "languages with the unit they rent. Call it first when the user names a tenant by a first name or "
                + "a part of a name, before any other tool, and again to learn which unit a tenant lives in. When it "
                + "returns more than one tenant, ask the user which one is meant: never choose one yourself. A tenant "
                + "that has no active lease is returned without a unit.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object()
                .string("tenant_name", "The name the user gave: a first name, a family name or a full name, in English "
                        + "or Arabic (partial match)")
                .required("tenant_name")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode input) {
        var name = new ToolArgs(input).requireString("tenant_name", MAX_NAME_LENGTH);
        var candidates = resolver.candidates(name);
        var shown = candidates.stream().limit(MAX_SHOWN).map(Match::of).toList();
        var data = new Result(candidates.size(), shown.size(), shown);
        return ToolResult.ok(json, data, switch (candidates.size()) {
            case 0 -> "No tenant matches";
            case 1 -> "1 tenant matches";
            default -> candidates.size() + " tenants match";
        });
    }

    /**
     * @param count how many tenants (and leases) matched, which is more than {@code shown} when the name was a short one
     * @param shown how many of them are in {@code tenants}
     */
    record Result(int count, int shown, List<Match> tenants) {
    }

    /** @param unitCode the unit of the active lease, absent when the tenant has none */
    record Match(String nameEn, String nameAr, @JsonInclude(JsonInclude.Include.NON_NULL) String unitCode,
                 boolean activeLease) {

        static Match of(TenantResolver.Candidate candidate) {
            var lease = candidate.activeLease();
            return new Match(candidate.tenant().getNameEn(), candidate.tenant().getNameAr(),
                    lease == null ? null : lease.getUnit().getCode(), lease != null);
        }
    }
}
