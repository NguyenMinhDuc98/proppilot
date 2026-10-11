package dev.proppilot.text;

import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Reads how far ahead a question about leases that end looks, as a number of days: "in the next 60 days", "within two
 * months", "خلال 3 أشهر". Weeks count 7 days, months 30, a quarter 90 and a year 365, and the answer is kept between 1
 * and 365, which is what the tool accepts. A number that is not about the window, such as the days of notice a lease
 * requires, is not read: only a number that a word like "in" or "within" leads in is.
 *
 * <p>The text is folded (see {@link Folding}). A run of white space is taken by one quantifier only, and a quantifier
 * that follows it never gives it back, so each pattern costs time in proportion to the question.
 */
public final class ExpiryWindow {

    private static final int MAX_DAYS = 365;
    private static final String WORD_START = "(?<![\\p{L}\\p{N}])";
    private static final String WORD_END = "(?![\\p{L}\\p{N}])";
    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(Map.entry("a", 1), Map.entry("an", 1),
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12));
    private static final Map<String, Integer> DAYS_IN = Map.ofEntries(
            Map.entry("day", 1), Map.entry("days", 1), Map.entry("week", 7), Map.entry("weeks", 7),
            Map.entry("month", 30), Map.entry("months", 30), Map.entry("quarter", 90), Map.entry("year", 365),
            Map.entry("يوم", 1), Map.entry("يوما", 1), Map.entry("ايام", 1), Map.entry("اسبوع", 7),
            Map.entry("اسبوعا", 7), Map.entry("اسابيع", 7), Map.entry("شهر", 30), Map.entry("شهرا", 30),
            Map.entry("اشهر", 30), Map.entry("شهور", 30), Map.entry("ربع", 90), Map.entry("سنه", 365),
            Map.entry("عام", 365));

    private static final Pattern ENGLISH_COUNT = Pattern.compile(WORD_START
            + "(?:next|within|in|over|during|coming|upcoming|following)\\s+(?:the\\s+)?"
            + "(?:(?:next|coming|upcoming|following)\\s+)?(\\d{1,9}|a|an|one|two|three|four|five|six|seven|eight|nine"
            + "|ten|eleven|twelve)[- ]*(days?|weeks?|months?)" + WORD_END, Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ENGLISH_PERIOD = Pattern.compile(WORD_START
            + "(?:this|next|coming|upcoming)\\s+(month|week|quarter|year)" + WORD_END, Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ARABIC_COUNT = Pattern.compile(
            "(?:خلال|في|بعد|مدى|الي|القادمه?|المقبله?)\\s*+(?:ال\\s*+)?(\\d{1,9})\\s*+(?:ال\\s*+)?"
                    + "(يوما|يوم|ايام|اسبوعا|اسبوع|اسابيع|شهرا|شهر|اشهر|شهور)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ARABIC_DUAL = Pattern.compile(
            "(?:خلال|في|بعد|مدى|الي)\\s*+(?:ال\\s*+)?(يومين|اسبوعين|شهرين)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ARABIC_THIS_PERIOD = Pattern.compile(
            "(?:هذا|هذه)\\s*(?:ال)?(شهر|اسبوع|سنه|عام|ربع)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ARABIC_NEXT_PERIOD = Pattern.compile(
            "(?:ال)?(شهر|اسبوع|سنه|عام|ربع)\\s*(?:ال)?(?:قادم|مقبل)", Pattern.UNICODE_CHARACTER_CLASS);

    private ExpiryWindow() {
    }

    /** Empty when the question names no window. */
    public static OptionalInt days(String folded) {
        var english = ENGLISH_COUNT.matcher(folded);
        if (english.find()) {
            return count(english.group(1), english.group(2));
        }
        var period = ENGLISH_PERIOD.matcher(folded);
        if (period.find()) {
            return OptionalInt.of(DAYS_IN.get(period.group(1)));
        }
        var arabic = ARABIC_COUNT.matcher(folded);
        if (arabic.find()) {
            return count(arabic.group(1), arabic.group(2));
        }
        var dual = ARABIC_DUAL.matcher(folded);
        if (dual.find()) {
            return clamp(2L * DAYS_IN.get(dual.group(1).replace("ين", "")));
        }
        for (var pattern : new Pattern[] {ARABIC_THIS_PERIOD, ARABIC_NEXT_PERIOD}) {
            var arabicPeriod = pattern.matcher(folded);
            if (arabicPeriod.find()) {
                return OptionalInt.of(DAYS_IN.get(arabicPeriod.group(1)));
            }
        }
        return OptionalInt.empty();
    }

    private static OptionalInt count(String number, String unit) {
        long n = NUMBER_WORDS.containsKey(number) ? NUMBER_WORDS.get(number) : Long.parseLong(number);
        return clamp(n * DAYS_IN.get(unit));
    }

    private static OptionalInt clamp(long days) {
        return OptionalInt.of((int) Math.clamp(days, 1, MAX_DAYS));
    }
}
