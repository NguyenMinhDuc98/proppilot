package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Knows one kind of change: whether it may be made, what it would do and how to do it. Executors never decide whether
 * a person approved; {@link ActionService} calls {@link #execute} only after an approval.
 *
 * @param <A> the validated arguments, already resolved against the data (lease, unit, ...)
 */
public interface ActionExecutor<A> {

    ActionKind kind();

    /**
     * Checks raw arguments against the data as it is now.
     *
     * @throws dev.proppilot.agent.tools.ToolInputException for malformed input
     * @throws ActionException for a well-formed change that may not be made
     */
    A validate(JsonNode rawArgs);

    /** The arguments in the normalized form they are stored in and validated again from at approval. */
    JsonNode arguments(A args);

    /** What the change is, in one sentence per language. */
    Summary summary(A args);

    /** What would change, without changing anything. */
    JsonNode preview(A args);

    /** Makes the change inside the caller's transaction; throwing rolls it back. */
    Execution execute(A args, ExecutionContext context);

    /** Argument names a person may overwrite when approving. */
    default Set<String> editableFields() {
        return Set.of();
    }

    record Summary(String en, String ar) {
    }

    record ExecutionContext(UUID actionId, Instant at) {
    }

    /**
     * @param result what the approver gets back
     * @param entityType, entityId what was changed, for the audit log
     * @param before the changed data as it was, null when it did not exist
     * @param after the changed data as it is now
     */
    record Execution(JsonNode result, String entityType, String entityId, JsonNode before, JsonNode after) {
    }
}
