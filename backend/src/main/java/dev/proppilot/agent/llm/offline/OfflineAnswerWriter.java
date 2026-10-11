package dev.proppilot.agent.llm.offline;

import com.fasterxml.jackson.databind.JsonNode;
import dev.proppilot.agent.DecisionNotice;
import dev.proppilot.agent.MoneyText;
import dev.proppilot.rag.CitationRefs;
import java.util.ArrayList;
import java.util.HashSet;

/** Formats a tool result as a short answer in English or Arabic. Every figure comes straight from the tool JSON. */
final class OfflineAnswerWriter {

    private OfflineAnswerWriter() {
    }

    static String write(String tool, JsonNode data, boolean arabic) {
        return switch (tool) {
            case "search_units" -> searchUnits(data, arabic);
            case "get_unit_details" -> unitDetails(data, arabic);
            case "find_overdue_tenants" -> overdue(data, arabic);
            case "get_occupancy_summary" -> occupancy(data, arabic);
            case "get_payment_history" -> paymentHistory(data, arabic);
            case "draft_tenant_message" -> draft(data, arabic);
            case "record_payment", "create_maintenance_request", "send_tenant_reminder" -> proposed(data, arabic);
            case "search_lease_documents" -> leaseClauses(data, arabic);
            case "find_expiring_leases" -> expiringLeases(data, arabic);
            default -> data.toString();
        };
    }

    /**
     * A line under the clauses of a question that listed leases and then searched their documents, when the search
     * covered fewer leases than the list holds. Empty when it covered them all.
     */
    static String searchedFewer(JsonNode listed, JsonNode searched, boolean arabic) {
        int covered = searched.path("scope").path("units").size() + searched.path("noDocumentFor").size();
        int total = listed.path("totalLeases").asInt();
        if (covered >= total) {
            return "";
        }
        return arabic
                ? "اقتصر البحث على أول " + covered + " عقود تنتهي من أصل " + total + "."
                : "I searched only the " + covered + " leases that end first, out of " + total + ".";
    }

    /** Confirms a decision a person took on a proposal; the server wrote the notice. */
    static String decision(DecisionNotice notice, boolean arabic) {
        var summary = arabic ? notice.summaryAr() : notice.summaryEn();
        return switch (notice.status()) {
            case APPROVED -> (arabic ? "تمت الموافقة والتنفيذ. " : "Approved and done. ") + summary + approvedDetail(notice, arabic);
            case REJECTED -> (arabic ? "تم الرفض ولم يتغيّر أي شيء: " : "Rejected, so nothing was changed: ") + summary
                    + (notice.reason() == null ? "" : (arabic ? " (السبب: " : " (reason: ") + notice.reason() + ")");
            default -> (arabic ? "تعذّر تنفيذ التغيير ولم يتغيّر أي شيء: " : "The change could not be made and nothing "
                    + "was changed: ") + (notice.reason() == null ? summary : notice.reason());
        };
    }

    private static String approvedDetail(DecisionNotice notice, boolean ar) {
        var result = notice.result();
        if (result == null || result.isNull()) {
            return "";
        }
        return switch (notice.kind()) {
            case RECORD_PAYMENT -> ar
                    ? " المبلغ المسجّل " + money(result.path("applied")) + " ريال، والمتبقي " + money(result.path("outstandingAfter")) + " ريال."
                    : " SAR " + money(result.path("applied")) + " was applied; SAR " + money(result.path("outstandingAfter"))
                      + " is still outstanding.";
            case CREATE_MAINTENANCE_REQUEST -> ar
                    ? " رقم الطلب " + result.path("requestId").asText() + "."
                    : " The request number is " + result.path("requestId").asText() + ".";
            case SEND_TENANT_REMINDER -> ar ? " الرسالة محفوظة في صندوق الصادر ولم يُرسل شيء." : " It is in the outbox; nothing was sent.";
        };
    }

    private static String proposed(JsonNode d, boolean ar) {
        return ar
                ? "جهّزتُ هذا التغيير لكنه لم يُنفَّذ بعد. حالته: مقترح (PROPOSED) وهو بانتظار موافقتك:\n\n- "
                  + d.path("summaryAr").asText() + "\n\nلن يتغيّر أي شيء قبل أن توافق عليه من التطبيق، ويمكنك رفضه أيضًا."
                : "I prepared this change but have not made it. Status: PROPOSED, waiting for your approval:\n\n- "
                  + d.path("summaryEn").asText() + "\n\nNothing changes until you approve it in the app, and you can reject it too.";
    }

    static String error(String message, boolean arabic) {
        return (arabic ? "تعذّر تنفيذ الطلب: " : "I could not complete that: ") + message;
    }

    static String help(boolean arabic) {
        return arabic
                ? "لم أفهم سؤالك. جرّب مثلاً: «ما الوحدات الشاغرة في الرياض؟» أو «من المستأجرون المتأخرون أكثر من 30 يومًا؟» "
                  + "أو «ملخص الإشغال للمبنى A» أو «صغ رسالة تذكير للوحدة A-203» "
                  + "أو «سجّل دفعة بمبلغ 2000 للوحدة A-203» أو «ما غرامة التأخير في عقد الوحدة A-203؟» "
                  + "أو «ما العقود التي تنتهي خلال 60 يومًا؟»."
                : "I did not understand that. Try: \"Which units in Riyadh are vacant?\", \"Who is more than 30 days late?\", "
                  + "\"Occupancy for building A\", \"Draft a rent reminder for unit A-203\", "
                  + "\"Record a payment of 2000 for unit A-203\", "
                  + "\"What is the late fee in the lease for unit A-203?\" or \"Which leases end in the next 60 days?\".";
    }

    private static String leaseClauses(JsonNode d, boolean ar) {
        var results = d.path("results");
        var sb = new StringBuilder();
        if (results.isEmpty()) {
            sb.append(ar ? "لم أجد في وثائق العقود بندًا يجيب عن هذا السؤال."
                    : "I found no clause in the lease documents that answers this.");
        } else {
            var documents = new HashSet<String>();
            results.forEach(clause -> documents.add(clause.path("document").asText()));
            if (d.path("scope").path("allDocuments").asBoolean()) {
                sb.append(ar ? "لم أتمكن من تحديد الوحدة أو المستأجر المقصود، لذلك بحثتُ في جميع وثائق العقود.\n\n"
                        : "I could not tell which unit or tenant you mean, so I searched every lease document.\n\n");
            }
            sb.append(ar
                    ? "عدد البنود المطابقة: " + results.size() + " من " + documents.size() + " وثيقة عقد:"
                    : results.size() + " matching clause(s) from " + documents.size() + " lease document(s):");
            for (var clause : results) {
                sb.append("\n\n").append(quoted(clause.path("text").asText()))
                        .append("\n\n").append(source(clause, ar));
            }
        }
        var withoutDocument = d.path("noDocumentFor");
        if (!withoutDocument.isEmpty()) {
            var units = new ArrayList<String>();
            withoutDocument.forEach(unit -> units.add(unit.asText()));
            sb.append("\n\n").append(ar ? "لا توجد وثيقة عقد محفوظة للوحدات: " + String.join("، ", units) + "."
                    : "No lease document is on file for unit(s): " + String.join(", ", units) + ".");
        }
        return sb.toString();
    }

    /**
     * A clause as a blockquote, its heading in bold so a number does not read as a list. A citation marker inside the
     * quoted text is turned into plain brackets: only the marker the writer adds is one.
     */
    private static String quoted(String text) {
        var lines = CitationRefs.withoutMarkers(text).strip().split("\\R");
        var quote = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            var line = lines[i].strip();
            if (i > 0) {
                quote.append('\n');
            }
            quote.append(line.isEmpty() ? ">" : "> " + (i == 0 && lines.length > 1 ? "**" + line + "**" : line));
        }
        return quote.toString();
    }

    private static String source(JsonNode clause, boolean ar) {
        var marker = CitationRefs.marker(clause.path("ref").asText());
        var unit = clause.path("unit").asText();
        var tenant = clause.path("tenant").path(ar ? "ar" : "en").asText();
        var document = clause.path("document").asText();
        int page = clause.path("page").asInt();
        return ar
                ? marker + " من الوحدة " + unit + " (" + tenant + ")، وثيقة العقد " + document + "، صفحة " + page + "."
                : marker + " From unit " + unit + " (" + tenant + "), lease document " + document + ", page " + page
                  + ".";
    }

    private static String expiringLeases(JsonNode d, boolean ar) {
        int total = d.path("totalLeases").asInt();
        int days = d.path("withinDays").asInt();
        if (total == 0) {
            return ar ? "لا يوجد عقد ساري ينتهي خلال " + days + " يومًا القادمة."
                    : "No active lease ends within the next " + days + " days.";
        }
        var sb = new StringBuilder(ar
                ? "عدد العقود التي تنتهي خلال " + days + " يومًا القادمة: " + total + "."
                : total + " lease(s) end within the next " + days + " days.");
        sb.append("\n\n").append(ar
                ? "| الوحدة | المستأجر | تاريخ الانتهاء | الأيام المتبقية | الإيجار (ريال/شهر) | وثيقة العقد |"
                  + "\n|---|---|---|---|---|---|"
                : "| Unit | Tenant | Ends | Days left | Rent (SAR/mo) | Lease document |\n|---|---|---|---|---|---|");
        for (var lease : d.path("leases")) {
            sb.append("\n| ").append(lease.path("unitCode").asText())
                    .append(" | ").append(lease.path(ar ? "tenantNameAr" : "tenantNameEn").asText())
                    .append(" | ").append(lease.path("endDate").asText())
                    .append(" | ").append(lease.path("daysLeft").asInt())
                    .append(" | ").append(money(lease.path("monthlyRent")))
                    .append(" | ").append(lease.path("hasLeaseDocument").asBoolean() ? (ar ? "نعم" : "Yes")
                            : (ar ? "لا" : "No")).append(" |");
        }
        if (total > d.path("shown").asInt()) {
            sb.append(ar ? "\n\nعُرضت أول " + d.path("shown").asInt() + " فقط." : "\n\nShowing the first "
                    + d.path("shown").asInt() + ".");
        }
        return sb.toString();
    }

    private static String searchUnits(JsonNode d, boolean ar) {
        var sb = new StringBuilder(ar
                ? "عدد الوحدات المطابقة: " + d.path("total").asInt() + "."
                : d.path("total").asInt() + " matching unit(s).");
        sb.append("\n\n").append(ar
                ? "| الوحدة | المبنى | المدينة | غرف النوم | الإيجار (ريال/شهر) | الحالة |\n|---|---|---|---|---|---|"
                : "| Unit | Building | City | Bedrooms | Rent (SAR/mo) | Status |\n|---|---|---|---|---|---|");
        for (var u : d.path("units")) {
            sb.append("\n| ").append(u.path("code").asText())
                    .append(" | ").append(u.path("building").asText())
                    .append(" | ").append(u.path(ar ? "cityAr" : "cityEn").asText())
                    .append(" | ").append(u.path("bedrooms").asInt())
                    .append(" | ").append(money(u.path("monthlyRent")))
                    .append(" | ").append(unitStatus(u.path("status").asText(), ar)).append(" |");
        }
        if (d.path("total").asInt() > d.path("shown").asInt()) {
            sb.append(ar ? "\n\nعُرضت أول " + d.path("shown").asInt() + " فقط." : "\n\nShowing the first " + d.path("shown").asInt() + ".");
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
        sb.append("\n\n").append(ar
                ? "| المستأجر | الوحدة | أيام التأخر | الأشهر | المبلغ (ريال) |\n|---|---|---|---|---|"
                : "| Tenant | Unit | Days overdue | Months | Unpaid (SAR) |\n|---|---|---|---|---|");
        for (var t : d.path("tenants")) {
            sb.append("\n| ").append(t.path(ar ? "nameAr" : "nameEn").asText())
                    .append(" | ").append(t.path("unitCode").asText())
                    .append(" | ").append(t.path("daysOverdue").asInt())
                    .append(" | ").append(t.path("unpaidMonths").asInt())
                    .append(" | ").append(money(t.path("unpaidAmount"))).append(" |");
        }
        if (total > d.path("shown").asInt()) {
            sb.append(ar ? "\n\nعُرض الأكثر تأخرًا فقط." : "\n\nShowing the most overdue.");
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
                .append(ar ? ") — أشهر غير مدفوعة: " : ") — unpaid months: ").append(d.path("unpaidMonths").asInt())
                .append("\n\n").append(ar
                        ? "| الشهر | الحالة | المبلغ (ريال) | المتبقي (ريال) |\n|---|---|---|---|"
                        : "| Month | Status | Amount (SAR) | Remaining (SAR) |\n|---|---|---|---|");
        for (var p : d.path("payments")) {
            sb.append("\n| ").append(p.path("month").asText())
                    .append(" | ").append(paymentStatus(p.path("status").asText(), ar))
                    .append(" | ").append(money(p.path("amount")))
                    .append(" | ").append(money(p.path("remainingAmount"))).append(" |");
        }
        return sb.toString();
    }

    private static String draft(JsonNode d, boolean ar) {
        return (ar ? "هذه مسودة للمراجعة (لم يتم إرسال أي شيء):" : "Here is a draft for you to review (nothing was sent):")
                + "\n\n" + d.path("subject").asText() + "\n\n" + d.path("body").asText();
    }

    private static String unitStatus(String status, boolean ar) {
        return switch (status) {
            case "VACANT" -> ar ? "شاغرة" : "Vacant";
            case "OCCUPIED" -> ar ? "مشغولة" : "Occupied";
            case "MAINTENANCE" -> ar ? "صيانة" : "Maintenance";
            default -> status;
        };
    }

    private static String paymentStatus(String status, boolean ar) {
        return switch (status) {
            case "UNPAID" -> ar ? "غير مدفوع" : "Unpaid";
            case "PARTIAL" -> ar ? "مدفوع جزئيًا" : "Partially paid";
            case "PAID_LATE" -> ar ? "مدفوع متأخرًا" : "Paid late";
            case "PAID_ON_TIME" -> ar ? "مدفوع في الموعد" : "Paid on time";
            default -> status;
        };
    }

    private static String money(JsonNode amount) {
        return MoneyText.of(amount.decimalValue());
    }
}
