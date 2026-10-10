package dev.proppilot.agent.llm.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises requests to change something (record a payment, open a maintenance request, send a reminder) in English and
 * Arabic and turns them into calls of the proposing tools. A request has to start with a command ("record a payment
 * ...", "سجّل دفعة ..."), so questions about payment history or drafting a reminder still reach the read tools.
 *
 * <p>Matching runs on a folded copy of the question that has the same length as the original, so a position found in
 * it is a position in the text the visitor wrote and the description can be cut from that text. Keywords are written
 * in the folded spelling.
 */
final class OfflineWriteIntents {

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static final String LEAD = "^\\W*(?:(?:please|kindly|can you|could you|would you|i want to|i need to|i would like to"
            + "|i'd like to|من فضلك|لو سمحت|رجاء|ارجو|اريد|ممكن|يمكنك)\\s+)*";

    private static final Pattern RECORD_PAYMENT = Pattern.compile(LEAD
            + "(?:(?:record|log|register|enter|add|post)\\b.*\\b(?:payment|received)\\b"
            + "|(?:سجل|تسجيل|اضف|اضافه|ادخل|ادخال|قيد)\\b.*(?<!\\p{L})دفعه(?!\\p{L}))", FLAGS);
    private static final Pattern MAINTENANCE_REQUEST = Pattern.compile(LEAD
            + "(?:(?:create|open|log|raise|file|submit|add|record|register)\\b.*\\b(?:maintenance|repair)\\s+"
            + "(?:request|ticket|issue|job|order|case)s?\\b"
            + "|(?:انشي|انشاء|افتح|فتح|سجل|تسجيل|اضف|اضافه|ارفع|رفع|قدم)\\b.*(?:طلب|بلاغ|تذكره)\\s+(?:ال)?صيانه)", FLAGS);
    private static final Pattern REMINDER = Pattern.compile(LEAD
            + "(?:(?:send|queue|issue|dispatch|email|text)\\b.*\\b(?:reminder|notice)\\b|remind\\b(?!\\s+me)"
            + "|(?:ارسل|ارسال|ابعث|بعث|اشعر)\\b.*تذكير)", FLAGS);
    private static final Pattern DRAFTING = Pattern.compile("\\b(?:draft|write|compose)\\b|(?<!\\p{L})(?:صغ|اكتب|مسوده|صياغه)",
            FLAGS);

    private static final Pattern DIACRITICS = Pattern.compile("[\\u064B-\\u0652\\u0640]");
    private static final Pattern DATE = Pattern.compile("\\b(20\\d{2}-\\d{2}-\\d{2})\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private record Category(String name, Pattern pattern) {

        Category(String name, String regex) {
            this(name, Pattern.compile(regex, FLAGS));
        }
    }

    /** The first category with a keyword wins, so "AC leaking water" is an HVAC problem and not a plumbing one. */
    private static final List<Category> CATEGORIES = List.of(
            new Category("HVAC", "\\b(?:hvac|a/c|ac|aircon|cooling|heating|ventilation|thermostat)\\b"
                    + "|air[- ]condition|تكييف|مكيف|تبريد|تدفئه|تهويه"),
            new Category("ELECTRICAL", "electric|\\b(?:power|outlet|socket|wiring|wire|light|lights|lamp|breaker|fuse)\\b"
                    + "|كهرب|انار|مصباح|اضاءه|اسلاك|فيوز"),
            new Category("APPLIANCE", "appliance|fridge|refrigerator|oven|stove|washer|washing machine|dishwasher|dryer"
                    + "|microwave|heater|ثلاجه|فرن|غساله|سخان|ميكروويف|(?<!\\p{L})جهاز"),
            new Category("PLUMBING", "plumb|leak|pipe|water|\\b(?:tap|faucet|drain\\w*|toilet|sink|shower|sewage|clog\\w*)\\b"
                    + "|سباك|تسرب|تسريب|ماسور|مواسير|مياه|حنفي|مجاري|تصريف|مرحاض"),
            new Category("STRUCTURAL", "crack|\\b(?:walls?|ceiling|roof|floor|foundation|structural|door|window)\\b"
                    + "|تشقق|شقوق|جدار|حائط|سقف|ارضي|(?<!\\p{L})باب(?!\\p{L})|نافذ|شباك|هيكل"),
            new Category("CLEANING", "clean|trash|garbage|نظاف|تنظيف|نظيف"));

    private static final Pattern URGENT = Pattern.compile("\\b(?:urgent|emergency|asap|immediately)\\b"
            + "|عاجل|طاري|طوارئ|فورا|مستعجل", FLAGS);
    private static final Pattern HIGH = Pattern.compile("\\bhigh\\b|عالي|مرتفع", FLAGS);
    private static final Pattern LOW = Pattern.compile("\\blow\\b|منخفض", FLAGS);

    private static final int MIN_DESCRIPTION_LENGTH = 5;
    private static final Pattern PRIORITY_PHRASE = Pattern.compile("(?:priority\\s*[:=]?\\s*(?:low|medium|normal|high|urgent)"
            + "|(?:low|medium|normal|high|urgent)\\s+priority|(?:ب|ذات\\s+)?(?:ال)?اولويه\\s*[:=]?\\s*\\S+"
            + "|[,;،]?\\s*(?:urgent|asap|emergency|high|medium|low|عاجله?|مستعجل|عاليه|متوسطه|منخفضه)\\s*$)", FLAGS);
    private static final Pattern COMMAND_PREFIX = Pattern.compile("^.*?(?:\\b(?:request|ticket|issue|job|order|case)s?\\b|صيانه)",
            FLAGS);
    private static final Pattern LEADING_FILLER = Pattern.compile("^[\\s:,;.\\-–—،]*"
            + "(?:(?:about|regarding|because|saying|describing|بخصوص|بشأن|بشان|بسبب|حول)\\s+)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern UNIT_REFERENCE = Pattern.compile("(?:\\b(?:in|for|at|on)\\s+)?\\b(?:unit|apartment|flat)\\s*$"
            + "|(?:في\\s+)?(?:لل|ل)?(?:ال)?(?:وحد[ةه]|شق[ةه])\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\s,;:.\\-–—،]+$");

    private final ObjectMapper json;

    OfflineWriteIntents(ObjectMapper json) {
        this.json = json;
    }

    /** Empty unless the question starts with a command to change something. */
    Optional<OfflineIntentRouter.Call> route(String question) {
        var text = DIACRITICS.matcher(question).replaceAll("");
        var folded = fold(text);
        if (RECORD_PAYMENT.matcher(folded).find()) {
            return Optional.of(recordPayment(text, folded));
        }
        if (MAINTENANCE_REQUEST.matcher(folded).find()) {
            return Optional.of(createMaintenanceRequest(text, folded));
        }
        if (REMINDER.matcher(folded).find() && !DRAFTING.matcher(folded).find()) {
            return Optional.of(sendReminder(text, folded));
        }
        return Optional.empty();
    }

    private OfflineIntentRouter.Call recordPayment(String text, String folded) {
        var call = call("record_payment");
        var unit = OfflineIntentRouter.UNIT_CODE.matcher(folded);
        boolean hasUnit = unit.find();
        if (hasUnit) {
            call.put("unit_code", unitCode(unit));
        } else {
            tenantName(text).ifPresent(name -> call.put("tenant_name", name));
        }
        // The unit code and the date hold digits too; what is left holds the amount.
        var remaining = new StringBuilder(folded);
        var date = DATE.matcher(folded);
        if (date.find()) {
            call.put("payment_date", date.group(1));
            blank(remaining, date.start(), date.end());
        }
        if (hasUnit) {
            blank(remaining, unit.start(), unit.end());
        }
        var amount = NUMBER.matcher(remaining);
        if (amount.find()) {
            call.args().put("amount", new BigDecimal(amount.group().replace(",", "")));
        }
        method(folded).ifPresent(method -> call.put("method", method));
        return call;
    }

    private OfflineIntentRouter.Call createMaintenanceRequest(String text, String folded) {
        var call = call("create_maintenance_request");
        var unit = OfflineIntentRouter.UNIT_CODE.matcher(folded);
        int from = 0;
        int to = folded.length();
        if (unit.find()) {
            call.put("unit_code", unitCode(unit));
            from = unit.end();
            to = unit.start();
        }
        call.put("category", category(folded));
        call.put("priority", priority(folded));
        // The problem comes after the unit code ("... for unit A-203: leaking pipe") or before it ("... about a
        // leaking pipe in unit A-203").
        var description = describe(text, folded, from, text.length());
        if (description.length() < MIN_DESCRIPTION_LENGTH) {
            description = describeBefore(text, folded, to);
        }
        if (!description.isEmpty()) {
            call.put("description", description);
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

    private static String category(String folded) {
        return CATEGORIES.stream().filter(category -> category.pattern().matcher(folded).find())
                .map(Category::name).findFirst().orElse("OTHER");
    }

    private static String priority(String folded) {
        if (URGENT.matcher(folded).find()) {
            return "URGENT";
        }
        if (HIGH.matcher(folded).find()) {
            return "HIGH";
        }
        return LOW.matcher(folded).find() ? "LOW" : "MEDIUM";
    }

    private static String describeBefore(String text, String folded, int to) {
        var command = COMMAND_PREFIX.matcher(folded).region(0, to);
        int from = command.lookingAt() ? command.end() : 0;
        var description = describe(text, folded, from, to);
        return TRAILING_PUNCTUATION.matcher(UNIT_REFERENCE.matcher(description).replaceFirst("")).replaceFirst("");
    }

    /** The text between two positions without the priority phrase, tidied up. */
    private static String describe(String text, String folded, int from, int to) {
        var kept = new StringBuilder();
        var phrase = PRIORITY_PHRASE.matcher(folded).region(from, to);
        int copied = from;
        while (phrase.find()) {
            kept.append(text, copied, phrase.start());
            copied = phrase.end();
        }
        kept.append(text, copied, to);
        var withoutFiller = LEADING_FILLER.matcher(kept).replaceFirst("");
        return TRAILING_PUNCTUATION.matcher(withoutFiller).replaceFirst("");
    }

    private static Optional<String> tenantName(String text) {
        return OfflineIntentRouter.first(OfflineIntentRouter.TENANT_NAME, text)
                .or(() -> OfflineIntentRouter.first(OfflineIntentRouter.TENANT_NAME_AR, text));
    }

    private static String unitCode(Matcher unit) {
        return unit.group(1).toUpperCase() + "-" + unit.group(2);
    }

    private static void blank(StringBuilder text, int from, int to) {
        text.replace(from, to, " ".repeat(to - from));
    }

    private static boolean has(String folded, String... keywords) {
        for (var keyword : keywords) {
            if (folded.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** Lower case, one Arabic spelling per letter and ASCII digits, one character for one character. */
    private static String fold(String text) {
        var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            out.append(foldChar(text.charAt(i)));
        }
        return out.toString();
    }

    private static char foldChar(char c) {
        return switch (c) {
            case 'أ', 'إ', 'آ' -> 'ا';
            case 'ى', 'ئ' -> 'ي';
            case 'ؤ' -> 'و';
            case 'ة' -> 'ه';
            case '٫' -> '.';
            case '٬' -> ',';
            default -> Character.isDigit(c) ? (char) ('0' + Character.digit(c, 10)) : Character.toLowerCase(c);
        };
    }

    private OfflineIntentRouter.Call call(String tool) {
        return new OfflineIntentRouter.Call(tool, json.createObjectNode());
    }
}
