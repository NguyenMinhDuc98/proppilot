package dev.proppilot.rag;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.rag.LeaseTerms.LateFeeKind;
import dev.proppilot.rag.LeaseTerms.PetPolicy;
import dev.proppilot.rag.LeaseTerms.RenewalMode;
import dev.proppilot.rag.LeaseTerms.Subletting;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class LeaseTermsTest {

    private static final List<LeaseTerms> FIRST_THIRTY = LongStream.rangeClosed(1, 30).mapToObj(LeaseTerms::of).toList();

    @Test
    void aLeaseAlwaysGetsTheSameTerms() {
        for (long id = 1; id <= 200; id++) {
            assertThat(LeaseTerms.of(id)).isEqualTo(LeaseTerms.of(id));
        }
    }

    @Test
    void theTermsOfOneLeaseAreFixedSoStoredDocumentsStayTrue() {
        // If this fails the way terms are derived has changed: raise LeasePdfGenerator.VERSION and update the values.
        assertThat(LeaseTerms.of(1)).isEqualTo(new LeaseTerms(60,
                new LeaseTerms.LateFee(LateFeeKind.FIXED_SAR_PER_DAY, 100, 5),
                new LeaseTerms.Renewal(RenewalMode.AUTOMATIC_12_MONTHS, 5), 500,
                new LeaseTerms.Pets(PetPolicy.WITH_WRITTEN_CONSENT, 0), new LeaseTerms.EarlyTermination(3, 90), 1,
                Subletting.PROHIBITED, 24, true, false));
    }

    @Test
    void neighbouringLeasesDoNotGetTheSameTerms() {
        assertThat(FIRST_THIRTY.stream().distinct().count()).isGreaterThan(25);
    }

    @Test
    void everyNoticePeriodOccurs() {
        assertThat(values(LeaseTerms::noticePeriodDays)).containsExactlyInAnyOrder(30, 60, 90);
    }

    @Test
    void bothKindsOfLateFeeOccurWithEveryAmountAndGracePeriod() {
        assertThat(values(t -> t.lateFee().kind())).containsExactlyInAnyOrder(LateFeeKind.values());
        assertThat(values(t -> t.lateFee().kind() == LateFeeKind.PERCENT_OF_RENT_PER_MONTH ? t.lateFee().amount() : 0))
                .contains(2, 3, 5);
        assertThat(values(t -> t.lateFee().kind() == LateFeeKind.FIXED_SAR_PER_DAY ? t.lateFee().amount() : 0))
                .contains(50, 75, 100);
        assertThat(values(t -> t.lateFee().graceDays())).containsExactlyInAnyOrder(3, 5, 7);
    }

    @Test
    void bothWaysToRenewOccurWithBothCaps() {
        assertThat(values(t -> t.renewal().mode())).containsExactlyInAnyOrder(RenewalMode.values());
        assertThat(values(t -> t.renewal().rentIncreaseCapPercent())).containsExactlyInAnyOrder(5, 10);
    }

    @Test
    void everyMaintenanceThresholdOccurs() {
        assertThat(values(LeaseTerms::minorRepairLimitSar)).containsExactlyInAnyOrder(300, 500, 750);
    }

    @Test
    void everyPetPolicyOccursAndOnlyTheDepositPolicyAsksForADeposit() {
        assertThat(values(t -> t.pets().policy())).containsExactlyInAnyOrder(PetPolicy.values());
        assertThat(FIRST_THIRTY).allSatisfy(t -> assertThat(t.pets().depositSar() > 0)
                .isEqualTo(t.pets().policy() == PetPolicy.WITH_DEPOSIT));
        assertThat(values(t -> t.pets().depositSar())).contains(1000, 1500);
    }

    @Test
    void everyEarlyTerminationFeeAndNoticeOccurs() {
        assertThat(values(t -> t.earlyTermination().feeMonths())).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(values(t -> t.earlyTermination().noticeDays())).containsExactlyInAnyOrder(30, 60, 90);
    }

    @Test
    void everyDepositSublettingAndInspectionVariantOccurs() {
        assertThat(values(LeaseTerms::securityDepositMonths)).containsExactlyInAnyOrder(1, 2);
        assertThat(values(LeaseTerms::subletting)).containsExactlyInAnyOrder(Subletting.values());
        assertThat(values(LeaseTerms::inspectionNoticeHours)).containsExactlyInAnyOrder(24, 48);
    }

    @Test
    void someLeasesCarryTheLongAnnexAndMostDoNot() {
        long withAnnex = FIRST_THIRTY.stream().filter(LeaseTerms::houseRulesAnnex).count();

        assertThat(withAnnex).isBetween(3L, 15L);
    }

    @Test
    void noLeaseCarriesThePlantedInstructionUnlessItIsAddedOnPurpose() {
        assertThat(FIRST_THIRTY).noneMatch(LeaseTerms::plantedInstruction);

        var plain = LeaseTerms.of(29);
        var planted = plain.withPlantedInstruction();

        assertThat(planted.plantedInstruction()).isTrue();
        assertThat(planted).isEqualTo(new LeaseTerms(plain.noticePeriodDays(), plain.lateFee(), plain.renewal(),
                plain.minorRepairLimitSar(), plain.pets(), plain.earlyTermination(), plain.securityDepositMonths(),
                plain.subletting(), plain.inspectionNoticeHours(), plain.houseRulesAnnex(), true));
    }

    private static <T> Set<T> values(Function<LeaseTerms, T> term) {
        return FIRST_THIRTY.stream().map(term).collect(Collectors.toSet());
    }
}
