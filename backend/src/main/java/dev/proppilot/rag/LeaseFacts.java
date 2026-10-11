package dev.proppilot.rag;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the database knows about one lease and the unit, building and tenant it is for. A lease document is written from
 * these facts and the {@link LeaseTerms} of the lease, so the document always agrees with the data.
 *
 * @param language the tenant's preferred language, and so the language of the document
 */
public record LeaseFacts(long leaseId, String unitCode, String buildingName, String cityEn, String cityAr,
                         String address, int floor, int bedrooms, int areaSqm, String tenantNameEn,
                         String tenantNameAr, DocumentLanguage language, LocalDate startDate, LocalDate endDate,
                         BigDecimal monthlyRent) {

    private static final String CODE_PREFIX = "LSE-";

    /** The document code, such as {@code LSE-A-504}: the prefix of every citation of the document. */
    public String documentCode() {
        return CODE_PREFIX + unitCode;
    }

    public String tenantName() {
        return language.rightToLeft() ? tenantNameAr : tenantNameEn;
    }

    public String city() {
        return language.rightToLeft() ? cityAr : cityEn;
    }
}
