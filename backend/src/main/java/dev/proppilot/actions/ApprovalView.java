package dev.proppilot.actions;

/** @param replayed true when this call repeated an approval that had already been made and changed nothing */
public record ApprovalView(ActionView action, boolean replayed) {
}
