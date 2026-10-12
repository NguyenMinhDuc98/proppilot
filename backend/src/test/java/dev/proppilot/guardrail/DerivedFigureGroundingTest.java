package dev.proppilot.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The first measurement of the guardrail on real Claude answers (2026-10-12, the 20 read questions) flagged seven
 * answers, all of them correct. This is what the rules for derived figures do with them. The fixtures are the results
 * that the tools behind those questions return from the seeded database on that date, read with the same tools through
 * the offline assistant (the amounts carry an exponent, as Jackson writes a whole BigDecimal): the stored trace of the
 * live run holds no tool results, so they are what the model was given if the demo's data was the seed's, which I could
 * not check. The answers are wordings I made up, in English and in Arabic, of the figures that were flagged; they are
 * not recordings.
 *
 * <p>Each test names the derivation that makes a figure of an answer follow from a result. Of the seven flagged
 * figures, four follow from a rule (11 and 11, 19,200 and 19,200) and three follow from none that this class can show
 * (50, 50 and 57,000), and those stay reported, as does every figure that is off by one from a derived one.
 */
class DerivedFigureGroundingTest {

    private static final TenantNames.Name AHMED = new TenantNames.Name("Ahmed Al-Harbi", "أحمد الحربي");
    private static final TenantNames.Name SARA = new TenantNames.Name("Sara Al-Qahtani", "سارة القحطاني");
    private static final TenantNames.Name LAMA = new TenantNames.Name("Lama Al-Ghamdi", "لمى الغامدي");

    private final GroundingChecker checker = new GroundingChecker(new TenantNames(() -> List.of(AHMED, SARA, LAMA)));

    /** find_overdue_tenants {"min_days_overdue":30}, the call behind en-04, as the demo returned it on 2026-10-12 */
    private static final String OVERDUE_OVER_30_DAYS = """
            {"minDaysOverdue":30,"totalTenants":19,"shown":15,"totalUnpaidAmount":2.422E+5,"tenants":[\
            {"nameEn":"Ahmed Al-Harbi","nameAr":"أحمد الحربي","unitCode":"B-203",\
            "unpaidMonths":3,"unpaidAmount":1.44E+4,"daysOverdue":103},\
            {"nameEn":"Sara Al-Qahtani","nameAr":"سارة القحطاني","unitCode":"B-502",\
            "unpaidMonths":3,"unpaidAmount":2.205E+4,"daysOverdue":103},\
            {"nameEn":"Yusuf Al-Otaibi","nameAr":"يوسف العتيبي","unitCode":"A-301",\
            "unpaidMonths":3,"unpaidAmount":2.535E+4,"daysOverdue":103},\
            {"nameEn":"Lama Al-Ghamdi","nameAr":"لمى الغامدي","unitCode":"A-504",\
            "unpaidMonths":3,"unpaidAmount":1.485E+4,"daysOverdue":103},\
            {"nameEn":"Ali Al-Shehri","nameAr":"علي الشهري","unitCode":"D-303",\
            "unpaidMonths":3,"unpaidAmount":1.665E+4,"daysOverdue":103},\
            {"nameEn":"Abdullah Al-Dosari","nameAr":"عبدالله الدوسري","unitCode":"C-102",\
            "unpaidMonths":3,"unpaidAmount":1.995E+4,"daysOverdue":103},\
            {"nameEn":"Nasser Al-Mutairi","nameAr":"ناصر المطيري","unitCode":"C-401",\
            "unpaidMonths":3,"unpaidAmount":1.32E+4,"daysOverdue":103},\
            {"nameEn":"Fahad Al-Shammari","nameAr":"فهد الشمري","unitCode":"F-104",\
            "unpaidMonths":3,"unpaidAmount":1.125E+4,"daysOverdue":103},\
            {"nameEn":"Majed Al-Anazi","nameAr":"ماجد العنزي","unitCode":"F-403",\
            "unpaidMonths":3,"unpaidAmount":1.89E+4,"daysOverdue":103},\
            {"nameEn":"Faisal Al-Subaie","nameAr":"فيصل السبيعي","unitCode":"E-202",\
            "unpaidMonths":3,"unpaidAmount":2.22E+4,"daysOverdue":103},\
            {"nameEn":"Noura Al-Juhani","nameAr":"نورة الجهني","unitCode":"E-501",\
            "unpaidMonths":3,"unpaidAmount":1.545E+4,"daysOverdue":103},\
            {"nameEn":"Abdullah Al-Otaibi","nameAr":"عبدالله العتيبي","unitCode":"A-102",\
            "unpaidMonths":1,"unpaidAmount":7.15E+3,"daysOverdue":41},\
            {"nameEn":"Hassan Al-Otaibi","nameAr":"حسن العتيبي","unitCode":"A-302",\
            "unpaidMonths":1,"unpaidAmount":4.85E+3,"daysOverdue":41},\
            {"nameEn":"Sultan Al-Zahrani","nameAr":"سلطان الزهراني","unitCode":"D-201",\
            "unpaidMonths":1,"unpaidAmount":6.7E+3,"daysOverdue":41},\
            {"nameEn":"Nasser Al-Shehri","nameAr":"ناصر الشهري","unitCode":"D-401",\
            "unpaidMonths":1,"unpaidAmount":4.4E+3,"daysOverdue":41}]}""";

    /** find_overdue_tenants {}, the call behind ar-03, as the demo returned it on 2026-10-12 */
    private static final String OVERDUE = """
            {"minDaysOverdue":0,"totalTenants":45,"shown":15,"totalUnpaidAmount":4.7195E+5,"tenants":[\
            {"nameEn":"Ahmed Al-Harbi","nameAr":"أحمد الحربي","unitCode":"B-203",\
            "unpaidMonths":4,"unpaidAmount":1.92E+4,"daysOverdue":103},\
            {"nameEn":"Sara Al-Qahtani","nameAr":"سارة القحطاني","unitCode":"B-502",\
            "unpaidMonths":4,"unpaidAmount":2.94E+4,"daysOverdue":103},\
            {"nameEn":"Yusuf Al-Otaibi","nameAr":"يوسف العتيبي","unitCode":"A-301",\
            "unpaidMonths":4,"unpaidAmount":3.38E+4,"daysOverdue":103},\
            {"nameEn":"Lama Al-Ghamdi","nameAr":"لمى الغامدي","unitCode":"A-504",\
            "unpaidMonths":4,"unpaidAmount":1.98E+4,"daysOverdue":103},\
            {"nameEn":"Ali Al-Shehri","nameAr":"علي الشهري","unitCode":"D-303",\
            "unpaidMonths":4,"unpaidAmount":2.22E+4,"daysOverdue":103},\
            {"nameEn":"Abdullah Al-Dosari","nameAr":"عبدالله الدوسري","unitCode":"C-102",\
            "unpaidMonths":4,"unpaidAmount":2.66E+4,"daysOverdue":103},\
            {"nameEn":"Nasser Al-Mutairi","nameAr":"ناصر المطيري","unitCode":"C-401",\
            "unpaidMonths":4,"unpaidAmount":1.76E+4,"daysOverdue":103},\
            {"nameEn":"Fahad Al-Shammari","nameAr":"فهد الشمري","unitCode":"F-104",\
            "unpaidMonths":4,"unpaidAmount":1.5E+4,"daysOverdue":103},\
            {"nameEn":"Majed Al-Anazi","nameAr":"ماجد العنزي","unitCode":"F-403",\
            "unpaidMonths":4,"unpaidAmount":2.52E+4,"daysOverdue":103},\
            {"nameEn":"Faisal Al-Subaie","nameAr":"فيصل السبيعي","unitCode":"E-202",\
            "unpaidMonths":4,"unpaidAmount":2.96E+4,"daysOverdue":103},\
            {"nameEn":"Noura Al-Juhani","nameAr":"نورة الجهني","unitCode":"E-501",\
            "unpaidMonths":4,"unpaidAmount":2.06E+4,"daysOverdue":103},\
            {"nameEn":"Abdullah Al-Otaibi","nameAr":"عبدالله العتيبي","unitCode":"A-102",\
            "unpaidMonths":1,"unpaidAmount":7.15E+3,"daysOverdue":41},\
            {"nameEn":"Hassan Al-Otaibi","nameAr":"حسن العتيبي","unitCode":"A-302",\
            "unpaidMonths":1,"unpaidAmount":4.85E+3,"daysOverdue":41},\
            {"nameEn":"Sultan Al-Zahrani","nameAr":"سلطان الزهراني","unitCode":"D-201",\
            "unpaidMonths":1,"unpaidAmount":6.7E+3,"daysOverdue":41},\
            {"nameEn":"Nasser Al-Shehri","nameAr":"ناصر الشهري","unitCode":"D-401",\
            "unpaidMonths":1,"unpaidAmount":4.4E+3,"daysOverdue":41}]}""";

    /** get_occupancy_summary {"building":"A"}: the call behind en-07 and ar-06, as returned on 2026-10-12 */
    private static final String OCCUPANCY_A = """
            {"building":"A","month":"2026-10","totalUnits":25,"occupiedUnits":20,"vacantUnits":3,\
            "maintenanceUnits":2,"occupancyRatePercent":8E+1,"monthlyRentRoll":1.6565E+5,\
            "rentCollected":9.31E+4,"rentExpected":1.33E+5,"collectionRatePercent":7E+1,"unpaidInvoices":6}""";

    /** get_occupancy_summary {"building":"D"}: the call behind en-08, as returned on 2026-10-12 */
    private static final String OCCUPANCY_D = """
            {"building":"D","month":"2026-10","totalUnits":25,"occupiedUnits":20,"vacantUnits":4,\
            "maintenanceUnits":1,"occupancyRatePercent":8E+1,"monthlyRentRoll":1.5315E+5,\
            "rentCollected":8.85E+4,"rentExpected":1.23E+5,"collectionRatePercent":72,"unpaidInvoices":6}""";

    /** get_payment_history {"unit_code":"B-203"}: the call behind en-09 and ar-08, as returned on 2026-10-12 */
    private static final String HISTORY_B203 = """
            {"tenantEn":"Ahmed Al-Harbi","tenantAr":"أحمد الحربي","unitCode":"B-203","allLeases":false,\
            "unpaidMonths":4,"payments":[\
            {"month":"2026-10","amount":4.8E+3,"paidAmount":0,"remainingAmount":4.8E+3,\
            "paidOn":null,"status":"UNPAID","daysLate":11},\
            {"month":"2026-09","amount":4.8E+3,"paidAmount":0,"remainingAmount":4.8E+3,\
            "paidOn":null,"status":"UNPAID","daysLate":41},\
            {"month":"2026-08","amount":4.8E+3,"paidAmount":0,"remainingAmount":4.8E+3,\
            "paidOn":null,"status":"UNPAID","daysLate":72},\
            {"month":"2026-07","amount":4.8E+3,"paidAmount":0,"remainingAmount":4.8E+3,\
            "paidOn":null,"status":"UNPAID","daysLate":103},\
            {"month":"2026-06","amount":4.8E+3,"paidAmount":4.8E+3,"remainingAmount":0,\
            "paidOn":"2026-06-10","status":"PAID_LATE","daysLate":9},\
            {"month":"2026-05","amount":4.8E+3,"paidAmount":4.8E+3,"remainingAmount":0,\
            "paidOn":"2026-05-03","status":"PAID_ON_TIME","daysLate":2},\
            {"month":"2026-04","amount":4.8E+3,"paidAmount":4.8E+3,"remainingAmount":0,\
            "paidOn":"2026-04-06","status":"PAID_ON_TIME","daysLate":5},\
            {"month":"2026-03","amount":4.8E+3,"paidAmount":4.8E+3,"remainingAmount":0,\
            "paidOn":"2026-03-09","status":"PAID_LATE","daysLate":8},\
            {"month":"2026-02","amount":4.8E+3,"paidAmount":4.8E+3,"remainingAmount":0,\
            "paidOn":"2026-02-02","status":"PAID_ON_TIME","daysLate":1},\
            {"month":"2026-01","amount":4.8E+3,"paidAmount":4.8E+3,"remainingAmount":0,\
            "paidOn":"2026-01-05","status":"PAID_ON_TIME","daysLate":4},\
            {"month":"2025-12","amount":4.8E+3,"paidAmount":4.8E+3,"remainingAmount":0,\
            "paidOn":"2025-12-08","status":"PAID_LATE","daysLate":7},\
            {"month":"2025-11","amount":4.8E+3,"paidAmount":4.8E+3,"remainingAmount":0,\
            "paidOn":"2025-11-01","status":"PAID_ON_TIME","daysLate":0}]}""";

    /** get_payment_history {"unit_code":"A-504"}, as returned on 2026-10-12 */
    private static final String HISTORY_A504 = """
            {"tenantEn":"Lama Al-Ghamdi","tenantAr":"لمى الغامدي","unitCode":"A-504","allLeases":false,\
            "unpaidMonths":4,\
            "payments":[\
            {"month":"2026-10","amount":4.95E+3,"paidAmount":0,"remainingAmount":4.95E+3,\
            "paidOn":null,"status":"UNPAID","daysLate":11},\
            {"month":"2026-09","amount":4.95E+3,"paidAmount":0,"remainingAmount":4.95E+3,\
            "paidOn":null,"status":"UNPAID","daysLate":41},\
            {"month":"2026-08","amount":4.95E+3,"paidAmount":0,"remainingAmount":4.95E+3,\
            "paidOn":null,"status":"UNPAID","daysLate":72},\
            {"month":"2026-07","amount":4.95E+3,"paidAmount":0,"remainingAmount":4.95E+3,\
            "paidOn":null,"status":"UNPAID","daysLate":103},\
            {"month":"2026-06","amount":4.95E+3,"paidAmount":4.95E+3,"remainingAmount":0,\
            "paidOn":"2026-06-03","status":"PAID_ON_TIME","daysLate":2},\
            {"month":"2026-05","amount":4.95E+3,"paidAmount":4.95E+3,"remainingAmount":0,\
            "paidOn":"2026-05-06","status":"PAID_ON_TIME","daysLate":5},\
            {"month":"2026-04","amount":4.95E+3,"paidAmount":4.95E+3,"remainingAmount":0,\
            "paidOn":"2026-04-09","status":"PAID_LATE","daysLate":8},\
            {"month":"2026-03","amount":4.95E+3,"paidAmount":4.95E+3,"remainingAmount":0,\
            "paidOn":"2026-03-02","status":"PAID_ON_TIME","daysLate":1},\
            {"month":"2026-02","amount":4.95E+3,"paidAmount":4.95E+3,"remainingAmount":0,\
            "paidOn":"2026-02-05","status":"PAID_ON_TIME","daysLate":4},\
            {"month":"2026-01","amount":4.95E+3,"paidAmount":4.95E+3,"remainingAmount":0,\
            "paidOn":"2026-01-08","status":"PAID_LATE","daysLate":7},\
            {"month":"2025-12","amount":4.95E+3,"paidAmount":4.95E+3,"remainingAmount":0,\
            "paidOn":"2025-12-01","status":"PAID_ON_TIME","daysLate":0},\
            {"month":"2025-11","amount":4.95E+3,"paidAmount":4.95E+3,"remainingAmount":0,\
            "paidOn":"2025-11-04","status":"PAID_ON_TIME","daysLate":3}]}""";

    /** A tool that returns its rates as fractions, which none of the tools of the app does at present */
    private static final String RATES = "{\"building\":\"A\",\"occupancyRate\":0.5,\"collectionRate\":0.857}";

    private List<Violation> check(String question, String answer, String... toolResults) {
        return checker.check(answer, new Evidence(List.of(toolResults), List.of(question), Set.of()));
    }

    private static List<String> values(List<Violation> violations) {
        return violations.stream().map(Violation::value).toList();
    }

    // en-04: 11 is the number of rows of the result that share daysOverdue 103
    @Test
    void theNumberOfTenantsWhoAreAsManyDaysLateIsTheNumberOfRowsThatShareThemEnglish() {
        var answer = "19 tenants are more than 30 days late on rent. 11 of them have been 103 days late, among them "
                + "Ahmed Al-Harbi (B-203) and Sara Al-Qahtani (B-502), and 4 have been late for 41 days.";

        assertThat(check("Who is more than 30 days late on rent?", answer, OVERDUE_OVER_30_DAYS)).isEmpty();
    }

    // ar-03: 11 is the number of rows of the result that share daysOverdue 103, or unpaidMonths 4
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"11|19,200", "١١|١٩٬٢٠٠"})
    void theNumberOfTenantsWhoAreAsManyDaysLateIsTheNumberOfRowsThatShareThemArabic(String count, String amount) {
        var answer = "عدد المستأجرين المتأخرين في الدفع 45 مستأجرًا. منهم " + count + " مستأجرًا متأخرين 103 يومًا، "
                + "ومنهم أحمد الحربي (B-203) الذي عليه " + amount + " ريال.";

        assertThat(check("من المستأجرين المتأخرين في الدفع؟", answer, OVERDUE)).isEmpty();
    }

    // en-09: 19,200 is the sum of remainingAmount over the rows, or of amount over the rows that share status UNPAID
    @Test
    void theAmountUnpaidIsTheSumOfTheRemainingAmountOfTheRowsEnglish() {
        var answer = "Payment history for Ahmed Al-Harbi (B-203): 4 months are unpaid, July to October 2026, "
                + "4,800 SAR each, which is 19,200 SAR. The earlier 8 months were paid, 3 of them late.";

        assertThat(check("Show the payment history for unit B-203.", answer, HISTORY_B203)).isEmpty();
    }

    // ar-08: 19,200 as in en-09
    @Test
    void theAmountUnpaidIsTheSumOfTheRemainingAmountOfTheRowsArabic() {
        var answer = "سجل دفعات أحمد الحربي (B-203): 4 أشهر لم يتم الدفع عنها، المبلغ المتبقي 19,200 ريال "
                + "(4,800 ريال شهريًا).";

        assertThat(check("ما سجل المدفوعات للوحدة B-203؟", answer, HISTORY_B203)).isEmpty();
    }

    // en-07, ar-06, en-08: the tool returns both rates as percentages (8E+1, 7E+1 and 72), so there is no fraction to
    // read 50 from, and no count, sum or group of its result gives 50 or 57,000. They stay reported.
    @Test
    void theOccupancyFiguresTheResultStatesAreGroundedAsTheyWereBefore() {
        var buildingA = "Building A: 20 of 25 units are occupied (80%). Rent collected is 93,100 of 133,000 SAR (70%).";
        var buildingD = "Building D is 80% occupied: 20 of 25 units, 4 vacant and 1 under maintenance. "
                + "Rent collected is 88,500 of 123,000 SAR (72%).";

        assertThat(check("Summarise occupancy and collected rent for Building A this month.", buildingA, OCCUPANCY_A))
                .isEmpty();
        assertThat(check("What is the occupancy rate of building D?", buildingD, OCCUPANCY_D)).isEmpty();
        assertThat(check("لخّص الإشغال والإيجار المحصّل للمبنى A هذا الشهر.",
                "المبنى A: 20 من 25 وحدة مشغولة (80٪). الإيجار المحصّل 93,100 من 133,000 ريال (70٪).", OCCUPANCY_A))
                .isEmpty();
    }

    @Test
    void theFiguresOfTheOccupancyAnswersThatNoRuleDerivesAreStillReported() {
        var question = "Summarise occupancy and collected rent for Building A this month.";

        assertThat(values(check(question, "Building A is 80% occupied and 50% of the rent is collected.", OCCUPANCY_A)))
                .containsExactly("50");
        assertThat(values(check("What is the occupancy rate of building D?", "Rent at stake is 57,000 SAR.",
                OCCUPANCY_D))).containsExactly("57,000");
    }

    @Test
    void aFractionIsGroundedAsThePercentageAnAnswerWritesIt() {
        var question = "How full is building A?";

        assertThat(check(question, "Occupancy is 50% and 85.7% of the rent is collected.", RATES)).isEmpty();
        assertThat(check(question, "About 86% of the rent is collected.", RATES)).isEmpty();
        assertThat(check(question, "الإشغال ٥٠٪ والتحصيل ٨٥٫٧٪", RATES)).isEmpty();
    }

    @Test
    void aPercentageThatIsNotTheFractionTimesAHundredIsReported() {
        var question = "How full is building A?";

        assertThat(values(check(question, "Occupancy is 51%.", RATES))).containsExactly("51");
        assertThat(values(check(question, "Occupancy is 50.5%.", RATES))).containsExactly("50.5");
        assertThat(values(check(question, "The rent collected is 85.8%.", RATES))).containsExactly("85.8");
    }

    @Test
    void theAmountOfOneMonthIsNotDerivedFromTheAmountOfSeveral() {
        // Lama Al-Ghamdi of A-504 owes 14,850 for 3 months; 4,950 a month is a quotient, which no rule derives
        var answer = "Lama Al-Ghamdi (A-504) owes 4,950 for each of the 3 months.";

        assertThat(values(check("Who is late?", answer, OVERDUE_OVER_30_DAYS))).containsExactly("4,950");
        assertThat(check("Who is late?", "Lama Al-Ghamdi (A-504) owes 14,850 for 3 months.", OVERDUE_OVER_30_DAYS))
                .isEmpty();
    }

    @Test
    void theSumOfTwoAmountsThatNothingGroupsIsReported() {
        var answer = "Ahmed Al-Harbi and Sara Al-Qahtani owe 36,450 together.";

        assertThat(values(check("Who is late?", answer, OVERDUE_OVER_30_DAYS))).containsExactly("36,450");
    }

    @Test
    void theSumOfSomeRowsOfAGroupIsReported() {
        // A-504 has 4 unpaid months of 4,950: 19,800 is the sum of the group, 14,850 only of 3 of its 4 rows
        var question = "Show the payment history for unit A-504.";

        assertThat(check(question, "4 months are unpaid, 19,800 SAR, 4,950 SAR each.", HISTORY_A504)).isEmpty();
        assertThat(values(check(question, "3 unpaid months are 14,850 SAR.", HISTORY_A504)))
                .containsExactly("14,850");
    }

    @Test
    void aCountThatIsOffByOneIsReported() {
        var question = "Who is more than 30 days late on rent?";

        assertThat(values(check(question, "12 tenants have been 103 days late.", OVERDUE_OVER_30_DAYS)))
                .containsExactly("12");
        assertThat(values(check(question, "16 tenants are late; 11 have been 103 days late.", OVERDUE_OVER_30_DAYS)))
                .containsExactly("16");
    }

    @Test
    void aDifferenceOfTwoDerivedFiguresIsReported() {
        // the 11 tenants who are 103 days late owe 194,250 and the 4 others 23,100; the gap between them is no sum
        var question = "Who is late?";

        assertThat(check(question, "The 11 tenants who are 103 days late owe 194,250 SAR.", OVERDUE_OVER_30_DAYS))
                .isEmpty();
        assertThat(values(check(question, "That is 171,150 SAR more than the others.", OVERDUE_OVER_30_DAYS)))
                .containsExactly("171,150");
    }

    @Test
    void aFigureOfAnotherRunIsNotGrounded() {
        var otherRun = new Evidence(List.of(OCCUPANCY_A), List.of("Who is late?"), Set.of());

        assertThat(values(checker.check("11 tenants have been 103 days late, owing 194,250 SAR.", otherRun)))
                .containsExactly("11", "103", "194,250");
    }

    @Test
    void theRowsOfTwoToolResultsOfOneRunAreNotCountedTogether() {
        var question = "Who is late?";

        assertThat(check(question, "11 tenants have been 103 days late.", OVERDUE_OVER_30_DAYS, OVERDUE)).isEmpty();
        // 11 + 11 tenants, the same in both results: there are not 22 of them
        assertThat(values(check(question, "22 tenants have been 103 days late.", OVERDUE_OVER_30_DAYS, OVERDUE)))
                .containsExactly("22");
    }

    @Test
    void aDerivedFigureGroundsNoUnitCode() {
        // 100 + 103 = 203, and A-203 is nowhere in the result
        var result = "[{\"unit\":\"B-101\",\"rent\":100},{\"unit\":\"B-102\",\"rent\":103}]";

        var violations = check("What do the units owe?", "Unit A-203 owes 203 SAR.", result);

        assertThat(violations).containsExactly(new Violation(ClaimKind.UNIT_CODE, "A-203", "A-203"));
    }

    @Test
    void aDerivedFigureGroundsNoTenantName() {
        var result = "[{\"unit\":\"B-101\",\"rent\":100},{\"unit\":\"B-102\",\"rent\":103}]";

        var violations = check("What do the units owe?", "Sara Al-Qahtani owes 203 SAR.", result);

        assertThat(violations).extracting(Violation::kind).containsExactly(ClaimKind.TENANT_NAME);
    }
}
