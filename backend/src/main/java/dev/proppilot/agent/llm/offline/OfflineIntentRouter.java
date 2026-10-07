package dev.proppilot.agent.llm.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword-based stand-in for the model's tool selection (English and Arabic).
 * Maps a question to at most one tool call; returns empty when it does not understand.
 */
final class OfflineIntentRouter {

    record Call(String tool, ObjectNode args) {

        Call put(String name, String value) {
            args.put(name, value);
            return this;
        }
    }

    private static final Pattern UNIT_CODE = Pattern.compile("\\b([A-Fa-f])-?(\\d{3})\\b");
    private static final Pattern BUILDING = Pattern.compile("(?:building|tower|مبنى|المبنى|برج)\\s*([A-Fa-f])\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAYS = Pattern.compile("(\\d+)\\s*(?:days?|day|يوم|أيام|ايام)");
    private static final Pattern BEDROOMS = Pattern.compile("(\\d)\\s*(?:-?\\s*bed(?:room)?s?|br\\b|غرف|غرفة)");
    private static final Pattern MONTH = Pattern.compile("\\b(20\\d{2}-(?:0[1-9]|1[0-2]))\\b");
    private static final Pattern TENANT_NAME = Pattern.compile("tenant\\s+([A-Za-z][A-Za-z-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TENANT_NAME_AR = Pattern.compile("(?:للمستأجر|المستأجر)\\s+(\\S+)");

    private static final Map<String, String> CITIES = Map.of(
            "riyadh", "Riyadh", "الرياض", "Riyadh", "jeddah", "Jeddah", "جدة", "Jeddah", "dammam", "Dammam", "الدمام", "Dammam");

    private final ObjectMapper json;

    OfflineIntentRouter(ObjectMapper json) {
        this.json = json;
    }

    Optional<Call> route(String question) {
        var q = question.toLowerCase();
        var unit = UNIT_CODE.matcher(question);
        String unitCode = unit.find() ? unit.group(1).toUpperCase() + "-" + unit.group(2) : null;

        if (hasAny(q, "draft", "reminder", "write a message", "رسالة", "تذكير", "صياغة", "صيغ")) {
            return draft(question, q, unitCode);
        }
        if (hasAny(q, "payment history", "payments of", "payment record", "سجل الدفع", "سجل المدفوعات", "سجل")) {
            return unitCode == null ? Optional.empty() : Optional.of(call("get_payment_history").put("unit_code", unitCode));
        }
        if (hasAny(q, "late", "overdue", "arrears", "behind on", "متأخر", "تأخر", "متأخرين")) {
            var call = call("find_overdue_tenants");
            first(DAYS, q).ifPresentOrElse(d -> call.args().put("min_days_overdue", Integer.parseInt(d)),
                    () -> first(Pattern.compile("(\\d+)"), q).ifPresent(d -> call.args().put("min_days_overdue", Integer.parseInt(d))));
            return Optional.of(call);
        }
        if (hasAny(q, "occupancy", "collected", "collection", "occupied rate", "إشغال", "الإشغال", "تحصيل", "المحصل", "محصل")) {
            var call = call("get_occupancy_summary");
            building(question).ifPresent(b -> call.args().put("building", b));
            first(MONTH, q).ifPresent(m -> call.args().put("month", m));
            return Optional.of(call);
        }
        if (unitCode != null) {
            return Optional.of(call("get_unit_details").put("unit_code", unitCode));
        }
        if (hasAny(q, "vacant", "available", "empty", "units", "apartments", "شاغر", "شاغرة", "متاح", "فارغ", "وحدات", "وحدة", "شقق")) {
            return Optional.of(searchUnits(question, q));
        }
        return Optional.empty();
    }

    private Optional<Call> draft(String question, String q, String unitCode) {
        var call = call("draft_tenant_message");
        if (unitCode != null) {
            call.args().put("unit_code", unitCode);
        } else {
            var name = first(TENANT_NAME, question).or(() -> first(TENANT_NAME_AR, question));
            if (name.isEmpty()) {
                return Optional.empty();
            }
            call.args().put("tenant_name", name.get());
        }
        call.args().put("purpose", hasAny(q, "renew", "تجديد") ? "LEASE_RENEWAL"
                : hasAny(q, "maintenance", "صيانة") ? "MAINTENANCE_NOTICE" : "RENT_REMINDER");
        boolean wantsEnglish = hasAny(q, "in english", "بالإنجليزية", "بالانجليزي", "بالإنجليزي");
        boolean wantsArabic = hasAny(q, "in arabic", "arabic", "بالعربية", "بالعربي");
        if (wantsArabic || (QuestionLanguage.isArabic(question) && !wantsEnglish)) {
            call.args().put("language", "AR");
        } else if (wantsEnglish) {
            call.args().put("language", "EN");
        }
        return Optional.of(call);
    }

    private Call searchUnits(String question, String q) {
        var call = call("search_units");
        CITIES.forEach((keyword, city) -> {
            if (q.contains(keyword) || question.contains(keyword)) {
                call.args().put("city", city);
            }
        });
        building(question).ifPresent(b -> call.args().put("building", b));
        first(BEDROOMS, q).ifPresent(n -> call.args().put("bedrooms", Integer.parseInt(n)));
        if (hasAny(q, "vacant", "available", "empty", "شاغر", "شاغرة", "متاح", "فارغ")) {
            call.args().put("status", "VACANT");
        } else if (hasAny(q, "maintenance", "صيانة")) {
            call.args().put("status", "MAINTENANCE");
        }
        return call;
    }

    private Optional<String> building(String question) {
        Matcher m = BUILDING.matcher(question);
        return m.find() ? Optional.of(m.group(1).toUpperCase()) : Optional.empty();
    }

    private Call call(String tool) {
        return new Call(tool, json.createObjectNode());
    }

    private static Optional<String> first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static boolean hasAny(String text, String... keywords) {
        for (var keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
