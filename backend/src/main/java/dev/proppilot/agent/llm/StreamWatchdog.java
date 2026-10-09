package dev.proppilot.agent.llm;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;

/**
 * Closes a response stream once its time is up. The HTTP client's request timeout ends when the response headers
 * arrive, and a read that is blocked on a stalled stream can only be freed by closing the stream from another thread.
 * Close the watchdog itself when the stream has been read.
 */
final class StreamWatchdog implements AutoCloseable {

    private final Thread timer;
    private volatile boolean expired;

    StreamWatchdog(Closeable stream, Duration limit) {
        timer = Thread.ofVirtual().name("llm-stream-watchdog").start(() -> {
            try {
                Thread.sleep(limit);
            } catch (InterruptedException e) {
                return;
            }
            expired = true;
            closeQuietly(stream);
        });
    }

    /** Whether the time ran out before the watchdog was closed. */
    boolean expired() {
        return expired;
    }

    @Override
    public void close() {
        timer.interrupt();
    }

    private static void closeQuietly(Closeable stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // Nothing is left to free, and the blocked reader fails on its own.
        }
    }
}
