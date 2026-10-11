package dev.proppilot.rag;

/** The language a lease document is written in: the tenant's preferred language. */
public enum DocumentLanguage {
    EN("en"),
    AR("ar");

    private final String code;

    DocumentLanguage(String code) {
        this.code = code;
    }

    /** The two letters the database stores. */
    public String code() {
        return code;
    }

    public boolean rightToLeft() {
        return this == AR;
    }

    public static DocumentLanguage fromCode(String code) {
        for (var language : values()) {
            if (language.code.equals(code)) {
                return language;
            }
        }
        throw new IllegalArgumentException("Unknown language code: " + code);
    }
}
