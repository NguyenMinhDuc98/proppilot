package dev.proppilot.agent.llm.offline;

import java.util.regex.Pattern;

final class QuestionLanguage {

    private static final Pattern ARABIC = Pattern.compile("[\\u0600-\\u06FF]");

    private QuestionLanguage() {
    }

    static boolean isArabic(String text) {
        return ARABIC.matcher(text).find();
    }
}
