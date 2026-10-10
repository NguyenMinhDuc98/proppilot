package dev.proppilot.actions;

import java.util.UUID;
import java.util.regex.Pattern;

final class ActionIds {

    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private ActionIds() {
    }

    /** Only the canonical form: {@link UUID#fromString} would also take sloppy input such as {@code 1-1-1-1-1}. */
    static UUID parse(String raw) {
        if (!UUID_FORMAT.matcher(raw).matches()) {
            throw new ActionException(ActionError.INVALID_ACTION_ID, "The action id is not a valid UUID.");
        }
        return UUID.fromString(raw);
    }
}
