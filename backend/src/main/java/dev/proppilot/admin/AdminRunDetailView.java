package dev.proppilot.admin;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.proppilot.chat.ChatRun;
import dev.proppilot.chat.RunTraceView;
import dev.proppilot.guardrail.ClaimKind;
import java.util.List;

/**
 * The trace of one run as the public sees it, with what is private to the admin: the question, what each tool was
 * asked and said, which carries the words of the question, and the values of the answer that the grounding guardrail
 * could not ground.
 *
 * @param toolCallDetails the arguments and result summary of each tool call, joined to {@code toolCalls} by
 *                        {@code seq}
 * @param guardrailFindings the kind of each value and the value as the answer wrote it (cut to 40 characters); the
 *                          public trace holds only how many there are
 */
public record AdminRunDetailView(String question, @JsonUnwrapped RunTraceView trace,
                                 List<ToolCallDetail> toolCallDetails, List<GuardrailFinding> guardrailFindings) {

    public record ToolCallDetail(int seq, String args, String summary) {
    }

    public record GuardrailFinding(ClaimKind kind, String value) {
    }

    /** Call inside the transaction: the calls of the run are loaded when they are read here. */
    static AdminRunDetailView of(ChatRun run) {
        var details = run.getToolCallRows().stream()
                .map(call -> new ToolCallDetail(call.sequence(), call.args(), call.summary())).toList();
        var findings = run.getGuardrailFindingRows().stream()
                .map(finding -> new GuardrailFinding(finding.kind(), finding.claim())).toList();
        return new AdminRunDetailView(run.getQuestion(), RunTraceView.of(run), details, findings);
    }
}
