package dev.proppilot.rag.pdf;

import dev.proppilot.rag.LeaseFacts;

/** How many people may live in a unit: two for each bedroom, and never fewer than two. */
final class Occupants {

    private Occupants() {
    }

    static int limit(LeaseFacts facts) {
        return Math.max(2, facts.bedrooms() * 2);
    }
}
