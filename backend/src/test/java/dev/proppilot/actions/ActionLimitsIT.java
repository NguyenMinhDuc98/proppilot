package dev.proppilot.actions;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.config.ActionProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The daily cap on approved actions, with an {@link ActionService} of its own so every test starts with a full
 * budget: the cap is a counter in memory, shared by everything that runs in one application context.
 */
class ActionLimitsIT extends ActionIntegrationTest {

    @Autowired
    List<ActionExecutor<?>> executors;
    @Autowired
    PendingActionRepository repository;
    @Autowired
    AuditTrail auditTrail;
    @Autowired
    PlatformTransactionManager transactions;

    private ActionService withDailyCap(int cap) {
        var props = new ActionProperties(15, 1000, cap);
        return new ActionService(executors, repository, auditTrail, new ActionLimits(props, clock), props, clock, json,
                transactions);
    }

    private ActionError refusal(Runnable call) {
        return Assertions.assertThrows(ActionException.class, call::run).error();
    }

    @Test
    void theApprovalOverTheDailyCapIsRefusedAndLeavesTheActionWaiting() {
        var capped = withDailyCap(2);
        var first = proposePayment("100");
        var second = proposePayment("100");
        var third = proposePayment("100");
        capped.approve(first.id(), newKey(), edits(), "visitor-test");
        capped.approve(second.id(), newKey(), edits(), "visitor-test");
        var invoices = invoicesOfTenant7();

        var error = refusal(() -> capped.approve(third.id(), newKey(), edits(), "visitor-test"));

        assertThat(error).isEqualTo(ActionError.DAILY_APPROVAL_LIMIT_REACHED);
        assertThat(error.status().value()).isEqualTo(429);
        assertThat(invoicesOfTenant7()).isEqualTo(invoices);
        assertThat(actions.get(third.id()).status()).isEqualTo(ActionStatus.PROPOSED);
    }

    @Test
    void repeatingAnApprovalNeedsNoBudget() {
        var capped = withDailyCap(1);
        var proposed = proposePayment("100");
        var key = newKey();
        capped.approve(proposed.id(), key, edits(), "visitor-test");

        var replay = capped.approve(proposed.id(), key, edits(), "visitor-test");

        assertThat(replay.replayed()).isTrue();
    }

    @Test
    void anApprovalThatFailsOrIsRejectedDoesNotUseUpTheBudget() {
        var capped = withDailyCap(1);
        var amount = rent.multiply(BigDecimal.valueOf(3)).toPlainString();
        var stale = proposePayment(amount);
        actions.approve(proposePayment(amount).id(), newKey(), edits(), "visitor-test");
        var rejected = proposePayment("100");
        var wanted = proposePayment("100");

        assertThat(refusal(() -> capped.approve(stale.id(), newKey(), edits(), "visitor-test")))
                .isEqualTo(ActionError.AMOUNT_EXCEEDS_OUTSTANDING);
        capped.reject(rejected.id(), "no", "visitor-test");

        assertThat(capped.approve(wanted.id(), newKey(), edits(), "visitor-test").action().status())
                .isEqualTo(ActionStatus.APPROVED);
        assertThat(refusal(() -> capped.approve(proposePayment("100").id(), newKey(), edits(), "visitor-test")))
                .isEqualTo(ActionError.DAILY_APPROVAL_LIMIT_REACHED);
    }

    @Test
    void aKeyThatAnotherApprovalClaimedFirstIsRefusedAndGivesTheBudgetBack() throws Exception {
        var capped = withDailyCap(1);
        var winner = proposePayment("100");
        var loser = proposePayment("100");
        var key = newKey();
        var invoices = invoicesOfTenant7();
        var pool = Executors.newSingleThreadExecutor();
        try (var other = dataSource.getConnection()) {
            // The winning approval has written the key but not committed yet, so the loser's check cannot see it.
            other.setAutoCommit(false);
            try (var claim = other.prepareStatement(
                    "update pending_actions set status = 'APPROVED', idempotency_key = ? where id = ?")) {
                claim.setString(1, key);
                claim.setObject(2, winner.id());
                claim.executeUpdate();
            }
            Future<ActionError> attempt = pool.submit(
                    () -> refusal(() -> capped.approve(loser.id(), key, edits(), "visitor-test")));
            waitUntilAStatementWaitsForALock();
            other.commit();

            var error = attempt.get(20, TimeUnit.SECONDS);

            assertThat(error).isEqualTo(ActionError.IDEMPOTENCY_KEY_REUSED);
            assertThat(error.status().value()).isEqualTo(409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(actions.get(loser.id()).status()).isEqualTo(ActionStatus.PROPOSED);
        assertThat(invoicesOfTenant7()).as("the loser's payment was rolled back").isEqualTo(invoices);
        assertThat(capped.approve(loser.id(), newKey(), edits(), "visitor-test").action().status())
                .as("with a budget of one, the refused attempt did not use it up").isEqualTo(ActionStatus.APPROVED);
    }

    @Test
    void anEditThatIsRefusedDoesNotUseUpTheBudget() {
        var capped = withDailyCap(1);
        var proposed = proposePayment("100");
        var tooMuch = edits().put("amount", rent.multiply(BigDecimal.valueOf(9)));

        assertThat(refusal(() -> capped.approve(proposed.id(), newKey(), tooMuch, "visitor-test")))
                .isEqualTo(ActionError.AMOUNT_EXCEEDS_OUTSTANDING);

        assertThat(capped.approve(proposed.id(), newKey(), edits(), "visitor-test").action().status())
                .isEqualTo(ActionStatus.APPROVED);
    }
}
