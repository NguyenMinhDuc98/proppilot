package dev.proppilot.actions;

import dev.proppilot.config.ActionProperties;
import dev.proppilot.limit.DailyQuota;
import dev.proppilot.limit.SlidingWindowLimiter;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * The caps on the public demo: decision calls per client per minute, and approvals and proposals per day for everyone.
 * The cap on proposals per run needs the stored proposals, so {@link ActionService} applies it.
 */
@Component
public class ActionLimits {

    private final SlidingWindowLimiter decisions;
    private final DailyQuota approvals;
    private final DailyQuota proposals;

    ActionLimits(ActionProperties props, Clock clock) {
        this.decisions = new SlidingWindowLimiter(props.decisionsPerMinute(), clock);
        this.approvals = new DailyQuota(props.maxApprovalsPerDay(), clock);
        this.proposals = new DailyQuota(props.maxProposalsPerDay(), clock);
    }

    /** Counts one decision call (preview, approve or reject) from this client. */
    void requireDecisionAllowed(String clientKey) {
        if (!decisions.tryAcquire(clientKey)) {
            throw new ActionException(ActionError.RATE_LIMITED, "Too many decisions. Please wait a minute and try again.");
        }
    }

    void takeApproval() {
        if (!approvals.tryTake()) {
            throw new ActionException(ActionError.DAILY_APPROVAL_LIMIT_REACHED,
                    "The daily limit of approved changes on this demo has been reached. Please come back tomorrow.");
        }
    }

    /** Frees the approval taken for a change that was not made after all. */
    void returnApproval() {
        approvals.giveBack();
    }

    void takeProposal() {
        if (!proposals.tryTake()) {
            throw new ActionException(ActionError.DAILY_PROPOSAL_LIMIT_REACHED,
                    "The daily limit of proposed changes on this demo has been reached. Please come back tomorrow.");
        }
    }

    /** Frees the proposal taken for a change that turned out not to be proposable. */
    void returnProposal() {
        proposals.giveBack();
    }
}
