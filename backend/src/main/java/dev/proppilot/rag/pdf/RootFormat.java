package dev.proppilot.rag.pdf;

import java.util.Locale;

/**
 * Formats with the root locale. {@code String.formatted} uses the default locale of the JVM, which on an Arabic host
 * writes {@code %d} with Arabic-Indic digits: the same lease would give a different document, and different headings
 * to search for, on every machine. A document has ASCII digits.
 */
final class RootFormat {

    private RootFormat() {
    }

    static String format(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }
}
