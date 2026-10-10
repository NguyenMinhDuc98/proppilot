package dev.proppilot.actions;

import dev.proppilot.agent.tools.MessageDrafter;
import dev.proppilot.domain.MaintenanceCategory;
import dev.proppilot.domain.MaintenancePriority;
import dev.proppilot.domain.PaymentMethod;

/** The words the English and Arabic summaries use for the enum values. */
final class ActionLabels {

    private ActionLabels() {
    }

    static String method(PaymentMethod method, boolean arabic) {
        return switch (method) {
            case CASH -> arabic ? "نقدًا" : "cash";
            case BANK_TRANSFER -> arabic ? "تحويل بنكي" : "bank transfer";
            case CARD -> arabic ? "بطاقة" : "card";
            case CHEQUE -> arabic ? "شيك" : "cheque";
        };
    }

    static String category(MaintenanceCategory category, boolean arabic) {
        return switch (category) {
            case PLUMBING -> arabic ? "سباكة" : "plumbing";
            case ELECTRICAL -> arabic ? "كهرباء" : "electrical";
            case HVAC -> arabic ? "تكييف" : "HVAC";
            case APPLIANCE -> arabic ? "أجهزة" : "appliance";
            case STRUCTURAL -> arabic ? "إنشائية" : "structural";
            case CLEANING -> arabic ? "نظافة" : "cleaning";
            case OTHER -> arabic ? "أخرى" : "other";
        };
    }

    static String priority(MaintenancePriority priority, boolean arabic) {
        return switch (priority) {
            case LOW -> arabic ? "منخفضة" : "low";
            case MEDIUM -> arabic ? "متوسطة" : "medium";
            case HIGH -> arabic ? "عالية" : "high";
            case URGENT -> arabic ? "عاجلة" : "urgent";
        };
    }

    /** Reads after "a" in English and after "رسالة" (message) in Arabic. */
    static String purpose(MessageDrafter.Purpose purpose, boolean arabic) {
        return switch (purpose) {
            case RENT_REMINDER -> arabic ? "تذكير بالإيجار" : "rent reminder";
            case LEASE_RENEWAL -> arabic ? "بشأن تجديد العقد" : "lease renewal notice";
            case MAINTENANCE_NOTICE -> arabic ? "إشعار بالصيانة" : "maintenance notice";
        };
    }

    /** Reads after "in" (English) and "باللغة" (Arabic). */
    static String language(MessageDrafter.Language language, boolean arabic) {
        return switch (language) {
            case EN -> arabic ? "الإنجليزية" : "English";
            case AR -> arabic ? "العربية" : "Arabic";
        };
    }
}
