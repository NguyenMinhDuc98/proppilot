package dev.proppilot.actions;

import dev.proppilot.domain.Payment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Spreads a payment over invoices first-in first-out: the oldest period is settled before the next one is touched. */
final class PaymentAllocator {

    /** One invoice's share of a payment. */
    record Slice(Payment payment, BigDecimal paidBefore, BigDecimal applied) {

        BigDecimal paidAfter() {
            return paidBefore.add(applied);
        }

        boolean settles() {
            return paidAfter().compareTo(payment.getAmount()) == 0;
        }
    }

    /**
     * @param applied the part of the payment that settled or reduced an invoice
     * @param excess the part that is left over once every given invoice is settled
     */
    record Allocation(List<Slice> slices, BigDecimal applied, BigDecimal excess) {
    }

    private PaymentAllocator() {
    }

    /** Does not change the invoices; {@code oldestFirst} must already be in period order. */
    static Allocation allocate(List<Payment> oldestFirst, BigDecimal amount) {
        var left = amount;
        var slices = new ArrayList<Slice>();
        for (var payment : oldestFirst) {
            var share = payment.remaining().min(left);
            if (share.signum() > 0) {
                slices.add(new Slice(payment, payment.getPaidAmount(), share));
                left = left.subtract(share);
            }
        }
        return new Allocation(List.copyOf(slices), amount.subtract(left), left);
    }
}
