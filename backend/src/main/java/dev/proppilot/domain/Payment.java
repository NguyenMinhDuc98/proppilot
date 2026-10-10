package dev.proppilot.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor
public class Payment {

    @Id
    private Long id;
    private LocalDate period;
    private LocalDate dueDate;
    private BigDecimal amount;
    private BigDecimal paidAmount;
    private LocalDate paidOn;

    /** How the latest payment toward this invoice was made; null for the payments that came with the seed data. */
    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    /** The approved action that last paid toward this invoice. */
    private UUID recordedActionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id")
    private Lease lease;

    public boolean isPaid() {
        return paidOn != null;
    }

    /** True once something was paid but the invoice is not settled yet; such a row still has {@code paid_on} null. */
    public boolean isPartiallyPaid() {
        return paidAmount.signum() > 0 && paidAmount.compareTo(amount) < 0;
    }

    /** What is still owed on this invoice. */
    public BigDecimal remaining() {
        return amount.subtract(paidAmount);
    }

    /** Pays {@code portion} of what is left; the invoice counts as paid on {@code on} once nothing is left. */
    public void receive(BigDecimal portion, LocalDate on, PaymentMethod method, UUID actionId) {
        paidAmount = paidAmount.add(portion);
        if (paidAmount.compareTo(amount) == 0) {
            paidOn = on;
        }
        this.method = method;
        this.recordedActionId = actionId;
    }

    public static BigDecimal totalRemaining(Collection<Payment> payments) {
        return payments.stream().map(Payment::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
