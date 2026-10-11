package dev.proppilot.rag;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Lease facts written by hand, so generator tests need no database. */
public final class LeaseFixtures {

    private LeaseFixtures() {
    }

    public static LeaseFacts english() {
        return facts(7, "B-312", "Najd Towers", DocumentLanguage.EN, new BigDecimal("4250.00"));
    }

    public static LeaseFacts arabic() {
        return facts(1, "A-504", "Palm Residence", DocumentLanguage.AR, new BigDecimal("4950.00"));
    }

    public static LeaseFacts facts(long leaseId, String unitCode, String building, DocumentLanguage language,
                                   BigDecimal rent) {
        return new LeaseFacts(leaseId, unitCode, building, "Riyadh", "الرياض", "Al Malqa District, Riyadh", 3, 2, 96,
                "Khalid Al-Qahtani", "خالد القحطاني", language, LocalDate.of(2025, 11, 1), LocalDate.of(2027, 11, 30),
                rent);
    }
}
