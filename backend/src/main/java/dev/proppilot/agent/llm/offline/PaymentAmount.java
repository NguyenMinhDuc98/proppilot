package dev.proppilot.agent.llm.offline;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the amount in a request to record a payment, in folded text.
 *
 * <p>The unit code and any date hold digits too, so they are blanked first. A number is the amount when a word that
 * introduces an amount comes before it ("of", "amount", "SAR", "بمبلغ") or one that names the money comes after it
 * ("riyals", "received"); without such a word the only number left is the amount. When it is not clear which number is
 * the amount, or how it is written ("2.5k", "2.500,50"), there is no amount and the tool asks the visitor for it,
 * because a proposal for a different sum than the visitor wrote is worse than a question. A sign is kept, so that the
 * executor refuses a negative amount.
 */
final class PaymentAmount {

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    /** How far before a number the word that introduces it can be. */
    private static final int INTRODUCTION_WINDOW = 40;
    private static final String WORD_END = "(?![\\p{L}\\p{N}])";
    private static final String DAY = "\\d{1,2}(?:st|nd|rd|th)?";
    private static final String MONTH = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?"
            + "|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
            + "|يناير|فبراير|مارس|ابريل|مايو|يونيو|يوليو|اغسطس|سبتمبر|اكتوبر|نوفمبر|ديسمبر";

    private static final Pattern DATE = Pattern.compile("(?<![\\p{L}\\p{N}])(?:"
            + "\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}"
            + "|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}"
            + "|(?:" + DAY + "\\s+(?:of\\s+|من\\s+)?)?(?:" + MONTH + ")(?![\\p{L}])(?:\\s+" + DAY + WORD_END + ")?"
            + "(?:,?\\s+20\\d{2}" + WORD_END + ")?"
            + "|\\d{1,2}(?:st|nd|rd|th)" + WORD_END + ")", FLAGS);

    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}.,])(?<sign>[-+])?(?<digits>(?:\\d|\\.\\d)(?:[\\d.,]*\\d)?)", FLAGS);
    /** Plain digits, thousands in groups of three, and at most two decimals: how money is written. */
    private static final Pattern WRITTEN_AS_MONEY = Pattern.compile("(?:\\d{1,3}(?:,\\d{3})+|\\d+)?(?:\\.\\d{1,2})?");

    private static final Pattern INTRODUCES_AMOUNT = Pattern.compile("(?:(?<![\\p{L}\\p{N}])(?:of|amount|amounting|total"
            + "|totalling|totaling|worth|payments?|paid|received|sar|sr|rs|riyals?)|مبلغ|بمبلغ|بقيمه|قيمه|قدرها|قدره"
            + "|بقدر|ريال|ر\\.?س)\\s*[:=]?\\s*$", FLAGS);
    private static final Pattern NAMES_MONEY = Pattern.compile("\\s*(?:(?:sar|sr|rs|riyals?|riyal|received|paid"
            + "|payments?)" + WORD_END + "|ريال|ر\\.?س)", FLAGS);
    private static final Pattern COUNTS_SOMETHING_ELSE = Pattern.compile("\\s*(?:months?|weeks?|days?|years?"
            + "|installments?)" + WORD_END + "|\\s*(?:شهر|اشهر|شهور|اسبوع|اسابيع|يوم|ايام|سنه|سنوات|دفعات)", FLAGS);
    private static final Pattern SCALE = Pattern.compile("\\s*(?:k|m|mm|thousand|million|billion)" + WORD_END
            + "|\\s*(?:الف|الاف|مليون|مليار)", FLAGS);
    /** "100 000" is one number written with a space, which would otherwise be read as 100. */
    private static final Pattern GROUPED_WITH_A_SPACE = Pattern.compile("\\s\\d{3}(?!\\d)", FLAGS);
    private static final Pattern GLUED_TO_A_WORD = Pattern.compile("\\p{L}", FLAGS);

    private record Candidate(BigDecimal value, boolean introduced) {
    }

    private PaymentAmount() {
    }

    static Optional<BigDecimal> read(String folded) {
        var remaining = blankedNumbersThatAreNotTheAmount(folded);
        var candidates = new ArrayList<Candidate>();
        var number = NUMBER.matcher(remaining);
        while (number.find()) {
            var after = remaining.substring(number.end());
            if (COUNTS_SOMETHING_ELSE.matcher(after).lookingAt()) {
                continue;
            }
            boolean introduced = isIntroduced(remaining, number.start()) || NAMES_MONEY.matcher(after).lookingAt();
            candidates.add(new Candidate(value(number.group("sign"), number.group("digits"), after), introduced));
        }
        return candidates.stream().filter(Candidate::introduced).findFirst()
                .or(() -> candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty())
                .map(Candidate::value);
    }

    private static boolean isIntroduced(String text, int numberStart) {
        var before = INTRODUCES_AMOUNT.matcher(text).region(Math.max(0, numberStart - INTRODUCTION_WINDOW), numberStart);
        return before.useTransparentBounds(true).find();
    }

    /** Null when the number is not written the way an amount is. */
    private static BigDecimal value(String sign, String digits, String after) {
        if (!WRITTEN_AS_MONEY.matcher(digits).matches() || isOnlyPartOfAnotherNumber(after)) {
            return null;
        }
        var value = new BigDecimal(digits.replace(",", ""));
        return "-".equals(sign) ? value.negate() : value;
    }

    /** "2.5k", "5 million", "100 000" and "1e3" are not the number that is written first in them. */
    private static boolean isOnlyPartOfAnotherNumber(String after) {
        boolean gluedToAWord = GLUED_TO_A_WORD.matcher(after).lookingAt() && !NAMES_MONEY.matcher(after).lookingAt();
        return gluedToAWord || SCALE.matcher(after).lookingAt() || GROUPED_WITH_A_SPACE.matcher(after).lookingAt();
    }

    private static String blankedNumbersThatAreNotTheAmount(String folded) {
        var blanked = new StringBuilder(folded);
        blank(blanked, OfflineIntentRouter.UNIT_CODE.matcher(folded));
        blank(blanked, DATE.matcher(folded));
        return blanked.toString();
    }

    private static void blank(StringBuilder text, Matcher found) {
        while (found.find()) {
            text.replace(found.start(), found.end(), " ".repeat(found.end() - found.start()));
        }
    }
}
