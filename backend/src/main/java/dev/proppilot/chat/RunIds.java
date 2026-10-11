package dev.proppilot.chat;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** The id of a run as it appears in a URL. */
public final class RunIds {

    private static final int UUID_LENGTH = 36;
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private RunIds() {
    }

    /** Only the canonical form: {@link UUID#fromString} would also take sloppy input such as {@code 1-1-1-1-1}. */
    public static Optional<UUID> parse(String raw) {
        if (raw.length() != UUID_LENGTH || !UUID_FORMAT.matcher(raw).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(raw));
    }
}
