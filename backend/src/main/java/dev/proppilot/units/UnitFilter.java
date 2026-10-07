package dev.proppilot.units;

import dev.proppilot.domain.UnitStatus;
import java.math.BigDecimal;

/** All fields optional; null means "do not filter". */
public record UnitFilter(
        String city,
        String building,
        UnitStatus status,
        Integer bedrooms,
        BigDecimal minRent,
        BigDecimal maxRent) {
}
