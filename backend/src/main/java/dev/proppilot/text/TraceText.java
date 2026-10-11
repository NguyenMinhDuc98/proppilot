package dev.proppilot.text;

/** Keeps the text of a trace, a log line or a stored finding short and storable. */
public final class TraceText {

    private static final char ELLIPSIS = '…';

    private TraceText() {
    }

    /**
     * Control characters (a NUL cannot be stored in a text column) become spaces. Text longer than {@code maxLength}
     * is cut so that it ends with an ellipsis and still fits; a surrogate pair is never split. One pass, no regular
     * expression, whatever the length of the input.
     */
    public static String capped(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        boolean fits = raw.length() <= maxLength;
        int limit = fits ? raw.length() : maxLength - 1;
        var text = new StringBuilder(limit + 1);
        int i = 0;
        while (i < limit) {
            int codePoint = raw.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (i + width > limit) {
                break;
            }
            text.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
            i += width;
        }
        return fits ? text.toString() : text.append(ELLIPSIS).toString();
    }
}
