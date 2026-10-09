package dev.proppilot.agent;

import java.util.Locale;

/**
 * Why a run failed, as the browser sees it: a stable code to localise, plus a generic English fallback message.
 * Provider error text is never part of it.
 */
public enum ErrorCode {
    LLM_OVERLOADED("The AI service is busy right now. Please try again in a moment."),
    LLM_TIMEOUT("The AI service took too long to respond. Please try again."),
    LLM_AUTH("The AI service is not available right now."),
    LLM_ERROR("The AI service could not complete this request."),
    RUN_TIMEOUT("This request took too long and was stopped.");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    /** The wire format, for example {@code llm_overloaded}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
