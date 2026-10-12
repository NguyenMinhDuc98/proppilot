package dev.proppilot.agent.tools;

import dev.proppilot.domain.Lease;
import dev.proppilot.domain.Payment;
import dev.proppilot.domain.PaymentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Writes a tenant message from {@link MessageTemplates} and the lease's current rent position. Nothing is sent. */
@Component
public class MessageDrafter {

    public enum Purpose { RENT_REMINDER, LEASE_RENEWAL, MAINTENANCE_NOTICE }

    public enum Language { EN, AR }

    /** Where the model picks a purpose: a plain "reminder" is a rent reminder, so it is never asked which kind. */
    static final String PURPOSE_HELP = "What the message is about. A reminder, or a message with no other purpose, is "
            + "RENT_REMINDER";

    public sealed interface Drafted {

        record Message(String tenantEn, String tenantAr, String unitCode, Language language, Purpose purpose,
                       String subject, String body) implements Drafted {
        }

        /** A rent reminder only makes sense while rent is overdue. */
        record NoOverdueRent(String unitCode) implements Drafted {

            public String message() {
                return "Unit " + unitCode + " has no overdue rent, so no reminder is needed.";
            }
        }
    }

    private final PaymentRepository payments;
    private final Clock clock;

    MessageDrafter(PaymentRepository payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    /** The language defaults to the tenant's preferred one. */
    public Drafted draft(Lease lease, Purpose purpose, Optional<Language> language) {
        var tenant = lease.getTenant();
        var unit = lease.getUnit();
        var chosen = language.orElse("ar".equals(tenant.getPreferredLanguage()) ? Language.AR : Language.EN);
        boolean arabic = chosen == Language.AR;

        var overdue = payments.findByLeaseIdAndPaidOnIsNullAndDueDateBeforeOrderByPeriod(lease.getId(), LocalDate.now(clock));
        if (purpose == Purpose.RENT_REMINDER && overdue.isEmpty()) {
            return new Drafted.NoOverdueRent(unit.getCode());
        }
        var context = new MessageTemplates.Context(
                arabic ? tenant.getNameAr() : tenant.getNameEn(), unit.getCode(), unit.getBuilding().getName(),
                overdue.size(), Payment.totalRemaining(overdue),
                overdue.isEmpty() ? null : overdue.get(0).getDueDate(), lease.getEndDate());

        var draft = switch (purpose) {
            case RENT_REMINDER -> MessageTemplates.rentReminder(arabic, context);
            case LEASE_RENEWAL -> MessageTemplates.leaseRenewal(arabic, context);
            case MAINTENANCE_NOTICE -> MessageTemplates.maintenanceNotice(arabic, context);
        };
        return new Drafted.Message(tenant.getNameEn(), tenant.getNameAr(), unit.getCode(), chosen, purpose,
                draft.subject(), draft.body());
    }
}
