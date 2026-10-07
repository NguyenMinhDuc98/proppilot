package dev.proppilot.agent.tools;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Fixed, polite wording for tenant messages. Drafts only - nothing here sends anything. */
final class MessageTemplates {

    record Draft(String subject, String body) {
    }

    record Context(String tenantName, String unitCode, String building, long unpaidMonths,
                   BigDecimal unpaidAmount, LocalDate oldestDue, LocalDate leaseEnd) {
    }

    private MessageTemplates() {
    }

    static Draft rentReminder(boolean arabic, Context c) {
        if (arabic) {
            return new Draft("تذكير ودّي بسداد إيجار الوحدة " + c.unitCode(),
                    "عزيزي/عزيزتي " + c.tenantName() + "،\n\nنأمل أن تكونوا بخير. نود تذكيركم بوجود إيجار مستحق للوحدة "
                            + c.unitCode() + " (" + c.building() + ") عن " + c.unpaidMonths() + " شهر بإجمالي "
                            + c.unpaidAmount().stripTrailingZeros().toPlainString() + " ريال سعودي، وأقدم استحقاق بتاريخ "
                            + date(c.oldestDue(), true) + ". إذا تم السداد مسبقًا فنرجو تجاهل هذه الرسالة، وإلا نأمل السداد في أقرب "
                            + "وقت ممكن. لا تترددوا في التواصل معنا لأي استفسار.\n\nمع خالص التحية،\nإدارة العقارات");
        }
        return new Draft("Friendly reminder: rent for unit " + c.unitCode(),
                "Dear " + c.tenantName() + ",\n\nI hope you are well. This is a friendly reminder that rent for unit "
                        + c.unitCode() + " (" + c.building() + ") is outstanding for " + c.unpaidMonths() + " month(s), totalling SAR "
                        + c.unpaidAmount().stripTrailingZeros().toPlainString() + ", with the oldest due on "
                        + date(c.oldestDue(), false) + ". If you have already paid, please disregard this message; otherwise "
                        + "we would appreciate payment at your earliest convenience. Please reach out if there is anything "
                        + "we can help with.\n\nKind regards,\nProperty Management");
    }

    static Draft leaseRenewal(boolean arabic, Context c) {
        if (arabic) {
            return new Draft("تجديد عقد إيجار الوحدة " + c.unitCode(),
                    "عزيزي/عزيزتي " + c.tenantName() + "،\n\nينتهي عقد إيجار الوحدة " + c.unitCode() + " بتاريخ "
                            + date(c.leaseEnd(), true) + ". يسعدنا استمراركم معنا ونودّ مناقشة تجديد العقد. "
                            + "يرجى التواصل معنا لتحديد موعد مناسب.\n\nمع خالص التحية،\nإدارة العقارات");
        }
        return new Draft("Lease renewal for unit " + c.unitCode(),
                "Dear " + c.tenantName() + ",\n\nYour lease for unit " + c.unitCode() + " ends on "
                        + date(c.leaseEnd(), false) + ". We would love to have you stay and would like to discuss "
                        + "renewing it. Please let us know a convenient time to talk.\n\nKind regards,\nProperty Management");
    }

    static Draft maintenanceNotice(boolean arabic, Context c) {
        if (arabic) {
            return new Draft("إشعار صيانة في " + c.building(),
                    "عزيزي/عزيزتي " + c.tenantName() + "،\n\nنود إعلامكم بأنه ستُجرى أعمال صيانة دورية في مبنى "
                            + c.building() + " قد تشمل الوحدة " + c.unitCode() + ". سنتواصل معكم لتأكيد الموعد، "
                            + "ونشكر لكم تعاونكم.\n\nمع خالص التحية،\nإدارة العقارات");
        }
        return new Draft("Maintenance notice for " + c.building(),
                "Dear " + c.tenantName() + ",\n\nWe would like to let you know that routine maintenance will take place in "
                        + c.building() + " and may involve unit " + c.unitCode() + ". We will contact you to confirm "
                        + "the timing. Thank you for your understanding.\n\nKind regards,\nProperty Management");
    }

    private static String date(LocalDate date, boolean arabic) {
        return date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", arabic ? Locale.forLanguageTag("ar") : Locale.ENGLISH));
    }
}
