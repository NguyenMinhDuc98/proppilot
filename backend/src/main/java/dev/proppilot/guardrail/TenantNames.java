package dev.proppilot.guardrail;

import java.util.List;
import java.util.function.Supplier;

/**
 * The names of the tenants, which the guardrail looks for in an answer. They are read once, when the first answer is
 * checked, and again whenever {@link #refresh()} is called.
 */
public final class TenantNames {

    /** One tenant, by the name in each language. */
    public record Name(String english, String arabic) {
    }

    private final Supplier<List<Name>> source;
    private volatile TenantRoster roster;

    public TenantNames(Supplier<List<Name>> source) {
        this.source = source;
    }

    /** Reads the names again, for a change to the tenants that was made while the application runs. */
    public void refresh() {
        roster = TenantRoster.of(source.get());
    }

    TenantRoster roster() {
        var current = roster;
        if (current == null) {
            // Two threads that get here together each read the names and end with equal rosters.
            current = TenantRoster.of(source.get());
            roster = current;
        }
        return current;
    }
}
