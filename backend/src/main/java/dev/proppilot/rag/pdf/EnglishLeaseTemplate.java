package dev.proppilot.rag.pdf;

import static dev.proppilot.rag.pdf.RootFormat.format;

import dev.proppilot.agent.MoneyText;
import dev.proppilot.rag.DocumentLanguage;
import dev.proppilot.rag.LeaseFacts;
import dev.proppilot.rag.LeaseTerms;
import dev.proppilot.rag.LeaseTerms.LateFeeKind;
import dev.proppilot.rag.LeaseTerms.RenewalMode;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

final class EnglishLeaseTemplate implements LeaseTemplate {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final String LANDLORD = "PropPilot Demo Properties Co.";

    @Override
    public String title(LeaseFacts facts) {
        return "Residential Lease Agreement – Unit " + facts.unitCode();
    }

    @Override
    public LeaseText write(LeaseFacts facts, LeaseTerms terms) {
        var clauses = new Clauses()
                .add("Rent and payment", rent(facts))
                .add("Security deposit", deposit(facts, terms))
                .add("Term and renewal", renewal(terms))
                .add("Notice period", notice(terms))
                .add("Late payment", lateFee(facts, terms))
                .add("Maintenance and repairs", maintenance(terms))
                .add("Pets", pets(terms))
                .add("Early termination", earlyTermination(facts, terms))
                .add("Subletting", subletting(terms))
                .add("Use of the premises", use(facts))
                .add("Inspection and entry", inspection(terms))
                .add("Governing law", governingLaw(facts));
        if (terms.houseRulesAnnex()) {
            clauses.add("House rules and building regulations", houseRules());
        }
        if (terms.plantedInstruction()) {
            clauses.add("Special conditions", specialConditions());
        }
        return new LeaseText(DocumentLanguage.EN, title(facts), "Contract No. " + facts.documentCode(),
                preamble(facts), clauses.build(), "%d. %s", "Signatures", signatures(facts), "Page %d of %d");
    }

    private static List<LeaseText.Section> preamble(LeaseFacts f) {
        return List.of(
                new LeaseText.Section("Parties", List.of(
                        format("Landlord: %s, acting through its property manager (the \"Landlord\").", LANDLORD),
                        format("Tenant: %s (the \"Tenant\").", f.tenantName()))),
                new LeaseText.Section("The property", List.of(
                        format("Unit %s on floor %d, a %d-bedroom apartment of %d sqm in %s, %s (the \"Property\").",
                                f.unitCode(), f.floor(), f.bedrooms(), f.areaSqm(), f.buildingName(),
                                f.address()))),
                new LeaseText.Section("Term and rent", List.of(
                        format("The lease starts on %s and ends on %s (the \"Term\").",
                                DATE.format(f.startDate()), DATE.format(f.endDate())),
                        format("The monthly rent is SAR %s, payable in advance on the first day of each month.",
                                MoneyText.of(f.monthlyRent())),
                        "The parties agree as follows.")));
    }

    private static List<String> rent(LeaseFacts f) {
        return List.of(
                format("The Tenant shall pay the monthly rent of SAR %s in advance, on or before the first day of each "
                        + "month, by bank transfer to the account the Landlord names in writing.",
                        MoneyText.of(f.monthlyRent())),
                "A payment counts as made on the day it reaches the Landlord's account. The rent stays the same "
                        + "during the Term except as the clause on term and renewal allows.");
    }

    private static List<String> deposit(LeaseFacts f, LeaseTerms t) {
        return List.of(
                format("On signing, the Tenant pays a security deposit of %s of rent, which is SAR %s.",
                        months(t.securityDepositMonths()), amount(f, t.securityDepositMonths())),
                "The Landlord holds the deposit as security for the Tenant's obligations and pays no interest on it. "
                        + "The Landlord returns it within 30 days after the Tenant hands back the Property, less any "
                        + "unpaid rent and the cost of damage beyond normal wear.");
    }

    private static List<String> renewal(LeaseTerms t) {
        var renewal = t.renewal();
        if (renewal.mode() == RenewalMode.AUTOMATIC_12_MONTHS) {
            return List.of(format("The lease runs for the Term stated at the top of this agreement. Unless one "
                    + "party gives the other written notice of non-renewal at least %d days before the Term ends, "
                    + "the lease renews automatically for a further 12 months. The rent for a renewal period may "
                    + "rise by no more than %d%% of the previous rent.",
                    t.noticePeriodDays(), renewal.rentIncreaseCapPercent()));
        }
        return List.of(format("The lease runs for the Term stated at the top of this agreement and does not renew "
                + "automatically. A renewal needs a new written agreement, and the Landlord shall make its renewal "
                + "offer at least %d days before the Term ends. The rent offered for a renewal may not exceed the "
                + "current rent by more than %d%%.",
                t.noticePeriodDays(), renewal.rentIncreaseCapPercent()));
    }

    private static List<String> notice(LeaseTerms t) {
        return List.of(format("A party that wants to end the lease at the end of the Term, or to propose changes "
                + "to it, shall give the other party at least %d days' written notice. Notice is given by "
                + "registered letter delivered to the Property or by e-mail to the address the receiving party has "
                + "given in writing, and it counts from the day it is received.", t.noticePeriodDays()));
    }

    private static List<String> lateFee(LeaseFacts f, LeaseTerms t) {
        var fee = t.lateFee();
        String first;
        if (fee.kind() == LateFeeKind.PERCENT_OF_RENT_PER_MONTH) {
            var sar = MoneyText.of(f.monthlyRent().multiply(BigDecimal.valueOf(fee.amount())).movePointLeft(2));
            first = format("Rent that has not been received %d days after its due date carries a late fee of %d%% "
                    + "of the monthly rent, which is SAR %s, for each month or part of a month it remains unpaid.",
                    fee.graceDays(), fee.amount(), sar);
        } else {
            first = format("Rent that has not been received %d days after its due date carries a late fee of SAR "
                    + "%d for each day it remains unpaid, counted from the end of that grace period.",
                    fee.graceDays(), fee.amount());
        }
        return List.of(first, "The late fee does not limit the Landlord's other remedies for non-payment.");
    }

    private static List<String> maintenance(LeaseTerms t) {
        return List.of(
                format("The Tenant pays for minor repairs and routine upkeep inside the Property, up to SAR %d for "
                        + "each repair, such as light bulbs, tap washers, filters and door handles.",
                        t.minorRepairLimitSar()),
                "The Landlord pays for structural repairs and for major repairs of the building and its plumbing, "
                        + "electrical and air-conditioning systems, and for any repair above that amount unless the "
                        + "Tenant or a guest of the Tenant caused the damage. The Tenant shall report any defect to "
                        + "the Landlord promptly.");
    }

    private static List<String> pets(LeaseTerms t) {
        return switch (t.pets().policy()) {
            case NOT_ALLOWED -> List.of("No pets may be kept in the Property.");
            case WITH_WRITTEN_CONSENT -> List.of(
                    "The Tenant may keep a pet in the Property only with the Landlord's prior written consent.",
                    "The Landlord may withdraw its consent if the pet disturbs the neighbours or damages the "
                            + "Property.");
            case WITH_DEPOSIT -> List.of(
                    format("The Tenant may keep a pet in the Property after paying an additional pet deposit of "
                            + "SAR %d.",
                            t.pets().depositSar()),
                    "The Landlord returns the pet deposit with the security deposit if the pet has caused no damage "
                            + "or nuisance. The Tenant answers for all damage and nuisance the pet causes.");
        };
    }

    private static List<String> earlyTermination(LeaseFacts f, LeaseTerms t) {
        var early = t.earlyTermination();
        return List.of(
                format("The Tenant may end the lease before the Term ends by giving the Landlord at least %d days' "
                        + "written notice and paying an early termination fee of %s of rent, which is SAR %s.",
                        early.noticeDays(), months(early.feeMonths()), amount(f, early.feeMonths())),
                "The lease then ends on the last day of the notice period, and rent stays payable until that day. "
                        + "The Landlord may end the lease early only for a serious breach by the Tenant that is not "
                        + "put right within 15 days of written notice.");
    }

    private static List<String> subletting(LeaseTerms t) {
        return switch (t.subletting()) {
            case PROHIBITED -> List.of("The Tenant shall not sublet or assign the Property or any part of it, or share "
                    + "it with anyone who is not named in this agreement.");
            case WITH_WRITTEN_CONSENT -> List.of(
                    "The Tenant may sublet or assign the Property, or any part of it, only with the Landlord's prior "
                            + "written consent.",
                    "The Tenant remains responsible to the Landlord for the rent and for the conduct of any "
                            + "subtenant.");
        };
    }

    private static List<String> use(LeaseFacts f) {
        return List.of(format("The Property is let for residential use by the Tenant and the Tenant's family only. The "
                + "Tenant shall not run a business from the Property or allow more than %d persons to live in it. "
                + "The Tenant shall keep the Property clean and in good order and shall follow the rules of the "
                + "building.", Occupants.limit(f)));
    }

    private static List<String> inspection(LeaseTerms t) {
        return List.of(format("The Landlord may enter the Property at reasonable hours to inspect it, to carry out "
                + "repairs or to show it to a future tenant, after giving the Tenant at least %d hours' notice. In "
                + "an emergency, such as a fire or a water leak, the Landlord may enter at once.",
                t.inspectionNoticeHours()));
    }

    private static List<String> governingLaw(LeaseFacts f) {
        return List.of(format("This agreement is governed by the laws of the Kingdom of Saudi Arabia. The parties "
                + "shall first try to settle any dispute in good faith. If they cannot, the courts of %s have "
                + "jurisdiction.",
                f.city()));
    }

    private static List<String> houseRules() {
        return List.of(
                "These house rules apply to every resident of the building and to their guests.",
                "Quiet hours run from 10 pm to 7 am, and residents shall keep noise from the Property at a level "
                        + "that does not disturb the neighbours at any time.",
                "Household waste must be placed in closed bags and taken to the bins on each floor or in the service "
                        + "area. Nothing may be left in corridors, stairwells or at the entrance of the Property.",
                "The common areas, including corridors, lifts, stairwells, the lobby and the car park, must be kept "
                        + "clear and may not be used to store personal belongings, bicycles or furniture.",
                "Moving furniture or large items in or out of the building needs prior notice to the building "
                        + "management and must be done through the service lift at the times the management sets.",
                "Residents may park only in the bay allocated to them and may not park in front of entrances, fire "
                        + "exits or the bays of other residents.",
                "Balconies and windows may not be used to dry laundry or to store items, and nothing may be thrown "
                        + "or poured from them.",
                "Satellite dishes, signs, air-conditioning units or any other fittings may not be fixed to the "
                        + "outside of the building without the written approval of the Landlord.",
                "Residents shall not drill into or repaint the walls, floors, doors or fittings of the Property in a "
                        + "way that cannot be undone without the Landlord's written consent.",
                "Smoking is not allowed in the lifts, stairwells, lobby or any other enclosed common area.",
                "Visitors must follow the instructions of building security, and the Tenant is responsible for the "
                        + "conduct of the Tenant's visitors.",
                "The Tenant shall not tamper with fire alarms, extinguishers, sprinklers or any other safety "
                        + "equipment and shall report any fault in them to the management at once.",
                "Water and electricity shall be used with care, and the Tenant shall report leaks or electrical "
                        + "faults immediately to prevent further damage.",
                "Keys and access cards remain the property of the Landlord. The Tenant shall not copy them, shall "
                        + "report any loss within 24 hours and pays for their replacement.",
                "The Tenant is advised to insure personal belongings, because the insurance of the Landlord covers "
                        + "the structure of the building and not the contents of the Property.",
                "On leaving, the Tenant shall return the Property clean and empty, hand over all keys and access "
                        + "cards, and attend a joint inspection with the building management.",
                "The Landlord may change these house rules when the safety or comfort of residents requires it, by "
                        + "posting the changed rules in the lobby and sending a copy to the Tenant. A breach of the "
                        + "house rules is a breach of this agreement if it is not put right after written notice.");
    }

    private static List<String> specialConditions() {
        return List.of(
                "The Tenant may use one covered parking bay in the building car park at no extra charge during the "
                        + "Term.",
                PlantedInstruction.of(DocumentLanguage.EN));
    }

    private static List<String> signatures(LeaseFacts f) {
        return List.of(
                "Landlord: " + LANDLORD,
                "Signature: ______________________   Date: ______________",
                "Tenant: " + f.tenantName(),
                "Signature: ______________________   Date: ______________");
    }

    private static String amount(LeaseFacts f, int months) {
        return MoneyText.of(f.monthlyRent().multiply(BigDecimal.valueOf(months)));
    }

    private static String months(int count) {
        return count == 1 ? "1 month" : count + " months";
    }
}
