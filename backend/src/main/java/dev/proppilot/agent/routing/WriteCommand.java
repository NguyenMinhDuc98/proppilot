package dev.proppilot.agent.routing;

import java.util.Optional;

/**
 * A request to change something (record a payment, open a maintenance request, send a reminder): a verb of writing and,
 * within a few words and with no preposition between them, the thing it writes. "Record a payment of 4950" is one,
 * "the record of the payments" is not. The command runs up to the next "and", because what follows its object is what
 * it is for ("a reminder about the overdue rent"), and none of its words count again as a question about payments.
 */
final class WriteCommand {

    /** The words of the command: from {@code from} up to, and not including, {@code to}. */
    record Span(int from, int to) {
    }

    private static final int MOST_WORDS_BETWEEN = 5;
    private static final Vocabulary VERBS = Vocabulary.of("record", "log", "register", "enter", "add", "create", "open",
            "raise", "file", "submit", "send", "queue", "issue", "make",
            "سجل", "اسجل", "تسجيل", "انشي", "انشاء", "افتح", "فتح", "اضف", "اضافه", "ارسل", "ارسال", "ابعث", "بعث", "اشعر");
    private static final Vocabulary THINGS = Vocabulary.of("payment", "payments", "reminder", "reminders", "maintenance",
            "repair", "notice", "دفعه", "دفعات", "تذكير*", "صيانه", "بلاغ");
    /** A word after which what follows is no longer the object of the verb. "add up the payments" adds nothing. */
    private static final Vocabulary BREAKERS = Vocabulary.of("of", "for", "in", "at", "on", "from", "to", "about", "with",
            "by", "and", "or", "but", "if", "when", "up", "في", "من", "علي", "الي", "عن", "مع", "حول", "بسبب", "او", "اذا");

    private WriteCommand() {
    }

    static Optional<Span> in(QuestionWords words) {
        for (int verb = 0; verb < words.size(); verb++) {
            if (!VERBS.matches(words.get(verb))) {
                continue;
            }
            int last = Math.min(words.size() - 1, verb + 1 + MOST_WORDS_BETWEEN);
            for (int next = verb + 1; next <= last; next++) {
                if (THINGS.matches(words.get(next))) {
                    return Optional.of(new Span(verb, words.andAfter(next)));
                }
                if (BREAKERS.matches(words.get(next))) {
                    break;
                }
            }
        }
        return Optional.empty();
    }
}
