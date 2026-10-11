package dev.proppilot.rag;

/**
 * The clause parameters of one lease: notice period, late fee, renewal, repairs, pets, early termination and so on.
 * They are derived from the lease id alone, so a lease always gets the same terms and a document can be written again
 * without the database having to remember them.
 *
 * <p>Changing how the terms are derived, or what a document says about them, changes the PDF a lease gets, so the
 * generator version has to be raised with it.
 *
 * @param plantedInstruction whether the special conditions of the document carry the deliberate prompt injection that
 *                           later evals use as a fixture; only the one lease chosen by the seeder has it
 */
public record LeaseTerms(
        int noticePeriodDays,
        LateFee lateFee,
        Renewal renewal,
        int minorRepairLimitSar,
        Pets pets,
        EarlyTermination earlyTermination,
        int securityDepositMonths,
        Subletting subletting,
        int inspectionNoticeHours,
        boolean houseRulesAnnex,
        boolean plantedInstruction) {

    private static final int[] NOTICE_DAYS = {30, 60, 90};
    private static final int[] LATE_FEE_PERCENTS = {2, 3, 5};
    private static final int[] LATE_FEE_SAR_PER_DAY = {50, 75, 100};
    private static final int[] GRACE_DAYS = {3, 5, 7};
    private static final int[] RENT_INCREASE_CAPS = {5, 10};
    private static final int[] MINOR_REPAIR_LIMITS = {300, 500, 750};
    private static final int[] PET_DEPOSITS = {1000, 1500};
    private static final int[] EARLY_TERMINATION_MONTHS = {1, 2, 3};
    private static final int[] DEPOSIT_MONTHS = {1, 2};
    private static final int[] INSPECTION_HOURS = {24, 48};
    /** One lease in this many carries the long house rules annex. */
    private static final int ANNEX_ONE_IN = 4;

    /**
     * @param amount a percentage of the monthly rent for each month, or SAR for each day, as {@code kind} says
     * @param graceDays days after the due date before the fee starts
     */
    public record LateFee(LateFeeKind kind, int amount, int graceDays) {
    }

    public enum LateFeeKind {
        PERCENT_OF_RENT_PER_MONTH,
        FIXED_SAR_PER_DAY
    }

    /** @param rentIncreaseCapPercent the most the rent may rise by when the lease renews */
    public record Renewal(RenewalMode mode, int rentIncreaseCapPercent) {
    }

    public enum RenewalMode {
        /** Renews for 12 months unless one side gives notice. */
        AUTOMATIC_12_MONTHS,
        /** Ends with the term; a renewal needs a new written agreement. */
        NEW_AGREEMENT
    }

    /** @param depositSar the extra deposit, only for {@link PetPolicy#WITH_DEPOSIT} */
    public record Pets(PetPolicy policy, int depositSar) {
    }

    public enum PetPolicy {
        NOT_ALLOWED,
        WITH_WRITTEN_CONSENT,
        WITH_DEPOSIT
    }

    /** @param feeMonths the fee for ending the lease early, in months of rent */
    public record EarlyTermination(int feeMonths, int noticeDays) {
    }

    public enum Subletting {
        PROHIBITED,
        WITH_WRITTEN_CONSENT
    }

    /** The terms of a lease, the same ones every time. */
    public static LeaseTerms of(long leaseId) {
        var petPolicy = PetPolicy.values()[pick(leaseId, Choice.PET_POLICY, PetPolicy.values().length)];
        var lateFeeKind = LateFeeKind.values()[pick(leaseId, Choice.LATE_FEE_KIND, LateFeeKind.values().length)];
        int[] lateFeeAmounts = lateFeeKind == LateFeeKind.PERCENT_OF_RENT_PER_MONTH
                ? LATE_FEE_PERCENTS : LATE_FEE_SAR_PER_DAY;
        return new LeaseTerms(
                choose(leaseId, Choice.NOTICE_DAYS, NOTICE_DAYS),
                new LateFee(lateFeeKind, choose(leaseId, Choice.LATE_FEE_AMOUNT, lateFeeAmounts),
                        choose(leaseId, Choice.GRACE_DAYS, GRACE_DAYS)),
                new Renewal(RenewalMode.values()[pick(leaseId, Choice.RENEWAL_MODE, RenewalMode.values().length)],
                        choose(leaseId, Choice.RENT_INCREASE_CAP, RENT_INCREASE_CAPS)),
                choose(leaseId, Choice.MINOR_REPAIR_LIMIT, MINOR_REPAIR_LIMITS),
                new Pets(petPolicy, petPolicy == PetPolicy.WITH_DEPOSIT
                        ? choose(leaseId, Choice.PET_DEPOSIT, PET_DEPOSITS) : 0),
                new EarlyTermination(choose(leaseId, Choice.EARLY_TERMINATION_MONTHS, EARLY_TERMINATION_MONTHS),
                        choose(leaseId, Choice.EARLY_TERMINATION_NOTICE, NOTICE_DAYS)),
                choose(leaseId, Choice.DEPOSIT_MONTHS, DEPOSIT_MONTHS),
                Subletting.values()[pick(leaseId, Choice.SUBLETTING, Subletting.values().length)],
                choose(leaseId, Choice.INSPECTION_HOURS, INSPECTION_HOURS),
                pick(leaseId, Choice.HOUSE_RULES_ANNEX, ANNEX_ONE_IN) == 0,
                false);
    }

    /** The same terms with the planted instruction added to the document. */
    public LeaseTerms withPlantedInstruction() {
        return new LeaseTerms(noticePeriodDays, lateFee, renewal, minorRepairLimitSar, pets, earlyTermination,
                securityDepositMonths, subletting, inspectionNoticeHours, houseRulesAnnex, true);
    }

    /** Each term has its own place in the sequence, so the terms of one lease do not move together. */
    private enum Choice {
        NOTICE_DAYS, LATE_FEE_KIND, LATE_FEE_AMOUNT, GRACE_DAYS, RENEWAL_MODE, RENT_INCREASE_CAP, MINOR_REPAIR_LIMIT,
        PET_POLICY, PET_DEPOSIT, EARLY_TERMINATION_MONTHS, EARLY_TERMINATION_NOTICE, DEPOSIT_MONTHS, SUBLETTING,
        INSPECTION_HOURS, HOUSE_RULES_ANNEX
    }

    private static int choose(long leaseId, Choice choice, int[] options) {
        return options[pick(leaseId, choice, options.length)];
    }

    private static int pick(long leaseId, Choice choice, int options) {
        return (int) Math.floorMod(splitMix64(leaseId * Choice.values().length + choice.ordinal()), (long) options);
    }

    /** The SplitMix64 finaliser: neighbouring inputs give unrelated outputs, and it is the same on every JVM. */
    private static long splitMix64(long x) {
        long z = x + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
