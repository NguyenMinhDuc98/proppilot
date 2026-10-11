package dev.proppilot.trace;

/** The tracer when OpenTelemetry is off: it does nothing and holds nothing. */
public final class NoopRunTracer implements RunTracer {

    @Override
    public void record(TracedRun run) {
    }
}
