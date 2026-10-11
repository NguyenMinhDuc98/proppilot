package dev.proppilot.agent.llm.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.text.Folding;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises requests to change something (record a payment, open a maintenance request, send a reminder) in English and
 * Arabic and turns them into calls of the proposing tools.
 *
 * <p>A request is a command: a verb that starts a sentence or a clause, after at most some politeness ("please", "can
 * you", "أريد أن"), and an object that follows the verb closely ("record a payment", "سجّل دفعة"). So questions about
 * payment history or drafting a reminder still reach the read tools, and a maintenance request that only mentions a
 * payment is a maintenance request. The first command in the text wins.
 *
 * <p>Matching runs on a folded copy of the question that has the same length as the original (see {@link Folding}), so
 * a position found in it is a position in the text the visitor wrote and the description can be cut from that text.
 * Keywords are written in the folded spelling.
 */
final class OfflineWriteIntents {

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static final String WORD_END = "(?![\\p{L}\\p{N}])";

    /** Words that may come before a command without changing what it is. */
    private static final List<String> FILLERS = List.of("please", "kindly", "now", "then", "ok", "okay", "yes", "yeah",
            "sure", "so", "and", "also", "just", "well", "alright", "thanks", "thank you", "hi", "hello", "hey",
            "go ahead", "can you", "could you", "would you", "will you", "you can", "you could", "i want to",
            "i need to", "i would like to", "i'd like to", "i want you to", "i need you to", "i'd like you to",
            "let's", "lets", "من فضلك", "لو سمحت", "رجاء", "ارجو", "اريد", "ابغي", "ابي", "اود", "احتاج", "ارغب", "ممكن",
            "يمكنك", "تقدر", "هل", "ان");
    /**
     * A sentence or clause that comes before the command: "Hi, ..." or "For unit A-203: ...". It ends in a run of
     * punctuation and the quantifiers do not give anything back, so the text splits into clauses in one way only and a
     * long run of punctuation cannot make the match slow.
     * <p>
     * The regex engine recurses once for each round of a repeated group, so the rounds are capped too: a long run of
     * ", , , ," or "so so so" would otherwise overflow the thread's stack. No real request has more clauses or filler
     * words than the caps in front of its command.
     */
    private static final String EARLIER_CLAUSE = "[^.!?;:,،؛؟\\n]{0,80}+[.!?;:,،؛؟]++";
    private static final int MOST_EARLIER_CLAUSES = 8;
    private static final int MOST_FILLERS = 12;
    private static final String LEAD_IN = "^(?:" + EARLIER_CLAUSE + "){0," + MOST_EARLIER_CLAUSES + "}?\\W*+(?:(?:"
            + String.join("|", FILLERS.stream().map(filler -> filler.replace(" ", "\\s+")).toList()) + ")" + WORD_END
            + "\\W*+){0," + MOST_FILLERS + "}";

    /** A word that ends a run of modifiers: what follows is no longer the object of the verb. */
    private static final String BREAKER = "for|in|at|on|from|to|of|about|with|by|as|because|regarding|that|which|and|or"
            + "|but|if|when|while|since|into|than|then|after|before"
            + "|في|من|علي|الي|عن|مع|حول|بسبب|بخصوص|بشان|او|ان|اذا|لان|عند|بعد|قبل";
    private static final String WORD = "[\\p{L}\\p{N}]+(?:['.,\\-][\\p{L}\\p{N}]+)*";

    private static final String RECORD_PAYMENT = "(?:record|log|register|enter|add|post)" + WORD_END + "\\s+"
            + modifiers(5) + "(?:payments?" + WORD_END + "(?!\\s+(?:history|records?|logs?|reminders?|notices?"
            + "|summar\\p{L}*)" + WORD_END + ")|received" + WORD_END + ")"
            + "|(?:سجل|اسجل|تسجل|تسجيل|اضف|اضافه|ادخل|ادخال|قيد)" + WORD_END + "\\s+" + modifiers(3) + "(?<![\\p{L}])دفعه"
            + WORD_END;
    private static final String MAINTENANCE_REQUEST = "(?:create|open|log|raise|file|submit|add|record|register|make)"
            + WORD_END + "\\s+" + modifiers(5) + "(?:maintenance|repair)\\s+(?:request|ticket|issue|job|order|case)"
            + WORD_END + "(?!\\s+(?:list|history|queue|status|log|report|summary|backlog)" + WORD_END + ")"
            + "|(?:انشي|انشاء|افتح|فتح|سجل|اسجل|تسجل|تسجيل|اضف|اضافه|ارفع|رفع|قدم|تفتح|تنشي|اضيف|تضيف)"
            + WORD_END + "\\s+" + modifiers(3) + "(?:طلب|بلاغ|تذكره)\\s+(?:ال)?صيانه"
            + "(?:\\s+(?:" + MaintenanceProblem.URGENT_WORDS + ")" + WORD_END + ")?";
    private static final String REMINDER = "(?:send|queue|issue|dispatch|email|text)" + WORD_END + "\\s+" + modifiers(5)
            + "(?:reminder|notice)" + WORD_END + "|remind" + WORD_END + "(?!\\s+me" + WORD_END + ")"
            + "|(?:ارسل|ارسال|ابعث|بعث|اشعر|ترسل|تبعث|تشعر)" + WORD_END + "\\s+" + modifiers(3) + "تذكير";

    private static final Pattern COMMAND = Pattern.compile(LEAD_IN + "(?:(?<maintenance>" + MAINTENANCE_REQUEST
            + ")|(?<payment>" + RECORD_PAYMENT + ")|(?<reminder>" + REMINDER + "))", FLAGS);
    private static final Pattern DRAFTING = Pattern.compile("\\b(?:draft|write|compose)\\b|(?<!\\p{L})(?:صغ|اكتب|مسوده|صياغه)",
            FLAGS);

    private static final Pattern DATE = Pattern.compile("\\b(20\\d{2}-\\d{2}-\\d{2})\\b");

    private final ObjectMapper json;

    OfflineWriteIntents(ObjectMapper json) {
        this.json = json;
    }

    /** Up to {@code most} words between the verb and its object: "a", "a new", "a SAR 2,500 bank transfer". */
    private static String modifiers(int most) {
        return "(?:(?!(?:" + BREAKER + ")" + WORD_END + ")" + WORD + "\\s+){0," + most + "}";
    }

    /** Whether the question uses a verb of drafting: "draft", "write", "compose", "صغ", "اكتب". */
    static boolean asksToDraft(String question) {
        return DRAFTING.matcher(Folding.fold(Folding.withoutDiacritics(question))).find();
    }

    /** Empty unless the question holds a command to change something. */
    Optional<OfflineIntentRouter.Call> route(String question) {
        var text = Folding.withoutDiacritics(question);
        var folded = Folding.fold(text);
        var command = COMMAND.matcher(folded);
        if (!command.find()) {
            return Optional.empty();
        }
        if (command.group("maintenance") != null) {
            return Optional.of(createMaintenanceRequest(text, folded, command));
        }
        if (command.group("payment") != null) {
            return Optional.of(recordPayment(text, folded));
        }
        return DRAFTING.matcher(folded).find() ? Optional.empty() : Optional.of(sendReminder(text, folded));
    }

    private OfflineIntentRouter.Call recordPayment(String text, String folded) {
        var call = call("record_payment");
        var unit = OfflineIntentRouter.UNIT_CODE.matcher(folded);
        if (unit.find()) {
            call.put("unit_code", unitCode(unit));
        } else {
            tenantName(text).ifPresent(name -> call.put("tenant_name", name));
        }
        var date = DATE.matcher(folded);
        if (date.find()) {
            call.put("payment_date", date.group(1));
        }
        PaymentAmount.read(folded).ifPresent(amount -> call.args().put("amount", amount));
        method(folded).ifPresent(method -> call.put("method", method));
        return call;
    }

    private OfflineIntentRouter.Call createMaintenanceRequest(String text, String folded, Matcher command) {
        var call = call("create_maintenance_request");
        int bodyStart = command.end("maintenance");
        // The problem comes after the unit code ("... for unit A-203: leaking pipe") or before it ("... about a
        // leaking pipe in unit A-203"); a unit code said before the command leaves only what follows the command.
        var regions = new ArrayList<MaintenanceProblem.Region>();
        var unit = OfflineIntentRouter.UNIT_CODE.matcher(folded);
        if (unit.find()) {
            call.put("unit_code", unitCode(unit));
            if (unit.start() >= bodyStart) {
                regions.add(new MaintenanceProblem.Region(unit.end(), text.length()));
                regions.add(new MaintenanceProblem.Region(bodyStart, unit.start()));
            }
        }
        if (regions.isEmpty()) {
            regions.add(new MaintenanceProblem.Region(bodyStart, text.length()));
        }
        var problem = MaintenanceProblem.read(text, folded, command.start("maintenance"), bodyStart, regions);
        call.put("category", problem.category());
        call.put("priority", problem.priority());
        if (!problem.description().isEmpty()) {
            call.put("description", problem.description());
        }
        return call;
    }

    private OfflineIntentRouter.Call sendReminder(String text, String folded) {
        var call = call("send_tenant_reminder");
        var unit = OfflineIntentRouter.UNIT_CODE.matcher(folded);
        if (unit.find()) {
            call.put("unit_code", unitCode(unit));
        } else {
            tenantName(text).ifPresent(name -> call.put("tenant_name", name));
        }
        call.put("purpose", purpose(folded));
        // As for a draft: the language asked for, else the language of the request. An English request without one
        // leaves it to the tenant's own preference.
        boolean wantsEnglish = has(folded, "english", "بالانجليزيه", "بالانجليزي");
        boolean wantsArabic = has(folded, "arabic", "بالعربيه", "بالعربي");
        if (wantsArabic || (QuestionLanguage.isArabic(text) && !wantsEnglish)) {
            call.put("language", "AR");
        } else if (wantsEnglish) {
            call.put("language", "EN");
        }
        return call;
    }

    private static String purpose(String folded) {
        if (has(folded, "renew", "تجديد")) {
            return "LEASE_RENEWAL";
        }
        return has(folded, "maintenance", "صيانه") ? "MAINTENANCE_NOTICE" : "RENT_REMINDER";
    }

    private static Optional<String> method(String folded) {
        if (has(folded, "cash", "نقد")) {
            return Optional.of("CASH");
        }
        if (has(folded, "cheque", "check", "شيك")) {
            return Optional.of("CHEQUE");
        }
        if (has(folded, "card", "بطاقه")) {
            return Optional.of("CARD");
        }
        return has(folded, "transfer", "bank", "تحويل") ? Optional.of("BANK_TRANSFER") : Optional.empty();
    }

    private static Optional<String> tenantName(String text) {
        return OfflineIntentRouter.first(OfflineIntentRouter.TENANT_NAME, text)
                .or(() -> OfflineIntentRouter.first(OfflineIntentRouter.TENANT_NAME_AR, text));
    }

    private static String unitCode(Matcher unit) {
        return unit.group(1).toUpperCase() + "-" + unit.group(2);
    }

    private static boolean has(String folded, String... keywords) {
        for (var keyword : keywords) {
            if (folded.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private OfflineIntentRouter.Call call(String tool) {
        return new OfflineIntentRouter.Call(tool, json.createObjectNode());
    }
}
