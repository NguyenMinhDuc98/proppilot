package dev.proppilot.admin;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.proppilot.chat.RunTraceView;

/** The trace of one run as the public sees it, and the question that is private to the admin. */
public record AdminRunDetailView(String question, @JsonUnwrapped RunTraceView trace) {
}
