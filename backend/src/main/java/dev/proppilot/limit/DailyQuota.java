package dev.proppilot.limit;

import java.time.Clock;
import java.time.LocalDate;

/** A global budget that starts again every calendar day. */
public class DailyQuota {

    private final int perDay;
    private final Clock clock;
    private LocalDate today;
    private int used;

    public DailyQuota(int perDay, Clock clock) {
        this.perDay = perDay;
        this.clock = clock;
    }

    /** Uses one unit of today's budget and returns true, or returns false when it is spent. */
    public synchronized boolean tryTake() {
        startNewDayIfNeeded();
        if (used >= perDay) {
            return false;
        }
        used++;
        return true;
    }

    /** Returns a unit taken for something that did not go ahead. */
    public synchronized void giveBack() {
        startNewDayIfNeeded();
        if (used > 0) {
            used--;
        }
    }

    private void startNewDayIfNeeded() {
        var date = LocalDate.now(clock);
        if (!date.equals(today)) {
            today = date;
            used = 0;
        }
    }
}
