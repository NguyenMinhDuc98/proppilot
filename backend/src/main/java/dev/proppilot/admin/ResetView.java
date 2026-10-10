package dev.proppilot.admin;

/** How much a demo reset changed. */
public record ResetView(int paymentsRestored, int maintenanceRequestsDeleted, int outboxMessagesDeleted,
                        int actionsDeleted, int auditEntriesDeleted) {
}
