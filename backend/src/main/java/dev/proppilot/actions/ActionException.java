package dev.proppilot.actions;

/** A refused action request. The message is a generic English fallback; clients show their own text for the code. */
public class ActionException extends RuntimeException {

    private final ActionError error;

    public ActionException(ActionError error, String message) {
        super(message);
        this.error = error;
    }

    public ActionError error() {
        return error;
    }
}
