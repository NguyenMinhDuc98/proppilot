package dev.proppilot.agent;

import java.time.Instant;

/**
 * One tool execution of a run. The text fields are capped so that a record stays small whatever the model sent or the
 * tool returned: arguments at {@value #MAX_ARGS_LENGTH} characters, the summary at {@value #MAX_SUMMARY_LENGTH}, and
 * control characters become spaces.
 *
 * @param sequence 1 for the first tool execution of the run
 * @param args the arguments as compact JSON; cut off, so no longer JSON, when they exceed the cap
 * @param summary the one line the user saw for the result
 * @param startedAt when the tool began, for placing its span; it is not stored
 */
public record ToolCallRecord(int sequence, String toolName, String args, String summary, boolean error, long latencyMs,
                             Instant startedAt) {

    public static final int MAX_ARGS_LENGTH = 2000;
    public static final int MAX_SUMMARY_LENGTH = 300;
    /** The width of the {@code tool_name} column; the model chooses the name, so it is not trusted to fit. */
    public static final int MAX_NAME_LENGTH = 64;

    public ToolCallRecord {
        toolName = TraceText.capped(toolName, MAX_NAME_LENGTH);
        args = TraceText.capped(args, MAX_ARGS_LENGTH);
        summary = TraceText.capped(summary, MAX_SUMMARY_LENGTH);
    }
}
