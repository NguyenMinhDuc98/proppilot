package dev.proppilot.agent.tools;

/** What a tool says when it is asked for a unit that does not exist. */
final class UnknownUnit {

    private UnknownUnit() {
    }

    /** A rejection: it repeats the code the model sent and gives an example of one, so it is no evidence. */
    static ToolResult result(String code) {
        return ToolResult.rejected("No unit with code '" + code + "'. Codes look like A-203 "
                + "(building letter A-F, then floor and number).");
    }
}
