package dev.proppilot.actions;

/** The changes the agent may propose. Each has one {@link ActionExecutor}. */
public enum ActionKind {
    RECORD_PAYMENT, CREATE_MAINTENANCE_REQUEST, SEND_TENANT_REMINDER
}
