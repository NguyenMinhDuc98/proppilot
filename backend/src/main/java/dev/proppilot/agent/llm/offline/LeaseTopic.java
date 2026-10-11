package dev.proppilot.agent.llm.offline;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A subject a lease clause is about, as a question words it in English or Arabic, and the words to look for in the
 * lease documents. A document is written in its tenant's language, so the query of a topic holds the clause's words in
 * both languages: the English ones are searched in English documents and the Arabic ones in Arabic documents.
 *
 * <p>Patterns are matched against folded text (see {@link dev.proppilot.text.Folding}), so Arabic is written without
 * hamza, diacritics or ta marbuta. An English word has to stand alone; an Arabic one may carry a prefix such as
 * "ال" or "ب". Every pattern is a plain alternation, with nothing that repeats a group.
 */
enum LeaseTopic {

    LATE_FEE("late payment fee penalty", "غرامة التأخير",
            "(?:late|overdue|delayed)(?: payment| rent)? (?:fees?|charges?|penalt(?:y|ies)|clause|terms)"
                    + "|penalt(?:y|ies)|grace period|(?:fees?|charges?) for (?:late|overdue|delayed)",
            "غرام[هات]|رسوم (?:ال)?تاخير|التاخير في (?:ال)?(?:سداد|دفع)|عقوبه (?:ال)?تاخير|مهله السماح"),

    NOTICE("notice period", "مدة الإشعار",
            "notices?|non-renewal",
            "اشعار|اخطار"),

    RENEWAL("term renewal renews automatically", "مدة العقد والتجديد",
            "renew(?:al|als|s|ed|ing|able)?",
            "تجديد|يتجدد|تتجدد|تجدد"),

    MAINTENANCE("maintenance repairs responsible", "الصيانة والإصلاحات",
            "repairs?|repaired|responsib(?:le|ility|ilities)|who pays|liable|upkeep",
            "مس[وي]{1,2}ول|اصلاح|تصليح|يتحمل|تتحمل|من يدفع|من يتكفل"),

    PETS("pets animals", "الحيوانات الأليفة",
            "pets?|animals?|dogs?|cats?",
            "حيوان|اليف|كلب|كلاب|قط[هط]"),

    EARLY_TERMINATION("early termination fee", "الإنهاء المبكر",
            "terminat\\p{L}*|break(?:ing)? (?:the|a|my|their) (?:lease|contract)"
                    + "|end(?:ing)? (?:the |a |their )?(?:lease|contract) early|leave early|move out early"
                    + "|cancel\\p{L}*|early exit",
            "انهاء|فسخ|المبكر|الغاء"),

    DEPOSIT("security deposit", "مبلغ التأمين",
            "deposits?",
            "تامين|وديعه"),

    SUBLETTING("subletting sublet assign", "التأجير من الباطن",
            "sub-?let\\p{L}*|sub-?leas\\p{L}*",
            "الباطن"),

    INSPECTION("inspection entry landlord enter", "المعاينة ودخول العقار",
            "inspect\\p{L}*|(?:enter|entry|access)(?: to)?(?: the)? (?:unit|property|apartment|premises)",
            "معاينه|دخول العقار|تفتيش"),

    HOUSE_RULES("house rules building regulations quiet hours", "قواعد السكن وأنظمة المبنى",
            "house rules|quiet hours|noise|smoking|building (?:rules|regulations)",
            "قواعد السكن|ساعات الهدوء|ضوضاء|التدخين|انظمه المبني"),

    GOVERNING_LAW("governing law disputes courts", "القانون الواجب التطبيق",
            "governing law|applicable law|jurisdiction|disputes?|courts?",
            "القانون الواجب|الاختصاص|المحاكم|نزاع"),

    SPECIAL_CONDITIONS("special conditions parking", "شروط خاصة",
            "special conditions?|parking",
            "(?:ال)?شروط (?:ال)?خاصه|موقف");

    private static final String WORD_START = "(?<![\\p{L}\\p{N}])";
    private static final String WORD_END = "(?![\\p{L}\\p{N}])";

    private final String query;
    private final Pattern pattern;

    LeaseTopic(String englishQuery, String arabicQuery, String english, String arabic) {
        this.query = englishQuery + " " + arabicQuery;
        this.pattern = Pattern.compile(WORD_START + "(?:" + english + ")" + WORD_END + "|" + arabic,
                Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** The words to search the lease documents for, in both languages. */
    String query() {
        return query;
    }

    /** The topics a folded question is about, in the order of this enum. */
    static List<LeaseTopic> in(String folded) {
        return Arrays.stream(values()).filter(topic -> topic.pattern.matcher(folded).find()).toList();
    }
}
