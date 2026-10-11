package dev.proppilot.rag.retrieve;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A subject a lease clause is about, as a question words it in English or Arabic, and the words to look for in the
 * lease documents. A document is written in its tenant's language, so the query of a topic holds the clause's words in
 * both languages: the English ones are searched in English documents and the Arabic ones in Arabic documents.
 *
 * <p>The words that name a topic come in three kinds. Most only ever mean the clause ("late fee", "subletting"). Some
 * also turn up in questions about rent, payments and units ("who pays", "responsible", "cancel", "noise", "court"):
 * those count only when the question holds a word that says it is about a lease, such as "lease", "clause" or "عقد".
 * And a few are a weak guess that the other topics overrule: "penalty" is the late fee unless the question asks for
 * the penalty of something else.
 *
 * <p>Patterns are matched against folded text (see {@link dev.proppilot.text.Folding}), so Arabic is written without
 * hamza, diacritics or ta marbuta. An English word has to stand alone. An Arabic one has to start a word, after the
 * article "ال" or a prefix such as "ب" or "ل", so that it is not found in the middle of another word ("اليف" in
 * "تكاليف"); its ending is left open for suffixes. Every pattern is a plain alternation: the gaps between two words
 * are bounded and nothing repeats a group.
 */
public enum LeaseTopic {

    LATE_FEE("late payment fee penalty", "غرامة التأخير",
            words("(?:late|overdue|delayed)(?: payment| rent)? (?:fees?|charges?|penalt(?:y|ies)|clause|terms)"
                            + "|grace period|(?:fees?|charges?) for (?:late|overdue|delayed)(?: payments?| rent)?",
                    "(?:غرام[هات] (?:ال)?(?:تاخير|تاخر)|رسوم (?:ال)?تاخير|عقوبه (?:ال)?تاخير)"
                            + "(?: في (?:ال)?(?:سداد|دفع))?|(?:ال)?تاخير في (?:ال)?(?:سداد|دفع)|مهله (?:ال)?سماح"),
            Words.NONE,
            words("penalt(?:y|ies)", "غرام[هات]")),

    NOTICE("notice period", "مدة الإشعار",
            words("notices?|non-renewal", "اشعار|اخطار")),

    RENEWAL("term renewal renews automatically", "مدة العقد والتجديد",
            words("renew(?:al|als|s|ed|ing|able)?", "تجديد|يتجدد|تتجدد|تجدد")),

    MAINTENANCE("maintenance repairs responsible", "الصيانة والإصلاحات",
            words("repairs?|repaired|upkeep"
                            // Who is responsible for maintenance, or the responsibility for it, in either order.
                            + "|(?:responsib(?:le|ility|ilities)|liab(?:le|ility)|who (?:pays|covers|bears)"
                            + "|obligations?|duties)[^.?!]{0,40}?(?:maintenance|repairs?|upkeep)"
                            + "|(?:maintenance|repairs?|upkeep)[^.?!]{0,40}?(?:responsib(?:le|ility|ilities)"
                            + "|liab(?:le|ility)|who (?:pays|covers|bears)|obligations?|duties)",
                    "اصلاح|تصليح"
                            + "|(?:مس[وي]{1,2}ول\\p{L}*|يتحمل|تتحمل|من يدفع|من يتكفل|التزام\\p{L}*)[^.؟?!]{0,40}?"
                            + "(?:ال)?(?:صيانه|اصلاح)"
                            + "|(?:ال)?(?:صيانه|اصلاح)[^.؟?!]{0,40}?"
                            + "(?:مس[وي]{1,2}ول\\p{L}*|يتحمل|تتحمل|من يدفع|من يتكفل|التزام\\p{L}*)"),
            words("maintenance|responsib(?:le|ility|ilities)|who pays|liable",
                    "صيانه|مس[وي]{1,2}ول|يتحمل|تتحمل|من يدفع|من يتكفل"),
            Words.NONE),

    PETS("pets animals", "الحيوانات الأليفة",
            words("pets?|animals?|dogs?|cats?", "حيوان\\p{L}*|اليف\\p{L}*|كلب|كلاب|قطه|قطط")),

    EARLY_TERMINATION("early termination fee", "الإنهاء المبكر",
            words("terminat\\p{L}*|break(?:ing)? (?:the|a|my|their) (?:lease|contract)"
                            + "|end(?:ing)? (?:the |a |their )?(?:lease|contract) early|leave early|move out early"
                            + "|early exit",
                    "انهاء|فسخ"),
            words("cancel\\p{L}*", "الغاء|المبكر"),
            Words.NONE),

    DEPOSIT("security deposit", "مبلغ التأمين",
            words("deposits?", "تامين|وديعه")),

    SUBLETTING("subletting sublet assign", "التأجير من الباطن",
            words("sub-?let\\p{L}*|sub-?leas\\p{L}*", "الباطن")),

    INSPECTION("inspection entry landlord enter", "المعاينة ودخول العقار",
            words("inspect\\p{L}*|(?:enter|entry|access)(?: to)?(?: the)? (?:unit|property|apartment|premises)",
                    "معاينه|دخول (?:ال)?عقار|تفتيش")),

    HOUSE_RULES("house rules building regulations quiet hours", "قواعد السكن وأنظمة المبنى",
            words("house rules|quiet hours|smoking|building (?:rules|regulations)",
                    "قواعد (?:ال)?سكن|ساعات (?:ال)?هدوء|تدخين|انظمه (?:ال)?مبني"),
            words("noise", "ضوضاء"),
            Words.NONE),

    GOVERNING_LAW("governing law disputes courts", "القانون الواجب التطبيق",
            words("governing law|applicable law|jurisdiction", "قانون (?:ال)?واجب|اختصاص"),
            words("disputes?|courts?", "محاكم|نزاع"),
            Words.NONE),

    SPECIAL_CONDITIONS("special conditions parking", "شروط خاصة",
            words("special conditions?", "شروط (?:ال)?خاصه"),
            words("parking", "موقف|مواقف"),
            Words.NONE);

    private static final String WORD_START = "(?<![\\p{L}\\p{N}])";
    private static final String WORD_END = "(?![\\p{L}\\p{N}])";
    /** Where an Arabic word starts: after nothing, or the article, or "و" "ف" "ب" "ك" "ل" "لل" in front of it. */
    private static final String ARABIC_START = "(?<![\\p{L}\\p{N}])(?:[وف]?(?:لل|[بكل]?(?:ال)?))";
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;

    /** What makes a question one about a lease: the lease itself, or what it holds. */
    private static final Pattern LEASE_WORD = pattern(words(
            "leases?|contracts?|agreements?|clauses?|terms|conditions|tenanc(?:y|ies)|obligations?|provisions?"
                    + "|policy|policies|rules?",
            "عقد|عقود|بند|بنود|شروط|التزام\\p{L}*|اتفاقيه|قواعد|انظمه|لوائح|سياسه"));

    /** The English and Arabic words of a pattern: alternations, folded. */
    private record Words(String english, String arabic) {

        static final Words NONE = new Words("(?!)", "(?!)");
    }

    private final String query;
    private final Pattern always;
    private final Pattern withLeaseWord;
    private final Pattern alone;

    LeaseTopic(String englishQuery, String arabicQuery, Words always) {
        this(englishQuery, arabicQuery, always, Words.NONE, Words.NONE);
    }

    /**
     * @param always words that name the topic wherever they are
     * @param withLeaseWord words that name it only in a question that also has a lease word
     * @param alone words that name it only when no other topic is named in the question
     */
    LeaseTopic(String englishQuery, String arabicQuery, Words always, Words withLeaseWord, Words alone) {
        this.query = englishQuery + " " + arabicQuery;
        this.always = pattern(always);
        this.withLeaseWord = pattern(withLeaseWord);
        this.alone = pattern(alone);
    }

    private static Words words(String english, String arabic) {
        return new Words(english, arabic);
    }

    private static Pattern pattern(Words words) {
        return Pattern.compile(WORD_START + "(?:" + words.english() + ")" + WORD_END + "|" + ARABIC_START + "(?:"
                + words.arabic() + ")", FLAGS);
    }

    /** The words to search the lease documents for, in both languages. */
    public String query() {
        return query;
    }

    /**
     * The folded text without the words that name this topic wherever they stand: what is left is what the question
     * says besides it, so that "the late payment fee" is not also read as a word about payments.
     */
    public String without(String folded) {
        return always.matcher(folded).replaceAll(" ");
    }

    /** The topics a folded question is about, in the order of this enum. */
    public static List<LeaseTopic> in(String folded) {
        boolean aboutALease = LEASE_WORD.matcher(folded).find();
        var named = Arrays.stream(values())
                .filter(topic -> topic.always.matcher(folded).find()
                        || (aboutALease && topic.withLeaseWord.matcher(folded).find()))
                .toList();
        if (!named.isEmpty()) {
            return named;
        }
        return Arrays.stream(values()).filter(topic -> topic.alone.matcher(folded).find()).toList();
    }
}
