package dev.proppilot.actions;

import static dev.proppilot.actions.Fixtures.invoice;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentAllocatorTest {

    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);

    private static List<dev.proppilot.domain.Payment> threeInvoicesOf1000() {
        return List.of(invoice(1, JULY, "1000", "0"), invoice(2, JULY.plusMonths(1), "1000", "0"),
                invoice(3, JULY.plusMonths(2), "1000", "0"));
    }

    @Test
    void settlesTheOldestInvoiceBeforeTouchingTheNext() {
        var allocation = PaymentAllocator.allocate(threeInvoicesOf1000(), new BigDecimal("1500"));

        assertThat(allocation.slices()).hasSize(2);
        assertThat(allocation.slices().get(0).payment().getId()).isEqualTo(1);
        assertThat(allocation.slices().get(0).applied()).isEqualByComparingTo("1000");
        assertThat(allocation.slices().get(0).settles()).isTrue();
        assertThat(allocation.slices().get(1).payment().getId()).isEqualTo(2);
        assertThat(allocation.slices().get(1).applied()).isEqualByComparingTo("500");
        assertThat(allocation.slices().get(1).settles()).isFalse();
        assertThat(allocation.applied()).isEqualByComparingTo("1500");
        assertThat(allocation.excess()).isEqualByComparingTo("0");
    }

    @Test
    void continuesWhereAnEarlierPartialPaymentStopped() {
        var rows = List.of(invoice(1, JULY, "1000", "400"), invoice(2, JULY.plusMonths(1), "1000", "0"));

        var allocation = PaymentAllocator.allocate(rows, new BigDecimal("700"));

        assertThat(allocation.slices().get(0).paidBefore()).isEqualByComparingTo("400");
        assertThat(allocation.slices().get(0).applied()).isEqualByComparingTo("600");
        assertThat(allocation.slices().get(0).paidAfter()).isEqualByComparingTo("1000");
        assertThat(allocation.slices().get(0).settles()).isTrue();
        assertThat(allocation.slices().get(1).applied()).isEqualByComparingTo("100");
    }

    @Test
    void anExactPaymentLeavesNothingOver() {
        var allocation = PaymentAllocator.allocate(threeInvoicesOf1000(), new BigDecimal("3000"));

        assertThat(allocation.slices()).hasSize(3).allMatch(PaymentAllocator.Slice::settles);
        assertThat(allocation.excess()).isEqualByComparingTo("0");
    }

    @Test
    void whatIsLeftOverAfterEveryInvoiceIsSettledIsReportedAsExcess() {
        var allocation = PaymentAllocator.allocate(threeInvoicesOf1000(), new BigDecimal("3250.75"));

        assertThat(allocation.applied()).isEqualByComparingTo("3000");
        assertThat(allocation.excess()).isEqualByComparingTo("250.75");
    }

    @Test
    void anAmountSmallerThanTheFirstInvoiceOnlyTouchesTheFirstInvoice() {
        var allocation = PaymentAllocator.allocate(threeInvoicesOf1000(), new BigDecimal("0.01"));

        assertThat(allocation.slices()).hasSize(1);
        assertThat(allocation.slices().get(0).applied()).isEqualByComparingTo("0.01");
    }

    @Test
    void withoutOutstandingInvoicesEverythingIsExcess() {
        var allocation = PaymentAllocator.allocate(List.of(), new BigDecimal("100"));

        assertThat(allocation.slices()).isEmpty();
        assertThat(allocation.applied()).isEqualByComparingTo("0");
        assertThat(allocation.excess()).isEqualByComparingTo("100");
    }

    @Test
    void anInvoiceWithNothingLeftIsSkipped() {
        var rows = List.of(invoice(1, JULY, "1000", "1000"), invoice(2, JULY.plusMonths(1), "1000", "0"));

        var allocation = PaymentAllocator.allocate(rows, new BigDecimal("300"));

        assertThat(allocation.slices()).singleElement().satisfies(slice -> assertThat(slice.payment().getId()).isEqualTo(2));
    }

    @Test
    void doesNotChangeTheInvoices() {
        var rows = threeInvoicesOf1000();

        PaymentAllocator.allocate(rows, new BigDecimal("2500"));

        assertThat(rows).allSatisfy(row -> assertThat(row.getPaidAmount()).isEqualByComparingTo("0"));
    }
}
