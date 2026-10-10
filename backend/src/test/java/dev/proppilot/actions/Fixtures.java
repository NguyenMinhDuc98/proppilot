package dev.proppilot.actions;

import dev.proppilot.domain.Building;
import dev.proppilot.domain.Lease;
import dev.proppilot.domain.Payment;
import dev.proppilot.domain.Tenant;
import dev.proppilot.domain.Unit;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.test.util.ReflectionTestUtils;

/** Entities have no setters on purpose; unit tests build them field by field. */
final class Fixtures {

    private Fixtures() {
    }

    static Unit unit(String code) {
        var building = new Building();
        ReflectionTestUtils.setField(building, "name", "Palm Residence");
        var unit = new Unit();
        ReflectionTestUtils.setField(unit, "id", 17L);
        ReflectionTestUtils.setField(unit, "code", code);
        ReflectionTestUtils.setField(unit, "building", building);
        return unit;
    }

    static Lease lease(String unitCode, LocalDate start) {
        var tenant = new Tenant();
        ReflectionTestUtils.setField(tenant, "id", 7L);
        ReflectionTestUtils.setField(tenant, "nameEn", "Ahmed Al-Harbi");
        ReflectionTestUtils.setField(tenant, "nameAr", "أحمد الحربي");
        ReflectionTestUtils.setField(tenant, "preferredLanguage", "en");
        var lease = new Lease();
        ReflectionTestUtils.setField(lease, "id", 99L);
        ReflectionTestUtils.setField(lease, "unit", unit(unitCode));
        ReflectionTestUtils.setField(lease, "tenant", tenant);
        ReflectionTestUtils.setField(lease, "startDate", start);
        ReflectionTestUtils.setField(lease, "endDate", start.plusYears(1));
        return lease;
    }

    static Payment invoice(long id, LocalDate period, String amount, String paid) {
        var payment = new Payment();
        ReflectionTestUtils.setField(payment, "id", id);
        ReflectionTestUtils.setField(payment, "period", period);
        ReflectionTestUtils.setField(payment, "dueDate", period);
        ReflectionTestUtils.setField(payment, "amount", new BigDecimal(amount));
        ReflectionTestUtils.setField(payment, "paidAmount", new BigDecimal(paid));
        return payment;
    }
}
