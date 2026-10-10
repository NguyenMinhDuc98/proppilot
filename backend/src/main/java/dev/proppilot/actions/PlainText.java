package dev.proppilot.actions;

/** Free text from the agent or a visitor that ends up stored and shown to other people. */
final class PlainText {

    private PlainText() {
    }

    /** Control characters (newlines and tabs included) become spaces, runs of whitespace collapse, the ends are trimmed. */
    static String clean(String raw) {
        var sb = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> sb.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp));
        return sb.toString().replaceAll("\\s+", " ").strip();
    }
}
