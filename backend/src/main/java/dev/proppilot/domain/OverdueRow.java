package dev.proppilot.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Aggregated unpaid rent for one tenant. */
public record OverdueRow(
        Long tenantId,
        String nameEn,
        String nameAr,
        String unitCode,
        long unpaidMonths,
        BigDecimal unpaidAmount,
        LocalDate oldestDueDate) {
}
