package dev.proppilot.agent.routing;

import dev.proppilot.rag.retrieve.LeaseTopic;
import dev.proppilot.text.ExpiryWindow;
import dev.proppilot.text.Folding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Scores how much work a question is, so that the router can send the ones that take several steps to the larger
 * model. The score is the sum of the points of the {@link ComplexityRule}s the question meets, and the rules are the
 * explanation: every one is a plain check of the words, in English and in Arabic.
 *
 * <p>Only the first {@value #READ_CHARACTERS} characters are read, as words and never as one long pattern, so the work
 * is the same for a question of a few lines and for a hostile one of a million characters. A question that long is a
 * long question anyway.
 */
@Component
public class ComplexityAssessor {

    static final int LONG_QUESTION_LENGTH = 160;
    static final int SEVERAL_UNITS = 3;
    static final int LONG_CONVERSATION = 6;
    static final int READ_CHARACTERS = 1_000;

    private static final Pattern UNIT_CODE = Pattern.compile("(?<![a-z0-9])[a-f]-?\\d{3}(?![a-z0-9])");

    private static final Vocabulary PAYMENTS = Vocabulary.of("overdue", "late", "arrears", "unpaid", "outstanding", "owe",
            "owes", "owed", "paid", "payment", "payments", "invoice", "invoices", "behind",
            "متاخر*", "تاخر*", "مستحق*", "دفع*", "مدفوع*", "سداد*", "فاتور*", "فواتير", "مديون*", "ديون");
    private static final Vocabulary OCCUPANCY = Vocabulary.of("occupancy", "occupied", "vacant", "vacancy", "vacancies",
            "units", "apartments", "flats", "bedroom", "bedrooms", "اشغال*", "مشغول*", "شاغر*", "وحدات", "شقق");
    private static final Vocabulary EXPIRING = Vocabulary.of("expir*", "*نته");
    private static final Vocabulary ENDING = Vocabulary.of("end", "ends", "ending");
    private static final Vocabulary SOON = Vocabulary.of("soon", "قريبا", "قريب*");
    private static final Vocabulary CLAUSES = Vocabulary.of("clause", "clauses", "بند", "بنود");
    private static final Vocabulary MESSAGES = Vocabulary.of("draft", "drafts", "drafting", "compose", "message",
            "messages", "email", "emails", "letter", "wording", "رساله", "رسائل", "مسوده", "صياغه", "صغ", "اكتب", "خطاب");

    private static final Vocabulary SEQUENCING = Vocabulary.of("then", "afterwards", "afterward", "compare*", "versus",
            "vs", "both", "each", "comparison", "ثم", "بعدها", "لكل", "قارن*", "مقارن*", "كلا", "كلاهما", "كليهما",
            "كلتا", "كلتاهما");
    private static final Vocabulary SUMMARISING = Vocabulary.of("summar*", "لخص*", "ملخص*", "تلخيص*", "اختصر*");

    /**
     * @param earlierMessages how many messages of the conversation come before the question
     */
    public Complexity assess(String question, int earlierMessages) {
        var folded = Folding.fold(Folding.withoutDiacritics(question.substring(0,
                Math.min(question.length(), READ_CHARACTERS))));
        var words = QuestionWords.of(folded);
        var command = WriteCommand.in(words);
        // A command is a change to make, in words of its own: what it says is no question about the data.
        var asked = command.map(span -> words.without(span.from(), span.to())).orElse(words);
        var topics = LeaseTopic.in(asked.text());
        boolean window = ExpiryWindow.days(asked.text()).isPresent();

        var rules = new ArrayList<ComplexityRule>();
        if (dataAreas(command.isPresent(), asked, topics, window) >= 2) {
            rules.add(ComplexityRule.SEVERAL_DATA_AREAS);
        }
        if (window && !topics.isEmpty()) {
            rules.add(ComplexityRule.WINDOW_WITH_LEASE_TERM);
        }
        if (question.strip().length() > LONG_QUESTION_LENGTH) {
            rules.add(ComplexityRule.LONG_QUESTION);
        }
        if (sequences(words)) {
            rules.add(ComplexityRule.SEQUENCING_WORD);
        }
        if (unitCodes(folded) >= SEVERAL_UNITS) {
            rules.add(ComplexityRule.MANY_UNITS);
        }
        if (earlierMessages >= LONG_CONVERSATION) {
            rules.add(ComplexityRule.LONG_CONVERSATION);
        }
        return new Complexity(rules);
    }

    /**
     * How many of the data areas the question touches. A command is one area and its own words count for no other
     * ("record a payment" is a change, not a question about payments, and "log a repair" is no question about the
     * maintenance clause), so {@code asked} is the question without them.
     */
    private static int dataAreas(boolean command, QuestionWords asked, List<LeaseTopic> topics, boolean window) {
        boolean expiring = asked.has(EXPIRING) || (asked.has(ENDING) && (window || asked.has(SOON)));
        return count(command, mentionsPayments(asked, topics), asked.has(OCCUPANCY), expiring,
                !topics.isEmpty() || asked.has(CLAUSES), asked.has(MESSAGES));
    }

    /**
     * Whether a word is about payments or rent that is owed. The words of a late fee clause ("late", "payment" in "the
     * late payment fee") name the clause and do not count, but a question that is also about who is late on rent does.
     */
    private static boolean mentionsPayments(QuestionWords asked, List<LeaseTopic> topics) {
        if (!topics.contains(LeaseTopic.LATE_FEE)) {
            return asked.has(PAYMENTS);
        }
        return QuestionWords.of(LeaseTopic.LATE_FEE.without(asked.text())).has(PAYMENTS);
    }

    private static int count(boolean... areas) {
        int count = 0;
        for (var area : areas) {
            count += area ? 1 : 0;
        }
        return count;
    }

    /** A word of sequence or aggregation, or "summarise" with an "and" after it. */
    private static boolean sequences(QuestionWords words) {
        if (words.has(SEQUENCING) || words.hasInOrder("after", "that") || words.hasInOrder("followed", "by")
                || words.hasInOrder("بعد", "ذلك")) {
            return true;
        }
        int summary = words.indexOf(SUMMARISING);
        return summary >= 0 && words.andAfter(summary) < words.size();
    }

    /** How many different units the question names by code. */
    private static int unitCodes(String folded) {
        var codes = new HashSet<String>();
        var match = UNIT_CODE.matcher(folded);
        while (match.find()) {
            codes.add(match.group().replace("-", ""));
        }
        return codes.size();
    }
}
