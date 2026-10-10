package dev.proppilot.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Aggregated unpaid rent for one tenant: what is still owed, so partly paid invoices count for their remainder. */
public record OverdueRow(
        Long tenantId,
        String nameEn,
        String nameAr,
        String unitCode,
        long unpaidMonths,
        BigDecimal unpaidAmount,
        LocalDate oldestDueDate) {
}
