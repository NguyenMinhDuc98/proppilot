package dev.proppilot.agent.llm.offline;

import com.fasterxml.jackson.databind.JsonNode;

/** Formats a tool result as a short answer in English or Arabic. Every figure comes straight from the tool JSON. */
final class OfflineAnswerWriter {

    private OfflineAnswerWriter() {
    }

    static String write(String tool, JsonNode data, boolean arabic) {
        var body = switch (tool) {
            case "search_units" -> searchUnits(data, arabic);
            case "get_unit_details" -> unitDetails(data, arabic);
            case "find_overdue_tenants" -> overdue(data, arabic);
            case "get_occupancy_summary" -> occupancy(data, arabic);
            case "get_payment_history" -> paymentHistory(data, arabic);
            case "draft_tenant_message" -> draft(data, arabic);
            default -> data.toString();
        };
        return body + "\n\n" + (arabic ? "البيانات من: " : "Data: ") + tool;
    }

    static String error(String message, boolean arabic) {
        return (arabic ? "تعذّر تنفيذ الطلب: " : "I could not complete that: ") + message;
    }

    static String help(boolean arabic) {
        return arabic
                ? "لم أفهم سؤالك. جرّب مثلاً: «ما الوحدات الشاغرة في الرياض؟» أو «من المستأجرون المتأخرون أكثر من 30 يومًا؟» "
                  + "أو «ملخص الإشغال للمبنى A» أو «صغ رسالة تذكير للوحدة A-203»."
                : "I did not understand that. Try: \"Which units in Riyadh are vacant?\", \"Who is more than 30 days late?\", "
                  + "\"Occupancy for building A\" or \"Draft a rent reminder for unit A-203\".";
    }

    private static String searchUnits(JsonNode d, boolean ar) {
        var sb = new StringBuilder(ar
                ? "عدد الوحدات المطابقة: " + d.path("total").asInt() + "."
                : d.path("total").asInt() + " matching unit(s).");
        for (var u : d.path("units")) {
            sb.append("\n- ").append(u.path("code").asText()).append(" · ").append(u.path("building").asText())
                    .append(" (").append(u.path(ar ? "cityAr" : "cityEn").asText()).append(") · ")
                    .append(u.path("bedrooms").asInt()).append(ar ? " غرف · " : " BR · ")
                    .append(money(u.path("monthlyRent"))).append(ar ? " ريال/شهر · " : " SAR/mo · ")
                    .append(u.path("status").asText());
        }
        if (d.path("total").asInt() > d.path("shown").asInt()) {
            sb.append(ar ? "\n(عُرضت أول " + d.path("shown").asInt() + " فقط)" : "\n(showing the first " + d.path("shown").asInt() + ")");
        }
        return sb.toString();
    }

    private static String unitDetails(JsonNode d, boolean ar) {
        var u = d.path("unit");
        var sb = new StringBuilder(ar ? "الوحدة " : "Unit ").append(u.path("code").asText()).append(" — ")
                .append(u.path("building").asText()).append(", ").append(u.path(ar ? "cityAr" : "cityEn").asText())
                .append(ar ? "\n- الحالة: " : "\n- Status: ").append(u.path("status").asText())
                .append(ar ? "\n- الإيجار: " : "\n- Rent: ").append(money(u.path("monthlyRent"))).append(ar ? " ريال/شهر" : " SAR/month")
                .append(ar ? "\n- غرف النوم: " : "\n- Bedrooms: ").append(u.path("bedrooms").asInt())
                .append(ar ? "\n- المساحة: " : "\n- Area: ").append(u.path("areaSqm").asInt()).append(ar ? " م²" : " sqm");
        var t = d.path("currentTenant");
        if (t.isMissingNode() || t.isNull()) {
            sb.append(ar ? "\n- لا يوجد مستأجر حالي." : "\n- No current tenant.");
        } else {
            sb.append(ar ? "\n- المستأجر: " : "\n- Tenant: ").append(t.path(ar ? "nameAr" : "nameEn").asText())
                    .append(ar ? "\n- العقد: " : "\n- Lease: ").append(t.path("leaseStart").asText()).append(" → ").append(t.path("leaseEnd").asText())
                    .append(ar ? "\n- أشهر متأخرة: " : "\n- Overdue months: ").append(t.path("overdueMonths").asInt())
                    .append(" (").append(money(t.path("overdueAmount"))).append(ar ? " ريال)" : " SAR)");
        }
        return sb.toString();
    }

    private static String overdue(JsonNode d, boolean ar) {
        int total = d.path("totalTenants").asInt();
        var sb = new StringBuilder(ar
                ? "عدد المستأجرين المتأخرين أكثر من " + d.path("minDaysOverdue").asInt() + " يوم: " + total
                        + " (إجمالي المستحق " + money(d.path("totalUnpaidAmount")) + " ريال)."
                : total + " tenant(s) more than " + d.path("minDaysOverdue").asInt() + " days overdue (total unpaid "
                        + money(d.path("totalUnpaidAmount")) + " SAR).");
        for (var t : d.path("tenants")) {
            sb.append("\n- ").append(t.path(ar ? "nameAr" : "nameEn").asText()).append(" · ").append(t.path("unitCode").asText())
                    .append(" · ").append(t.path("daysOverdue").asInt()).append(ar ? " يوم · " : " days · ")
                    .append(t.path("unpaidMonths").asInt()).append(ar ? " شهر · " : " month(s) · ")
                    .append(money(t.path("unpaidAmount"))).append(ar ? " ريال" : " SAR");
        }
        if (total > d.path("shown").asInt()) {
            sb.append(ar ? "\n(عُرض الأكثر تأخرًا فقط)" : "\n(showing the most overdue)");
        }
        return sb.toString();
    }

    private static String occupancy(JsonNode d, boolean ar) {
        var scope = d.path("building").asText();
        return ar
                ? "ملخص " + (scope.equals("ALL") ? "جميع المباني" : "المبنى " + scope) + " لشهر " + d.path("month").asText() + ":"
                  + "\n- الوحدات: " + d.path("totalUnits").asInt() + " (مشغولة " + d.path("occupiedUnits").asInt() + "، شاغرة "
                  + d.path("vacantUnits").asInt() + "، صيانة " + d.path("maintenanceUnits").asInt() + ")"
                  + "\n- نسبة الإشغال: " + d.path("occupancyRatePercent").asText() + "%"
                  + "\n- الإيجار المحصّل: " + money(d.path("rentCollected")) + " من " + money(d.path("rentExpected")) + " ريال ("
                  + d.path("collectionRatePercent").asText() + "%)"
                  + "\n- فواتير غير مدفوعة: " + d.path("unpaidInvoices").asInt()
                : "Summary for " + (scope.equals("ALL") ? "all buildings" : "building " + scope) + ", " + d.path("month").asText() + ":"
                  + "\n- Units: " + d.path("totalUnits").asInt() + " (occupied " + d.path("occupiedUnits").asInt() + ", vacant "
                  + d.path("vacantUnits").asInt() + ", maintenance " + d.path("maintenanceUnits").asInt() + ")"
                  + "\n- Occupancy: " + d.path("occupancyRatePercent").asText() + "%"
                  + "\n- Rent collected: " + money(d.path("rentCollected")) + " of " + money(d.path("rentExpected")) + " SAR ("
                  + d.path("collectionRatePercent").asText() + "%)"
                  + "\n- Unpaid invoices: " + d.path("unpaidInvoices").asInt();
    }

    private static String paymentHistory(JsonNode d, boolean ar) {
        var sb = new StringBuilder(ar ? "سجل دفعات " : "Payment history for ")
                .append(d.path(ar ? "tenantAr" : "tenantEn").asText()).append(" (").append(d.path("unitCode").asText())
                .append(ar ? ") — أشهر غير مدفوعة: " : ") — unpaid months: ").append(d.path("unpaidMonths").asInt());
        for (var p : d.path("payments")) {
            sb.append("\n- ").append(p.path("month").asText()).append(": ").append(p.path("status").asText())
                    .append(" (").append(money(p.path("amount"))).append(ar ? " ريال)" : " SAR)");
        }
        return sb.toString();
    }

    private static String draft(JsonNode d, boolean ar) {
        return (ar ? "هذه مسودة للمراجعة (لم يتم إرسال أي شيء):" : "Here is a draft for you to review (nothing was sent):")
                + "\n\n" + d.path("subject").asText() + "\n\n" + d.path("body").asText();
    }

    private static String money(JsonNode amount) {
        return amount.decimalValue().stripTrailingZeros().toPlainString();
    }
}
