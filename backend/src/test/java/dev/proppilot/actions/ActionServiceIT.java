package dev.proppilot.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** The payment action end to end through the service, against the seeded database. */
class ActionServiceIT extends ActionIntegrationTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private BigDecimal paidAmountOfInvoice(int index) {
        return (BigDecimal) invoicesOfTenant7().get(index).get("paid_amount");
    }

    /** The invoices of tenant 7 that are unpaid in the seed are the last four periods (indexes 8 to 11). */
    private static final int OLDEST_UNPAID = 8;

    private ActionError errorOf(Runnable call) {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(ActionException.class, call::run);
        return thrown.error();
    }

    @Test
    void proposingStoresAWaitingActionAndChangesNothing() {
        var before = invoicesOfTenant7();
        var amount = rent.add(rent.divide(bd("2")));

        var proposed = proposePayment(amount.toPlainString());

        assertThat(proposed.status()).isEqualTo(ActionStatus.PROPOSED);
        assertThat(proposed.kind()).isEqualTo(ActionKind.RECORD_PAYMENT);
        assertThat(Duration.between(proposed.createdAt(), proposed.expiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(proposed.decidedAt()).isNull();
        assertThat(proposed.result()).isNull();
        assertThat(proposed.summaryEn()).contains("SAR").contains(unit).contains("Ahmed Al-Harbi");
        assertThat(proposed.summaryAr()).contains("ريال").contains(unit).contains("أحمد الحربي");
        assertThat(proposed.args().get("unit_code").asText()).isEqualTo(unit);
        assertThat(proposed.args().get("amount").decimalValue()).isEqualByComparingTo(amount);
        assertThat(proposed.args().get("payment_date").asText()).isEqualTo(today().toString());
        assertThat(proposed.args().get("method").asText()).isEqualTo("CASH");
        assertThat(proposed.args().get("overridePayment").asBoolean()).isFalse();

        var rows = proposed.preview().get("payments");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("status").asText()).isEqualTo("PAID");
        assertThat(rows.get(1).get("status").asText()).isEqualTo("PARTIAL");
        assertThat(proposed.preview().get("outstandingBefore").decimalValue()).isEqualByComparingTo(rent.multiply(bd("4")));
        assertThat(proposed.preview().get("outstandingAfter").decimalValue())
                .isEqualByComparingTo(rent.multiply(bd("4")).subtract(amount));

        assertThat(invoicesOfTenant7()).isEqualTo(before);
        assertThat(actions.get(proposed.id()).status()).isEqualTo(ActionStatus.PROPOSED);
        assertThat(audit()).hasSize(2); // the RESET of the test set-up, then PROPOSED
        assertThat(audit().get(1)).containsEntry("event", "PROPOSED").containsEntry("actor", "agent")
                .containsEntry("action_id", proposed.id());
    }

    @Test
    void approvingChangesTheDatabaseExactlyAsPreviewed() {
        var amount = rent.multiply(bd("2.5"));
        var proposed = proposePayment(amount.toPlainString());
        var untouchedBefore = invoicesOfTenant7();

        var approval = actions.approve(proposed.id(), newKey(), edits(), "visitor-1a2b3c4d");

        assertThat(approval.replayed()).isFalse();
        var action = approval.action();
        assertThat(action.status()).isEqualTo(ActionStatus.APPROVED);
        assertThat(action.actor()).isEqualTo("visitor-1a2b3c4d");
        assertThat(action.decidedAt()).isNotNull();
        assertThat(action.confirmedAt()).as("the chat has not confirmed it yet").isNull();

        var invoices = invoicesOfTenant7();
        var previewedIds = new java.util.HashSet<Long>();
        for (JsonNode previewed : proposed.preview().get("payments")) {
            previewedIds.add(previewed.get("paymentId").asLong());
            var row = invoices.stream().filter(r -> r.get("id").equals(previewed.get("paymentId").asLong())).findFirst().orElseThrow();
            assertThat((BigDecimal) row.get("paid_amount")).isEqualByComparingTo(previewed.get("paidAfter").decimalValue());
            assertThat(row.get("method")).isEqualTo("CASH");
            assertThat(row.get("recorded_action_id")).isEqualTo(proposed.id());
            if (previewed.get("status").asText().equals("PAID")) {
                assertThat(row.get("paid_on")).isEqualTo(Date.valueOf(today()));
            } else {
                assertThat(row.get("paid_on")).as("a part-paid invoice is not marked paid").isNull();
            }
        }
        assertThat(previewedIds).hasSize(3);
        assertThat(invoices.stream().filter(r -> !previewedIds.contains((Long) r.get("id"))).toList())
                .isEqualTo(untouchedBefore.stream().filter(r -> !previewedIds.contains((Long) r.get("id"))).toList());
        assertThat(outstandingOfTenant7()).isEqualByComparingTo(rent.multiply(bd("4")).subtract(amount));

        assertThat(action.result().get("applied").decimalValue()).isEqualByComparingTo(amount);
        assertThat(action.result().get("overpaymentCredit").decimalValue()).isEqualByComparingTo("0");
        assertThat(jsonOf(actions.get(proposed.id()).result())).as("stored as it was returned").isEqualTo(jsonOf(action.result()));
    }

    @Test
    void anAmountIsAppliedOldestInvoiceFirstAndSpreadOverSeveralInvoices() {
        var proposed = proposePayment(rent.multiply(bd("2.5")).toPlainString());

        actions.approve(proposed.id(), newKey(), edits(), "visitor-test");

        assertThat(paidAmountOfInvoice(OLDEST_UNPAID)).isEqualByComparingTo(rent);
        assertThat(paidAmountOfInvoice(OLDEST_UNPAID + 1)).isEqualByComparingTo(rent);
        assertThat(paidAmountOfInvoice(OLDEST_UNPAID + 2)).isEqualByComparingTo(rent.divide(bd("2")));
        assertThat(paidAmountOfInvoice(OLDEST_UNPAID + 3)).isEqualByComparingTo("0");
        assertThat(invoicesOfTenant7().get(OLDEST_UNPAID).get("paid_on")).isEqualTo(Date.valueOf(today()));
        assertThat(invoicesOfTenant7().get(OLDEST_UNPAID + 2).get("paid_on")).isNull();
    }

    @Test
    void aPartialPaymentFollowedByTheRestSettlesTheInvoice() {
        var first = proposePayment("1000");
        actions.approve(first.id(), newKey(), edits(), "visitor-test");

        assertThat(paidAmountOfInvoice(OLDEST_UNPAID)).isEqualByComparingTo("1000");
        assertThat(invoicesOfTenant7().get(OLDEST_UNPAID).get("paid_on")).isNull();
        assertThat(outstandingOfTenant7()).isEqualByComparingTo(rent.multiply(bd("4")).subtract(bd("1000")));

        var second = proposePayment(rent.subtract(bd("1000")).toPlainString());
        assertThat(second.preview().get("payments")).hasSize(1);
        assertThat(second.preview().get("payments").get(0).get("paidBefore").decimalValue()).isEqualByComparingTo("1000");
        actions.approve(second.id(), newKey(), edits(), "visitor-test");

        assertThat(paidAmountOfInvoice(OLDEST_UNPAID)).isEqualByComparingTo(rent);
        assertThat(invoicesOfTenant7().get(OLDEST_UNPAID).get("paid_on")).isEqualTo(Date.valueOf(today()));
        assertThat(invoicesOfTenant7().get(OLDEST_UNPAID).get("recorded_action_id")).isEqualTo(second.id());
        assertThat(paidAmountOfInvoice(OLDEST_UNPAID + 1)).isEqualByComparingTo("0");
    }

    @Test
    void anAmountAboveTheOutstandingBalanceIsRefusedAndNothingIsStored() {
        var tooMuch = rent.multiply(bd("4")).add(bd("0.01"));

        var error = errorOf(() -> proposePayment(tooMuch.toPlainString()));

        assertThat(error).isEqualTo(ActionError.AMOUNT_EXCEEDS_OUTSTANDING);
        assertThat(count("pending_actions")).isZero();
    }

    @Test
    void anOverpaymentIsOnlyAcceptedWithTheOverrideAndTheExcessIsNeverStoredOnAnInvoice() {
        var tooMuch = rent.multiply(bd("4")).add(bd("250.50"));
        var args = paymentArgs(tooMuch.toPlainString()).put("overridePayment", true);

        var proposed = actions.propose(ActionKind.RECORD_PAYMENT, args, null);
        var approval = actions.approve(proposed.id(), newKey(), edits(), "visitor-test");

        assertThat(proposed.preview().get("overpaymentCredit").decimalValue()).isEqualByComparingTo("250.50");
        assertThat(approval.action().result().get("overpaymentCredit").decimalValue()).isEqualByComparingTo("250.50");
        assertThat(approval.action().result().get("applied").decimalValue()).isEqualByComparingTo(rent.multiply(bd("4")));
        assertThat(outstandingOfTenant7()).isEqualByComparingTo("0");
        for (var invoice : invoicesOfTenant7()) {
            assertThat((BigDecimal) invoice.get("paid_amount")).isEqualByComparingTo((BigDecimal) invoice.get("amount"));
        }
        var approved = audit().stream().filter(row -> row.get("event").equals("APPROVED")).findFirst().orElseThrow();
        assertThat(approved.get("entity_type")).isEqualTo("LEASE_PAYMENTS");
        assertThat(jsonOf(approved.get("before")).get("payments")).hasSize(4);
        var after = jsonOf(approved.get("after"));
        assertThat(after.get("payments").get(0).get("paidOn").asText()).isEqualTo(today().toString());
        assertThat(after.get("applied").decimalValue()).isEqualByComparingTo(rent.multiply(bd("4")));
        assertThat(after.get("overpaymentCredit").decimalValue()).as("the excess is in the audit log")
                .isEqualByComparingTo("250.50");
    }

    @Test
    void anOverpaymentWithNothingOutstandingIsStillWrittenToTheAuditLog() {
        actions.approve(proposePayment(rent.multiply(bd("4")).toPlainString()).id(), newKey(), edits(), "visitor-test");
        var args = paymentArgs("300.25").put("overridePayment", true);
        var proposed = actions.propose(ActionKind.RECORD_PAYMENT, args, null);

        actions.approve(proposed.id(), newKey(), edits(), "visitor-test");

        var approved = audit().get(audit().size() - 1);
        assertThat(approved).containsEntry("event", "APPROVED").containsEntry("entity_type", "LEASE_PAYMENTS")
                .containsEntry("action_id", proposed.id());
        assertThat(jsonOf(approved.get("before")).get("payments")).isEmpty();
        var after = jsonOf(approved.get("after"));
        assertThat(after.get("payments")).isEmpty();
        assertThat(after.get("applied").decimalValue()).isEqualByComparingTo("0");
        assertThat(after.get("overpaymentCredit").decimalValue()).isEqualByComparingTo("300.25");
    }

    @Test
    void nothingCanBePaidWhenNothingIsOutstanding() {
        actions.approve(proposePayment(rent.multiply(bd("4")).toPlainString()).id(), newKey(), edits(), "visitor-test");

        assertThat(errorOf(() -> proposePayment("1"))).isEqualTo(ActionError.NOTHING_OUTSTANDING);
    }

    @Test
    void aPaymentDatedInTheFutureOrBeforeTheLeaseIsRefused() {
        var future = paymentArgs("100").put("payment_date", today().plusDays(1).toString());
        var leaseStart = jdbc.queryForObject("select start_date from leases where tenant_id = 7", java.time.LocalDate.class);
        var beforeLease = paymentArgs("100").put("payment_date", leaseStart.minusDays(1).toString());
        var onLeaseStart = paymentArgs("100").put("payment_date", leaseStart.toString());

        assertThat(errorOf(() -> actions.propose(ActionKind.RECORD_PAYMENT, future, null)))
                .isEqualTo(ActionError.PAYMENT_DATE_IN_FUTURE);
        assertThat(errorOf(() -> actions.propose(ActionKind.RECORD_PAYMENT, beforeLease, null)))
                .isEqualTo(ActionError.PAYMENT_BEFORE_LEASE_START);
        assertThat(actions.propose(ActionKind.RECORD_PAYMENT, onLeaseStart, null).status()).isEqualTo(ActionStatus.PROPOSED);
    }

    @Test
    void theSameKeyReplaysTheFirstApprovalAndExecutesNothingAgain() {
        var proposed = proposePayment("1000");
        var key = newKey();
        var first = actions.approve(proposed.id(), key, edits(), "visitor-test");
        var paidAfterFirst = invoicesOfTenant7();
        var auditAfterFirst = audit();

        var second = actions.approve(proposed.id(), key, edits(), "visitor-other");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(jsonOf(second.action().result())).isEqualTo(jsonOf(first.action().result()));
        assertThat(second.action().actor()).as("the stored decision is returned unchanged").isEqualTo("visitor-test");
        assertThat(invoicesOfTenant7()).isEqualTo(paidAfterFirst);
        assertThat(audit()).isEqualTo(auditAfterFirst);
    }

    @Test
    void aDifferentKeyOnADecidedActionIsAConflict() {
        var proposed = proposePayment("1000");
        actions.approve(proposed.id(), newKey(), edits(), "visitor-test");

        var error = errorOf(() -> actions.approve(proposed.id(), newKey(), edits(), "visitor-test"));

        assertThat(error).isEqualTo(ActionError.ACTION_ALREADY_DECIDED);
        assertThat(error.status().value()).isEqualTo(409);
        assertThat(paidAmountOfInvoice(OLDEST_UNPAID)).as("paid once, not twice").isEqualByComparingTo("1000");
    }

    @Test
    void aKeyAlreadyUsedForAnotherActionIsRefused() {
        var key = newKey();
        actions.approve(proposePayment("1000").id(), key, edits(), "visitor-test");
        var other = proposePayment("500");

        var error = errorOf(() -> actions.approve(other.id(), key, edits(), "visitor-test"));

        assertThat(error).isEqualTo(ActionError.IDEMPOTENCY_KEY_REUSED);
        assertThat(actions.get(other.id()).status()).isEqualTo(ActionStatus.PROPOSED);
    }

    @Test
    void theKeyMustBeOneToSixtyFourSafeCharacters() {
        var proposed = proposePayment("1000");

        for (String key : new String[] {null, "", "has space", "semi;colon", "x".repeat(65), "ключ"}) {
            assertThat(errorOf(() -> actions.approve(proposed.id(), key, edits(), "visitor-test")))
                    .as("key %s", key).isEqualTo(ActionError.INVALID_IDEMPOTENCY_KEY);
        }
        assertThat(actions.approve(proposed.id(), "x".repeat(64), edits(), "visitor-test").action().status())
                .isEqualTo(ActionStatus.APPROVED);
    }

    @Test
    void twoApprovalsAtTheSameTimeExecuteTheActionOnce() throws Exception {
        var proposed = proposePayment("1000");

        var outcomes = inParallel(
                () -> actions.approve(proposed.id(), newKey(), edits(), "visitor-a"),
                () -> actions.approve(proposed.id(), newKey(), edits(), "visitor-b"));

        assertThat(outcomes.stream().filter(o -> o instanceof ApprovalView).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o instanceof ActionException).map(o -> ((ActionException) o).error()))
                .containsExactly(ActionError.ACTION_ALREADY_DECIDED);
        assertThat(paidAmountOfInvoice(OLDEST_UNPAID)).isEqualByComparingTo("1000");
        assertThat(auditEvents()).filteredOn("APPROVED"::equals).hasSize(1);
    }

    @Test
    void twoCallsWithTheSameKeyAtTheSameTimeExecuteOnceAndBothGetTheResult() throws Exception {
        var proposed = proposePayment("1000");
        var key = newKey();

        var outcomes = inParallel(
                () -> actions.approve(proposed.id(), key, edits(), "visitor-a"),
                () -> actions.approve(proposed.id(), key, edits(), "visitor-a"));

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(ApprovalView.class));
        assertThat(outcomes.stream().map(o -> ((ApprovalView) o).replayed())).containsExactlyInAnyOrder(false, true);
        assertThat(paidAmountOfInvoice(OLDEST_UNPAID)).isEqualByComparingTo("1000");
    }

    @Test
    void twoProposalsThatTogetherExceedTheBalanceNeverOverpay() throws Exception {
        var amount = rent.multiply(bd("3")).toPlainString();
        var first = proposePayment(amount);
        var second = proposePayment(amount);

        var outcomes = inParallel(
                () -> actions.approve(first.id(), newKey(), edits(), "visitor-a"),
                () -> actions.approve(second.id(), newKey(), edits(), "visitor-b"));

        assertThat(outcomes.stream().filter(o -> o instanceof ApprovalView)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o instanceof ActionException).map(o -> ((ActionException) o).error()))
                .containsExactly(ActionError.AMOUNT_EXCEEDS_OUTSTANDING);
        assertThat(outstandingOfTenant7()).isEqualByComparingTo(rent);
        assertThat(List.of(actions.get(first.id()).status(), actions.get(second.id()).status()))
                .containsExactlyInAnyOrder(ActionStatus.APPROVED, ActionStatus.FAILED);
    }

    @Test
    void aProposalNobodyDecidedOnCannotBeApprovedAfterItExpired() {
        var proposed = proposePayment("1000");
        jdbc.update("update pending_actions set expires_at = now() - interval '1 second' where id = ?", proposed.id());
        var before = invoicesOfTenant7();

        var error = errorOf(() -> actions.approve(proposed.id(), newKey(), edits(), "visitor-test"));

        assertThat(error).isEqualTo(ActionError.ACTION_EXPIRED);
        assertThat(error.status().value()).isEqualTo(410);
        assertThat(pendingActionRow(proposed.id())).containsEntry("status", "EXPIRED");
        assertThat(invoicesOfTenant7()).isEqualTo(before);
        assertThat(errorOf(() -> actions.approve(proposed.id(), newKey(), edits(), "visitor-test")))
                .as("expired stays expired").isEqualTo(ActionError.ACTION_EXPIRED);
        assertThat(audit().stream().filter(row -> row.get("event").equals("EXPIRED")).toList()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("actor", "system").containsEntry("action_id", proposed.id()));
    }

    @Test
    void expiryIsAppliedWhenAnActionIsReadOrListed() {
        var proposed = proposePayment("1000");
        var other = proposePayment("500");
        jdbc.update("update pending_actions set expires_at = now() - interval '1 minute'");

        assertThat(actions.get(proposed.id()).status()).isEqualTo(ActionStatus.EXPIRED);
        assertThat(actions.latest(50)).extracting(ActionView::status).containsOnly(ActionStatus.EXPIRED);
        assertThat(actions.get(other.id()).decidedAt()).isNotNull();
        assertThat(auditEvents()).filteredOn("EXPIRED"::equals).hasSize(2);
    }

    @Test
    void aProposalThatNoLongerHoldsFailsAtApprovalWithoutChangingAnything() {
        var amount = rent.multiply(bd("3")).toPlainString();
        var stale = proposePayment(amount);
        actions.approve(proposePayment(amount).id(), newKey(), edits(), "visitor-test");
        var before = invoicesOfTenant7();
        var key = newKey();

        var error = errorOf(() -> actions.approve(stale.id(), key, edits(), "visitor-test"));

        assertThat(error).isEqualTo(ActionError.AMOUNT_EXCEEDS_OUTSTANDING);
        assertThat(invoicesOfTenant7()).isEqualTo(before);
        var failed = actions.get(stale.id());
        assertThat(failed.status()).isEqualTo(ActionStatus.FAILED);
        assertThat(failed.decisionReason()).contains("outstanding");
        assertThat(failed.result().get("code").asText()).isEqualTo("amount_exceeds_outstanding");
        assertThat(audit().get(audit().size() - 1)).containsEntry("event", "FAILED").containsEntry("action_id", stale.id());

        assertThat(errorOf(() -> actions.approve(stale.id(), key, edits(), "visitor-test")))
                .as("the same key gets the same answer").isEqualTo(ActionError.AMOUNT_EXCEEDS_OUTSTANDING);
        assertThat(errorOf(() -> actions.approve(stale.id(), newKey(), edits(), "visitor-test")))
                .as("a failed action is final").isEqualTo(ActionError.ACTION_ALREADY_DECIDED);
    }

    @Test
    void anApproverMayChangeTheAmountAndTheEditIsAudited() {
        var proposed = proposePayment("1000");
        var edit = edits().put("amount", new BigDecimal("2500.50"));

        var approval = actions.approve(proposed.id(), newKey(), edit, "visitor-test");

        var action = approval.action();
        assertThat(action.args().get("amount").decimalValue()).isEqualByComparingTo("2500.50");
        assertThat(action.summaryEn()).contains("2,500.50");
        assertThat(action.result().get("applied").decimalValue()).isEqualByComparingTo("2500.50");
        assertThat(outstandingOfTenant7()).isEqualByComparingTo(rent.multiply(bd("4")).subtract(bd("2500.50")));
        var edited = audit().stream().filter(row -> row.get("event").equals("EDITED")).toList();
        assertThat(edited).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("actor", "visitor-test").containsEntry("entity_type", "PENDING_ACTION");
            assertThat(jsonOf(row.get("before")).get("amount").decimalValue()).isEqualByComparingTo("1000");
            assertThat(jsonOf(row.get("after")).get("amount").decimalValue()).isEqualByComparingTo("2500.50");
        });
        assertThat(auditEvents()).containsSubsequence("PROPOSED", "EDITED", "APPROVED");
    }

    @Test
    void anEditThatIsNotAllowedLeavesTheProposalWaitingSoItCanBeCorrected() {
        var proposed = proposePayment("1000");
        var tooMuch = edits().put("amount", rent.multiply(bd("4")).add(bd("1")));

        assertThat(errorOf(() -> actions.approve(proposed.id(), newKey(), tooMuch, "visitor-test")))
                .isEqualTo(ActionError.AMOUNT_EXCEEDS_OUTSTANDING);
        assertThat(errorOf(() -> actions.approve(proposed.id(), newKey(), edits().put("unit_code", "A-101"), "visitor-test")))
                .isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(actions.get(proposed.id()).status()).isEqualTo(ActionStatus.PROPOSED);
        assertThat(auditEvents()).doesNotContain("EDITED", "FAILED");

        tooMuch.put("overridePayment", true);
        var approval = actions.approve(proposed.id(), newKey(), tooMuch, "visitor-test");

        assertThat(approval.action().status()).isEqualTo(ActionStatus.APPROVED);
        assertThat(approval.action().result().get("overpaymentCredit").decimalValue()).isEqualByComparingTo("1");
    }

    @Test
    void sendingBackTheProposedValuesIsNotAnEdit() {
        var proposed = proposePayment("1000");
        var repeated = edits().put("amount", 1000).put("overridePayment", false);

        var approval = actions.approve(proposed.id(), newKey(), repeated, "visitor-test");

        assertThat(approval.action().status()).isEqualTo(ActionStatus.APPROVED);
        assertThat(auditEvents()).doesNotContain("EDITED");
        assertThat(paidAmountOfInvoice(OLDEST_UNPAID)).isEqualByComparingTo("1000");
    }

    @Test
    void theSameAmountInAnotherNotationIsNotAnEdit() {
        for (var amount : List.of(bd("100"), bd("100.0"), bd("100.00"), bd("1E+2"))) {
            var proposed = proposePayment("100");

            actions.approve(proposed.id(), newKey(), edits().put("amount", amount), "visitor-test");
        }
        var asDouble = proposePayment("100");
        actions.approve(asDouble.id(), newKey(), edits().put("amount", 100.0), "visitor-test");

        assertThat(auditEvents()).doesNotContain("EDITED");
    }

    @Test
    void aBodyThatChangesOneValueAndRepeatsAnotherIsOneEdit() {
        var proposed = proposePayment("1000");
        var edit = edits().put("amount", 1200).put("overridePayment", false);

        actions.approve(proposed.id(), newKey(), edit, "visitor-test");

        var edited = audit().stream().filter(row -> row.get("event").equals("EDITED")).toList();
        assertThat(edited).singleElement().satisfies(row -> {
            assertThat(jsonOf(row.get("before")).get("amount").decimalValue()).isEqualByComparingTo("1000");
            assertThat(jsonOf(row.get("after")).get("amount").decimalValue()).isEqualByComparingTo("1200");
        });
    }

    @Test
    void aProposalThatNoLongerHoldsFailsEvenWhenTheBodyRepeatsItsValues() {
        var amount = rent.multiply(bd("3"));
        var stale = proposePayment(amount.toPlainString());
        actions.approve(proposePayment(amount.toPlainString()).id(), newKey(), edits(), "visitor-test");
        var repeated = edits().put("amount", amount).put("overridePayment", false);
        var before = invoicesOfTenant7();

        var error = errorOf(() -> actions.approve(stale.id(), newKey(), repeated, "visitor-test"));

        assertThat(error).isEqualTo(ActionError.AMOUNT_EXCEEDS_OUTSTANDING);
        assertThat(actions.get(stale.id()).status()).as("it failed for good, it was not sent back for correction")
                .isEqualTo(ActionStatus.FAILED);
        assertThat(invoicesOfTenant7()).isEqualTo(before);
        assertThat(auditEvents()).doesNotContain("EDITED");
    }

    @Test
    void anAmountThatIsNotAFiniteNumberIsRefusedAndLeavesTheProposalWaiting() {
        var proposed = proposePayment("1000");
        var infinite = edits().put("amount", Double.POSITIVE_INFINITY);

        assertThat(errorOf(() -> actions.approve(proposed.id(), newKey(), infinite, "visitor-test")))
                .isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(errorOf(() -> actions.preview(proposed.id(), infinite))).isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(actions.get(proposed.id()).status()).isEqualTo(ActionStatus.PROPOSED);
    }

    @Test
    void aPreviewWithEditsShowsTheNewDiffAndChangesNothing() {
        var proposed = proposePayment("1000");
        var before = invoicesOfTenant7();
        var auditBefore = audit();

        var preview = actions.preview(proposed.id(), edits().put("amount", rent.add(bd("100"))));

        assertThat(preview.args().get("amount").decimalValue()).isEqualByComparingTo(rent.add(bd("100")));
        assertThat(preview.preview().get("payments")).hasSize(2);
        assertThat(preview.summaryEn()).contains(Money.format(rent.add(bd("100"))));
        assertThat(invoicesOfTenant7()).isEqualTo(before);
        assertThat(audit()).isEqualTo(auditBefore);
        assertThat(actions.get(proposed.id()).args().get("amount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(errorOf(() -> actions.preview(proposed.id(), edits().put("amount", bd("99999999")))))
                .isEqualTo(ActionError.INVALID_AMOUNT);
    }

    @Test
    void rejectingStoresTheReasonAndLeavesEveryTableUnchanged() {
        var proposed = proposePayment("1000");
        var tables = new String[] {"payments", "maintenance_requests", "outbox_messages", "tenants", "leases"};
        var before = invoicesOfTenant7();
        var counts = java.util.Arrays.stream(tables).map(this::count).toList();

        var rejected = actions.reject(proposed.id(), "  wrong\namount  ", "visitor-test");

        assertThat(rejected.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(rejected.decisionReason()).isEqualTo("wrong amount");
        assertThat(rejected.actor()).isEqualTo("visitor-test");
        assertThat(rejected.decidedAt()).isNotNull();
        assertThat(invoicesOfTenant7()).isEqualTo(before);
        assertThat(java.util.Arrays.stream(tables).map(this::count).toList()).isEqualTo(counts);
        assertThat(audit().get(audit().size() - 1)).containsEntry("event", "REJECTED").containsEntry("actor", "visitor-test")
                .containsEntry("note", "wrong amount");
        assertThat(errorOf(() -> actions.approve(proposed.id(), newKey(), edits(), "visitor-test")))
                .isEqualTo(ActionError.ACTION_ALREADY_DECIDED);
    }

    @Test
    void rejectingTwiceChangesNothingTheSecondTime() {
        var proposed = proposePayment("1000");
        actions.reject(proposed.id(), "no", "visitor-test");
        var auditAfterFirst = audit();

        var again = actions.reject(proposed.id(), "still no", "visitor-other");

        assertThat(again.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(again.decisionReason()).isEqualTo("no");
        assertThat(audit()).isEqualTo(auditAfterFirst);
    }

    @Test
    void aRejectionReasonMayHaveTwoHundredCharactersAndNoMore() {
        var proposed = proposePayment("1000");

        assertThat(errorOf(() -> actions.reject(proposed.id(), "x".repeat(201), "visitor-test")))
                .isEqualTo(ActionError.INVALID_ARGUMENT);
        assertThat(actions.get(proposed.id()).status()).isEqualTo(ActionStatus.PROPOSED);
        assertThat(actions.reject(proposed.id(), "x".repeat(200), "visitor-test").decisionReason()).hasSize(200);
    }

    @Test
    void anUnknownActionIsNotFound() {
        assertThat(errorOf(() -> actions.get(UUID.randomUUID()))).isEqualTo(ActionError.ACTION_NOT_FOUND);
        assertThat(errorOf(() -> actions.approve(UUID.randomUUID(), newKey(), edits(), "visitor-test")))
                .isEqualTo(ActionError.ACTION_NOT_FOUND);
        assertThat(errorOf(() -> actions.reject(UUID.randomUUID(), null, "visitor-test")))
                .isEqualTo(ActionError.ACTION_NOT_FOUND);
    }

    @Test
    void theLatestActionsComeNewestFirstAndAreCapped() {
        var first = proposePayment("100");
        var second = proposePayment("200");

        assertThat(actions.latest(50)).extracting(ActionView::id).containsExactly(second.id(), first.id());
        assertThat(actions.latest(1)).extracting(ActionView::id).containsExactly(second.id());
    }

    @Test
    void anApprovalWorksFromTheInvoicesAsTheyAreOnceAnotherPaymentLetGoOfThem() throws Exception {
        var proposed = proposePayment(rent.multiply(bd("3")).toPlainString());
        var leaseId = jdbc.queryForObject("select id from leases where tenant_id = 7", Long.class);
        var pool = Executors.newSingleThreadExecutor();
        try (var other = dataSource.getConnection()) {
            // Another payment is in the middle of changing the lease's invoices ...
            other.setAutoCommit(false);
            try (var lock = other.prepareStatement("select id from payments where lease_id = ? and paid_on is null for update")) {
                lock.setLong(1, leaseId);
                lock.executeQuery();
            }
            Future<Object> approval = pool.submit(() -> {
                try {
                    return actions.approve(proposed.id(), newKey(), edits(), "visitor-test");
                } catch (ActionException refused) {
                    return refused;
                }
            });
            waitUntilAStatementWaitsForALock();
            // ... and pays half of every invoice before it lets go.
            try (var pay = other.prepareStatement("update payments set paid_amount = amount / 2 where lease_id = ? and paid_on is null")) {
                pay.setLong(1, leaseId);
                pay.executeUpdate();
            }
            other.commit();

            var outcome = approval.get(20, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(outcome).isInstanceOfSatisfying(ActionException.class,
                    refused -> assertThat(refused.error()).isEqualTo(ActionError.AMOUNT_EXCEEDS_OUTSTANDING));
            assertThat(outstandingOfTenant7()).as("the half payments of the other payment are intact")
                    .isEqualByComparingTo(rent.multiply(bd("2")));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aDecidedActionIsConfirmedInTheChatOnce() {
        var approved = proposePayment("1000");
        actions.approve(approved.id(), newKey(), edits(), "visitor-test");
        var rejected = proposePayment("200");
        actions.reject(rejected.id(), "wrong unit", "visitor-test");

        var confirmed = actions.claimConfirmation(approved.id());

        assertThat(confirmed.status()).isEqualTo(ActionStatus.APPROVED);
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(pendingActionRow(approved.id()).get("confirmed_at")).isNotNull();
        assertThat(errorOf(() -> actions.claimConfirmation(approved.id()))).isEqualTo(ActionError.ACTION_ALREADY_CONFIRMED);
        assertThat(actions.claimConfirmation(rejected.id()).decisionReason()).isEqualTo("wrong unit");
        assertThat(errorOf(() -> actions.claimConfirmation(rejected.id()))).isEqualTo(ActionError.ACTION_ALREADY_CONFIRMED);
    }

    @Test
    void aFailedApprovalCanBeConfirmedToo() {
        var amount = rent.multiply(bd("3")).toPlainString();
        var stale = proposePayment(amount);
        actions.approve(proposePayment(amount).id(), newKey(), edits(), "visitor-test");
        errorOf(() -> actions.approve(stale.id(), newKey(), edits(), "visitor-test"));

        var confirmed = actions.claimConfirmation(stale.id());

        assertThat(confirmed.status()).isEqualTo(ActionStatus.FAILED);
        assertThat(confirmed.decisionReason()).contains("outstanding");
    }

    @Test
    void anUndecidedOrExpiredOrUnknownActionHasNothingToConfirm() {
        var waiting = proposePayment("1000");
        var expired = proposePayment("500");
        jdbc.update("update pending_actions set expires_at = now() - interval '1 second' where id = ?", expired.id());

        assertThat(errorOf(() -> actions.claimConfirmation(waiting.id()))).isEqualTo(ActionError.ACTION_NOT_DECIDED);
        assertThat(errorOf(() -> actions.claimConfirmation(expired.id()))).isEqualTo(ActionError.ACTION_NOT_DECIDED);
        assertThat(errorOf(() -> actions.claimConfirmation(UUID.randomUUID()))).isEqualTo(ActionError.ACTION_NOT_FOUND);
        assertThat(pendingActionRow(waiting.id())).containsEntry("status", "PROPOSED").containsEntry("confirmed_at", null);
    }

    @Test
    void anActionThatWasNotConfirmedYetCanStillBeConfirmedAfterItWasDecidedOn() {
        var proposed = proposePayment("1000");
        assertThat(errorOf(() -> actions.claimConfirmation(proposed.id()))).isEqualTo(ActionError.ACTION_NOT_DECIDED);

        actions.approve(proposed.id(), newKey(), edits(), "visitor-test");

        assertThat(actions.claimConfirmation(proposed.id()).confirmedAt()).isNotNull();
    }

    @Test
    void ofTwoSimultaneousConfirmationsExactlyOneWins() throws Exception {
        var proposed = proposePayment("1000");
        actions.approve(proposed.id(), newKey(), edits(), "visitor-test");

        var outcomes = inParallel(() -> actions.claimConfirmation(proposed.id()), () -> actions.claimConfirmation(proposed.id()));

        assertThat(outcomes.stream().filter(o -> o instanceof ActionView)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o instanceof ActionException).map(o -> ((ActionException) o).error()))
                .containsExactly(ActionError.ACTION_ALREADY_CONFIRMED);
    }

    /** Runs the calls on separate threads, released together, and returns what each returned or threw. */
    private List<Object> inParallel(Callable<?>... calls) throws Exception {
        var ready = new CountDownLatch(calls.length);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(calls.length);
        try {
            var futures = new java.util.ArrayList<Future<Object>>();
            for (var call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return call.call();
                    } catch (ActionException refused) {
                        return refused;
                    }
                }));
            }
            ready.await();
            go.countDown();
            var outcomes = new java.util.ArrayList<Object>();
            for (var future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
