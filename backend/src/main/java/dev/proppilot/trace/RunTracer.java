package dev.proppilot.trace;

/** Where the trace of a finished run goes beyond the database. */
public interface RunTracer {

    /** Reports a run once it has been recorded. Telemetry must never affect a question, so this does not throw. */
    void record(TracedRun run);
}
