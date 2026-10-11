package dev.proppilot.admin;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.proppilot.chat.ChatRun;
import dev.proppilot.chat.RunTraceView;
import java.util.List;

/**
 * The trace of one run as the public sees it, with what is private to the admin: the question, and what each tool was
 * asked and said, which carries the words of the question.
 *
 * @param toolCallDetails the arguments and result summary of each tool call, joined to {@code toolCalls} by
 *                        {@code seq}
 */
public record AdminRunDetailView(String question, @JsonUnwrapped RunTraceView trace,
                                 List<ToolCallDetail> toolCallDetails) {

    public record ToolCallDetail(int seq, String args, String summary) {
    }

    /** Call inside the transaction: the calls of the run are loaded when they are read here. */
    static AdminRunDetailView of(ChatRun run) {
        var details = run.getToolCallRows().stream()
                .map(call -> new ToolCallDetail(call.sequence(), call.args(), call.summary())).toList();
        return new AdminRunDetailView(run.getQuestion(), RunTraceView.of(run), details);
    }
}
