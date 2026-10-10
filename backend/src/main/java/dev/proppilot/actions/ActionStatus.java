package dev.proppilot.actions;

/** PROPOSED is the only state that can still change; every other one is final. */
public enum ActionStatus {
    PROPOSED, APPROVED, REJECTED, EXPIRED, FAILED
}
